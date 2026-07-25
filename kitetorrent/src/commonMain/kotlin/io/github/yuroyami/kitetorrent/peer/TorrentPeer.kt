package io.github.yuroyami.kitetorrent.peer

import io.github.yuroyami.kitetorrent.PeerId

/**
 * A single known peer of a torrent. The pure-Kotlin port of libtorrent's
 * `struct torrent_peer` (`include/libtorrent/torrent_peer.hpp`,
 * `src/torrent_peer.cpp`).
 *
 * A `torrent_peer` is **not** a connection. It is the long-lived bookkeeping
 * record for an endpoint we have heard about (from a tracker, the DHT, PEX,
 * LSD, resume data, or an incoming connection), whether or not we are connected
 * to it right now. The [PeerList] owns a sorted collection of these and uses
 * their counters to decide who to (re)connect to.
 *
 * ### Address representation
 * Upstream subclasses `torrent_peer` into `ipv4_peer` / `ipv6_peer` / `i2p_peer`
 * to store the address compactly in a Boost.Asio type. KiteTorrent v0 carries
 * no socket types in `commonMain`, so we keep the address as a plain [host]
 * **string** plus an integer [port]. When a behaviour genuinely needs the
 * numeric address (ranking, locality, canonical ordering) we parse it on demand
 * via [PeerAddress.parseOrNull] and cache the result in [address]. i2p peers are
 * out of scope for v0 and are not modelled.
 *
 * ### Field widths
 * libtorrent packs many of these into bit-fields (`failcount:5`,
 * `trust_points:4`, `source:6`, plus a wall of `bool:1`s) purely to shrink the
 * struct. We use ordinary [Int]/[Boolean] properties. The *semantics* are what
 * matter, and we preserve the documented value ranges in the accessors that
 * mutate them (e.g. [failcount] saturates at 31, mirroring the 5-bit field).
 *
 * Mutability mirrors upstream: a `torrent_peer` is a mutable record poked by the
 * owning [PeerList]; only [host] is immutable here (the endpoint identity),
 * while counters and flags change over the peer's lifetime. The [port] can be
 * updated when a peer advertises its real listen port, exactly as
 * `peer_list::update_peer_port` does.
 */
class TorrentPeer(
    /** The peer's host, as a literal IP string (e.g. `"192.168.0.4"`). Immutable identity. */
    val host: String,
    port: Int,
    connectable: Boolean,
    source: Int,
) {
    /**
     * The port this peer is or was connected on. Mutable: an incoming peer is
     * created with the *source* port of its connection and later updated to its
     * advertised listen port. Held as a 16-bit unsigned value in `[0, 65535]`.
     */
    var port: Int = port and 0xFFFF
        internal set

    /**
     * Lazily-parsed numeric form of [host], or `null` if [host] is not a literal
     * IP (e.g. an unresolved name). Cached on first access. Used for ranking,
     * locality and the canonical address ordering.
     */
    val address: PeerAddress? by lazy { PeerAddress.parseOrNull(host) }

    // ---- accumulated transfer (kiB; shift left 10 to compare to byte counters) ----

    /** Bytes uploaded to this peer during the last closed connection, in kiB. */
    var prevAmountUpload: Int = 0
        internal set

    /** Bytes downloaded from this peer during the last closed connection, in kiB. */
    var prevAmountDownload: Int = 0
        internal set

    // ---- connection state ----

    /**
     * Whether we currently hold a live connection to this peer. libtorrent stores
     * a `peer_connection_interface*` here; v0 has no connection layer, so we model
     * only the boolean "is connected" fact that all of the connect-candidate logic
     * actually keys off of. Set through [PeerList.setConnected].
     */
    var connected: Boolean = false
        internal set

    /**
     * Cached BEP 40 peer rank (`peer_priority` of our address and this peer's),
     * computed lazily and memoised. `0` means "not yet computed". Cleared (set to
     * 0) by [PeerList.clearPeerPrio] when our external IP changes. Held as a
     * 32-bit value in an [Int]; read as unsigned when comparing.
     */
    var peerRank: Int = 0
        internal set

    /**
     * Session-time (seconds since session start) at which we last connected to
     * (or disconnected from) this peer. 0 means "never". 16-bit in upstream.
     */
    var lastConnected: Int = 0
        internal set

    /** Session-time of this peer's last optimistic unchoke. 0 means "never". */
    var lastOptimisticallyUnchoked: Int = 0
        internal set

    /** Number of pieces this peer participated in that failed the hash check. `[0, 255]`. */
    var hashFails: Int = 0
        internal set

    /**
     * Number of failed connection attempts to this peer. Saturates at 31 (a 5-bit
     * field upstream); [PeerList.incFailcount] never lets it exceed that.
     */
    var failcount: Int = 0
        internal set

    /**
     * Whether we have a listen port for this peer and may therefore initiate an
     * outgoing connection. Incoming peers that never advertise a port are *not*
     * connectable. This is a precondition of being a connect candidate.
     */
    var connectable: Boolean = connectable
        internal set

    /** True while this peer is unchoked because of an optimistic unchoke. */
    var optimisticallyUnchoked: Boolean = false
        internal set

    /**
     * True once we *know* this peer is a seed because we connected and it told us.
     * Distinct from [maybeUploadOnly], which is only a hint.
     */
    var seed: Boolean = false
        internal set

    /**
     * A soft hint (from PEX `pex_seed`, before we have connected) that this peer
     * is upload-only / probably a seed. Cleared the moment we connect, since we
     * then learn the truth. De-prioritised when we are finished.
     */
    var maybeUploadOnly: Boolean = false
        internal set

    /** Number of fast reconnects we have allowed this peer. `[0, 15]` (4-bit). */
    var fastReconnects: Int = 0
        internal set

    /**
     * Trust score in `[-7, 8]` (a signed 4-bit field upstream): incremented for
     * each valid piece this peer helped deliver, decremented for each bad one.
     * If it sinks far enough the peer is treated as bad. Used as a tie-breaker
     * when choosing which peer to evict ([comparePeerErase]).
     */
    var trustPoints: Int = 0
        internal set

    /**
     * Bitmap of [PeerSource] flags: every channel that has told us about this
     * peer, OR-ed together. Drives [sourceRank] in connect ordering.
     */
    var source: Int = source and 0x3F // 6-bit field upstream
        internal set

    // ---- assorted capability / status flags (single bits upstream) ----

    /** Encryption-support hint. Initially false to prefer plaintext, per upstream. */
    var peSupport: Boolean = false
        internal set

    /** True if this peer is on parole after participating in a failed piece. */
    var onParole: Boolean = false
        internal set

    /** True once this peer has been banned; a banned peer is never a connect candidate. */
    var banned: Boolean = false
        internal set

    /** We think this peer supports uTP. Defaults true (assume support), per upstream. */
    var supportsUtp: Boolean = true
        internal set

    /** We have connected via uTP at least once. */
    var confirmedSupportsUtp: Boolean = false
        internal set

    /** We think this peer supports holepunching. */
    var supportsHolepunch: Boolean = false
        internal set

    /**
     * True for web seeds. Web seeds are not stored in the [PeerList]'s main vector
     * and are exempt from connect-candidate bookkeeping, so a web-seed peer is
     * never a connect candidate. Present for fidelity; v0 does not add web seeds.
     */
    var webSeed: Boolean = false
        internal set

    /** True if this peer supports BitTorrent protocol v2. */
    var protocolV2: Boolean = false
        internal set

    /**
     * The peer id we learned for this peer, or `null` if unknown. Not part of the
     * upstream `torrent_peer` struct (that lives on the connection), but a natural
     * field for a connection-less peer record and called for by the KiteTorrent
     * assignment.
     */
    var peerId: PeerId? = null
        internal set

    /**
     * Total bytes downloaded from this peer (best estimate). With no live
     * connection layer in v0, this is simply [prevAmountDownload] scaled from kiB
     * to bytes. The port of `torrent_peer::total_download`'s offline branch.
     */
    fun totalDownload(): Long = prevAmountDownload.toLong() shl 10

    /** Total bytes uploaded to this peer. See [totalDownload]. */
    fun totalUpload(): Long = prevAmountUpload.toLong() shl 10

    /**
     * The BEP 40 rank of this peer relative to our [external] address. The port
     * of `torrent_peer::rank`. Computed lazily and cached in [peerRank]. Returns
     * 0 if either our address (of the matching family) or this peer's address is
     * unknown, which makes ranking a no-op tie for non-literal hosts.
     *
     * @param external our external IP, used to pick our address of this peer's
     *   family.
     * @param externalPort the port to pair with our address (our listen port).
     */
    fun rank(external: ExternalIp, externalPort: Int): Int {
        if (peerRank == 0) {
            val mine = address?.let { external.externalAddressFor(it) }
            val theirs = address
            if (mine != null && theirs != null) {
                peerRank = peerPriority(mine, externalPort, theirs, port)
            }
        }
        return peerRank
    }

    /**
     * True if this peer's endpoint equals `(otherHost, otherPort)`. Endpoint
     * identity is `(address, port)`. This is the port of the `torrent_peer_equal`
     * predicate for the IP case. Compares by parsed [address] when both hosts are
     * literal IPs (so `"127.0.0.1"` and `"127.000.000.001"` match), falling back
     * to string equality otherwise.
     */
    fun matchesEndpoint(otherHost: String, otherPort: Int): Boolean {
        if (port != otherPort) return false
        val a = address
        val b = PeerAddress.parseOrNull(otherHost)
        return if (a != null && b != null) a == b else host == otherHost
    }

    override fun toString(): String = "$host:$port"
}
