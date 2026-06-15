package io.github.yuroyami.kitetorrent.peer

import kotlin.random.Random

/**
 * State the owning torrent passes into [PeerList] operations — the port of
 * `struct torrent_state` (`include/libtorrent/peer_list.hpp`).
 *
 * libtorrent deliberately routes the small amount of torrent/session context the
 * peer list needs through this struct rather than giving the peer list a pointer
 * to the torrent, so the data structure stays decoupled and unit-testable. We
 * keep that design exactly: every mutating [PeerList] call that needs to know
 * "are we finished", "how big may the list grow", "what's our external IP" takes
 * a [TorrentState].
 *
 * A couple of fields are *outputs* written back by the peer list:
 *  - [firstTimeSeen] is set by [PeerList.addPeer] to report whether the peer was
 *    brand new;
 *  - [erased] collects every [TorrentPeer] removed during the call, so the caller
 *    can drop any dangling references (in libtorrent, e.g. the piece picker).
 */
class TorrentState {
    /** True if the torrent has finished downloading (affects seed handling). */
    var isFinished: Boolean = false

    /** Whether multiple connections to the same IP (different ports) are allowed. */
    var allowMultipleConnectionsPerIp: Boolean = false

    /** Output: set by [PeerList.addPeer] — true iff the peer was newly added. */
    var firstTimeSeen: Boolean = false

    /** Maximum number of peers to keep in the list before weeding. 0 = unlimited. */
    var maxPeerlistSize: Int = 1000

    /** Minimum seconds between reconnect attempts to the same peer. */
    var minReconnectTime: Int = 60

    /** Diagnostic counter: how many peer-list slots were scanned this call. */
    var loopCounter: Int = 0

    /** Our external IP, for BEP 40 peer ranking. */
    var ip: ExternalIp = ExternalIp.UNKNOWN

    /** The port to pair with our external IP when ranking (our listen port). */
    var port: Int = 0

    /** Failcount at or above which a peer is no longer a connect candidate. */
    var maxFailcount: Int = 3

    /**
     * Output: every [TorrentPeer] removed from the list during the call. The
     * caller should clear references to these and then [clearErased].
     */
    val erased: MutableList<TorrentPeer> = ArrayList()

    /** Clear the [erased] output list before reusing this state. */
    fun clearErased() {
        erased.clear()
    }
}

/**
 * The set of known peers for one torrent — the pure-Kotlin port of libtorrent's
 * `struct peer_list` (`include/libtorrent/peer_list.hpp`, `src/peer_list.cpp`).
 *
 * This is a **pure data structure**: it owns no sockets and performs no I/O. Its
 * job is to remember every endpoint we have heard about ([TorrentPeer]), keep the
 * collection deduplicated and bounded, track how many of them are worth trying to
 * connect to right now ("connect candidates"), and hand back the next best peer to
 * dial via [connectOnePeer]. The actual dialing, handshaking and transfer live in
 * higher layers that v0 does not include.
 *
 * ### What is ported faithfully
 *  - the connect-candidate predicate [isConnectCandidate],
 *  - the candidate **ranking** ([comparePeer] / [findConnectCandidates]) — lower
 *    failcount first, local peers first, least-recently-tried first, then source
 *    rank and BEP 40 peer rank,
 *  - dedupe-on-insert keyed by endpoint, with the peer vector kept sorted by
 *    address,
 *  - list-size weeding ([erasePeers] + the erase-candidate predicates),
 *  - the connect-candidate counter bookkeeping that every mutator nudges,
 *  - [banPeer], [setSeed], [setConnected], [incFailcount], [setFailcount],
 *    [updatePeerPort], [erasePeer], reconnect back-off in candidate selection.
 *
 * ### What is intentionally omitted
 * Anything that requires a live connection or session pointer: the duplicate-
 * connection *resolution* dance in `new_connection` (which decides which of two
 * live sockets to drop), ip/port-filter application (needs a filter + live
 * disconnects), and i2p peers. The connection-lifecycle hooks are reduced to
 * [setConnected] / [onConnectionClosed], which maintain exactly the same
 * candidate bookkeeping the upstream functions do.
 *
 * ### Threading
 * libtorrent's `peer_list` is `single_threaded`; so is this. It is not safe for
 * concurrent use and assumes a single owning torrent task.
 */
class PeerList {

    /**
     * The peers, kept sorted by [PeerAddress] ascending (IPv4 before IPv6, then
     * unsigned-lexicographic) so that dedupe and lookup are binary searches —
     * mirroring `m_peers` and `peer_address_compare`. Peers whose host is not a
     * literal IP ([TorrentPeer.address] == null) sort after all literal ones and
     * are deduped by host:port via a linear fallback.
     */
    private val peers: MutableList<TorrentPeer> = ArrayList()

    /** Number of peers currently flagged [TorrentPeer.seed]. */
    private var seedCount: Int = 0

    /** Snapshot of [TorrentState.isFinished] from the last candidate recompute. */
    private var finished: Boolean = false

    /** Round-robin cursor into [peers] for incremental candidate scanning. */
    private var roundRobin: Int = 0

    /** Cache of pre-ranked good connect candidates, drained by [connectOnePeer]. */
    private val candidateCache: MutableList<TorrentPeer> = ArrayList()

    /** Running count of connect candidates; kept in sync by every mutator. */
    private var connectCandidateCount: Int = 0

    /** Failcount threshold above which a peer stops being a connect candidate. */
    private var maxFailcount: Int = 3

    /** RNG for the randomized weeding cursor (mirrors libtorrent's `random()`). */
    private val random: Random

    /** @param random RNG used for weeding; inject a seeded one for deterministic tests. */
    constructor(random: Random = Random.Default) {
        this.random = random
    }

    // ---- simple observers ---------------------------------------------------

    /** Number of peers in the list (`peer_list::num_peers`). */
    fun numPeers(): Int = peers.size

    /** Number of known seeds (`peer_list::num_seeds`). */
    fun numSeeds(): Int = seedCount

    /** Number of current connect candidates (`peer_list::num_connect_candidates`). */
    fun numConnectCandidates(): Int = connectCandidateCount

    /** Read-only snapshot of the peers, in stored (address-sorted) order. */
    fun peers(): List<TorrentPeer> = peers

    /** True if [p] is in this list (`peer_list::has_peer`). */
    fun hasPeer(p: TorrentPeer): Boolean = peers.contains(p)

    /** Drop all peers and reset all counters (`peer_list::clear`). */
    fun clear() {
        peers.clear()
        candidateCache.clear()
        connectCandidateCount = 0
        seedCount = 0
    }

    // ---- connect-candidate predicate ---------------------------------------

    /**
     * Whether [p] is a connect candidate — the port of
     * `peer_list::is_connect_candidate`. A peer is *not* a candidate if it is
     * already connected, banned, a web seed, not connectable, a seed while we are
     * finished, or has failed too many times. Everything else is a candidate.
     */
    fun isConnectCandidate(p: TorrentPeer): Boolean {
        if (p.connected ||
            p.banned ||
            p.webSeed ||
            !p.connectable ||
            (p.seed && finished) ||
            p.failcount >= maxFailcount
        ) return false
        return true
    }

    private fun updateConnectCandidates(delta: Int) {
        if (delta == 0) return
        connectCandidateCount += delta
        if (connectCandidateCount < 0) connectCandidateCount = 0
    }

    // ---- finding / inserting peers -----------------------------------------

    /**
     * Add (or merge) a peer learned for `(host, port)` from [source] — the port of
     * the IP path of `peer_list::add_peer`. Returns the stored [TorrentPeer]
     * (existing or freshly created), or `null` if the endpoint is rejected.
     *
     * Faithful behaviours:
     *  - **invalid endpoints** are ignored: port 0 or 1, and IPv6 link-local
     *    addresses (which need a scope to be usable);
     *  - **dedupe**: if we already know this endpoint we update the existing record
     *    ([updatePeer]) instead of inserting a duplicate, and set
     *    [TorrentState.firstTimeSeen] to false; a brand-new peer sets it true;
     *  - the new peer is created **connectable** (we have a port for it) with the
     *    given [source] and any [pexFlags] hints folded in.
     *
     * @param host literal IP or hostname. A literal IP enables address-keyed
     *   dedupe; a non-literal host is deduped by exact host:port.
     * @param port the peer's port in `[0, 65535]`.
     * @param source one of the [PeerSource] flags.
     * @param pexFlags optional [PexFlags] hints (seed/utp/holepunch/…).
     * @param state torrent context; [TorrentState.firstTimeSeen] is written.
     */
    fun addPeer(
        host: String,
        port: Int,
        source: Int,
        pexFlags: Int = PexFlags.NONE,
        state: TorrentState = TorrentState(),
    ): TorrentPeer? {
        // ignore obviously invalid entries (port 0/1)
        if (port == 0 || port == 1) return null

        val addr = PeerAddress.parseOrNull(host)
        // reject the unspecified (any) address and IPv6 link-local addresses
        if (addr != null && (addr.isUnspecified || addr.isV6LinkLocal)) return null

        // single-connection-per-IP dedupes by address only (one entry per IP);
        // multi-connection mode dedupes by the full endpoint.
        val existing = findExact(host, port, addr, matchPort = state.allowMultipleConnectionsPerIp)
        return if (existing == null) {
            val p = TorrentPeer(host, port, connectable = true, source = source)
            if (!insertPeer(p, pexFlags, state)) return null
            state.firstTimeSeen = true
            p
        } else {
            updatePeer(existing, source, pexFlags, port)
            state.firstTimeSeen = false
            existing
        }
    }

    /**
     * Locate an existing peer matching `(host, port)`, or `null`. Uses the sorted
     * vector when [addr] is a literal IP; otherwise a linear scan.
     *
     * @param matchPort if true, the [port] must also match (endpoint dedupe, as in
     *   multi-connection-per-IP mode); if false, the first peer with the matching
     *   address is returned regardless of port (address dedupe).
     */
    private fun findExact(host: String, port: Int, addr: PeerAddress?, matchPort: Boolean): TorrentPeer? {
        if (addr != null) {
            // binary search the address-sorted region for the matching address.
            val idx = lowerBound(addr)
            var i = idx
            while (i < peers.size) {
                val a = peers[i].address ?: break
                if (a != addr) break
                if (!matchPort || peers[i].port == port) return peers[i]
                i++
            }
            return null
        }
        // non-literal host: linear fallback (always endpoint-keyed)
        return peers.firstOrNull { it.address == null && it.host == host && it.port == port }
    }

    /**
     * Index of the first peer whose address is >= [addr] (a `std::lower_bound`
     * over the address-sorted prefix). Peers with a null address sort after all
     * literal ones, so they are treated as +infinity here.
     */
    private fun lowerBound(addr: PeerAddress): Int {
        var lo = 0
        var hi = peers.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val a = peers[mid].address
            val cmp = if (a == null) 1 else a.compareTo(addr)
            if (cmp < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Insert a brand-new peer into the sorted vector, applying [pexFlags] and the
     * list-size cap — the port of `peer_list::insert_peer`. Returns false if the
     * peer could not be inserted (list full and weeding couldn't make room, or the
     * peer's only source is resume data and the list is full).
     */
    private fun insertPeer(p: TorrentPeer, pexFlags: Int, state: TorrentState): Boolean {
        val maxSize = state.maxPeerlistSize
        if (maxSize != 0 && peers.size >= maxSize) {
            if (p.source == PeerSource.RESUME_DATA) return false
            erasePeers(state, forceErase = false)
            if (peers.size >= maxSize) return false
        }

        val insertAt = p.address?.let { lowerBound(it) } ?: peers.size
        peers.add(insertAt, p)
        if (roundRobin >= insertAt) roundRobin++

        applyPexFlags(p, pexFlags)

        if (isConnectCandidate(p)) updateConnectCandidates(1)
        return true
    }

    /** Fold the soft [PexFlags] hints into a peer, per `insert_peer`/`update_peer`. */
    private fun applyPexFlags(p: TorrentPeer, pexFlags: Int, fromUpdate: Boolean = false) {
        if (pexFlags and PexFlags.ENCRYPTION != 0) p.peSupport = true
        if (pexFlags and PexFlags.SEED != 0) {
            // update_peer only trusts the seed hint if we're not connected
            if (!fromUpdate || !p.connected) p.maybeUploadOnly = true
        }
        if (pexFlags and PexFlags.UTP != 0) p.supportsUtp = true
        if (pexFlags and PexFlags.HOLEPUNCH != 0) p.supportsHolepunch = true
        if (pexFlags and PexFlags.LT_V2 != 0) p.protocolV2 = true
    }

    /**
     * Merge new information into an already-known peer — the port of
     * `peer_list::update_peer`. Marks it connectable, refreshes its [port],
     * accumulates the [source] bit, decrements failcount once if the news came
     * from a tracker (somebody can evidently reach it), folds in [pexFlags], and
     * keeps the candidate counter in sync.
     */
    private fun updatePeer(p: TorrentPeer, source: Int, pexFlags: Int, port: Int) {
        val wasCand = isConnectCandidate(p)
        p.connectable = true
        p.port = port and 0xFFFF
        p.source = p.source or source

        // if it failed before, give it another try when a *tracker* re-offers it
        if (p.failcount > 0 && source == PeerSource.TRACKER) p.failcount--

        applyPexFlags(p, pexFlags, fromUpdate = true)

        if (wasCand != isConnectCandidate(p)) {
            updateConnectCandidates(if (wasCand) -1 else 1)
        }
    }

    /**
     * Update a connected peer's advertised listen [port] — the simplified port of
     * `peer_list::update_peer_port` (the single-connection-per-IP path). Returns
     * false on a duplicate-endpoint collision (caller should drop the connection),
     * true otherwise. Marks the peer connectable and folds in [src].
     */
    fun updatePeerPort(p: TorrentPeer, port: Int, src: Int, state: TorrentState): Boolean {
        if (p.port == port) return true

        if (state.allowMultipleConnectionsPerIp) {
            // if another entry already owns this exact endpoint, this is a dup
            val clash = findExact(p.host, port, p.address, matchPort = true)
            if (clash != null && clash !== p) {
                if (clash.connected) {
                    val wasCand = isConnectCandidate(clash)
                    clash.connectable = true
                    clash.source = clash.source or src
                    if (!wasCand && isConnectCandidate(clash)) updateConnectCandidates(1)
                    erasePeer(p, state)
                    return false
                }
                erasePeer(clash, state)
            }
        }

        val wasCand = isConnectCandidate(p)
        p.port = port and 0xFFFF
        p.source = p.source or src
        p.connectable = true
        if (wasCand != isConnectCandidate(p)) {
            updateConnectCandidates(if (wasCand) -1 else 1)
        }
        return true
    }

    // ---- mutators that touch candidate bookkeeping --------------------------

    /**
     * Ban [p] — the port of `peer_list::ban_peer`. A banned peer is removed from
     * the connect-candidate count and can never become a candidate again. Returns
     * true (upstream always reports the ban as applied).
     */
    fun banPeer(p: TorrentPeer): Boolean {
        if (isConnectCandidate(p)) updateConnectCandidates(-1)
        p.banned = true
        return true
    }

    /**
     * Mark [p] as connected or not — the candidate-bookkeeping core of
     * `peer_list::set_connection` (when connecting) and `connection_closed`'s
     * pointer clearing. Setting connected clears the [TorrentPeer.maybeUploadOnly]
     * hint (we will soon learn the truth) and removes the peer from the candidate
     * count; clearing it may make the peer a candidate again.
     */
    fun setConnected(p: TorrentPeer, connected: Boolean) {
        if (p.connected == connected) return
        val wasCand = isConnectCandidate(p)
        p.connected = connected
        if (connected) p.maybeUploadOnly = false
        val nowCand = isConnectCandidate(p)
        if (wasCand != nowCand) updateConnectCandidates(if (nowCand) 1 else -1)
    }

    /**
     * Record that a connection to [p] just closed — the port of the bookkeeping in
     * `peer_list::connection_closed`. Clears the connected flag and optimistic
     * unchoke, optionally stamps [lastConnected] with [sessionTime], bumps the
     * failcount when [failed], and re-counts the peer as a candidate if it now is
     * one. If multiple-connections-per-IP is on and this was a non-connectable
     * incoming peer, the entry is erased (we can't reliably identify it again).
     *
     * @param fastReconnect if true, [lastConnected] is left untouched (mirrors
     *   `!c.fast_reconnect()`).
     */
    fun onConnectionClosed(
        p: TorrentPeer,
        sessionTime: Int,
        failed: Boolean,
        state: TorrentState,
        fastReconnect: Boolean = false,
    ) {
        p.connected = false
        p.optimisticallyUnchoked = false
        if (!fastReconnect) p.lastConnected = sessionTime and 0xFFFF
        if (failed && p.failcount < 31) p.failcount++
        if (isConnectCandidate(p)) updateConnectCandidates(1)

        if (state.allowMultipleConnectionsPerIp && !p.connectable) {
            erasePeer(p, state)
        }
    }

    /**
     * Increment [p]'s failcount (saturating at 31) — the port of
     * `peer_list::inc_failcount`. Updates the candidate count if this pushes the
     * peer past [maxFailcount].
     */
    fun incFailcount(p: TorrentPeer) {
        if (p.failcount == 31) return
        val wasCand = isConnectCandidate(p)
        p.failcount++
        if (wasCand && !isConnectCandidate(p)) updateConnectCandidates(-1)
    }

    /**
     * Set [p]'s failcount to [f] — the port of `peer_list::set_failcount`,
     * adjusting the candidate count in whichever direction the change implies.
     */
    fun setFailcount(p: TorrentPeer, f: Int) {
        val wasCand = isConnectCandidate(p)
        p.failcount = f
        if (wasCand != isConnectCandidate(p)) {
            updateConnectCandidates(if (wasCand) -1 else 1)
        }
    }

    /**
     * Set [p]'s seed flag — the port of `peer_list::set_seed`. Maintains both the
     * connect-candidate count (a seed is not a candidate when we're finished) and
     * the [numSeeds] tally.
     */
    fun setSeed(p: TorrentPeer, s: Boolean) {
        if (p.seed == s) return
        val wasCand = isConnectCandidate(p)
        p.seed = s
        if (wasCand && !isConnectCandidate(p)) updateConnectCandidates(-1)

        if (p.webSeed) return
        if (s) seedCount++ else seedCount--
    }

    /** Clear every cached peer rank — the port of `peer_list::clear_peer_prio`. */
    fun clearPeerPrio() {
        for (p in peers) p.peerRank = 0
    }

    // ---- erase --------------------------------------------------------------

    /**
     * Remove [p] from the list, fixing up every counter and cursor — the port of
     * `peer_list::erase_peer(torrent_peer*)`. The removed peer is appended to
     * [TorrentState.erased]. No-op if [p] is not present.
     */
    fun erasePeer(p: TorrentPeer, state: TorrentState) {
        val index = peers.indexOf(p)
        if (index < 0) return
        erasePeerAt(index, state)
    }

    /** Index-based erase core (`peer_list::erase_peer(iterator)`). */
    private fun erasePeerAt(index: Int, state: TorrentState) {
        val p = peers[index]
        state.erased.add(p)
        if (p.seed) seedCount--
        if (isConnectCandidate(p)) updateConnectCandidates(-1)
        if (roundRobin > index) roundRobin--
        candidateCache.remove(p)
        peers.removeAt(index)
        if (roundRobin >= peers.size) roundRobin = 0
    }

    private fun shouldEraseImmediately(p: TorrentPeer): Boolean =
        p.source == PeerSource.RESUME_DATA

    private fun isEraseCandidate(p: TorrentPeer): Boolean {
        if (p.connected) return false
        if (isConnectCandidate(p)) return false
        return p.failcount > 0 || p.source == PeerSource.RESUME_DATA
    }

    private fun isForceEraseCandidate(p: TorrentPeer): Boolean = !p.connected

    /**
     * Weed the list down toward 95% of [TorrentState.maxPeerlistSize] when it has
     * grown too large — the port of `peer_list::erase_peers`. Scans up to 300
     * peers from a random cursor, picks the best erase candidate via
     * [comparePeerErase] (and erases resume-data-only peers immediately), and as a
     * last resort, if [forceErase] is set, drops the best force-erase candidate.
     */
    private fun erasePeers(state: TorrentState, forceErase: Boolean) {
        val maxSize = state.maxPeerlistSize
        if (maxSize == 0 || peers.isEmpty()) return

        if (finished != state.isFinished) recalculateConnectCandidates(state)

        var eraseCandidate = -1
        var forceEraseCandidate = -1

        var rr = if (peers.size <= 1) 0 else random.nextInt(peers.size - 1)

        var lowWatermark = maxSize * 95 / 100
        if (lowWatermark == maxSize) lowWatermark--

        var iterations = if (peers.size < 300) peers.size else 300
        while (iterations > 0) {
            if (peers.size < lowWatermark) break
            if (rr == peers.size) rr = 0

            val pe = peers[rr]
            val current = rr

            if (isEraseCandidate(pe) &&
                (eraseCandidate == -1 || !comparePeerErase(peers[eraseCandidate], pe))
            ) {
                if (shouldEraseImmediately(pe)) {
                    if (eraseCandidate > current) eraseCandidate--
                    if (forceEraseCandidate > current) forceEraseCandidate--
                    erasePeerAt(current, state)
                    iterations--
                    continue
                } else {
                    eraseCandidate = current
                }
            }
            if (isForceEraseCandidate(pe) &&
                (forceEraseCandidate == -1 || !comparePeerErase(peers[forceEraseCandidate], pe))
            ) {
                forceEraseCandidate = current
            }
            rr++
            iterations--
        }

        if (eraseCandidate > -1) {
            erasePeerAt(eraseCandidate, state)
        } else if (forceErase && forceEraseCandidate > -1) {
            erasePeerAt(forceEraseCandidate, state)
        }
    }

    // ---- candidate selection ------------------------------------------------

    /**
     * Recompute the connect-candidate count from scratch and snapshot the
     * finished / max-failcount state — the port of
     * `peer_list::recalculate_connect_candidates`. Called whenever the finished
     * state flips or the failcount threshold changes, since both alter which
     * peers count.
     */
    fun recalculateConnectCandidates(state: TorrentState) {
        connectCandidateCount = 0
        finished = state.isFinished
        maxFailcount = state.maxFailcount
        for (p in peers) if (isConnectCandidate(p)) connectCandidateCount++
    }

    /** Re-evaluate the candidate count if [TorrentState.maxFailcount] changed. */
    fun setMaxFailcount(state: TorrentState) {
        if (state.maxFailcount == maxFailcount) return
        recalculateConnectCandidates(state)
    }

    /**
     * Build a small, ranked list of the best connect candidates into [out] — the
     * port of `peer_list::find_connect_candidates`. Scans up to 300 peers from the
     * round-robin cursor, skips peers that are not candidates or are still within
     * their reconnect back-off window, and keeps the best 10 sorted by
     * [comparePeer]. Also opportunistically weeds the list while scanning if it is
     * near the size cap.
     *
     * @param sessionTime current session time (seconds) for the reconnect back-off.
     */
    private fun findConnectCandidates(out: MutableList<TorrentPeer>, sessionTime: Int, state: TorrentState) {
        val candidateCount = 10

        var eraseCandidate = -1

        if (finished != state.isFinished) recalculateConnectCandidates(state)

        val external = state.ip
        val externalPort = state.port

        if (roundRobin >= peers.size) roundRobin = 0
        val maxSize = state.maxPeerlistSize

        var iterations = if (peers.size < 300) peers.size else 300
        while (iterations > 0) {
            state.loopCounter++
            if (roundRobin >= peers.size) roundRobin = 0

            val pe = peers[roundRobin]
            val current = roundRobin

            // weed while we're near the cap
            if (maxSize > 0 && peers.size >= maxSize * 0.95) {
                if (isEraseCandidate(pe) &&
                    (eraseCandidate == -1 || !comparePeerErase(peers[eraseCandidate], pe))
                ) {
                    if (shouldEraseImmediately(pe)) {
                        if (eraseCandidate > current) eraseCandidate--
                        erasePeerAt(current, state)
                        iterations--
                        continue
                    } else {
                        eraseCandidate = current
                    }
                }
            }

            roundRobin++

            if (!isConnectCandidate(pe)) {
                iterations--
                continue
            }

            // reconnect back-off: (failcount+1) * min_reconnect_time seconds
            if (pe.lastConnected != 0 &&
                sessionTime - pe.lastConnected < (pe.failcount + 1) * state.minReconnectTime
            ) {
                iterations--
                continue
            }

            // if the list is full and the worst kept candidate already beats pe,
            // skip pe
            if (out.size == candidateCount &&
                comparePeer(out[out.size - 1], pe, external, externalPort, finished)
            ) {
                iterations--
                continue
            }

            if (out.size >= candidateCount) {
                // drop the worst before inserting
                while (out.size >= candidateCount) out.removeAt(out.size - 1)
            }

            // insert pe into out keeping it sorted best-first by comparePeer
            val insertAt = lowerBoundCandidate(out, pe, external, externalPort, finished)
            out.add(insertAt, pe)
            iterations--
        }

        if (eraseCandidate > -1) erasePeerAt(eraseCandidate, state)
    }

    /**
     * `std::lower_bound` over a best-first candidate list using [comparePeer] as
     * the strict-weak ordering ("lhs is a better candidate than rhs"). Returns the
     * first index whose element is *not better* than [pe].
     */
    private fun lowerBoundCandidate(
        list: List<TorrentPeer>,
        pe: TorrentPeer,
        external: ExternalIp,
        externalPort: Int,
        finished: Boolean,
    ): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (comparePeer(list[mid], pe, external, externalPort, finished)) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Return the single best peer to try to connect to next, or `null` if there is
     * none — the port of `peer_list::connect_one_peer`. Maintains a cache of
     * pre-ranked candidates: stale entries (no longer candidates) are dropped, the
     * cache is refilled via [findConnectCandidates] when empty, and the front (best)
     * peer is popped and returned. The returned peer is guaranteed to be a current
     * connect candidate (not banned, not connected, connectable).
     *
     * @param sessionTime current session time (seconds) for reconnect back-off.
     */
    fun connectOnePeer(sessionTime: Int, state: TorrentState): TorrentPeer? {
        if (finished != state.isFinished) recalculateConnectCandidates(state)

        // drop cache entries that are no longer candidates
        var i = 0
        while (i < candidateCache.size) {
            if (!isConnectCandidate(candidateCache[i])) candidateCache.removeAt(i)
            else i++
        }

        if (candidateCache.isEmpty()) {
            findConnectCandidates(candidateCache, sessionTime, state)
            if (candidateCache.isEmpty()) return null
        }

        return candidateCache.removeAt(0)
    }

    // ---- comparators --------------------------------------------------------

    /**
     * True if [lhs] is a **better connect candidate** than [rhs] — the port of the
     * file-local `compare_peer` in `src/peer_list.cpp`. The ordering, in priority
     * order:
     *  1. lower [failcount] wins;
     *  2. a [PeerAddress.isLocal] peer wins (try LAN peers first);
     *  3. smaller [lastConnected] wins (least-recently-tried first; 0 = never);
     *  4. when [finished], a peer that is *not* [maybeUploadOnly] wins (de-prioritise
     *     suspected seeds);
     *  5. higher [sourceRank] wins (tracker > LSD > DHT > PEX);
     *  6. higher BEP 40 [TorrentPeer.rank] wins (compared unsigned).
     */
    fun comparePeer(
        lhs: TorrentPeer,
        rhs: TorrentPeer,
        external: ExternalIp,
        externalPort: Int,
        finished: Boolean,
    ): Boolean {
        if (lhs.failcount != rhs.failcount) return lhs.failcount < rhs.failcount

        val lhsLocal = lhs.address?.isLocal ?: false
        val rhsLocal = rhs.address?.isLocal ?: false
        if (lhsLocal != rhsLocal) return lhsLocal // true (local) > false

        if (lhs.lastConnected != rhs.lastConnected) return lhs.lastConnected < rhs.lastConnected

        if (finished && lhs.maybeUploadOnly != rhs.maybeUploadOnly) {
            // prefer the one that is NOT maybe-upload-only -> return rhs.maybeUploadOnly
            return rhs.maybeUploadOnly
        }

        val lhsRank = sourceRank(lhs.source)
        val rhsRank = sourceRank(rhs.source)
        if (lhsRank != rhsRank) return lhsRank > rhsRank

        val lhsPeerRank = lhs.rank(external, externalPort)
        val rhsPeerRank = rhs.rank(external, externalPort)
        // compare as unsigned 32-bit
        return (lhsPeerRank.toLong() and 0xFFFFFFFFL) > (rhsPeerRank.toLong() and 0xFFFFFFFFL)
    }

    /**
     * True if [lhs] is a **better erase candidate** than [rhs] (i.e. should be
     * dropped first) — the port of the file-local `compare_peer_erase`. Priority:
     *  1. higher [failcount] is dropped first;
     *  2. a peer whose only source is resume data is dropped first;
     *  3. a non-[connectable] peer is dropped first;
     *  4. lower [trustPoints] is dropped first.
     */
    fun comparePeerErase(lhs: TorrentPeer, rhs: TorrentPeer): Boolean {
        if (lhs.failcount != rhs.failcount) return lhs.failcount > rhs.failcount

        val lhsResume = lhs.source == PeerSource.RESUME_DATA
        val rhsResume = rhs.source == PeerSource.RESUME_DATA
        if (lhsResume != rhsResume) return lhsResume // true > false

        if (lhs.connectable != rhs.connectable) return !lhs.connectable // non-connectable dropped first

        return lhs.trustPoints < rhs.trustPoints
    }
}
