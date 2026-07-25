package io.github.yuroyami.kitetorrent.session.dht

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.dht.CompactNode
import io.github.yuroyami.kitetorrent.dht.CompactNodes
import io.github.yuroyami.kitetorrent.dht.DhtEndpoint
import io.github.yuroyami.kitetorrent.dht.DhtItem
import io.github.yuroyami.kitetorrent.dht.DhtKeySizes
import io.github.yuroyami.kitetorrent.dht.DhtMessage
import io.github.yuroyami.kitetorrent.dht.DhtStorage
import io.github.yuroyami.kitetorrent.dht.ImmutableItem
import io.github.yuroyami.kitetorrent.dht.MutableItem
import io.github.yuroyami.kitetorrent.dht.NODE_ID_SIZE
import io.github.yuroyami.kitetorrent.dht.RoutingTable
import io.github.yuroyami.kitetorrent.dht.generateId
import io.github.yuroyami.kitetorrent.dht.generatePrefixMask
import io.github.yuroyami.kitetorrent.dht.generateRandomId
import io.github.yuroyami.kitetorrent.dht.verifyId
import io.github.yuroyami.kitetorrent.peer.PeerAddress
import io.github.yuroyami.kitetorrent.session.net.DatagramTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A live Kademlia DHT node (BEP-5). This is the networked half of the DHT. It wires the
 * pure [RoutingTable], [DhtMessage], [DhtStorage] and [DhtItem] types to a UDP socket via
 * [DhtRpc] and [TraversalState]. Port of `dht::node`, `dht_tracker` and the
 * `find_data` and `get_peers` traversal algorithms.
 *
 * Drives the two things a client actually needs from the DHT: trackerless peer
 * discovery ([getPeers]) and re-announcing ourselves ([announce]); plus it answers
 * inbound queries so we are a good citizen of the network.
 *
 * @param clock supplies epoch-seconds for storage expiry and token rotation. The core is
 *   clockless, so the platform passes one in (`{ System.currentTimeMillis()/1000 }`).
 */
class DhtNode(
    private val socket: DatagramTransport,
    nodeId: Sha1Hash,
    private val scope: CoroutineScope,
    val routingTable: RoutingTable = RoutingTable(nodeId, K_BUCKET),
    val storage: DhtStorage = DhtStorage(),
    private val tokens: DhtTokens = DhtTokens(),
    private val clock: () -> Long = { 0L },
    /**
     * BEP-42 secure node-id enforcement (`enforce_node_id`). When true, a node whose id
     * does not derive from its source IP is kept out of the routing table. This makes
     * Sybil attacks harder. It is off by default for compatibility with the many legacy
     * nodes that do not use secure ids. Hostnames cannot be verified, so they are always
     * allowed.
     */
    private var enforceNodeId: Boolean = false,
    /**
     * BEP-43 read-only mode (`dht_read_only`). When true we tag our own outbound queries
     * with `ro:1` and stop answering inbound queries (so we still learn the network but
     * never serve it). Off by default.
     */
    private var readOnly: Boolean = false,
    /** RNG for secure-id construction and bucket-refresh target generation. */
    private val random: Random = Random.Default,
) {
    /** Our own node id (`m_id`). Mutable so [enableSecureId] can re-derive it from our IP. */
    var nodeId: Sha1Hash = nodeId
        private set

    private val rpc = DhtRpc(socket)

    /**
     * Per-source datagram counters for a simple denial-of-service guard. This is a
     * reduced version of the `dos_blocker` in `node.cpp`. [maintain] resets the counters
     * every [DOS_WINDOW_SECONDS]. A source that exceeds [maxQueriesPerSource] inside one
     * window is ignored until the next reset.
     */
    private val sourceQueryCounts = HashMap<String, Int>()
    private var dosWindowStart = clock()

    /** Add a node to the routing table, subject to the BEP-42 [enforceNodeId] gate. */
    private fun sawNode(id: Sha1Hash, host: String, port: Int, rtt: Int) {
        if (dhtNodeAccepted(id, host, enforceNodeId)) routingTable.nodeSeen(id, host, port, rtt)
    }

    /** Peers found for an info-hash, plus the closest responders + their write tokens (for announce). */
    class GetPeersResult(
        val peers: List<DhtEndpoint>,
        val closestWithTokens: List<Pair<CompactNode, ByteArray>>,
    )

    /** Start the inbound receive loop. Responses are routed to RPC waiters; queries are answered. */
    fun start(): Job = scope.launch {
        while (isActive) {
            val pkt = try {
                socket.receive()
            } catch (_: Throwable) {
                break // socket closed
            }
            val raw = Bdecode.decodeOrNull(pkt.data)
            val msg = raw?.let { DhtMessage.parse(it) }
            if (msg != null) rpc.dispatchResponse(msg)
            if (msg is DhtMessage.Query) {
                // BEP-43: a read-only node never answers queries (it only listens).
                if (readOnly) continue
                // dos_blocker-lite: drop queries from a source flooding us this window.
                if (!admitQuerySource(pkt.host)) continue
                runCatching { handleQuery(msg, pkt.host, pkt.port) }
            } else if (msg == null && raw != null) {
                // DhtMessage.parse returns null for queries it doesn't model (e.g.
                // sample_infohashes, BEP-51). Salvage those at the raw level.
                if (readOnly) continue
                if (!admitQuerySource(pkt.host)) continue
                runCatching { handleRawQuery(raw, pkt.host, pkt.port) }
            }
        }
    }

    // --- client side -------------------------------------------------------------

    /** Ping the router nodes, pull their neighbours, then self-lookup to fill the table. */
    suspend fun bootstrap(routers: List<Pair<String, Int>>) {
        coroutineScope {
            routers.map { (h, p) ->
                async {
                    val resp = rpc.query(h, p) { tid -> DhtMessage.buildFindNodeQuery(tid, nodeId, nodeId) }
                    if (resp != null) absorbNodes(resp, h, p)
                }
            }.awaitAll()
        }
        lookup(nodeId) // populate the routing table around our own id
    }

    /** Iterative get_peers traversal toward [infoHash]; returns the swarm peers it found. */
    suspend fun getPeers(infoHash: Sha1Hash, maxRounds: Int = 12): GetPeersResult {
        val state = TraversalState(infoHash)
        seed(state, infoHash)

        val peers = LinkedHashSet<DhtEndpoint>()
        val tokenByNode = HashMap<Sha1Hash, ByteArray>()
        var rounds = 0
        while (!state.isDone() && rounds++ < maxRounds) {
            val batch = state.next()
            if (batch.isEmpty()) break
            coroutineScope {
                batch.map { c ->
                    async {
                        val ep = c.node.endpoint
                        val resp = rpc.query(ep.addressLiteral(), ep.port) { tid ->
                            withWant(DhtMessage.buildGetPeersQuery(tid, nodeId, infoHash))
                        }
                        if (resp == null) {
                            state.fail(c)
                            nodeFailed(c.node)
                        } else {
                            state.alive(c)
                            sawNode(resp.nodeId, ep.addressLiteral(), ep.port, RTT_OK)
                            val reply = resp.reply
                            if (reply is DhtMessage.Reply.Peers) {
                                peers.addAll(reply.values)
                                state.addAll(reply.nodes)
                                state.addAll(reply.nodes6) // BEP-32: consume nodes6
                                reply.token?.let { tokenByNode[c.node.id] = it }
                            } else if (reply is DhtMessage.Reply.Nodes) {
                                state.addAll(reply.nodes)
                                state.addAll(reply.nodes6)
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val closest = state.closestAlive().mapNotNull { node ->
            tokenByNode[node.id]?.let { node to it }
        }
        return GetPeersResult(peers.toList(), closest)
    }

    /** Announce that we are a peer for [infoHash] on [port] to the closest nodes that gave us a token. */
    suspend fun announce(infoHash: Sha1Hash, port: Int) {
        val result = getPeers(infoHash)
        coroutineScope {
            result.closestWithTokens.map { (node, token) ->
                async {
                    val ep = node.endpoint
                    rpc.query(ep.addressLiteral(), ep.port) { tid ->
                        DhtMessage.buildAnnouncePeerQuery(tid, nodeId, infoHash, port, token)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun lookup(target: Sha1Hash, maxRounds: Int = 12) {
        val state = TraversalState(target)
        seed(state, target)
        var rounds = 0
        while (!state.isDone() && rounds++ < maxRounds) {
            val batch = state.next()
            if (batch.isEmpty()) break
            coroutineScope {
                batch.map { c ->
                    async {
                        val ep = c.node.endpoint
                        val resp = rpc.query(ep.addressLiteral(), ep.port) { tid ->
                            withWant(DhtMessage.buildFindNodeQuery(tid, nodeId, target))
                        }
                        if (resp == null) {
                            state.fail(c)
                            nodeFailed(c.node)
                        } else {
                            state.alive(c)
                            absorbNodes(resp, ep.addressLiteral(), ep.port)
                            (resp.reply as? DhtMessage.Reply.Nodes)?.let {
                                state.addAll(it.nodes)
                                state.addAll(it.nodes6) // BEP-32
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun seed(state: TraversalState, target: Sha1Hash) {
        for (entry in routingTable.findNode(target, K_BUCKET)) {
            val ep = DhtEndpoint.of(entry.host, entry.port) ?: continue
            state.add(CompactNode(entry.id, ep))
        }
    }

    private fun absorbNodes(resp: DhtMessage.Response, host: String, port: Int) {
        sawNode(resp.nodeId, host, port, RTT_OK)
        val nodes: List<CompactNode> = when (val r = resp.reply) {
            is DhtMessage.Reply.Nodes -> r.nodes + r.nodes6
            is DhtMessage.Reply.Peers -> r.nodes + r.nodes6
            is DhtMessage.Reply.Item -> r.nodes + r.nodes6
            else -> emptyList()
        }
        for (n in nodes) sawNode(n.id, n.endpoint.addressLiteral(), n.endpoint.port, RTT_UNKNOWN)
    }

    /**
     * Append `want:["n4","n6"]` to an outgoing query envelope (BEP-32). We always ask for
     * both families so a responder gives us `nodes` and `nodes6`; the traversal merges
     * whatever it gets. Builds the list on the parsed [Entry] [DhtMessage.build*Query]
     * returned.
     */
    private fun withWant(query: Entry): Entry {
        val list = Entry.list()
        list.items.add(Entry.of("n4"))
        list.items.add(Entry.of("n6"))
        (query.findKey("a") ?: query)["want"] = list
        return query
    }

    /** Drive [RoutingTable.nodeFailed] for a node whose RPC timed out, so it can evict/replace. */
    private fun nodeFailed(node: CompactNode) {
        if (node.id == nodeId) return
        runCatching { routingTable.nodeFailed(node.id, node.endpoint.addressLiteral(), node.endpoint.port) }
    }

    // --- server side: answer inbound queries -------------------------------------

    private suspend fun handleQuery(query: DhtMessage.Query, host: String, port: Int) {
        sawNode(query.nodeId, host, port, RTT_UNKNOWN)
        val addrBytes = PeerAddress.parseOrNull(host)?.bytes ?: ByteArray(0)
        val tid = query.transactionId

        val response: io.github.yuroyami.kitetorrent.bencode.Entry = when (val a = query.args) {
            DhtMessage.Args.Ping -> DhtMessage.buildPingResponse(tid, nodeId)

            is DhtMessage.Args.FindNode ->
                DhtMessage.buildNodesResponse(tid, nodeId, closestCompact(a.target))

            is DhtMessage.Args.GetPeers -> {
                val token = tokens.generate(addrBytes, a.infoHash)
                val stored = storage.getPeers(a.infoHash, wantV4 = true, now = clock(), noseed = a.noseed)
                DhtMessage.buildPeersResponse(
                    tid, nodeId, token,
                    values = stored?.peers ?: emptyList(),
                    nodes = if (stored?.peers.isNullOrEmpty()) closestCompact(a.infoHash) else emptyList(),
                )
            }

            is DhtMessage.Args.AnnouncePeer -> {
                if (tokens.verify(a.token, addrBytes, a.infoHash)) {
                    val p = if (a.impliedPort) port else a.port
                    DhtEndpoint.of(host, p)?.let { storage.announcePeer(a.infoHash, it, clock(), a.seed, a.name) }
                    DhtMessage.buildPingResponse(tid, nodeId) // bare {id} ack
                } else {
                    DhtMessage.buildError(tid, "bad token", 203)
                }
            }

            // BEP-44 get: return the stored item (immutable preferred, else mutable),
            // a write token, and the closest nodes. Port of the `get` branch of
            // node::incoming_request.
            is DhtMessage.Args.Get -> {
                val token = tokens.generate(addrBytes, a.target)
                val nodes = closestCompact(a.target)
                val item: DhtItem? = if (a.seq == null) {
                    storage.getImmutableItem(a.target) ?: storage.getMutableItem(a.target)
                } else {
                    storage.getMutableItem(a.target)
                }
                // libtorrent withholds `v`/`sig`/`k` when the requester's seq is already current.
                val storedSeq: Long? = (item as? MutableItem)?.seq
                val reqSeq = a.seq
                val includeValue = reqSeq == null || storedSeq == null || storedSeq > reqSeq
                DhtMessage.buildItemResponse(tid, nodeId, token, item, nodes, includeValue = includeValue)
            }

            // BEP-44 put: verify the write token, validate the item, store it (with
            // CAS / old-sequence checks for a mutable put). Port of the `put` branch.
            is DhtMessage.Args.Put -> handlePut(tid, a, addrBytes)

            else -> DhtMessage.buildError(tid, "method unknown", 204)
        }
        socket.send(Bencode.encode(response), host, port)
    }

    /**
     * Answer a BEP-44 `put`. Mirrors `node::incoming_request`'s `put` branch: token
     * verification first (against the item's target), then a size/salt sanity pass, then
     * the immutable/mutable store. A mutable put honours `cas` (301 mismatch) and the
     * monotonic-sequence rule (302 old sequence). On success the bare `{id}` ack is sent.
     */
    private fun handlePut(tid: ByteArray, a: DhtMessage.Args.Put, addrBytes: ByteArray): Entry {
        val item = a.item
        // The token is bound to the item's target (info-hash slot), as in libtorrent.
        if (!tokens.verify(a.token, addrBytes, item.target)) {
            return DhtMessage.buildError(tid, "invalid token", 203)
        }
        if (item.bencodedValue.isEmpty() || item.bencodedValue.size > DhtKeySizes.MAX_VALUE_LEN) {
            return DhtMessage.buildError(tid, "message too big", 205)
        }
        when (item) {
            is ImmutableItem -> storage.putImmutableItem(item, clock())
            is MutableItem -> {
                if (item.salt.size > DhtKeySizes.MAX_SALT_LEN) {
                    return DhtMessage.buildError(tid, "salt too big", 207)
                }
                if (item.seq < 0L) {
                    return DhtMessage.buildError(tid, "invalid (negative) sequence number", 203)
                }
                if (!item.verify()) {
                    return DhtMessage.buildError(tid, "invalid signature", 206)
                }
                val itemSeq = storage.getMutableItemSeq(item.target)
                if (itemSeq != null) {
                    if (a.cas != null && itemSeq != a.cas) {
                        return DhtMessage.buildError(tid, "CAS mismatch", 301)
                    }
                    if (itemSeq > item.seq) {
                        return DhtMessage.buildError(tid, "old sequence number", 302)
                    }
                }
                storage.putMutableItem(item, clock())
            }
        }
        return DhtMessage.buildPingResponse(tid, nodeId) // bare {id} ack
    }

    private fun closestCompact(target: Sha1Hash): List<CompactNode> =
        routingTable.findNode(target, K_BUCKET).mapNotNull { e ->
            DhtEndpoint.of(e.host, e.port)?.let { CompactNode(e.id, it) }
        }

    /**
     * Answer a query [DhtMessage.parse] could not model. The only such query is
     * `sample_infohashes` (BEP-51). The reply carries `interval`, `num` (how many
     * info-hashes we track), an empty `samples` blob, the write token and the closest
     * nodes. That is a valid response even when we cannot enumerate the stored
     * info-hashes.
     */
    private suspend fun handleRawQuery(raw: BdecodeNode, host: String, port: Int) {
        if (raw.type != BdecodeNode.Type.DICT) return
        if (raw.dictFindStringValue("y") != "q") return
        val name = raw.dictFindStringValue("q")
        val tNode = raw.dictFindString("t")
        if (!tNode.isValid) return
        val tid = tNode.stringBytes()
        val a = raw.dictFindDict("a")
        if (!a.isValid) return
        val idNode = a.dictFindString("id")
        if (idNode.isValid && idNode.stringLength() == NODE_ID_SIZE) {
            sawNode(Sha1Hash.of(idNode.stringBytes()), host, port, RTT_UNKNOWN)
        }
        if (name != "sample_infohashes") {
            socket.send(Bencode.encode(DhtMessage.buildError(tid, "unknown message", 204)), host, port)
            return
        }
        val targetNode = a.dictFindString("target")
        if (!targetNode.isValid || targetNode.stringLength() != NODE_ID_SIZE) {
            socket.send(Bencode.encode(DhtMessage.buildError(tid, "invalid arguments", 203)), host, port)
            return
        }
        val target = Sha1Hash.of(targetNode.stringBytes())
        val addrBytes = PeerAddress.parseOrNull(host)?.bytes ?: ByteArray(0)
        val reply = Entry.dict()
        reply["t"] = Entry.of(tid)
        reply["y"] = Entry.of("r")
        val r = Entry.dict()
        r["id"] = Entry.of(nodeId.toByteArray())
        r["token"] = Entry.of(tokens.generate(addrBytes, target))
        r["interval"] = Entry.of(SAMPLE_INFOHASHES_INTERVAL.toLong())
        r["num"] = Entry.of(storage.torrentCount.toLong())
        r["samples"] = Entry.of(ByteArray(0)) // we don't enumerate stored info-hashes
        val nodes = closestCompact(target)
        if (nodes.isNotEmpty()) r["nodes"] = Entry.of(CompactNodes.encode(nodes))
        reply["r"] = r
        socket.send(Bencode.encode(reply), host, port)
    }

    // --- BEP-44 immutable / mutable get & put ------------------------------------

    /**
     * Fetch a BEP-44 item from the DHT. This is the client port of `node::get_item`
     * and its traversal. Runs a `get` traversal toward [target], merging closer
     * nodes from each reply, and returns the best item found (the highest-`seq` verified
     * mutable item, or the immutable item) together with the closest responders and their
     * write tokens (so a follow-up [putItem] can re-publish without a second lookup).
     *
     * @param mutableSeq if non-null, an optional `seq` hint to add to the query so peers
     *   may withhold a value the caller already has.
     */
    suspend fun getItem(target: Sha1Hash, mutableSeq: Long? = null, maxRounds: Int = 12): GetItemResult {
        val state = TraversalState(target)
        seed(state, target)

        var best: DhtItem? = null
        var bestSeq = Long.MIN_VALUE
        val tokenByNode = HashMap<Sha1Hash, ByteArray>()
        var rounds = 0
        while (!state.isDone() && rounds++ < maxRounds) {
            val batch = state.next()
            if (batch.isEmpty()) break
            coroutineScope {
                batch.map { c ->
                    async {
                        val ep = c.node.endpoint
                        val resp = rpc.query(ep.addressLiteral(), ep.port) { tid ->
                            withWant(DhtMessage.buildGetQuery(tid, nodeId, target, mutableSeq))
                        }
                        if (resp == null) {
                            state.fail(c)
                            nodeFailed(c.node)
                        } else {
                            state.alive(c)
                            sawNode(resp.nodeId, ep.addressLiteral(), ep.port, RTT_OK)
                            val reply = resp.reply
                            if (reply is DhtMessage.Reply.Item) {
                                state.addAll(reply.nodes)
                                state.addAll(reply.nodes6)
                                reply.token?.let { tokenByNode[c.node.id] = it }
                                reply.item?.let { item ->
                                    val seq = (item as? MutableItem)?.seq ?: 0L
                                    if (best == null || seq > bestSeq) {
                                        best = item
                                        bestSeq = seq
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        val closest = state.closestAlive().mapNotNull { node ->
            tokenByNode[node.id]?.let { node to it }
        }
        return GetItemResult(best, closest, if (bestSeq == Long.MIN_VALUE) null else bestSeq)
    }

    /**
     * Result of [getItem]: the best item found (or null), the closest token-bearing nodes,
     * and [observedSeq], the highest mutable sequence number seen across responders. Use
     * that as the `cas` value for a following mutable [putItem]. It is null if nothing
     * was found.
     */
    class GetItemResult(
        val item: DhtItem?,
        val closestWithTokens: List<Pair<CompactNode, ByteArray>>,
        val observedSeq: Long?,
    )

    /**
     * Store a BEP-44 item in the DHT. This is the client port of `node::put_item`. It runs a
     * get→(optional cas)→put: a [getItem] traversal locates the closest nodes and their
     * write tokens, then a `put` is fired to each. For a mutable [item], the discovered
     * highest stored sequence number is supplied as `cas` so a concurrent writer can't be
     * silently clobbered (libtorrent's compare-and-swap). Returns the number of nodes the
     * put was accepted by (a non-error reply).
     */
    suspend fun putItem(item: DhtItem): Int {
        val seqHint = (item as? MutableItem)?.seq
        val lookup = getItem(item.target, mutableSeq = seqHint)
        // For a mutable put, bind the put to the sequence we observed so a racing writer
        // with a newer value triggers a CAS mismatch rather than an overwrite.
        val cas: Long? = if (item is MutableItem) lookup.observedSeq else null
        var accepted = 0
        coroutineScope {
            lookup.closestWithTokens.map { (node, token) ->
                async {
                    val ep = node.endpoint
                    val resp = runCatching {
                        rpc.query(ep.addressLiteral(), ep.port) { tid ->
                            DhtMessage.buildPutQuery(tid, nodeId, token, item, cas)
                        }
                    }.getOrNull()
                    if (resp != null) accepted++
                }
            }.awaitAll()
        }
        return accepted
    }

    /**
     * BEP-51 client: ask [host]:[port] for a sample of the info-hashes it tracks. Returns
     * the decoded sample (20-byte info-hashes) plus the closest nodes it pointed us at, or
     * null on timeout/parse failure. This is the querier half; most callers only need it
     * for DHT-wide content indexing.
     */
    suspend fun sampleInfohashes(host: String, port: Int, target: Sha1Hash = generateRandomId(random)): SampleResult? {
        val build: (ByteArray) -> Entry = { tid ->
            val e = Entry.dict()
            e["t"] = Entry.of(tid)
            e["y"] = Entry.of("q")
            e["q"] = Entry.of("sample_infohashes")
            val a = Entry.dict()
            a["id"] = Entry.of(nodeId.toByteArray())
            a["target"] = Entry.of(target.toByteArray())
            e["a"] = a
            e
        }
        val resp = rpc.query(host, port, build) ?: return null
        sawNode(resp.nodeId, host, port, RTT_OK)
        val r = resp.raw ?: return SampleResult(emptyList(), emptyList(), 0, 0)
        val samplesBytes = r.dictFindString("samples").takeIf { it.isValid }?.stringBytes() ?: ByteArray(0)
        val infohashes = ArrayList<Sha1Hash>(samplesBytes.size / NODE_ID_SIZE)
        var off = 0
        while (off + NODE_ID_SIZE <= samplesBytes.size) {
            infohashes.add(Sha1Hash.of(samplesBytes, off, NODE_ID_SIZE))
            off += NODE_ID_SIZE
        }
        val nodes = r.dictFindString("nodes").takeIf { it.isValid }
            ?.let { CompactNodes.decodeV4(it.stringBytes()) } ?: emptyList()
        val nodes6 = r.dictFindString("nodes6").takeIf { it.isValid }
            ?.let { CompactNodes.decodeV6(it.stringBytes()) } ?: emptyList()
        return SampleResult(
            infohashes = infohashes,
            nodes = nodes + nodes6,
            num = r.dictFindIntValue("num", 0L).toInt(),
            interval = r.dictFindIntValue("interval", 0L).toInt(),
        )
    }

    /** Result of a BEP-51 `sample_infohashes` query. */
    class SampleResult(
        val infohashes: List<Sha1Hash>,
        val nodes: List<CompactNode>,
        val num: Int,
        val interval: Int,
    )

    // --- maintenance -------------------------------------------------------------

    /**
     * Periodic upkeep: the session-layer port of `node::tick` and `dht_tracker`'s timers.
     * Call on a steady cadence (libtorrent ticks the refresh timer every 5 s and the
     * storage/token timers on slower multiples; ~30 s is a sensible single cadence here):
     *
     *  1. **Self-refresh.** When the table is shallow (`depth < 4`) we run a `find_node`
     *     toward a random id in-range of our own, expanding the buckets nearest us.
     *  2. **Stale-bucket refresh.** For each bucket we pick a random in-range target and
     *     run a short lookup, pulling fresh nodes and exercising the ones we have.
     *  3. **Ping questionable nodes.** Live nodes with at least one failure are re-pinged;
     *     a timeout drives [RoutingTable.nodeFailed] so they evict/replace.
     *  4. **Token + storage ticks.** Rotates the write-token secret every
     *     [TOKEN_ROTATE_SECONDS] and ages out the storage every [STORAGE_TICK_SECONDS].
     *  5. **DoS window reset.** Clears the per-source query counters once per window.
     *
     * All RPC work is best-effort; a dead node simply marks failed.
     */
    suspend fun maintain() {
        val now = clock()

        // 5. DoS window reset.
        if (now - dosWindowStart >= DOS_WINDOW_SECONDS) {
            sourceQueryCounts.clear()
            dosWindowStart = now
        }

        // 4. token rotation + storage expiry (time-driven; clock() supplies seconds).
        if (now - lastTokenRotate >= TOKEN_ROTATE_SECONDS) {
            tokens.rotateSecret()
            lastTokenRotate = now
        }
        if (now - lastStorageTick >= STORAGE_TICK_SECONDS) {
            storage.tick(now)
            lastStorageTick = now
        }

        // 1. self-refresh when the table is shallow.
        if (routingTable.numActiveBuckets() < 4 || routingTable.depth() < 4) {
            val target = randomIdInBucket(minOf(routingTable.numActiveBuckets(), 4))
            runCatching { lookup(target, maxRounds = 6) }
        }

        // 2 + 3. refresh stale buckets and re-ping questionable nodes.
        pingQuestionableNodes()
        refreshBuckets()
    }

    /** Alias for [maintain], named to match [DhtStorage.tick] and [DhtTokens]. */
    suspend fun tick() = maintain()

    /**
     * Re-ping live nodes that have failed at least once (`fail_count > 0`); a timeout
     * drives [RoutingTable.nodeFailed]. Snapshots the table first so we don't iterate it
     * while [nodeFailed] mutates it.
     */
    private suspend fun pingQuestionableNodes() {
        val questionable = ArrayList<CompactNode>()
        routingTable.forEachNode { e ->
            if (e.failCount() > 0) {
                DhtEndpoint.of(e.host, e.port)?.let { questionable.add(CompactNode(e.id, it)) }
            }
        }
        if (questionable.isEmpty()) return
        coroutineScope {
            questionable.take(MAX_REFRESH_PINGS).map { node ->
                async {
                    val ep = node.endpoint
                    val resp = rpc.query(ep.addressLiteral(), ep.port) { tid ->
                        DhtMessage.buildPingQuery(tid, nodeId)
                    }
                    if (resp == null) nodeFailed(node)
                    else sawNode(resp.nodeId, ep.addressLiteral(), ep.port, RTT_OK)
                }
            }.awaitAll()
        }
    }

    /**
     * For each active bucket, send a `find_node` toward a random in-range id. This is the
     * port of `node::send_single_refresh` over a non-full bucket. Keeps every bucket populated and
     * exercises its nodes (timeouts evict via [nodeFailed]).
     */
    private suspend fun refreshBuckets() {
        val numBuckets = routingTable.numActiveBuckets()
        if (numBuckets == 0) return
        coroutineScope {
            (0 until numBuckets).map { bucket ->
                async {
                    val target = randomIdInBucket(bucket)
                    runCatching { lookup(target, maxRounds = 4) }
                }
            }.awaitAll()
        }
    }

    /**
     * A random node id that shares our own id's top [bucket] bits, so it lands in bucket
     * [bucket] of our routing table. Port of `send_single_refresh`'s
     * `target = (random & ~mask) | (m_id & mask)` with `mask = generate_prefix_mask(bucket+1)`.
     */
    private fun randomIdInBucket(bucket: Int): Sha1Hash {
        val bits = (bucket + 1).coerceIn(0, NODE_ID_SIZE * 8)
        val mask = generatePrefixMask(bits).toByteArray()
        val rnd = generateRandomId(random).toByteArray()
        val myId = nodeId.toByteArray()
        val out = ByteArray(NODE_ID_SIZE)
        for (i in 0 until NODE_ID_SIZE) {
            val m = mask[i].toInt() and 0xff
            out[i] = (((rnd[i].toInt() and m.inv()) or (myId[i].toInt() and m)) and 0xff).toByte()
        }
        return Sha1Hash.of(out)
    }

    // --- announce re-announce ----------------------------------------------------

    /**
     * Re-announce ourselves as a peer for every info-hash in [infoHashes] on [port]. A
     * session calls this on its DHT announce interval, roughly every 15 minutes. It loops
     * over [announce]. Each announce is independent, so one failure does not stop the
     * others.
     */
    suspend fun reannounce(infoHashes: Iterable<Sha1Hash>, port: Int) {
        for (ih in infoHashes) {
            runCatching { announce(ih, port) }
        }
    }

    // --- BEP-42 secure node id ---------------------------------------------------

    /**
     * Re-derive our node id from our observed external IP per BEP-42 and (optionally) turn
     * on the [enforceNodeId] admission gate. The engine calls this once it learns our
     * external address (e.g. from a router's `ip` reply or the platform). The routing
     * table is rebuilt around the new id ([RoutingTable.updateNodeId]).
     *
     * @param externalIp our external address literal (IPv4 dotted-quad or IPv6 colon-hex).
     * @param enforce whether to also enable the BEP-42 admission gate from now on.
     */
    fun enableSecureId(externalIp: String, enforce: Boolean = true) {
        val ip = PeerAddress.parseOrNull(externalIp) ?: return
        val newId = generateId(ip, random)
        nodeId = newId
        routingTable.updateNodeId(newId)
        if (enforce) enforceNodeId = true
    }

    /** Turn BEP-43 read-only mode on/off at runtime (`dht_read_only`). */
    fun setReadOnly(enabled: Boolean) { readOnly = enabled }

    // --- state persistence -------------------------------------------------------

    /**
     * Serialize the node id and the live routing-table nodes into a bencoded blob the
     * platform can persist and pass back to [restore] on the next start. This is the
     * KiteTorrent analogue of libtorrent's `dht_state` save (`save_state`, `dht_settings`).
     * Only the id and `(host, port)` of every live node is kept; ids are re-confirmed on the next
     * bootstrap, so a stale node simply fails to answer and is dropped.
     */
    fun serialize(): ByteArray {
        val root = Entry.dict()
        root["node_id"] = Entry.of(nodeId.toByteArray())
        val list = Entry.list()
        routingTable.forEachNode { e ->
            DhtEndpoint.of(e.host, e.port)?.let { ep ->
                val n = Entry.dict()
                n["id"] = Entry.of(e.id.toByteArray())
                n["ep"] = Entry.of(ep.encode())
                list.items.add(n)
            }
        }
        root["nodes"] = list
        return Bencode.encode(root)
    }

    /**
     * Restore known nodes from a [serialize] blob, re-seeding the routing table (they're
     * added un-pinged via [RoutingTable.heardAbout]; the next lookup confirms them). The
     * node id is **not** changed here. The constructor and [enableSecureId] own id
     * continuity. The saved id is returned, so a caller can reconstruct with it.
     *
     * @return the persisted node id, or null if [data] was not a valid blob.
     */
    fun restore(data: ByteArray): Sha1Hash? {
        val root = Bdecode.decodeOrNull(data) ?: return null
        if (root.type != BdecodeNode.Type.DICT) return null
        val list = root.dictFindList("nodes")
        if (list.isValid) {
            for (i in 0 until list.listSize()) {
                val n = list.listAt(i)
                if (n.type != BdecodeNode.Type.DICT) continue
                val idNode = n.dictFindString("id")
                val epNode = n.dictFindString("ep")
                if (!idNode.isValid || idNode.stringLength() != NODE_ID_SIZE || !epNode.isValid) continue
                val id = Sha1Hash.of(idNode.stringBytes())
                val epBytes = epNode.stringBytes()
                val ep = when (epBytes.size) {
                    DhtEndpoint.V4_SIZE -> DhtEndpoint.decodeV4(epBytes, 0)
                    DhtEndpoint.V6_SIZE -> DhtEndpoint.decodeV6(epBytes, 0)
                    else -> null
                } ?: continue
                if (dhtNodeAccepted(id, ep.addressLiteral(), enforceNodeId)) {
                    routingTable.heardAbout(id, ep.addressLiteral(), ep.port)
                }
            }
        }
        val savedId = root.dictFindString("node_id")
        return if (savedId.isValid && savedId.stringLength() == NODE_ID_SIZE) {
            Sha1Hash.of(savedId.stringBytes())
        } else null
    }

    // --- DoS guard ---------------------------------------------------------------

    /** `true` if [host] is within its per-window query budget; bumps its counter. */
    private fun admitQuerySource(host: String): Boolean {
        val count = (sourceQueryCounts[host] ?: 0) + 1
        sourceQueryCounts[host] = count
        return count <= maxQueriesPerSource
    }

    private var lastTokenRotate = clock()
    private var lastStorageTick = clock()

    /** Per-source query budget per [DOS_WINDOW_SECONDS] window (`dos_blocker` analogue). */
    private val maxQueriesPerSource = DEFAULT_MAX_QUERIES_PER_SOURCE

    companion object {
        const val K_BUCKET = 8
        private const val RTT_OK = 50
        private const val RTT_UNKNOWN = 0xffff

        /** Write-token secret rotation cadence (libtorrent: 5 minutes). */
        const val TOKEN_ROTATE_SECONDS: Long = 5L * 60L

        /** Storage expiry cadence (libtorrent ticks storage every ~2 minutes). */
        const val STORAGE_TICK_SECONDS: Long = 2L * 60L

        /** BEP-51 `interval` we advertise in a `sample_infohashes` reply (max 21600). */
        const val SAMPLE_INFOHASHES_INTERVAL: Int = 21_600

        /** dos_blocker-lite window length in seconds. */
        const val DOS_WINDOW_SECONDS: Long = 5L

        /** Max queries a single source may send per [DOS_WINDOW_SECONDS] before being dropped. */
        const val DEFAULT_MAX_QUERIES_PER_SOURCE: Int = 30

        /** Cap on how many questionable nodes we re-ping in one [maintain] pass. */
        private const val MAX_REFRESH_PINGS = 8
    }
}

/**
 * BEP-42 routing-table admission gate. With [enforce] off, every node is accepted (legacy
 * compatibility); with it on, a node's id must derive from its source IP ([verifyId]). A
 * non-IP [host] (a hostname) can't be verified, so it's allowed.
 */
internal fun dhtNodeAccepted(id: Sha1Hash, host: String, enforce: Boolean): Boolean {
    if (!enforce) return true
    val ip = PeerAddress.parseOrNull(host) ?: return true
    return verifyId(id, ip)
}
