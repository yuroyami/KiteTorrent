package io.github.yuroyami.kitetorrent.extensions

import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder

/**
 * BEP-11 "Peer Exchange (PEX)" (`ut_pex`) — the pure message codec, ported from
 * libtorrent's `ut_pex.cpp` (src/ut_pex.cpp, `ut_pex_plugin::tick` builds the
 * message and `ut_pex_peer_plugin::on_extended` parses it).
 *
 * PEX lets connected peers gossip the addresses of *other* peers, so a swarm can
 * grow without everyone hammering the tracker. A PEX message is the bencoded
 * *payload* of a BEP-10 extended message (id 20), carried under the extension id
 * the remote peer advertised for `ut_pex`.
 *
 * ### Wire format
 * A single bencode dict with up to six string keys:
 *
 * | key         | contents                                                    |
 * |-------------|-------------------------------------------------------------|
 * | `added`     | compact IPv4 peers — 6 bytes each: 4-byte IP + 2-byte port  |
 * | `added.f`   | one [PexFlags] byte per peer in `added`, same order         |
 * | `dropped`   | compact IPv4 peers that left since the last message         |
 * | `added6`    | compact IPv6 peers — 18 bytes each: 16-byte IP + 2-byte port |
 * | `added6.f`  | one flags byte per peer in `added6`                         |
 * | `dropped6`  | compact IPv6 peers that left                                |
 *
 * All integers (IPs and ports) are network byte order (big-endian); a port like
 * 6881 is the two bytes `0x1a 0xe1`. There are no flags for dropped peers.
 *
 * libtorrent only parses `added`/`added.f` when their lengths agree
 * (`added.f.length == added.length / 6`); a mismatch means the whole `added` list
 * is ignored. [parse] reproduces that. The internal `pex_lt_v2` flag (bit 0x10) is
 * masked off on receive ("disregard it from the internet") — see [PexFlags.RECEIVE_MASK].
 *
 * This class is the codec only; the once-a-minute scheduling, the dedup against
 * previously-sent peers, and the `add_peer` side effects live in the peer-plugin
 * layer.
 */
object UtPex {

    /** Bytes per compact IPv4 peer entry: 4-byte address + 2-byte port. */
    const val IPV4_ENTRY_SIZE: Int = 6

    /** Bytes per compact IPv6 peer entry: 16-byte address + 2-byte port. */
    const val IPV6_ENTRY_SIZE: Int = 18

    /**
     * The per-peer PEX flag bits — port of the `pex_*` constants in
     * `libtorrent/extensions/ut_pex.hpp` (referenced from `ut_pex.cpp`). These ride
     * in the `added.f` / `added6.f` byte strings.
     */
    object PexFlags {
        /** `0x01` — peer supports (BitTorrent) encryption. `pex_encryption`. */
        const val ENCRYPTION: Int = 0x01

        /** `0x02` — peer is a seed. `pex_seed`. */
        const val SEED: Int = 0x02

        /** `0x04` — peer supports uTP. Positive-only (0 doesn't imply no-uTP). `pex_utp`. */
        const val UTP: Int = 0x04

        /** `0x08` — peer supports the hole-punching protocol. `pex_holepunch`. */
        const val HOLEPUNCH: Int = 0x08

        /**
         * `0x10` — internal "lt v2" marker (`pex_lt_v2`). libtorrent strips this
         * from received flags with `flags &= ~pex_lt_v2`; it is never trusted from
         * the wire.
         */
        const val LT_V2: Int = 0x10

        /**
         * The mask applied to a received flags byte — every bit except the internal
         * [LT_V2]. `parse` ANDs each incoming flag with this, matching
         * `flags &= ~pex_lt_v2` in `on_extended`.
         */
        const val RECEIVE_MASK: Int = 0xFF and LT_V2.inv()
    }

    /**
     * One peer advertised in (or dropped from) a PEX message.
     *
     * The address is kept as raw network-byte-order [ip] bytes (4 for IPv4, 16 for
     * IPv6) plus the [port], exactly as it appears on the wire — this codec does no
     * string formatting or DNS. [host] renders a dotted-quad / colon-hex literal for
     * convenience and tests.
     *
     * @property ip the address bytes, length 4 (IPv4) or 16 (IPv6), big-endian.
     * @property port the TCP port, 0..65535.
     * @property flags the [PexFlags] bits; `0` for dropped peers (which carry none).
     */
    data class PexPeer(val ip: ByteArray, val port: Int, val flags: Int = 0) {

        /** True if this is an IPv4 (4-byte) address. */
        val isV4: Boolean get() = ip.size == 4

        /** True if this is an IPv6 (16-byte) address. */
        val isV6: Boolean get() = ip.size == 16

        /**
         * The address as a literal string: dotted-quad for IPv4, fully-expanded
         * colon-hex for IPv6 (no `::` compression — unambiguous and round-trips).
         */
        val host: String
            get() = if (isV4) {
                val sb = StringBuilder(15)
                for (i in 0 until 4) {
                    if (i > 0) sb.append('.')
                    sb.append(ip[i].toInt() and 0xFF)
                }
                sb.toString()
            } else {
                val sb = StringBuilder(39)
                for (g in 0 until 8) {
                    if (g > 0) sb.append(':')
                    val hi = ip[g * 2].toInt() and 0xFF
                    val lo = ip[g * 2 + 1].toInt() and 0xFF
                    val v = (hi shl 8) or lo
                    sb.append(hexGroup(v))
                }
                sb.toString()
            }

        /** "host:port" with the [host] literal — convenience for logging and tests. */
        val hostPort: String get() = "$host:$port"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PexPeer) return false
            return port == other.port && flags == other.flags && ip.contentEquals(other.ip)
        }

        override fun hashCode(): Int {
            var h = ip.contentHashCode()
            h = 31 * h + port
            h = 31 * h + flags
            return h
        }

        override fun toString(): String = "PexPeer($hostPort, flags=0x${flags.toString(16)})"

        companion object {
            private fun hexGroup(v: Int): String {
                // lowercase, no leading zeros (e.g. 0x0db8 -> "db8", 0 -> "0")
                val s = StringBuilder(4)
                var started = false
                for (shift in intArrayOf(12, 8, 4, 0)) {
                    val nibble = (v ushr shift) and 0xF
                    if (nibble != 0 || started || shift == 0) {
                        s.append(HEX[nibble])
                        started = true
                    }
                }
                return s.toString()
            }

            private val HEX = "0123456789abcdef".toCharArray()
        }
    }

    /**
     * A fully parsed PEX message: the four logical lists libtorrent reads out of the
     * dict. IPv4 and IPv6 are merged here for convenience — each [PexPeer] knows its
     * own family via [PexPeer.isV4] — while [encode] still writes them into the
     * correct `added`/`added6` keys.
     *
     * @property added peers newly seen since the last message (with flags).
     * @property dropped peers that left since the last message (no flags).
     */
    data class Message(
        val added: List<PexPeer> = emptyList(),
        val dropped: List<PexPeer> = emptyList(),
    )

    // --- encoding --------------------------------------------------------------

    /**
     * Encode a PEX message from [added] and [dropped] peer lists. Each peer is
     * routed to its IPv4 or IPv6 key by address length; flags are written for added
     * peers only (`added.f` / `added6.f`), never for dropped ones — matching
     * `ut_pex_plugin::tick`.
     *
     * Empty lists are still emitted as empty strings, the way libtorrent always
     * materializes `added`/`dropped`/`added.f` (and the v6 variants) even when no
     * peers fall into a bucket. Pass [includeEmpty] = false to omit empty keys.
     */
    fun encode(
        added: List<PexPeer>,
        dropped: List<PexPeer> = emptyList(),
        includeEmpty: Boolean = true,
    ): ByteArray {
        val added4 = ByteArrayBuilder(64)
        val flags4 = ByteArrayBuilder(16)
        val added6 = ByteArrayBuilder(64)
        val flags6 = ByteArrayBuilder(16)
        for (p in added) {
            if (p.isV4) {
                writeEndpoint(added4, p)
                flags4.append((p.flags and 0xFF).toByte())
            } else if (p.isV6) {
                writeEndpoint(added6, p)
                flags6.append((p.flags and 0xFF).toByte())
            }
        }

        val dropped4 = ByteArrayBuilder(64)
        val dropped6 = ByteArrayBuilder(64)
        for (p in dropped) {
            if (p.isV4) writeEndpoint(dropped4, p)
            else if (p.isV6) writeEndpoint(dropped6, p)
        }

        val dict = Entry.dict()
        putStr(dict, "added", added4, includeEmpty)
        putStr(dict, "added.f", flags4, includeEmpty)
        putStr(dict, "dropped", dropped4, includeEmpty)
        putStr(dict, "added6", added6, includeEmpty)
        putStr(dict, "added6.f", flags6, includeEmpty)
        putStr(dict, "dropped6", dropped6, includeEmpty)

        return Bencode.encode(dict)
    }

    private fun putStr(dict: Entry.Dict, key: String, b: ByteArrayBuilder, includeEmpty: Boolean) {
        if (b.size == 0 && !includeEmpty) return
        dict[key] = Entry.of(b.toByteArray())
    }

    private fun writeEndpoint(out: ByteArrayBuilder, p: PexPeer) {
        out.append(p.ip)
        out.append(((p.port ushr 8) and 0xFF).toByte())
        out.append((p.port and 0xFF).toByte())
    }

    // --- decoding --------------------------------------------------------------

    /**
     * Parse a PEX message [payload] (the bencoded body of the extended message,
     * without the wire framing).
     *
     * Reproduces `on_extended`'s logic:
     *  - the payload must bdecode to a dict (else `null`);
     *  - `dropped` / `dropped6` are read as compact peer lists with no flags;
     *  - `added` is paired with `added.f` only if the flag string has exactly one
     *    byte per peer (`added.f.length == added.length / 6`); otherwise the added
     *    list is skipped. Same for `added6` / `added6.f` at 18 bytes per peer;
     *  - each received flag byte is masked with [PexFlags.RECEIVE_MASK] to strip the
     *    internal `pex_lt_v2` bit.
     *
     * @return the decoded [Message], or `null` if [payload] is not a bencode dict.
     */
    fun parse(payload: ByteArray): Message? {
        val root: BdecodeNode = Bdecode.decodeOrNull(payload) ?: return null
        if (root.type != BdecodeNode.Type.DICT) return null

        val added = ArrayList<PexPeer>()
        val dropped = ArrayList<PexPeer>()

        // dropped (IPv4): compact, no flags
        readCompact(root.dictFindStringBytes("dropped"), IPV4_ENTRY_SIZE, null, dropped)
        // dropped6 (IPv6): compact, no flags
        readCompact(root.dictFindStringBytes("dropped6"), IPV6_ENTRY_SIZE, null, dropped)

        // added (IPv4) + added.f, only if the flag count matches the peer count
        val a4 = root.dictFindStringBytes("added")
        val f4 = root.dictFindStringBytes("added.f")
        if (a4 != null && f4 != null && f4.size == a4.size / IPV4_ENTRY_SIZE) {
            readCompact(a4, IPV4_ENTRY_SIZE, f4, added)
        }

        // added6 (IPv6) + added6.f
        val a6 = root.dictFindStringBytes("added6")
        val f6 = root.dictFindStringBytes("added6.f")
        if (a6 != null && f6 != null && f6.size == a6.size / IPV6_ENTRY_SIZE) {
            readCompact(a6, IPV6_ENTRY_SIZE, f6, added)
        }

        return Message(added, dropped)
    }

    /**
     * Read a compact peer string [buf] of fixed [entrySize] (6 or 18) into [out].
     * If [flags] is non-null, one flag byte per peer is taken from it (already
     * length-checked by the caller) and masked with [PexFlags.RECEIVE_MASK]; if
     * null, peers get flags 0 (the dropped case). A trailing partial entry (length
     * not a multiple of [entrySize]) is ignored, mirroring libtorrent's
     * `string_length() / entrySize` truncation.
     */
    private fun readCompact(
        buf: ByteArray?,
        entrySize: Int,
        flags: ByteArray?,
        out: MutableList<PexPeer>,
    ) {
        if (buf == null) return
        val ipLen = entrySize - 2
        val count = buf.size / entrySize
        for (i in 0 until count) {
            val base = i * entrySize
            val ip = buf.copyOfRange(base, base + ipLen)
            val port = ((buf[base + ipLen].toInt() and 0xFF) shl 8) or
                (buf[base + ipLen + 1].toInt() and 0xFF)
            val f = if (flags != null) (flags[i].toInt() and PexFlags.RECEIVE_MASK) else 0
            out.add(PexPeer(ip, port, f))
        }
    }

    // --- small construction helpers -------------------------------------------

    /**
     * Build a [PexPeer] from a literal IPv4 dotted-quad [host] (e.g. "1.2.3.4") and
     * a [port]. Returns `null` if [host] is not four decimal octets. IPv6 literals
     * are not parsed here — construct those from raw bytes — to keep this codec free
     * of a full address parser (the peer layer's `PeerAddress` does that).
     */
    fun peerFromIpv4(host: String, port: Int, flags: Int = 0): PexPeer? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val ip = ByteArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3) return null
            var v = 0
            for (c in p) {
                if (c < '0' || c > '9') return null
                v = v * 10 + (c - '0')
            }
            if (v > 255) return null
            ip[i] = v.toByte()
        }
        return PexPeer(ip, port, flags)
    }
}
