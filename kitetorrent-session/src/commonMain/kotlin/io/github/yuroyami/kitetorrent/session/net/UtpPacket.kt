package io.github.yuroyami.kitetorrent.session.net

import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder

/**
 * uTP packet types (BEP-29). Mirrors libtorrent's `utp_socket_state_t`
 * (`include/libtorrent/aux_/utp_stream.hpp`):
 *
 * ```
 * enum utp_socket_state_t : std::uint8_t
 * { ST_DATA, ST_FIN, ST_STATE, ST_RESET, ST_SYN, NUM_TYPES };
 * ```
 *
 * The numeric value lives in the high nibble of the first header byte
 * (`type_ver = (type << 4) | version`).
 */
enum class UtpType(val code: Int) {
    /** Normal data payload. */
    ST_DATA(0),
    /** No more data will be sent; graceful shutdown. */
    ST_FIN(1),
    /** State / pure-ACK packet (no payload). */
    ST_STATE(2),
    /** Hard reset: terminate the connection immediately. */
    ST_RESET(3),
    /** Connection request (handshake). */
    ST_SYN(4),
    ;

    companion object {
        /** Map a wire nibble back to a type, or `null` if out of range (>= NUM_TYPES). */
        fun fromCode(code: Int): UtpType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * uTP extension-header kinds. From libtorrent's `utp_extensions_t`:
 *
 * ```
 * enum utp_extensions_t : std::uint8_t
 * { utp_no_extension = 0, utp_sack = 1, utp_close_reason = 3 };
 * ```
 *
 * (2 is intentionally skipped, because a deprecated extension already uses that number.)
 */
object UtpExtension {
    const val NONE = 0
    const val SACK = 1
    const val CLOSE_REASON = 3
}

/** The uTP protocol version this codec speaks (always 1, per BEP-29). */
const val UTP_VERSION = 1

/**
 * The fixed uTP header size: 20 bytes. Matches `sizeof(utp_header)` in libtorrent
 * (`utp_stream.cpp` uses `acquire_packet(sizeof(utp_header))` everywhere).
 *
 * Layout (BEP-29, all multi-byte fields big-endian / network order):
 * ```
 * 0       4       8               16              24              32
 * +-------+-------+---------------+---------------+---------------+
 * | type  | ver   | extension     | connection_id                 |
 * +-------+-------+---------------+---------------+---------------+
 * | timestamp_microseconds                                        |
 * +---------------+---------------+---------------+---------------+
 * | timestamp_difference_microseconds                             |
 * +---------------+---------------+---------------+---------------+
 * | wnd_size                                                      |
 * +---------------+---------------+---------------+---------------+
 * | seq_nr                        | ack_nr                        |
 * +---------------+---------------+---------------+---------------+
 * ```
 */
const val UTP_HEADER_SIZE = 20

/**
 * A parsed/parseable uTP header: the faithful Kotlin analogue of libtorrent's
 * `struct utp_header` (`include/libtorrent/aux_/utp_stream.hpp`).
 *
 * libtorrent stores each field in a `big_endian_int`, which casts a raw network
 * buffer in place; here we hold host-order values and (de)serialise explicitly in
 * [encode]/[decode]. All wrap-around-counter fields ([seqNr], [ackNr],
 * [connectionId]) are kept as 16-bit unsigned values in an [Int]; the 32-bit
 * fields ([timestampMicros], [timestampDiffMicros], [wndSize]) likewise use a
 * `Long` so the unsigned range survives. Producing the on-wire bytes only ever
 * uses the low 16/32 bits, so over-large values truncate exactly as the C++ does.
 */
data class UtpHeader(
    val type: UtpType,
    val version: Int = UTP_VERSION,
    /** First extension chain id (0 == none). See [UtpExtension]. */
    val extension: Int = UtpExtension.NONE,
    /** 16-bit connection id (send_id for non-SYN, recv_id for ST_SYN). */
    val connectionId: Int,
    /** Sender's clock in microseconds (32-bit, may be a synthetic counter). */
    val timestampMicros: Long,
    /** Difference between our recv time and their send time; 0 == no sample yet. */
    val timestampDiffMicros: Long,
    /** Bytes of receive-window space the sender currently has free. */
    val wndSize: Long,
    /** 16-bit sequence number of this packet. */
    val seqNr: Int,
    /** 16-bit sequence number the sender has received in order (cumulative ack). */
    val ackNr: Int,
) {
    init {
        require(version in 0..0xF) { "version out of nibble range: $version" }
        require(extension in 0..0xFF) { "extension out of byte range: $extension" }
    }

    /**
     * Serialise just the 20-byte header (no extension body, no payload). The
     * `type_ver` byte packs `(type.code << 4) | version`, exactly like
     * `h->type_ver = (ST_SYN << 4) | 1` in `send_syn()`.
     */
    fun encode(): ByteArray {
        val out = ByteArray(UTP_HEADER_SIZE)
        out[0] = (((type.code and 0xF) shl 4) or (version and 0xF)).toByte()
        out[1] = (extension and 0xFF).toByte()
        putU16(out, 2, connectionId)
        putU32(out, 4, timestampMicros)
        putU32(out, 8, timestampDiffMicros)
        putU32(out, 12, wndSize)
        putU16(out, 16, seqNr)
        putU16(out, 18, ackNr)
        return out
    }

    companion object {
        /**
         * Parse a [UtpHeader] from the first 20 bytes of [buf] starting at [offset].
         * Returns `null` when there aren't 20 bytes available or the type nibble is
         * not a known [UtpType]. libtorrent's `incoming_packet()` likewise rejects
         * `buf.size() < sizeof(utp_header)` and `get_type() >= NUM_TYPES`.
         *
         * Note: this does NOT validate the version; callers that need libtorrent's
         * "version != 1 → drop" rule should check [UtpHeader.version] themselves.
         */
        fun decode(buf: ByteArray, offset: Int = 0): UtpHeader? {
            if (offset < 0 || buf.size - offset < UTP_HEADER_SIZE) return null
            val typeVer = buf[offset].toInt() and 0xFF
            val type = UtpType.fromCode(typeVer ushr 4) ?: return null
            return UtpHeader(
                type = type,
                version = typeVer and 0xF,
                extension = buf[offset + 1].toInt() and 0xFF,
                connectionId = getU16(buf, offset + 2),
                timestampMicros = getU32(buf, offset + 4),
                timestampDiffMicros = getU32(buf, offset + 8),
                wndSize = getU32(buf, offset + 12),
                seqNr = getU16(buf, offset + 16),
                ackNr = getU16(buf, offset + 18),
            )
        }
    }
}

/**
 * A fully-decoded uTP packet: its [header], the optional Selective-ACK bitmask (if
 * the first extension was [UtpExtension.SACK]), and the [payload] (bytes after all
 * extension headers).
 *
 * libtorrent doesn't materialise a struct like this (it walks the extension chain
 * in place inside `incoming_packet()`), but a value object keeps the Kotlin parser
 * pure and trivially testable.
 */
class UtpPacket(
    val header: UtpHeader,
    /** Raw SACK bitmask bytes, or `null` if no SACK extension was present. */
    val sack: ByteArray?,
    val payload: ByteArray,
)

/**
 * Build a full uTP packet: [header] (its [UtpHeader.extension] is overridden to
 * advertise a SACK iff [sack] is non-null), then, if present, the SACK extension
 * TLV (`next_ext=0`, `len`, bitmask), then [payload].
 *
 * The extension framing matches the chain libtorrent writes/reads: each extension
 * is `{ next_extension_id : u8, length : u8, body[length] }` (see the
 * `while (extension)` loop in `incoming_packet()` and `remove_sack_header()`).
 * This core only ever emits a single SACK extension followed by `next_ext = 0`.
 */
fun buildUtpPacket(
    header: UtpHeader,
    payload: ByteArray = EMPTY_BYTES,
    sack: ByteArray? = null,
): ByteArray {
    val b = ByteArrayBuilder(UTP_HEADER_SIZE + (sack?.let { it.size + 2 } ?: 0) + payload.size)
    val effective = if (sack != null) header.copy(extension = UtpExtension.SACK) else header
    b.append(effective.encode())
    if (sack != null) {
        b.append(0.toByte())             // next extension id: end of chain
        b.append(sack.size.toByte())     // length of the SACK bitmask
        b.append(sack)
    }
    if (payload.isNotEmpty()) b.append(payload)
    return b.toByteArray()
}

/**
 * Parse a datagram into a [UtpPacket], walking the extension chain to find a SACK
 * bitmask and to locate the payload. Returns `null` on any malformed framing
 * (short header, unknown type, truncated extension). These are the same rejects
 * libtorrent applies in `incoming_packet()`.
 *
 * Faithful to the extension walk:
 * ```
 * std::uint8_t extension = ph->extension;
 * while (extension) {
 *     next_extension = *ptr++; len = *ptr++;
 *     switch (extension) { case utp_sack: ...; }
 *     ptr += len; extension = next_extension;
 * }
 * ```
 * Only [UtpExtension.SACK] is captured; other extensions (e.g. close_reason) are
 * skipped over, exactly as libtorrent skips bodies it doesn't act on here.
 */
fun parseUtpPacket(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): UtpPacket? {
    if (length < UTP_HEADER_SIZE) return null
    val header = UtpHeader.decode(buf, offset) ?: return null
    val end = offset + length
    var ptr = offset + UTP_HEADER_SIZE
    var ext = header.extension
    var sack: ByteArray? = null
    while (ext != 0) {
        if (ptr + 2 > end) return null            // invalid: claims an extension but too short
        val next = buf[ptr].toInt() and 0xFF
        val len = buf[ptr + 1].toInt() and 0xFF
        ptr += 2
        if (ptr + len > end) return null          // invalid extension length
        if (ext == UtpExtension.SACK) {
            sack = buf.copyOfRange(ptr, ptr + len)
        }
        ptr += len
        ext = next
    }
    val payload = if (ptr >= end) EMPTY_BYTES else buf.copyOfRange(ptr, end)
    return UtpPacket(header, sack, payload)
}

// ---- big-endian helpers (network byte order) --------------------------------

private fun putU16(out: ByteArray, off: Int, v: Int) {
    out[off] = (v ushr 8).toByte()
    out[off + 1] = v.toByte()
}

private fun putU32(out: ByteArray, off: Int, v: Long) {
    out[off] = (v ushr 24).toByte()
    out[off + 1] = (v ushr 16).toByte()
    out[off + 2] = (v ushr 8).toByte()
    out[off + 3] = v.toByte()
}

private fun getU16(buf: ByteArray, off: Int): Int =
    ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

private fun getU32(buf: ByteArray, off: Int): Long =
    ((buf[off].toLong() and 0xFF) shl 24) or
        ((buf[off + 1].toLong() and 0xFF) shl 16) or
        ((buf[off + 2].toLong() and 0xFF) shl 8) or
        (buf[off + 3].toLong() and 0xFF)

internal val EMPTY_BYTES = ByteArray(0)
