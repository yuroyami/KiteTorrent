package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.peer.PeerAddress

/**
 * The Kademlia k-bucket routing table. This is the pure-Kotlin port of libtorrent's
 * `routing_table` (`include/libtorrent/kademlia/routing_table.hpp`,
 * `src/kademlia/routing_table.cpp`).
 *
 * The table is a list of *buckets*, each holding up to `k` ([bucketSize]) live
 * nodes plus a small *replacement cache*. Bucket 0 is the one furthest from our
 * own id; every time the bucket closest to us fills up it is split in two,
 * doubling the resolution of the part of the keyspace nearest to us. This is the
 * standard Kademlia structure that lets a lookup converge on any target in
 * `O(log n)` hops.
 *
 * **What this port covers (the faithful core):**
 *  - [addNode] and [addNodeImpl]: the full insert and replace state machine,
 *    including the "same IP, new ID" eviction, replacement-cache promotion, and
 *    the bucket-split decision ([canSplit]).
 *  - [nodeSeen] and [heardAbout]: the two public entry points, for a pinged node
 *    and for a node we have only been told about.
 *  - [findNode]: gather the `count` nodes closest to a target id by XOR distance.
 *  - [splitBucket], [fillFromReplacements] and [nodeFailed]: bucket maintenance.
 *  - [classifyPrefix], [allInSameBucket], [mostlyVerifiedNodes] and
 *    [replaceNodeImpl]: the free functions the table is built on.
 *
 * **Deliberate omissions / simplifications** (each noted at its call site):
 *  - **Endpoints are `host: String` + `port: Int`** (no socket types in
 *    `commonMain`), matching [NodeEntry]. IP identity for de-duplication and the
 *    BEP 42 `verified` flag come from [NodeEntry.addr] ([PeerAddress]).
 *  - **`restrict_routing_ips`** is honoured via [compareIpCidr] and [IpSet], but
 *    a node whose host is not a parseable IP literal (so `addr == null`) is
 *    treated as having no IP identity. It never collides in [IpSet] and is never
 *    rejected by the CIDR-closeness check. Real libtorrent always has a numeric
 *    address here.
 *  - **RTT-based refresh scheduling** (`next_refresh`) and the
 *    logging/`dht_logger` plumbing are not ported; they don't affect the routing
 *    structure that [findNode] returns.
 *  - **Time-based fields** (`last_queried`, `first_seen`) are not modelled. The
 *    one place upstream uses `last_queried` to influence structure is the
 *    malicious-node "schedule a re-ping" loop, which we collapse to a fail-count
 *    reset (see [addNodeImpl]).
 *
 * Settings come from [RoutingTableSettings], which mirrors the five DHT
 * `settings_pack` booleans/int the routing table reads, with libtorrent's exact
 * defaults.
 *
 * @param id our own node id (`m_id`).
 * @param bucketSize the constant `k` from the Kademlia paper (`m_bucket_size`);
 *   must be a power of two.
 * @param settings the DHT routing knobs (see [RoutingTableSettings]).
 */
class RoutingTable(
    id: Sha1Hash,
    val bucketSize: Int,
    val settings: RoutingTableSettings = RoutingTableSettings(),
) {
    /** Result of [addNodeImpl], port of `routing_table::add_node_status_t`. */
    enum class AddNodeStatus { FAILED_TO_ADD, NODE_ADDED, NEED_BUCKET_SPLIT }

    /** our own node id (`m_id`). Read-only from outside; mutated by [updateNodeId]. */
    var id: Sha1Hash = id
        private set

    init {
        // bucket sizes must be a power of 2 (TORRENT_ASSERT in the ctor)
        require(bucketSize > 0 && (bucketSize - 1) and bucketSize == 0) {
            "bucket size must be a power of two, was $bucketSize"
        }
    }

    /**
     * One `(live, replacements)` pair, the port of `struct routing_table_node`. The
     * lists are mutable; nodes are mutated in place (matching libtorrent, which
     * stores `node_entry` by value in `aux::vector`).
     */
    private class Bucket {
        val liveNodes = ArrayList<NodeEntry>()
        val replacements = ArrayList<NodeEntry>()
    }

    // (k-bucket, replacement cache) pairs. m_buckets[0] is the furthest from us.
    private val buckets = ArrayList<Bucket>()

    // endpoints identified as router nodes: used in lookups, never added to the
    // table (m_router_nodes). Keyed by "host:port".
    private val routerNodes = HashSet<String>()

    // all IPs currently in the table. It allows only a single entry per IP
    // (or a CIDR-bounded few) in the whole table. Port of `ip_set m_ips`.
    private val ips = IpSet()

    private var depthCache = 0

    // --- public surface --------------------------------------------------------

    /** The number of active buckets (`num_active_buckets`). */
    fun numActiveBuckets(): Int = buckets.size

    /** Our own node id (`id()`); same as the [id] property. */
    fun ownId(): Sha1Hash = id

    /**
     * `(nodes, replacements, confirmed)`: total live nodes, total replacement
     * nodes, and the number of live nodes that are confirmed up. Port of
     * `std::tuple<int,int,int> routing_table::size()`.
     */
    fun size(): Triple<Int, Int, Int> {
        var nodes = 0
        var replacements = 0
        var confirmed = 0
        for (b in buckets) {
            nodes += b.liveNodes.size
            for (n in b.liveNodes) if (n.confirmed()) confirmed++
            replacements += b.replacements.size
        }
        return Triple(nodes, replacements, confirmed)
    }

    /**
     * `true` if the table has no live nodes at all. This is a KiteTorrent convenience
     * (libtorrent has no single `need_bootstrap()` function; the DHT node decides
     * to bootstrap based on `routing_table::size()` and the return value of
     * [nodeSeen]). A freshly-constructed table that has never seen a node is
     * empty and therefore needs bootstrapping.
     */
    fun needBootstrap(): Boolean = size().first == 0

    /**
     * The per-bucket size limit. Port of `int routing_table::bucket_limit(int)`.
     * With the extended routing table (the default), the first four buckets are
     * larger (`k*{16,8,4,2}`) so the table gets closer to us in fewer hops.
     */
    fun bucketLimit(bucket: Int): Int {
        if (!settings.extendedRoutingTable) return bucketSize
        return when (bucket) {
            0 -> bucketSize * 16
            1 -> bucketSize * 8
            2 -> bucketSize * 4
            3 -> bucketSize * 2
            else -> bucketSize
        }
    }

    /** Number of live nodes in bucket [bucket] (`bucket_size(int)`). */
    fun bucketSize(bucket: Int): Int {
        if (buckets.isEmpty()) return 0
        val b = if (bucket >= buckets.size) buckets.size - 1 else bucket
        return buckets[b].liveNodes.size
    }

    /**
     * `true` if bucket [bucket] is full in both its live list and its replacement
     * cache. Port of `bool routing_table::is_full(int)`.
     */
    fun isFull(bucket: Int): Boolean {
        if (bucket >= buckets.size) return false
        val b = buckets[bucket]
        return b.liveNodes.size >= bucketLimit(bucket) && b.replacements.size >= bucketSize
    }

    /**
     * The number of bits down we have full buckets. Port of `int depth()`. This
     * uses (and updates) [depthCache] exactly like the upstream `mutable m_depth`.
     */
    fun depth(): Int {
        if (depthCache >= buckets.size) depthCache = buckets.size - 1
        if (depthCache < 0) return depthCache
        while (depthCache < buckets.size - 1 &&
            buckets[depthCache + 1].liveNodes.size >= bucketSize / 2
        ) depthCache++
        while (depthCache > 0 &&
            buckets[depthCache - 1].liveNodes.size < bucketSize / 2
        ) depthCache--
        return depthCache
    }

    /** Register a router node by endpoint (`add_router_node`). */
    fun addRouterNode(host: String, port: Int) {
        routerNodes.add(endpointKey(host, port))
    }

    /** The set of router endpoints, each as `"host:port"` (`begin()`/`end()`). */
    fun routerNodes(): Set<String> = routerNodes

    /** Invoke [f] for every live node, then (optionally) every replacement node. */
    fun forEachNode(includeReplacements: Boolean = false, f: (NodeEntry) -> Unit) {
        for (b in buckets) {
            for (n in b.liveNodes) f(n)
            if (includeReplacements) for (n in b.replacements) f(n)
        }
    }

    // --- node_seen / heard_about ----------------------------------------------

    /**
     * We saw a verified sign of life from a node (a response with a matching
     * transaction id). Port of `bool routing_table::node_seen(...)`: the node is
     * created as *pinged* and added; the return value is `true` if the table
     * wants a refresh (i.e. a [findNode] on our own id should be issued). The node
     * is only considered if it passes [verifyNodeAddress].
     */
    fun nodeSeen(id: Sha1Hash, host: String, port: Int, rtt: Int): Boolean {
        val addr = PeerAddress.parseOrNull(host)
        if (!verifyNodeAddress(id, addr)) return false
        return addNode(NodeEntry(id, host, port, rtt, pinged = true))
    }

    /**
     * We heard *about* a node (e.g. in another node's `find_node` response) but
     * have not confirmed it ourselves. Port of `void heard_about(...)`: the entry
     * is created un-pinged, and if its bucket is full it is simply ignored.
     */
    fun heardAbout(id: Sha1Hash, host: String, port: Int) {
        val addr = PeerAddress.parseOrNull(host)
        if (!verifyNodeAddress(id, addr)) return
        addNode(NodeEntry(id, host, port))
    }

    /**
     * When `dht_enforce_node_id` is set, a node is only admitted if its id is a
     * valid BEP 42 secure id for its address. Port of the file-local
     * `verify_node_address`. A node whose host is not a parseable IP literal can't
     * be verified, so it is rejected when enforcement is on.
     */
    private fun verifyNodeAddress(id: Sha1Hash, addr: PeerAddress?): Boolean {
        if (!settings.enforceNodeId) return true
        return addr != null && verifyId(id, addr)
    }

    // --- add_node --------------------------------------------------------------

    /**
     * Add a node, performing bucket splits as needed. Port of
     * `bool routing_table::add_node(node_entry const& e)`. Returns `true` if the
     * node ended up in the table (live or replacement bucket).
     */
    fun addNode(e: NodeEntry): Boolean {
        var s = addNodeImpl(e)
        if (s == AddNodeStatus.FAILED_TO_ADD) return false
        if (s == AddNodeStatus.NODE_ADDED) return true

        while (s == AddNodeStatus.NEED_BUCKET_SPLIT) {
            splitBucket()

            if (buckets.size > 50) {
                // sanity guard: a real table is never deeper than ~26. If we get
                // here someone is likely spoofing node ids; stop splitting.
                s = addNodeImpl(e)
                return s == AddNodeStatus.NODE_ADDED
            }

            // if the new last bucket still has too many nodes, keep splitting
            if (buckets.last().liveNodes.size > bucketLimit(buckets.size - 1)) continue

            s = addNodeImpl(e)

            // we just split and tried to insert. If nothing (neither split nodes
            // nor e) ended up in the new last bucket, erase it.
            if (buckets.last().liveNodes.isEmpty()) {
                buckets.removeAt(buckets.size - 1)
            }
            if (s == AddNodeStatus.FAILED_TO_ADD) return false
            if (s == AddNodeStatus.NODE_ADDED) return true
        }
        return false
    }

    /**
     * The core insert state machine. Port of
     * `routing_table::add_node_status_t routing_table::add_node_impl(node_entry e)`.
     * Returns one of [AddNodeStatus]; [addNode] drives the splitting loop on
     * [AddNodeStatus.NEED_BUCKET_SPLIT].
     */
    fun addNodeImpl(eIn: NodeEntry): AddNodeStatus {
        var e = eIn

        // if we already have this router node by endpoint, ignore it. (libtorrent
        // also drops nodes of the wrong address family here via native_endpoint;
        // since this table is family-agnostic we skip that.)
        if (routerNodes.contains(endpointKey(e.host, e.port))) return AddNodeStatus.FAILED_TO_ADD

        // do we already have this IP in the table?
        if (e.addr != null && ips.exists(e.addr!!)) {
            val found = findNodeByEndpoint(e.host, e.port)
            if (found == null) {
                // same IP, but a different port (or unparsed): only an issue when
                // we restrict to a single entry per IP.
                if (settings.restrictRoutingIps) return AddNodeStatus.FAILED_TO_ADD
            } else {
                val existing = found.node
                val bucket = found.bucket
                val existingBucketIdx = found.bucketIndex
                when {
                    existing.id == e.id -> {
                        // same id: just refresh the fail count (and RTT if pinged)
                        existing.timeoutCount = 0
                        if (e.pinged()) existing.updateRtt(e.rtt)
                        // a replacement may now be eligible for promotion
                        fillFromReplacements(existingBucketIdx)
                        pruneEmptyBucket()
                        return AddNodeStatus.NODE_ADDED
                    }
                    existing.id.isAllZeros() -> {
                        // the old entry's id was unknown: drop it, fall through to
                        // insert the node with its real id.
                        removeNode(existing, bucket)
                    }
                    !e.pinged() -> {
                        // possible routing-table poisoning: we haven't confirmed
                        // this new id actually exists. Don't replace the existing.
                        if (settings.restrictRoutingIps) return AddNodeStatus.FAILED_TO_ADD
                    }
                    else -> {
                        // same IP:port, new id, and pinged. This is likely malicious,
                        // so evict the existing entry.
                        removeNode(existing, bucket)
                        fillFromReplacements(existingBucketIdx)
                        // schedule the rest of the bucket to be re-pinged soon by
                        // resetting their fail counts (we don't model time, so we
                        // approximate the upstream last_queried reset this way).
                        for (n in buckets[existingBucketIdx].liveNodes) n.resetFailCount()
                        pruneEmptyBucket()
                        return AddNodeStatus.FAILED_TO_ADD
                    }
                }
            }
        }

        // don't add ourselves
        if (e.id == id) return AddNodeStatus.FAILED_TO_ADD

        val bucketIndex = findBucketIndex(e.id)
        val b = buckets[bucketIndex].liveNodes
        val rb = buckets[bucketIndex].replacements
        val bucketSizeLimit = bucketLimit(bucketIndex)

        // if the node already exists in the live bucket, refresh it
        run {
            val j = b.firstOrNull { it.id == e.id }
            if (j != null) {
                // a new IP just claimed this id: ignore
                if (!j.sameEndpoint(e.host, e.port)) return AddNodeStatus.FAILED_TO_ADD
                j.timeoutCount = 0
                j.updateRtt(e.rtt)
                return AddNodeStatus.NODE_ADDED
            }
        }

        // if it exists in the replacement bucket: pull it out (we may re-add it
        // there, or replace a live node now that we have an updated RTT).
        run {
            val j = rb.firstOrNull { it.id == e.id }
            if (j != null) {
                if (!j.sameEndpoint(e.host, e.port)) return AddNodeStatus.FAILED_TO_ADD
                j.timeoutCount = 0
                j.updateRtt(e.rtt)
                e = j
                if (j.addr != null) ips.erase(j.addr!!)
                rb.remove(j)
            }
        }

        if (settings.restrictRoutingIps && e.addr != null) {
            // don't allow multiple entries from IPs very close to each other
            val cmp = e.addr!!
            val close = b.firstOrNull { it.addr != null && compareIpCidr(it.addr!!, cmp) }
                ?: rb.firstOrNull { it.addr != null && compareIpCidr(it.addr!!, cmp) }
            if (close != null) return AddNodeStatus.FAILED_TO_ADD
        }

        // if there's room in the main bucket, just insert it
        if (e.pinged() && b.size < bucketSizeLimit) {
            b.add(e)
            if (e.addr != null) ips.insert(e.addr!!)
            return AddNodeStatus.NODE_ADDED
        }

        val lastBucket = bucketIndex + 1 == buckets.size

        // only confirmed nodes can split, and only the last bucket can split, and
        // only if the entries don't all fall on the same side of the split.
        val canSplit = (bucketIndex == buckets.size - 1 && buckets.size < 159) &&
            (!settings.preferVerifiedNodeIds || (e.verified && mostlyVerifiedNodes(b))) &&
            e.confirmed() &&
            (bucketIndex == 0 || buckets[bucketIndex - 1].liveNodes.size > 1) &&
            !allInSameBucket(b, e.id, bucketIndex)

        if (canSplit) return AddNodeStatus.NEED_BUCKET_SPLIT

        if (e.confirmed()) {
            val ret = replaceNodeImpl(e, b, ips, bucketIndex, bucketSizeLimit, lastBucket)
            if (ret != AddNodeStatus.NEED_BUCKET_SPLIT) return ret
        }

        // couldn't split nor replace a live node: try the replacement bucket.
        run {
            val j = rb.firstOrNull { it.id == e.id }
            if (j != null) {
                // already in the replacement bucket: just mark it pinged.
                if (j.sameEndpoint(e.host, e.port)) j.setPinged()
                return AddNodeStatus.NODE_ADDED
            }
        }

        if (rb.size >= bucketSize) {
            // replacement bucket full: prefer evicting an un-pinged entry.
            val j = rb.firstOrNull { !it.pinged() }
            if (j == null) {
                val ret = replaceNodeImpl(e, rb, ips, bucketIndex, bucketSize, lastBucket)
                return if (ret == AddNodeStatus.NODE_ADDED) AddNodeStatus.NODE_ADDED
                else AddNodeStatus.FAILED_TO_ADD
            }
            if (j.addr != null) ips.erase(j.addr!!)
            rb.remove(j)
        }

        rb.add(e)
        if (e.addr != null) ips.insert(e.addr!!)
        return AddNodeStatus.NODE_ADDED
    }

    // --- find_node -------------------------------------------------------------

    /**
     * Gather up to [count] nodes from our buckets closest (by XOR distance) to
     * [target]. Port of
     * `std::vector<node_entry> routing_table::find_node(node_id const& target,
     * find_nodes_flags_t options, int count)`.
     *
     * It scans outward from [target]'s home bucket toward higher-numbered buckets,
     * then back toward bucket 0, sorting only the final over-full slice by
     * [compareRef] to the target. With [includeFailed] = false (the default) only
     * `confirmed()` nodes are returned.
     *
     * @param count the desired number of nodes; `0` means [bucketSize].
     * @param includeFailed if `true`, also include non-confirmed (failed /
     *   un-pinged) live nodes (`include_failed` flag).
     */
    fun findNode(target: Sha1Hash, count: Int = 0, includeFailed: Boolean = false): List<NodeEntry> {
        val want = if (count == 0) bucketSize else count
        val l = ArrayList<NodeEntry>()

        if (buckets.isEmpty()) return l
        val homeIdx = findBucketIndex(target)

        var unsortedStart = 0

        // scan from the home bucket toward the end (closer to us)
        var j = homeIdx
        while (j < buckets.size && l.size < want) {
            appendBucket(buckets[j].liveNodes, l, includeFailed)
            if (l.size == want) return l
            if (l.size > want) {
                sortAndTrim(l, unsortedStart, target, want)
                return l
            }
            unsortedStart = l.size
            j++
        }

        // if we still need more, copy nodes further away from us (toward bucket 0)
        if (homeIdx == 0) return l

        unsortedStart = l.size
        j = homeIdx
        do {
            j--
            appendBucket(buckets[j].liveNodes, l, includeFailed)
            if (l.size == want) return l
            if (l.size > want) {
                sortAndTrim(l, unsortedStart, target, want)
                return l
            }
            unsortedStart = l.size
        } while (j != 0 && l.size < want)

        return l
    }

    /** `std::find` over both buckets by endpoint (`find_node(udp::endpoint)`). */
    private class FoundNode(val node: NodeEntry, val bucketIndex: Int, val bucket: MutableList<NodeEntry>)

    private fun findNodeByEndpoint(host: String, port: Int): FoundNode? {
        for (i in buckets.indices) {
            val b = buckets[i]
            for (n in b.replacements) if (n.sameEndpoint(host, port)) return FoundNode(n, i, b.replacements)
            for (n in b.liveNodes) if (n.sameEndpoint(host, port)) return FoundNode(n, i, b.liveNodes)
        }
        return null
    }

    private fun appendBucket(b: List<NodeEntry>, out: MutableList<NodeEntry>, includeFailed: Boolean) {
        if (includeFailed) out.addAll(b)
        else for (n in b) if (n.confirmed()) out.add(n)
    }

    private fun sortAndTrim(l: MutableList<NodeEntry>, from: Int, target: Sha1Hash, count: Int) {
        // sort only the just-appended (unsorted) tail by closeness to target, the
        // way std::sort(l.begin()+unsorted_start_idx, l.end(), compare_ref) does.
        val tail = l.subList(from, l.size)
        tail.sortWith { a, b -> if (a.id == b.id) 0 else if (compareRef(a.id, b.id, target)) -1 else 1 }
        while (l.size > count) l.removeAt(l.size - 1)
    }

    // --- node_failed -----------------------------------------------------------

    /**
     * Record that a request to a node timed out. Port of
     * `void routing_table::node_failed(node_id const&, udp::endpoint const&)`.
     * After enough failures (or for a never-pinged node) the entry is removed and
     * the bucket back-filled from its replacement cache.
     */
    fun nodeFailed(nid: Sha1Hash, host: String, port: Int) {
        if (nid == id) return
        val bucketIndex = findBucketIndex(nid)
        val b = buckets[bucketIndex].liveNodes
        val rb = buckets[bucketIndex].replacements

        var j = b.firstOrNull { it.id == nid }
        if (j == null) {
            j = rb.firstOrNull { it.id == nid }
            if (j == null || !j.sameEndpoint(host, port)) return
            j.timedOut()
            return
        }

        // a different node claiming the same id from another endpoint: not stale.
        if (!j.sameEndpoint(host, port)) return

        if (rb.isEmpty()) {
            j.timedOut()
            if (j.failCount() >= settings.maxFailCount || !j.pinged()) {
                if (j.addr != null) ips.erase(j.addr!!)
                b.remove(j)
            }
            return
        }

        if (j.addr != null) ips.erase(j.addr!!)
        b.remove(j)
        fillFromReplacements(bucketIndex)
        pruneEmptyBucket()
    }

    // --- internals -------------------------------------------------------------

    /**
     * Index of the bucket [nid] belongs in. Port of `find_bucket`: creates the
     * first bucket on demand, then returns
     * `min(159 - distance_exp(m_id, id), num_buckets - 1)`.
     */
    private fun findBucketIndex(nid: Sha1Hash): Int {
        if (buckets.isEmpty()) buckets.add(Bucket())
        val idx = 159 - distanceExp(id, nid)
        return if (idx < buckets.size - 1) idx else buckets.size - 1
    }

    /** Remove [n] from [b] and drop its IP from [ips] (`remove_node`). */
    private fun removeNode(n: NodeEntry, b: MutableList<NodeEntry>) {
        if (n.addr != null) ips.erase(n.addr!!)
        b.remove(n)
    }

    /** Drop the last bucket if it is completely empty (`prune_empty_bucket`). */
    private fun pruneEmptyBucket() {
        if (buckets.isNotEmpty() &&
            buckets.last().liveNodes.isEmpty() &&
            buckets.last().replacements.isEmpty()
        ) {
            buckets.removeAt(buckets.size - 1)
        }
    }

    /**
     * Promote pinged replacement nodes into the live bucket while there's room.
     * Port of `void routing_table::fill_from_replacements(...)`. Replacements are
     * sorted "best first" ([NodeEntry.isBetterThan]) before promotion.
     */
    private fun fillFromReplacements(bucketIndex: Int) {
        val b = buckets[bucketIndex].liveNodes
        val rb = buckets[bucketIndex].replacements
        val limit = bucketLimit(bucketIndex)
        if (b.size >= limit) return

        // sort by RTT/verified first (operator< on node_entry)
        rb.sortWith { x, y -> if (x.isBetterThan(y)) -1 else if (y.isBetterThan(x)) 1 else 0 }

        while (b.size < limit) {
            val j = rb.firstOrNull { it.pinged() } ?: break
            b.add(j)
            rb.remove(j)
        }
    }

    /**
     * Split the last (closest) bucket in two. Port of
     * `void routing_table::split_bucket()`. Nodes whose distance-exp puts them in
     * the newly-created deeper bucket are moved there; the rest stay. Overflowing
     * live nodes spill into the replacement cache.
     */
    private fun splitBucket() {
        val bucketIndex = buckets.size - 1
        val bucketSizeLimit = bucketLimit(bucketIndex)

        buckets.add(Bucket())
        val newBucket = buckets.last().liveNodes
        val newReplacement = buckets.last().replacements

        val b = buckets[bucketIndex].liveNodes
        val rb = buckets[bucketIndex].replacements
        val newBucketSize = bucketLimit(bucketIndex + 1)

        // move entries that belong deeper into the new bucket
        run {
            var i = 0
            while (i < b.size) {
                val d = distanceExp(id, b[i].id)
                if (d >= 159 - bucketIndex) {
                    i++
                } else {
                    newBucket.add(b[i])
                    b.removeAt(i)
                }
            }
        }

        if (b.size > bucketSizeLimit) {
            // overflow spills into the replacement cache
            for (k in bucketSizeLimit until b.size) rb.add(b[k])
            while (b.size > bucketSizeLimit) b.removeAt(b.size - 1)
        }

        // split the replacement cache as well
        run {
            var i = 0
            while (i < rb.size) {
                val n = rb[i]
                if (distanceExp(id, n.id) >= 159 - bucketIndex) {
                    if (!n.pinged() || b.size >= bucketSizeLimit) {
                        i++
                        continue
                    }
                    b.add(n)
                } else {
                    if (n.pinged() && newBucket.size < newBucketSize) newBucket.add(n)
                    else newReplacement.add(n)
                }
                rb.removeAt(i)
            }
        }
    }

    /**
     * Change our node id and rebuild the whole table around it. Port of
     * `void routing_table::update_node_id(node_id const& id)`.
     */
    fun updateNodeId(newId: Sha1Hash) {
        id = newId
        ips.clear()
        val old = ArrayList(buckets)
        buckets.clear()
        // re-add live nodes first, then replacements
        for (bk in old) for (n in bk.liveNodes) addNode(n)
        for (bk in old) for (n in bk.replacements) addNode(n)
    }

    companion object {
        /** `"host:port"` endpoint key for router-node and same-endpoint matching. */
        private fun endpointKey(host: String, port: Int): String = "$host:$port"
    }
}

/**
 * The DHT routing knobs the table reads, mirroring the five relevant
 * `settings_pack` entries with libtorrent's exact defaults (see
 * `src/settings_pack.cpp`). Pulling them into a small value object keeps the
 * routing table decoupled from KiteTorrent's general `SettingsPack`, which does
 * not (yet) carry DHT fields.
 *
 * @property maxFailCount `dht_max_fail_count` (default 20): live nodes are
 *   dropped after this many consecutive request failures.
 * @property restrictRoutingIps `dht_restrict_routing_ips` (default `true`): allow
 *   at most one routing-table entry per IP, and reject entries from IPs in the
 *   same /24 (v4) or /64 (v6) as an existing entry. When `false`, multiple
 *   entries per IP are permitted.
 * @property extendedRoutingTable `dht_extended_routing_table` (default `true`):
 *   make the first four buckets larger (see [RoutingTable.bucketLimit]).
 * @property preferVerifiedNodeIds `dht_prefer_verified_node_ids` (default
 *   `true`): only allow a bucket to split (and so only really grow) when the new
 *   node is verified and the bucket is mostly verified.
 * @property enforceNodeId `dht_enforce_node_id` (default `false`): require every
 *   admitted node to have a BEP 42 secure id matching its IP.
 */
data class RoutingTableSettings(
    val maxFailCount: Int = 20,
    val restrictRoutingIps: Boolean = true,
    val extendedRoutingTable: Boolean = true,
    val preferVerifiedNodeIds: Boolean = true,
    val enforceNodeId: Boolean = false,
)

/**
 * Tracks which IP addresses are present in the routing table, allowing duplicates
 * (a multiset) so that the same IP may legitimately appear with several ports when
 * `restrict_routing_ips` is off. Port of `struct ip_set` (the v4/v6
 * `unordered_multiset`s collapsed into one count-keyed map, since [PeerAddress]
 * already distinguishes the families by byte length and provides equality).
 */
internal class IpSet {
    private val counts = HashMap<PeerAddress, Int>()

    fun insert(addr: PeerAddress) {
        counts[addr] = (counts[addr] ?: 0) + 1
    }

    fun exists(addr: PeerAddress): Boolean = (counts[addr] ?: 0) > 0

    fun erase(addr: PeerAddress) {
        val c = counts[addr] ?: return
        if (c <= 1) counts.remove(addr) else counts[addr] = c - 1
    }

    fun clear() = counts.clear()

    /** Total number of (multiset) entries (`ip_set::size`). */
    fun size(): Int = counts.values.sum()
}

/**
 * `true` if at least two-thirds of the bucket's nodes are verified. Port of
 * `bool mostly_verified_nodes(bucket_t const&)`:
 * ```
 * if (num_verified == 0 && b.size() > 0) return false;
 * return num_verified >= b.size() * 2 / 3;
 * ```
 * A bucket with at least one node but *no* verified node short-circuits to
 * `false`; an empty bucket returns `true` (`0 >= 0`), exactly as upstream.
 */
fun mostlyVerifiedNodes(b: List<NodeEntry>): Boolean {
    val numVerified = b.count { it.verified }
    if (numVerified == 0 && b.isNotEmpty()) return false
    return numVerified >= b.size * 2 / 3
}

/**
 * `true` if two IPs are "too close" to share a DHT lookup. Port of
 * `bool compare_ip_cidr(address const&, address const&)`: IPv6 addresses in the
 * same /64 and IPv4 addresses in the same /24 are considered too close. Mixed
 * families are never close.
 */
fun compareIpCidr(lhs: PeerAddress, rhs: PeerAddress): Boolean {
    if (lhs.isV4 != rhs.isV4) return false
    return if (lhs.isV6) {
        // same /64: the first 8 bytes are identical
        for (i in 0 until 8) if (lhs.bytes[i] != rhs.bytes[i]) return false
        true
    } else {
        // same /24: the first 3 bytes are identical
        lhs.bytes[0] == rhs.bytes[0] && lhs.bytes[1] == rhs.bytes[1] && lhs.bytes[2] == rhs.bytes[2]
    }
}

/**
 * Would every node in [b], plus the candidate [nodeId], fall on the same side
 * of the split at bit [bucketIndex]? If so, splitting the bucket gains nothing.
 * Port of `bool all_in_same_bucket(span<node_entry const>, node_id const&, int)`.
 */
fun allInSameBucket(b: List<NodeEntry>, nodeId: Sha1Hash, bucketIndex: Int): Boolean {
    val byteOffset = bucketIndex / 8
    val bitOffset = bucketIndex % 8
    val mask = 0x80 ushr bitOffset
    val counter = intArrayOf(0, 0)
    counter[if (nodeId[byteOffset] and mask != 0) 1 else 0]++
    for (e in b) counter[if (e.id[byteOffset] and mask != 0) 1 else 0]++
    return counter[0] == 0 || counter[1] == 0
}

/**
 * Classify which "sub-branch" of a bucket [nid] falls into, using the few bits
 * after the bucket's shared prefix. Port of
 * `std::uint8_t classify_prefix(int bucket_idx, bool last_bucket, int bucket_size, node_id nid)`.
 *
 * The return value is in `0 until bucketSize`. [replaceNodeImpl] uses it to keep a
 * good spread of ids in each bucket so that lookups branch out evenly.
 *
 * @param lastBucket whether this is the routing table's last (not-yet-split)
 *   bucket; the last bucket still holds entries from "both sides" so its top
 *   defining bit is included (one fewer left-shift).
 * @param bucketSizeLimit the bucket's size limit; must be a power of two ≤ 256.
 */
fun classifyPrefix(bucketIdx: Int, lastBucket: Boolean, bucketSizeLimit: Int, nid: Sha1Hash): Int {
    require(bucketSizeLimit in 1..256) { "bucket size must be in 1..256, was $bucketSizeLimit" }
    var mask = bucketSizeLimit - 1
    require(mask and bucketSizeLimit == 0) { "bucket size must be a power of two, was $bucketSizeLimit" }

    // number of leading zero bits of `mask` within a single byte. `mask` here is
    // in 0..255, so this is 8 - bitLength(mask); for bucketSize 8, mask=7 -> 5.
    val maskShift = countLeadingZerosByte(mask)
    mask = (mask shl maskShift) and 0xff

    // shift away the bucket's shared prefix (and, except for the last bucket, the
    // top defining bit too), then read the classified bits out of the top byte.
    val shifted = nid.shl(bucketIdx + if (lastBucket) 0 else 1)
    return (shifted[0] and mask) ushr maskShift
}

/**
 * Leading zeros of [v] treated as an 8-bit value (`v` is assumed in `0..255`).
 * This mirrors the single-byte case of libtorrent's `aux::count_leading_zeros`
 * over the low byte of the bucket-size mask. Returns 8 for `v == 0`.
 */
private fun countLeadingZerosByte(v: Int): Int {
    if (v and 0xff == 0) return 8
    var n = 0
    var m = 0x80
    while (m != 0 && (v and m) == 0) {
        n++
        m = m ushr 1
    }
    return n
}

/**
 * Pick a node in full bucket [b] to replace with [e], honouring the affinity
 * rules. Port of `routing_table::add_node_status_t replace_node_impl(...)`:
 *
 *  1. If any node has failed at least once, replace the most-failed one.
 *  2. Otherwise, classify by [classifyPrefix]: if there is already a node in
 *     `e`'s prefix slot, replace it only if `e` is strictly better; if the slot
 *     is empty, replace the "worst" node among the prefix slots that have a
 *     duplicate.
 *  3. If nothing was replaced, return [RoutingTable.AddNodeStatus.NEED_BUCKET_SPLIT].
 *
 * [ips] is updated for any eviction/insertion. The [bucketSizeLimit] is the size
 * the bucket is being held to, and [lastBucket] feeds [classifyPrefix].
 */
internal fun replaceNodeImpl(
    e: NodeEntry,
    b: MutableList<NodeEntry>,
    ips: IpSet,
    bucketIndex: Int,
    bucketSizeLimit: Int,
    lastBucket: Boolean,
): RoutingTable.AddNodeStatus {
    // 1. replace the node that has failed the most, if any has failed at all.
    var worstFail = b[0]
    for (n in b) if (n.failCount() > worstFail.failCount()) worstFail = n
    if (worstFail.failCount() > 0) {
        replaceInPlace(b, worstFail, e, ips)
        return RoutingTable.AddNodeStatus.NODE_ADDED
    }

    // 2. spread by 3-bit (or more) prefix.
    val toAddPrefix = classifyPrefix(bucketIndex, lastBucket, bucketSizeLimit, e.id)

    // nodes grouped by prefix slot
    val nodesByPrefix = Array(bucketSizeLimit) { ArrayList<NodeEntry>() }
    for (n in b) {
        val p = classifyPrefix(bucketIndex, lastBucket, bucketSizeLimit, n.id)
        nodesByPrefix[p].add(n)
    }

    var target: NodeEntry? = null
    if (nodesByPrefix[toAddPrefix].isNotEmpty()) {
        // worst node already in e's slot
        var worst = nodesByPrefix[toAddPrefix][0]
        for (n in nodesByPrefix[toAddPrefix]) if (worst.isBetterThan(n)) worst = n
        // only replace if e is strictly better than that worst node
        target = if (!e.isBetterThan(worst)) null else worst
    } else {
        // e's slot is empty: replace the worst node among slots with duplicates
        val candidates = ArrayList<NodeEntry>()
        for (slot in nodesByPrefix) if (slot.size > 1) candidates.addAll(slot)
        if (candidates.isNotEmpty()) {
            var worst = candidates[0]
            for (n in candidates) if (worst.isBetterThan(n)) worst = n
            target = worst
        }
    }

    return if (target != null) {
        replaceInPlace(b, target, e, ips)
        RoutingTable.AddNodeStatus.NODE_ADDED
    } else {
        RoutingTable.AddNodeStatus.NEED_BUCKET_SPLIT
    }
}

/** Swap [old] for [new] at [old]'s position in [b], keeping [ips] consistent. */
private fun replaceInPlace(b: MutableList<NodeEntry>, old: NodeEntry, new: NodeEntry, ips: IpSet) {
    val idx = b.indexOf(old)
    if (old.addr != null) ips.erase(old.addr!!)
    b[idx] = new
    if (new.addr != null) ips.insert(new.addr!!)
}
