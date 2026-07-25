package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.peer.PeerAddress

/**
 * One node in the DHT routing table: the pure-Kotlin port of libtorrent's
 * `node_entry` (`include/libtorrent/kademlia/node_entry.hpp`,
 * `src/kademlia/node_entry.cpp`).
 *
 * libtorrent stores the endpoint as a `union_endpoint` (a Boost.Asio
 * address+port). KiteTorrent's `commonMain` has no socket types, so per the DHT
 * module's contract the endpoint is carried as a host [String] plus an [Int]
 * [port]. The host is additionally parsed once into a [PeerAddress] ([addr]) so
 * the routing table can do the things that genuinely need the numeric address:
 * the BEP 42 [verified] check and per-IP de-duplication.
 *
 * **Counters & flags** (ported verbatim):
 *  - [rtt]: the smoothed round-trip time in milliseconds; `0xffff` means
 *    "unknown". [updateRtt] applies libtorrent's `rtt*2/3 + new/3` EWMA.
 *  - [timeoutCount]: consecutive failed requests. The sentinel `0xff` means
 *    "never pinged" (see [pinged]); `0` means "confirmed up" (see [confirmed]).
 *  - [verified]: the node id matches its IP per BEP 42 ([verifyId]). The
 *    "which node is better" ordering ([isBetterThan]) uses it as the primary key.
 *
 * This is a mutable value object (libtorrent mutates entries in place inside the
 * buckets), so it is a plain class with `var` fields rather than a `data class`.
 */
class NodeEntry {

    /** The node's 160-bit id. Defaults to all-zeroes (`node_id{nullptr}`). */
    var id: Sha1Hash

    /** The endpoint host literal (IPv4 dotted-quad or IPv6 colon-hex), as given. */
    val host: String

    /** The endpoint UDP port. */
    val port: Int

    /**
     * The numeric form of [host], or `null` if [host] was not a parseable IP
     * literal. The routing table treats un-parseable hosts as having no IP
     * identity (they can't be verified and don't participate in IP de-dup).
     */
    val addr: PeerAddress?

    /** Smoothed round-trip time in ms; `0xffff` (= [RTT_UNKNOWN]) means unknown. */
    var rtt: Int

    /**
     * Consecutive failures. `0xff` (= [NOT_PINGED]) is the special "we have not
     * pinged this node yet" sentinel; otherwise it counts timeouts in a row.
     */
    var timeoutCount: Int

    /** `true` if the node id is BEP-42-valid for its IP (`verify_id`). */
    var verified: Boolean

    /**
     * Full constructor: port of
     * `node_entry(node_id const&, udp::endpoint const&, int rtt, bool pinged)`.
     *
     * @param pinged whether we have already had a verified response from this
     *   endpoint; if so [timeoutCount] starts at 0 (confirmed), otherwise at the
     *   [NOT_PINGED] sentinel.
     */
    constructor(
        id: Sha1Hash,
        host: String,
        port: Int,
        rtt: Int = RTT_UNKNOWN,
        pinged: Boolean = false,
    ) {
        this.id = id
        this.host = host
        this.port = port
        this.addr = PeerAddress.parseOrNull(host)
        this.rtt = rtt and 0xffff
        this.timeoutCount = if (pinged) 0 else NOT_PINGED
        // verify_id(id_, ep.address()): un-parseable hosts can't be verified.
        this.verified = addr != null && verifyId(id, addr)
    }

    /**
     * Endpoint-only constructor with an unknown (all-zero) id: port of
     * `explicit node_entry(udp::endpoint const& ep)`. Such an entry is
     * "not pinged" and "not verified".
     */
    constructor(host: String, port: Int) : this(Digest32.zeros(NODE_ID_SIZE), host, port)

    /**
     * Update the smoothed [rtt] with a fresh sample. Port of
     * `node_entry::update_rtt(int)`:
     * ```
     * if (new_rtt == 0xffff) return;
     * if (rtt == 0xffff) rtt = new_rtt;
     * else rtt = rtt * 2 / 3 + new_rtt / 3;
     * ```
     */
    fun updateRtt(newRtt: Int) {
        if (newRtt == RTT_UNKNOWN) return
        require(newRtt in 0..0xffff) { "rtt out of range: $newRtt" }
        rtt = if (rtt == RTT_UNKNOWN) newRtt else rtt * 2 / 3 + newRtt / 3
    }

    /** `true` once we've ever pinged this node (`timeout_count != 0xff`). */
    fun pinged(): Boolean = timeoutCount != NOT_PINGED

    /** Mark the node as pinged if it wasn't already (`set_pinged`). */
    fun setPinged() {
        if (timeoutCount == NOT_PINGED) timeoutCount = 0
    }

    /** Record a timed-out request (`timed_out`): bump the fail counter, capped. */
    fun timedOut() {
        if (pinged() && timeoutCount < 0xfe) timeoutCount++
    }

    /** Number of consecutive failures, or 0 if never pinged (`fail_count`). */
    fun failCount(): Int = if (pinged()) timeoutCount else 0

    /** Reset the fail counter to 0 if the node has been pinged (`reset_fail_count`). */
    fun resetFailCount() {
        if (pinged()) timeoutCount = 0
    }

    /** `true` if the node has been pinged and has zero failures (`confirmed`). */
    fun confirmed(): Boolean = timeoutCount == 0

    /**
     * "Smaller is better" ordering from `bool operator<(node_entry const&)`:
     * ```
     * std::make_tuple(!verified, rtt) < std::make_tuple(!rhs.verified, rhs.rtt)
     * ```
     * i.e. verified nodes sort before unverified ones, and within the same
     * verified-ness the lower RTT sorts first. Returns `true` if `this` is
     * strictly better than [other]. Used by the replacement-selection logic in
     * [RoutingTable].
     */
    fun isBetterThan(other: NodeEntry): Boolean {
        // tuple compare on (!verified, rtt). !verified == false (0) is "better".
        val lv = if (verified) 0 else 1
        val rv = if (other.verified) 0 else 1
        if (lv != rv) return lv < rv
        return rtt < other.rtt
    }

    /** Endpoints match iff both host and port are equal (used by `find_node(ep)`). */
    fun sameEndpoint(otherHost: String, otherPort: Int): Boolean =
        host == otherHost && port == otherPort

    override fun toString(): String =
        "NodeEntry(id=${id.toHex()}, $host:$port, rtt=$rtt, fail=${failCount()}, " +
            "pinged=${pinged()}, verified=$verified)"

    companion object {
        /** `rtt` sentinel meaning "round-trip time unknown" (`0xffff`). */
        const val RTT_UNKNOWN: Int = 0xffff

        /** `timeout_count` sentinel meaning "never pinged" (`0xff`). */
        const val NOT_PINGED: Int = 0xff
    }
}
