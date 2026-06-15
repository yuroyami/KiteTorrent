package io.github.yuroyami.kitetorrent.peer

/**
 * Our externally-visible IP address — the slimmed port of libtorrent's
 * `external_ip` (`include/libtorrent/ip_voter.hpp`) for what the peer list
 * actually needs.
 *
 * libtorrent votes on its external address (it may look different to v4 vs v6
 * peers) and feeds it into peer-priority ranking so the swarm forms a stable,
 * well-connected topology (see http://blog.libtorrent.org/2012/12/swarm-connectivity/
 * and BEP 40). For the peer-list port we only need a single side: given a remote
 * peer's address, what is *our* address of the matching family to hash against
 * it.
 *
 * @property externalV4 our external IPv4 literal, or `null` if unknown.
 * @property externalV6 our external IPv6 literal, or `null` if unknown.
 */
class ExternalIp(
    private val externalV4: PeerAddress? = null,
    private val externalV6: PeerAddress? = null,
) {
    /**
     * Return our external address of the same family as [forRemote] — the
     * analogue of `external_ip::external_address(address const&)`. Returns
     * `null` if we don't have an address of that family, in which case the
     * caller skips priority ranking for that peer.
     */
    fun externalAddressFor(forRemote: PeerAddress): PeerAddress? =
        if (forRemote.isV6) externalV6 else externalV4

    companion object {
        /** An [ExternalIp] with no known address — ranking degrades gracefully. */
        val UNKNOWN: ExternalIp = ExternalIp(null, null)
    }
}

/**
 * Compute the BEP 40 *peer priority* of the (ordered) pair of endpoints
 * `(addr1:port1)` and `(addr2:port2)` — the pure-Kotlin port of
 * `std::uint32_t peer_priority(tcp::endpoint, tcp::endpoint)` from
 * `src/torrent_peer.cpp`. One endpoint should be our own and the other the
 * peer's; the function is symmetric, so which is which does not matter.
 *
 * The algorithm (https://www.bittorrent.org/beps/bep_0040.html), reproduced
 * faithfully:
 *  1. **Same address** — CRC32C the two 16-bit ports in network byte order,
 *     lower port first.
 *  2. **Different IPv4** — order the endpoints, then mask both addresses:
 *     `0xffff5555` if they differ in the first two bytes, `0xffffff55` if they
 *     share `/16` but differ in the third byte, otherwise `0xffffffff`
 *     (i.e. same `/24` keeps all bytes). CRC32C the concatenation, lower first.
 *  3. **Different IPv6** — order the endpoints; never mask the first 6 bytes,
 *     and from the first differing byte onward (but at index 5 at the earliest)
 *     mask each remaining byte with `0x55`. CRC32C the 32-byte concatenation.
 *
 * Both endpoints must be the same address family (the BEP requires it); this is
 * the caller's responsibility, exactly as upstream `TORRENT_ASSERT`s it.
 *
 * The result is returned as a 32-bit value held in an [Int] (read it as unsigned
 * via [Int.toUInt]/`and 0xFFFFFFFFL` when a magnitude is needed). [TorrentPeer.rank]
 * compares these as unsigned, matching `std::uint32_t`.
 */
fun peerPriority(
    addr1: PeerAddress,
    port1: Int,
    addr2: PeerAddress,
    port2: Int,
): Int {
    if (addr1 == addr2) {
        // hash the two ports, lower first, each as 2 big-endian bytes
        var pLo = port1
        var pHi = port2
        if (pLo > pHi) {
            val t = pLo; pLo = pHi; pHi = t
        }
        val buf = ByteArray(4)
        buf[0] = ((pLo ushr 8) and 0xFF).toByte()
        buf[1] = (pLo and 0xFF).toByte()
        buf[2] = ((pHi ushr 8) and 0xFF).toByte()
        buf[3] = (pHi and 0xFF).toByte()
        return Crc32c.compute(buf)
    }

    // order the two endpoints; "lower first" uses endpoint ordering (address,
    // then port), matching tcp::endpoint::operator<.
    var a = addr1
    var ap = port1
    var b = addr2
    var bp = port2
    if (!endpointLess(a, ap, b, bp)) {
        val ta = a; val tap = ap
        a = b; ap = bp
        b = ta; bp = tap
    }

    return if (a.isV6) {
        val b1 = a.bytes.copyOf()
        val b2 = b.bytes.copyOf()
        var offset = 0xFF // sentinel "not yet started masking"
        for (i in b1.indices) {
            if (offset == 0xFF && b1[i] != b2[i]) {
                // earliest mask start is index 5 (per upstream max(i+1, 5))
                offset = if (i + 1 > 5) i + 1 else 5
            } else if (i > offset) {
                b1[i] = (b1[i].toInt() and 0x55).toByte()
                b2[i] = (b2[i].toInt() and 0x55).toByte()
            }
        }
        val buf = ByteArray(32)
        b1.copyInto(buf, 0)
        b2.copyInto(buf, 16)
        Crc32c.compute(buf)
    } else {
        val b1 = a.bytes.copyOf()
        val b2 = b.bytes.copyOf()
        // mask index: 0 -> differ in first 2 bytes, 1 -> share /16 differ at byte 3,
        // 2 -> share /24 (keep all bytes)
        val maskIdx = when {
            b1[0] != b2[0] || b1[1] != b2[1] -> 0
            b1[2] != b2[2] -> 1
            else -> 2
        }
        val mask = V4_MASKS[maskIdx]
        for (i in 0 until 4) {
            b1[i] = (b1[i].toInt() and mask[i]).toByte()
            b2[i] = (b2[i].toInt() and mask[i]).toByte()
        }
        val buf = ByteArray(8)
        b1.copyInto(buf, 0)
        b2.copyInto(buf, 4)
        Crc32c.compute(buf)
    }
}

/** The three IPv4 masks from `src/torrent_peer.cpp`, indexed by shared-prefix length. */
private val V4_MASKS: Array<IntArray> = arrayOf(
    intArrayOf(0xFF, 0xFF, 0x55, 0x55), // differ in first two bytes
    intArrayOf(0xFF, 0xFF, 0xFF, 0x55), // share /16
    intArrayOf(0xFF, 0xFF, 0xFF, 0xFF), // share /24
)

/**
 * `tcp::endpoint::operator<` for the priority computation: compare by address
 * first, then by port. Returns true if `(a,ap)` sorts strictly before `(b,bp)`.
 */
private fun endpointLess(a: PeerAddress, ap: Int, b: PeerAddress, bp: Int): Boolean {
    val c = a.compareTo(b)
    if (c != 0) return c < 0
    return ap < bp
}
