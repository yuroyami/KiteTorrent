package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.peer.PeerAddress

/**
 * A network endpoint (address + UDP/TCP port) as it appears inside DHT messages —
 * the pure-Kotlin stand-in for the `udp::endpoint` / `tcp::endpoint` that
 * libtorrent serialises with `aux::write_endpoint` / `aux::read_v4_endpoint`
 * (include/libtorrent/socket_io.hpp).
 *
 * libtorrent's compact endpoint form is the address in network byte order (4 bytes
 * for IPv4, 16 for IPv6) immediately followed by the 16-bit port, big-endian. That
 * is exactly 6 bytes for v4 and 18 for v6 — see [encode] / [decodeV4] / [decodeV6].
 *
 * KiteTorrent has no socket types in `commonMain`, and [PeerAddress] exposes only
 * `parseOrNull` (no raw-bytes constructor), so this type owns the raw [address]
 * bytes itself. [toPeerAddress] bridges to [PeerAddress] when the richer predicates
 * (locality, ordering) are needed.
 *
 * @property address the address in network byte order: 4 bytes (IPv4) or 16 (IPv6).
 * @property port the 16-bit port (0..65535).
 */
class DhtEndpoint(val address: ByteArray, val port: Int) {

    init {
        require(address.size == 4 || address.size == 16) {
            "address must be 4 (IPv4) or 16 (IPv6) bytes, was ${address.size}"
        }
        require(port in 0..0xFFFF) { "port out of range: $port" }
    }

    /** True if this endpoint carries an IPv4 (4-byte) address. */
    val isV4: Boolean get() = address.size == 4

    /** True if this endpoint carries an IPv6 (16-byte) address. */
    val isV6: Boolean get() = address.size == 16

    /** The compact byte width of this endpoint: 6 for IPv4, 18 for IPv6. */
    val compactSize: Int get() = address.size + 2

    /**
     * Append this endpoint's compact form (address bytes then big-endian port) to
     * [out] — port of `aux::write_endpoint`.
     */
    fun encodeInto(out: ByteArrayBuilder) {
        out.append(address)
        out.append(((port ushr 8) and 0xFF).toByte())
        out.append((port and 0xFF).toByte())
    }

    /** This endpoint's compact form as a fresh byte array (6 or 18 bytes). */
    fun encode(): ByteArray {
        val out = ByteArrayBuilder(compactSize)
        encodeInto(out)
        return out.toByteArray()
    }

    /**
     * The address as a [PeerAddress] (via its dotted-quad / colon-hex literal), or
     * `null` if the bytes don't round-trip through [PeerAddress.parseOrNull]
     * (should never happen for well-formed 4/16-byte addresses).
     */
    fun toPeerAddress(): PeerAddress? = PeerAddress.parseOrNull(addressLiteral())

    /** The address rendered as an IPv4 dotted-quad or IPv6 colon-hex literal. */
    fun addressLiteral(): String = if (isV4) v4Literal(address) else v6Literal(address)

    override fun equals(other: Any?): Boolean =
        other is DhtEndpoint && port == other.port && address.contentEquals(other.address)

    override fun hashCode(): Int = address.fold(port) { acc, b -> acc * 31 + b }

    override fun toString(): String =
        if (isV4) "${addressLiteral()}:$port" else "[${addressLiteral()}]:$port"

    companion object {
        /** Compact size of an IPv4 endpoint: 4-byte address + 2-byte port. */
        const val V4_SIZE = 6

        /** Compact size of an IPv6 endpoint: 16-byte address + 2-byte port. */
        const val V6_SIZE = 18

        /**
         * Decode a 6-byte IPv4 endpoint from [buf] at [offset] — port of
         * `aux::read_v4_endpoint`.
         */
        fun decodeV4(buf: ByteArray, offset: Int = 0): DhtEndpoint {
            require(offset + V4_SIZE <= buf.size) { "need 6 bytes for a v4 endpoint" }
            val addr = buf.copyOfRange(offset, offset + 4)
            val port = ((buf[offset + 4].toInt() and 0xFF) shl 8) or (buf[offset + 5].toInt() and 0xFF)
            return DhtEndpoint(addr, port)
        }

        /**
         * Decode an 18-byte IPv6 endpoint from [buf] at [offset] — port of
         * `aux::read_v6_endpoint`.
         */
        fun decodeV6(buf: ByteArray, offset: Int = 0): DhtEndpoint {
            require(offset + V6_SIZE <= buf.size) { "need 18 bytes for a v6 endpoint" }
            val addr = buf.copyOfRange(offset, offset + 16)
            val port = ((buf[offset + 16].toInt() and 0xFF) shl 8) or (buf[offset + 17].toInt() and 0xFF)
            return DhtEndpoint(addr, port)
        }

        /**
         * Build a [DhtEndpoint] from a host literal and port, or `null` if [host] is
         * not a parseable IPv4/IPv6 literal. Convenience bridge from the
         * string-host world ([NodeEntry.host]) to the compact world.
         */
        fun of(host: String, port: Int): DhtEndpoint? {
            val a = PeerAddress.parseOrNull(host) ?: return null
            return DhtEndpoint(a.bytes.copyOf(), port)
        }

        private fun v4Literal(b: ByteArray): String =
            "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"

        private fun v6Literal(b: ByteArray): String {
            // Group into 8 hex words; collapse the single longest run of zero words
            // into "::" (canonical-ish; PeerAddress.parseOrNull accepts it either way).
            val words = IntArray(8) { ((b[it * 2].toInt() and 0xFF) shl 8) or (b[it * 2 + 1].toInt() and 0xFF) }
            // find longest zero run
            var bestStart = -1
            var bestLen = 0
            var i = 0
            while (i < 8) {
                if (words[i] == 0) {
                    var j = i
                    while (j < 8 && words[j] == 0) j++
                    if (j - i > bestLen) { bestLen = j - i; bestStart = i }
                    i = j
                } else i++
            }
            if (bestLen < 2) { bestStart = -1; bestLen = 0 }
            val sb = StringBuilder()
            i = 0
            while (i < 8) {
                if (i == bestStart) {
                    sb.append("::")
                    i += bestLen
                    continue
                }
                if (sb.isNotEmpty() && sb[sb.length - 1] != ':') sb.append(':')
                sb.append(hexWord(words[i]))
                i++
            }
            return if (sb.isEmpty()) "::" else sb.toString()
        }

        private fun hexWord(w: Int): String {
            val digits = "0123456789abcdef"
            val sb = StringBuilder()
            var started = false
            for (shift in intArrayOf(12, 8, 4, 0)) {
                val nib = (w ushr shift) and 0xF
                if (nib != 0 || started || shift == 0) {
                    sb.append(digits[nib])
                    started = true
                }
            }
            return sb.toString()
        }
    }
}

/**
 * A single entry in a DHT compact `nodes` / `nodes6` list — a 20-byte [NodeId]
 * followed by a compact [DhtEndpoint]. This is the on-wire form produced by
 * libtorrent's `write_nodes_entry` (src/kademlia/node.cpp), which concatenates
 * `id` and `write_endpoint(ep)` for each node.
 *
 * A v4 entry is therefore 26 bytes (20 + 6); a v6 entry is 38 bytes (20 + 18).
 */
class CompactNode(val id: Sha1Hash, val endpoint: DhtEndpoint) {

    override fun equals(other: Any?): Boolean =
        other is CompactNode && id == other.id && endpoint == other.endpoint

    override fun hashCode(): Int = id.hashCode() * 31 + endpoint.hashCode()

    override fun toString(): String = "CompactNode(${id.toHex()}, $endpoint)"
}

/**
 * Encode/decode of the compact `nodes` strings that BEP-5 queries return — the
 * counterpart to libtorrent's `write_nodes_entry` and the inline decode loops in
 * `find_data` / the routing table's `read_nodes`. The IPv4 form ("n4"/`nodes`,
 * 26-byte records) and the IPv6 form ("n6"/`nodes6`, 38-byte records) are encoded
 * here separately because the record width is fixed per family.
 */
object CompactNodes {

    /** Width of one IPv4 compact node record: 20-byte id + 6-byte endpoint. */
    const val V4_NODE_SIZE = 26

    /** Width of one IPv6 compact node record: 20-byte id + 18-byte endpoint. */
    const val V6_NODE_SIZE = 38

    /**
     * Concatenate the compact form of every node in [nodes] into one byte string —
     * direct port of `write_nodes_entry`. Each record is `id || write_endpoint(ep)`.
     * The nodes may freely mix v4/v6 (libtorrent keeps them in separate keys, but
     * the encoding itself is just self-describing-width records).
     */
    fun encode(nodes: List<CompactNode>): ByteArray {
        val out = ByteArrayBuilder(nodes.sumOf { 20 + it.endpoint.compactSize })
        for (n in nodes) {
            out.append(n.id.toByteArray())
            n.endpoint.encodeInto(out)
        }
        return out.toByteArray()
    }

    /**
     * Decode a `nodes` string of fixed-width IPv4 records (26 bytes each). Any
     * trailing partial record is ignored, matching libtorrent's `while (end - in >=
     * 26)` style loops.
     */
    fun decodeV4(buf: ByteArray): List<CompactNode> = decode(buf, V4_NODE_SIZE, v6 = false)

    /**
     * Decode a `nodes6` string of fixed-width IPv6 records (38 bytes each). Any
     * trailing partial record is ignored.
     */
    fun decodeV6(buf: ByteArray): List<CompactNode> = decode(buf, V6_NODE_SIZE, v6 = true)

    private fun decode(buf: ByteArray, recordSize: Int, v6: Boolean): List<CompactNode> {
        val out = ArrayList<CompactNode>(buf.size / recordSize)
        var off = 0
        while (off + recordSize <= buf.size) {
            val id = Sha1Hash.of(buf, off, 20)
            val ep = if (v6) DhtEndpoint.decodeV6(buf, off + 20) else DhtEndpoint.decodeV4(buf, off + 20)
            out.add(CompactNode(id, ep))
            off += recordSize
        }
        return out
    }
}
