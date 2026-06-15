package io.github.yuroyami.kitetorrent.peer

/**
 * A parsed IP address — the pure-Kotlin stand-in for libtorrent's
 * `libtorrent::address` (Boost.Asio) inside the peer-list port.
 *
 * KiteTorrent v0 deliberately keeps a peer's address as a host **string** (see
 * [TorrentPeer.host]); there are no socket types in `commonMain`. But several
 * faithful behaviours — BEP 40 peer priority ([peerPriority]), local-address
 * preference ([isLocal]), IPv6 link-local rejection, and the canonical address
 * ordering used to keep the peer list sorted — genuinely need the address in its
 * numeric, network-byte-order form. [PeerAddress] provides exactly that: it
 * parses a dotted-quad / colon-hex literal into its raw bytes and exposes the
 * predicates those behaviours require, and nothing more.
 *
 * Only numeric literals are understood (this layer never resolves DNS — that is
 * a platform concern). [parseOrNull] returns `null` for anything it cannot parse
 * as a literal IPv4 or IPv6 address, and callers treat such hosts as opaque.
 *
 * @property bytes the address in network byte order: 4 bytes for IPv4, 16 for
 *   IPv6. The array is owned by this instance and must not be mutated.
 */
class PeerAddress private constructor(val bytes: ByteArray) : Comparable<PeerAddress> {

    /** True if this is an IPv4 (4-byte) address. */
    val isV4: Boolean get() = bytes.size == 4

    /** True if this is an IPv6 (16-byte) address. */
    val isV6: Boolean get() = bytes.size == 16

    /**
     * True for the "unspecified" / any address (`0.0.0.0` or `::`) — the analogue
     * of comparing against a default-constructed `address`. libtorrent's
     * `add_peer` ignores such endpoints.
     */
    val isUnspecified: Boolean get() = isAllZero(0, bytes.size)

    /**
     * True for an IPv6 link-local address (`fe80::/10`) — the port of
     * `address_v6::is_link_local`. libtorrent refuses to add such peers in
     * `peer_list::add_peer` because they require a scope/interface to be usable.
     * Always false for IPv4.
     */
    val isV6LinkLocal: Boolean
        get() = isV6 &&
            (bytes[0].toInt() and 0xFF) == 0xFE &&
            (bytes[1].toInt() and 0xC0) == 0x80

    /**
     * True if this address is "local" in the sense of
     * `aux::is_local(address const&)` (`src/ip_helpers.cpp`). Local peers are
     * always tried first in [comparePeer].
     *
     * IPv4: the RFC 1918 / CGNAT / loopback / link-local ranges
     * (10/8, 172.16/12, 192.168/16, 169.254/16, 127/8, 100.64/10).
     * IPv6: loopback, link-local, (deprecated) site-local, the two multicast
     * link/site-local prefixes, and unique-local `fc00::/7`.
     */
    val isLocal: Boolean
        get() {
            if (isV4) {
                val ip = ((bytes[0].toLong() and 0xFF) shl 24) or
                    ((bytes[1].toLong() and 0xFF) shl 16) or
                    ((bytes[2].toLong() and 0xFF) shl 8) or
                    (bytes[3].toLong() and 0xFF)
                return (ip and 0xFF000000L) == 0x0A000000L ||  // 10.x.x.x
                    (ip and 0xFFF00000L) == 0xAC100000L ||      // 172.16.x.x
                    (ip and 0xFFFF0000L) == 0xC0A80000L ||      // 192.168.x.x
                    (ip and 0xFFFF0000L) == 0xA9FE0000L ||      // 169.254.x.x
                    (ip and 0xFF000000L) == 0x7F000000L ||      // 127.x.x.x
                    (ip and 0xFFC00000L) == 0x64400000L         // 100.64.0.0/10
            }
            // IPv6
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // ::1 loopback
            if (isAllZero(0, 15) && bytes[15].toInt() == 1) return true
            // fe80::/10 link-local
            if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
            // fec0::/10 site-local (deprecated)
            if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) return true
            // ff02::/16 multicast link-local, ff05::/16 multicast site-local
            if (b0 == 0xFF && (b1 == 0x02 || b1 == 0x05)) return true
            // fc00::/7 unique local
            if ((b0 and 0xFE) == 0xFC) return true
            return false
        }

    private fun isAllZero(from: Int, until: Int): Boolean {
        for (i in from until until) if (bytes[i].toInt() != 0) return false
        return true
    }

    /**
     * Total ordering over addresses matching `address::operator<`: IPv4 sorts
     * before IPv6 (fewer bytes first), and within a family the comparison is the
     * unsigned lexicographic order of the network-byte-order bytes. This is the
     * ordering [PeerList] keeps its peer vector in.
     */
    override fun compareTo(other: PeerAddress): Int {
        if (bytes.size != other.bytes.size) return bytes.size - other.bytes.size
        for (i in bytes.indices) {
            val d = (bytes[i].toInt() and 0xFF) - (other.bytes[i].toInt() and 0xFF)
            if (d != 0) return if (d < 0) -1 else 1
        }
        return 0
    }

    override fun equals(other: Any?): Boolean =
        other is PeerAddress && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.fold(0) { acc, b -> acc * 31 + b }

    companion object {
        /**
         * Parse [host] as a literal IPv4 or IPv6 address, returning `null` if it
         * is neither (e.g. a DNS name). IPv4 is recognised as four
         * dot-separated decimal octets; IPv6 as colon-separated hex groups with
         * optional `::` compression and an optional trailing dotted-quad
         * (IPv4-mapped form).
         */
        fun parseOrNull(host: String): PeerAddress? {
            if (host.isEmpty()) return null
            // IPv6 literals always contain a ':'; IPv4 never does.
            return if (host.indexOf(':') >= 0) parseV6(host) else parseV4(host)
        }

        private fun parseV4(host: String): PeerAddress? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for (i in 0 until 4) {
                val p = parts[i]
                if (p.isEmpty() || p.length > 3) return null
                var v = 0
                for (c in p) {
                    if (c < '0' || c > '9') return null
                    v = v * 10 + (c - '0')
                }
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return PeerAddress(out)
        }

        private fun parseV6(host: String): PeerAddress? {
            // Split off a possible "%zone" scope suffix (ignored for parsing).
            val zoneIdx = host.indexOf('%')
            val text = if (zoneIdx >= 0) host.substring(0, zoneIdx) else host

            val doubleColon = text.indexOf("::")
            if (text.indexOf("::", doubleColon + 1) >= 0) return null // at most one "::"

            val head: String
            val tail: String
            if (doubleColon >= 0) {
                head = text.substring(0, doubleColon)
                tail = text.substring(doubleColon + 2)
            } else {
                head = text
                tail = ""
            }

            val headGroups = if (head.isEmpty()) emptyList() else head.split(':')
            val tailGroups = if (tail.isEmpty()) emptyList() else tail.split(':')

            // The final group of either side may be an embedded IPv4 dotted-quad.
            val headBytes = groupsToBytes(headGroups) ?: return null
            val tailBytes = groupsToBytes(tailGroups) ?: return null

            return if (doubleColon >= 0) {
                val fill = 16 - headBytes.size - tailBytes.size
                if (fill < 0) return null
                val out = ByteArray(16)
                headBytes.copyInto(out, 0)
                tailBytes.copyInto(out, 16 - tailBytes.size)
                PeerAddress(out)
            } else {
                if (headBytes.size != 16) return null
                PeerAddress(headBytes)
            }
        }

        /**
         * Convert a list of IPv6 hex groups (the last of which may itself be a
         * dotted-quad IPv4 literal) into their raw bytes, or `null` on any
         * malformed group.
         */
        private fun groupsToBytes(groups: List<String>): ByteArray? {
            if (groups.isEmpty()) return ByteArray(0)
            val bytes = ArrayList<Byte>(16)
            for (i in groups.indices) {
                val g = groups[i]
                if (i == groups.size - 1 && g.indexOf('.') >= 0) {
                    // embedded IPv4 occupies the final two 16-bit groups
                    val v4 = parseV4(g) ?: return null
                    for (b in v4.bytes) bytes.add(b)
                    continue
                }
                if (g.isEmpty() || g.length > 4) return null
                var v = 0
                for (c in g) {
                    val d = hexDigit(c)
                    if (d < 0) return null
                    v = (v shl 4) or d
                }
                bytes.add((v ushr 8).toByte())
                bytes.add((v and 0xFF).toByte())
            }
            return bytes.toByteArray()
        }

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> 10 + (c - 'a')
            in 'A'..'F' -> 10 + (c - 'A')
            else -> -1
        }
    }
}
