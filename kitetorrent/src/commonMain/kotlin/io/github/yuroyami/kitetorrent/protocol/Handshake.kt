package io.github.yuroyami.kitetorrent.protocol

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Digest32

/**
 * The fixed BitTorrent peer-protocol handshake — the pure-Kotlin port of
 * `bt_peer_connection::write_handshake()` (src/bt_peer_connection.cpp) and the
 * receive-side parsing in `bt_peer_connection::on_receive`/`recv_handshake`.
 *
 * Defined by [BEP-3](https://www.bittorrent.org/beps/bep_0003.html). The wire layout
 * is exactly 68 bytes:
 *
 * ```
 * <pstrlen=19><pstr="BitTorrent protocol"><8 reserved bytes><20-byte info_hash><20-byte peer_id>
 *      1     +         19                 +        8        +        20        +       20        = 68
 * ```
 *
 * The 8 [reserved] bytes carry capability flags negotiated by ANDing the two peers'
 * reserved blocks. libtorrent sets, in `write_handshake()`:
 *  - `reserved[7] |= 0x01` — DHT support (BEP-5),
 *  - `reserved[7] |= 0x04` — Fast extension (BEP-6),
 *  - `reserved[5] |= 0x10` — Extension protocol (BEP-10),
 *  - `reserved[7] |= 0x10` — v2 upgrade (BEP-52), set only for a v1 peer in a hybrid
 *    torrent to advertise willingness to upgrade to the v2 protocol.
 *
 * (`reserved[7]` is the last byte; `reserved[5]` is the 6th byte / index 5.)
 */
class Handshake(
    /** The 20-byte info-hash of the torrent both peers claim to be swarming. */
    val infoHash: Sha1Hash,
    /** The remote (or local) 20-byte peer id. */
    val peerId: Sha1Hash,
    /** The raw 8 reserved bytes exactly as they appeared on the wire. */
    val reserved: ByteArray,
) {
    /** True if the peer set the DHT bit (`reserved[7] & 0x01`, BEP-5). */
    val supportsDht: Boolean get() = (reserved[7].toInt() and DHT_FLAG) != 0

    /** True if the peer set the Fast-extension bit (`reserved[7] & 0x04`, BEP-6). */
    val supportsFast: Boolean get() = (reserved[7].toInt() and FAST_FLAG) != 0

    /** True if the peer set the extension-protocol bit (`reserved[5] & 0x10`, BEP-10). */
    val supportsExtended: Boolean get() = (reserved[5].toInt() and EXTENSION_FLAG) != 0

    /**
     * True if the peer set the v2-upgrade bit (`reserved[7] & 0x10`, BEP-52). For a
     * v1 connection this signals the peer is willing to upgrade to the BitTorrent v2
     * protocol on a hybrid torrent.
     */
    val supportsV2: Boolean get() = (reserved[7].toInt() and V2_FLAG) != 0

    /** Re-encode this handshake to its canonical 68-byte form. */
    fun encode(): ByteArray = encode(infoHash, peerId, reserved)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Handshake) return false
        return infoHash == other.infoHash &&
            peerId == other.peerId &&
            reserved.contentEquals(other.reserved)
    }

    override fun hashCode(): Int {
        var h = infoHash.hashCode()
        h = h * 31 + peerId.hashCode()
        h = h * 31 + reserved.contentHashCode()
        return h
    }

    override fun toString(): String =
        "Handshake(infoHash=${infoHash.toHex()}, peerId=${peerId.toHex()}, " +
            "dht=$supportsDht, fast=$supportsFast, ext=$supportsExtended, v2=$supportsV2)"

    companion object {
        /** The single length-prefix byte: the length of [PROTOCOL_STRING]. */
        const val PSTRLEN: Int = 19

        /** The BitTorrent v1 protocol identifier string ("pstr"). */
        const val PROTOCOL_STRING: String = "BitTorrent protocol"

        /** Total on-the-wire size of a handshake: `1 + 19 + 8 + 20 + 20`. */
        const val LENGTH: Int = 1 + PSTRLEN + 8 + Digest32.SHA1_SIZE + Digest32.SHA1_SIZE // 68

        /** DHT-support flag, OR'd into reserved byte 7 (BEP-5). */
        const val DHT_FLAG: Int = 0x01

        /** Fast-extension flag, OR'd into reserved byte 7 (BEP-6). */
        const val FAST_FLAG: Int = 0x04

        /** Extension-protocol flag, OR'd into reserved byte 5 (BEP-10). */
        const val EXTENSION_FLAG: Int = 0x10

        /** v2-upgrade flag, OR'd into reserved byte 7 (BEP-52). */
        const val V2_FLAG: Int = 0x10

        /** The protocol string as raw ASCII bytes, computed once. */
        private val PROTOCOL_BYTES: ByteArray = PROTOCOL_STRING.encodeToByteArray()

        /**
         * Build the 8-byte reserved block libtorrent emits by default: DHT + Fast +
         * Extension-protocol bits set (matching `write_handshake()` with all features
         * compiled in, minus the v2-upgrade bit which depends on torrent state).
         *
         * [v2] adds the BEP-52 v2-upgrade bit (`reserved[7] |= 0x10`). libtorrent only
         * sets it for a v1 peer in a hybrid torrent (`!protocol_v2 && info_hash.has_v2()`),
         * so it defaults to `false` to preserve the prior wire output.
         */
        fun defaultReserved(v2: Boolean = false): ByteArray =
            buildReserved(dht = true, fast = true, extended = true, v2 = v2)

        /**
         * Build an 8-byte reserved block advertising exactly the chosen capabilities.
         * Mirrors the OR-into-reserved logic of `write_handshake()`:
         *  - [dht] → `reserved[7] |= 0x01` (BEP-5),
         *  - [fast] → `reserved[7] |= 0x04` (BEP-6),
         *  - [extended] → `reserved[5] |= 0x10` (BEP-10),
         *  - [v2] → `reserved[7] |= 0x10` (BEP-52).
         */
        fun buildReserved(
            dht: Boolean = true,
            fast: Boolean = true,
            extended: Boolean = true,
            v2: Boolean = false,
        ): ByteArray {
            val r = ByteArray(8)
            if (extended) r[5] = (r[5].toInt() or EXTENSION_FLAG).toByte()
            var b7 = 0
            if (dht) b7 = b7 or DHT_FLAG
            if (fast) b7 = b7 or FAST_FLAG
            if (v2) b7 = b7 or V2_FLAG
            r[7] = (r[7].toInt() or b7).toByte()
            return r
        }

        /**
         * Encode a handshake. [reserved] must be exactly 8 bytes; it defaults to
         * [defaultReserved]. Mirrors the byte order written by `write_handshake()`.
         */
        fun encode(
            infoHash: Sha1Hash,
            peerId: Sha1Hash,
            reserved: ByteArray = defaultReserved(),
        ): ByteArray {
            require(reserved.size == 8) { "reserved must be 8 bytes, was ${reserved.size}" }
            require(infoHash.size == Digest32.SHA1_SIZE) { "infoHash must be 20 bytes" }
            require(peerId.size == Digest32.SHA1_SIZE) { "peerId must be 20 bytes" }
            val out = ByteArray(LENGTH)
            var p = 0
            out[p++] = PSTRLEN.toByte()
            PROTOCOL_BYTES.copyInto(out, p); p += PSTRLEN
            reserved.copyInto(out, p); p += 8
            infoHash.toByteArray().copyInto(out, p); p += Digest32.SHA1_SIZE
            peerId.toByteArray().copyInto(out, p)
            return out
        }

        /**
         * Parse a 68-byte handshake. Returns null if [bytes] is shorter than
         * [LENGTH], if the length prefix is not 19, or if the protocol string does
         * not match — i.e. the peer is not speaking BitTorrent v1. Bytes beyond
         * [LENGTH] are ignored (they belong to subsequent messages on the stream).
         */
        fun decode(bytes: ByteArray): Handshake? {
            if (bytes.size < LENGTH) return null
            if ((bytes[0].toInt() and 0xff) != PSTRLEN) return null
            for (i in 0 until PSTRLEN) {
                if (bytes[1 + i] != PROTOCOL_BYTES[i]) return null
            }
            var p = 1 + PSTRLEN
            val reserved = bytes.copyOfRange(p, p + 8); p += 8
            val infoHash = Digest32.of(bytes, p, Digest32.SHA1_SIZE); p += Digest32.SHA1_SIZE
            val peerId = Digest32.of(bytes, p, Digest32.SHA1_SIZE)
            return Handshake(infoHash, peerId, reserved)
        }
    }
}
