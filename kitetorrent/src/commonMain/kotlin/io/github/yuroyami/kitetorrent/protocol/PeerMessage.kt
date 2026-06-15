package io.github.yuroyami.kitetorrent.protocol

import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder

/**
 * One message of the BitTorrent peer wire protocol — the pure-Kotlin port of the
 * `write_*` senders and `on_*` parsers in `bt_peer_connection.cpp`, plus the message
 * id enum `bt_peer_connection::message_type` (include/libtorrent/bt_peer_connection.hpp).
 *
 * Every non-keepalive message on the wire is length-prefixed:
 *
 * ```
 * <4-byte big-endian length N><1-byte message id><N-1 bytes payload>
 * ```
 *
 * A keep-alive is the degenerate case `N == 0`: just four zero bytes, no id, no body
 * (`write_keepalive()` sends `{0,0,0,0}`).
 *
 * The message set spans three specs:
 *  - [BEP-3](https://www.bittorrent.org/beps/bep_0003.html): choke, unchoke,
 *    interested, not-interested, have, bitfield, request, piece, cancel.
 *  - [BEP-5](https://www.bittorrent.org/beps/bep_0005.html): the DHT `port` message.
 *  - [BEP-6](https://www.bittorrent.org/beps/bep_0006.html) Fast extension: suggest,
 *    have-all, have-none, reject-request, allowed-fast.
 *  - [BEP-10](https://www.bittorrent.org/beps/bep_0010.html): the extension-protocol
 *    `extended` carrier message.
 *
 * [encode] returns the full frame including the 4-byte length prefix, ready to write
 * to a socket. Decoding is split: [PeerMessage.decode] parses ONE already-deframed
 * body (id byte + payload, the length prefix already stripped), while
 * [PeerMessage.tryReadMessage] does length-prefixed stream framing.
 */
sealed class PeerMessage {

    /** The full on-the-wire frame: 4-byte big-endian length prefix + id + payload. */
    abstract fun encode(): ByteArray

    /**
     * The numeric message id, or -1 for [KeepAlive] (which has no id byte on the wire).
     * Matches `bt_peer_connection::message_type`.
     */
    abstract val id: Int

    /** Keep-alive: four zero bytes, length 0, no id, no payload. */
    object KeepAlive : PeerMessage() {
        override val id: Int get() = -1
        override fun encode(): ByteArray = byteArrayOf(0, 0, 0, 0)
    }

    /** `choke` (id 0): sender will not honour the receiver's requests. */
    object Choke : PeerMessage() {
        override val id: Int get() = MessageId.CHOKE
        override fun encode(): ByteArray = encodeEmpty(id)
    }

    /** `unchoke` (id 1): sender will now honour the receiver's requests. */
    object Unchoke : PeerMessage() {
        override val id: Int get() = MessageId.UNCHOKE
        override fun encode(): ByteArray = encodeEmpty(id)
    }

    /** `interested` (id 2): sender wants to request pieces. */
    object Interested : PeerMessage() {
        override val id: Int get() = MessageId.INTERESTED
        override fun encode(): ByteArray = encodeEmpty(id)
    }

    /** `not_interested` (id 3): sender no longer wants to request pieces. */
    object NotInterested : PeerMessage() {
        override val id: Int get() = MessageId.NOT_INTERESTED
        override fun encode(): ByteArray = encodeEmpty(id)
    }

    /** `have` (id 4): the sender now has piece [piece]. */
    data class Have(val piece: Int) : PeerMessage() {
        override val id: Int get() = MessageId.HAVE
        override fun encode(): ByteArray = encodeInts(id, piece)
    }

    /**
     * `bitfield` (id 5): the complete set of pieces the sender has. The payload is
     * the bitfield's raw bytes (MSB-first, exactly `Bitfield.data()`).
     */
    data class Bitfield(val bitfield: io.github.yuroyami.kitetorrent.Bitfield) : PeerMessage() {
        override val id: Int get() = MessageId.BITFIELD
        override fun encode(): ByteArray {
            val payload = bitfield.data()
            val b = ByteArrayBuilder(5 + payload.size)
            ByteIo.writeUInt32BE(b, (1 + payload.size).toLong())
            b.append(id.toByte())
            b.append(payload)
            return b.toByteArray()
        }
    }

    /** `request` (id 6): ask for [length] bytes at [begin] within [piece]. */
    data class Request(val piece: Int, val begin: Int, val length: Int) : PeerMessage() {
        override val id: Int get() = MessageId.REQUEST
        override fun encode(): ByteArray = encodeInts(id, piece, begin, length)
    }

    /**
     * `piece` (id 7): a block of data — [block] bytes at [begin] within [piece].
     * On the wire: `[len][7][int32 piece][int32 begin][raw block]`, len = `block.size + 9`.
     */
    data class Piece(val piece: Int, val begin: Int, val block: ByteArray) : PeerMessage() {
        override val id: Int get() = MessageId.PIECE
        override fun encode(): ByteArray {
            val b = ByteArrayBuilder(13 + block.size)
            ByteIo.writeUInt32BE(b, (9 + block.size).toLong())
            b.append(id.toByte())
            ByteIo.writeUInt32BE(b, piece.toLong())
            ByteIo.writeUInt32BE(b, begin.toLong())
            b.append(block)
            return b.toByteArray()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Piece) return false
            return piece == other.piece && begin == other.begin && block.contentEquals(other.block)
        }

        override fun hashCode(): Int {
            var h = piece
            h = h * 31 + begin
            h = h * 31 + block.contentHashCode()
            return h
        }

        override fun toString(): String = "Piece(piece=$piece, begin=$begin, block.size=${block.size})"
    }

    /** `cancel` (id 8): cancel a previously sent [Request]. Same body as request. */
    data class Cancel(val piece: Int, val begin: Int, val length: Int) : PeerMessage() {
        override val id: Int get() = MessageId.CANCEL
        override fun encode(): ByteArray = encodeInts(id, piece, begin, length)
    }

    /**
     * `port` (id 9, BEP-5): the sender's DHT UDP [port]. On the wire the port is a
     * 16-bit big-endian value (`write_dht_port` emits `{0,0,0,3, 9, hi, lo}`).
     */
    data class Port(val port: Int) : PeerMessage() {
        override val id: Int get() = MessageId.DHT_PORT
        override fun encode(): ByteArray {
            val b = ByteArrayBuilder(7)
            ByteIo.writeUInt32BE(b, 3L) // 1 (id) + 2 (uint16 port)
            b.append(id.toByte())
            ByteIo.writeUInt16BE(b, port)
            return b.toByteArray()
        }
    }

    /** `suggest` (id 13, BEP-6): a hint that the receiver may want [piece]. */
    data class Suggest(val piece: Int) : PeerMessage() {
        override val id: Int get() = MessageId.SUGGEST
        override fun encode(): ByteArray = encodeInts(id, piece)
    }

    /** `have_all` (id 14, BEP-6): the sender has every piece (a seed). */
    object HaveAll : PeerMessage() {
        override val id: Int get() = MessageId.HAVE_ALL
        override fun encode(): ByteArray = encodeEmpty(id)
    }

    /** `have_none` (id 15, BEP-6): the sender has no pieces. */
    object HaveNone : PeerMessage() {
        override val id: Int get() = MessageId.HAVE_NONE
        override fun encode(): ByteArray = encodeEmpty(id)
    }

    /** `reject_request` (id 16, BEP-6): refuse a [Request]. Same body as request. */
    data class RejectRequest(val piece: Int, val begin: Int, val length: Int) : PeerMessage() {
        override val id: Int get() = MessageId.REJECT_REQUEST
        override fun encode(): ByteArray = encodeInts(id, piece, begin, length)
    }

    /** `allowed_fast` (id 17, BEP-6): the receiver may request [piece] even while choked. */
    data class AllowedFast(val piece: Int) : PeerMessage() {
        override val id: Int get() = MessageId.ALLOWED_FAST
        override fun encode(): ByteArray = encodeInts(id, piece)
    }

    /**
     * `extended` (id 20, BEP-10): the extension-protocol carrier. [extId] is the
     * single-byte extended message id (0 = the extension handshake), followed by an
     * opaque (usually bencoded) [payload]. On the wire:
     * `[len][20][uint8 extId][payload]`, len = `payload.size + 2`.
     */
    data class Extended(val extId: Int, val payload: ByteArray) : PeerMessage() {
        override val id: Int get() = MessageId.EXTENDED
        override fun encode(): ByteArray {
            val b = ByteArrayBuilder(6 + payload.size)
            ByteIo.writeUInt32BE(b, (2 + payload.size).toLong())
            b.append(id.toByte())
            b.append((extId and 0xff).toByte())
            b.append(payload)
            return b.toByteArray()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Extended) return false
            return extId == other.extId && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = extId * 31 + payload.contentHashCode()

        override fun toString(): String = "Extended(extId=$extId, payload.size=${payload.size})"
    }

    /**
     * A frame whose message id is not one this codec recognises. libtorrent would
     * route this to a peer-plugin or drop the connection; we surface it so the caller
     * can decide. [id] is the raw id byte, [payload] is everything after it.
     */
    data class Unknown(override val id: Int, val payload: ByteArray) : PeerMessage() {
        override fun encode(): ByteArray {
            val b = ByteArrayBuilder(5 + payload.size)
            ByteIo.writeUInt32BE(b, (1 + payload.size).toLong())
            b.append(id.toByte())
            b.append(payload)
            return b.toByteArray()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unknown) return false
            return id == other.id && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = id * 31 + payload.contentHashCode()

        override fun toString(): String = "Unknown(id=$id, payload.size=${payload.size})"
    }

    companion object {
        /** Encode a body-less message: `[len=1][id]`. */
        private fun encodeEmpty(id: Int): ByteArray =
            byteArrayOf(0, 0, 0, 1, id.toByte())

        /** Encode `[len=1+4n][id][int32 BE args...]` — the `send_message` template shape. */
        private fun encodeInts(id: Int, vararg ints: Int): ByteArray {
            val len = 1 + ints.size * 4
            val out = ByteArray(4 + len)
            // 4-byte big-endian length prefix
            out[0] = ((len ushr 24) and 0xff).toByte()
            out[1] = ((len ushr 16) and 0xff).toByte()
            out[2] = ((len ushr 8) and 0xff).toByte()
            out[3] = (len and 0xff).toByte()
            out[4] = id.toByte()
            var p = 5
            for (v in ints) {
                out[p++] = ((v ushr 24) and 0xff).toByte()
                out[p++] = ((v ushr 16) and 0xff).toByte()
                out[p++] = ((v ushr 8) and 0xff).toByte()
                out[p++] = (v and 0xff).toByte()
            }
            return out
        }

        /**
         * Parse ONE already-deframed message body: the 4-byte length prefix has been
         * stripped, so [frame] is `[id][payload...]` (length >= 1 for a real message).
         * An empty [frame] is a keep-alive (the caller saw `length == 0`).
         *
         * Mirrors the per-type `on_*` parsers and the `dispatch_message` switch. Frames
         * that are too short for their declared type fall through to [Unknown] rather
         * than throwing, so a caller looping over a buffer never crashes on a peer that
         * sends a malformed body — the connection layer decides whether to disconnect.
         */
        fun decode(frame: ByteArray): PeerMessage {
            if (frame.isEmpty()) return KeepAlive
            val id = frame[0].toInt() and 0xff
            return when (id) {
                MessageId.CHOKE -> Choke
                MessageId.UNCHOKE -> Unchoke
                MessageId.INTERESTED -> Interested
                MessageId.NOT_INTERESTED -> NotInterested
                MessageId.HAVE ->
                    if (frame.size >= 5) Have(ByteIo.readUInt32BEAsInt(frame, 1))
                    else Unknown(id, frame.copyOfRange(1, frame.size))
                MessageId.BITFIELD -> {
                    val raw = frame.copyOfRange(1, frame.size)
                    Bitfield(io.github.yuroyami.kitetorrent.Bitfield.fromBytes(raw, raw.size * 8))
                }
                MessageId.REQUEST ->
                    if (frame.size >= 13) Request(
                        ByteIo.readUInt32BEAsInt(frame, 1),
                        ByteIo.readUInt32BEAsInt(frame, 5),
                        ByteIo.readUInt32BEAsInt(frame, 9),
                    ) else Unknown(id, frame.copyOfRange(1, frame.size))
                MessageId.PIECE ->
                    if (frame.size >= 9) Piece(
                        ByteIo.readUInt32BEAsInt(frame, 1),
                        ByteIo.readUInt32BEAsInt(frame, 5),
                        frame.copyOfRange(9, frame.size),
                    ) else Unknown(id, frame.copyOfRange(1, frame.size))
                MessageId.CANCEL ->
                    if (frame.size >= 13) Cancel(
                        ByteIo.readUInt32BEAsInt(frame, 1),
                        ByteIo.readUInt32BEAsInt(frame, 5),
                        ByteIo.readUInt32BEAsInt(frame, 9),
                    ) else Unknown(id, frame.copyOfRange(1, frame.size))
                MessageId.DHT_PORT ->
                    if (frame.size >= 3) Port(ByteIo.readUInt16BE(frame, 1))
                    else Unknown(id, frame.copyOfRange(1, frame.size))
                MessageId.SUGGEST ->
                    if (frame.size >= 5) Suggest(ByteIo.readUInt32BEAsInt(frame, 1))
                    else Unknown(id, frame.copyOfRange(1, frame.size))
                MessageId.HAVE_ALL -> HaveAll
                MessageId.HAVE_NONE -> HaveNone
                MessageId.REJECT_REQUEST ->
                    if (frame.size >= 13) RejectRequest(
                        ByteIo.readUInt32BEAsInt(frame, 1),
                        ByteIo.readUInt32BEAsInt(frame, 5),
                        ByteIo.readUInt32BEAsInt(frame, 9),
                    ) else Unknown(id, frame.copyOfRange(1, frame.size))
                MessageId.ALLOWED_FAST ->
                    if (frame.size >= 5) AllowedFast(ByteIo.readUInt32BEAsInt(frame, 1))
                    else Unknown(id, frame.copyOfRange(1, frame.size))
                MessageId.EXTENDED ->
                    if (frame.size >= 2) Extended(frame[1].toInt() and 0xff, frame.copyOfRange(2, frame.size))
                    else Unknown(id, frame.copyOfRange(1, frame.size))
                else -> Unknown(id, frame.copyOfRange(1, frame.size))
            }
        }

        /**
         * Stream-framing read. Treats `buffer[offset until buffer.size]` as a byte
         * stream and tries to pull exactly one length-prefixed message off the front.
         *
         * Returns the decoded message paired with the total number of bytes consumed
         * (the 4 length bytes + the body), or null if fewer than a whole message is
         * available yet — the caller keeps buffering and retries. This is the codec
         * half of libtorrent's `m_recv_buffer` packet machinery: read a 4-byte length,
         * then wait for that many body bytes before dispatching.
         *
         * A declared length of 0 is a keep-alive consuming 4 bytes. The length is read
         * as unsigned; a peer announcing an absurd length (here, anything that would
         * overflow a signed Int frame) yields a [BadFrameLengthException] so callers
         * can disconnect rather than try to allocate it.
         */
        fun tryReadMessage(buffer: ByteArray, offset: Int = 0): Pair<PeerMessage, Int>? {
            val available = buffer.size - offset
            if (available < 4) return null
            val len = ByteIo.readUInt32BE(buffer, offset) // unsigned, as Long
            if (len == 0L) return KeepAlive to 4
            // A frame longer than Int.MAX_VALUE can't be addressed in a ByteArray and
            // is certainly a malformed/hostile peer; surface it instead of looping forever.
            if (len > Int.MAX_VALUE.toLong()) throw BadFrameLengthException(len)
            val bodyLen = len.toInt()
            val total = 4 + bodyLen
            if (available < total) return null // body not fully buffered yet
            val body = buffer.copyOfRange(offset + 4, offset + total)
            return decode(body) to total
        }
    }
}

/** Thrown by [PeerMessage.tryReadMessage] when a peer announces an unaddressable frame length. */
class BadFrameLengthException(val declaredLength: Long) :
    RuntimeException("peer announced frame length $declaredLength (exceeds Int.MAX_VALUE)")

/**
 * The numeric BitTorrent peer-message ids — a faithful copy of the
 * `bt_peer_connection::message_type` enum (include/libtorrent/bt_peer_connection.hpp).
 */
object MessageId {
    const val CHOKE: Int = 0
    const val UNCHOKE: Int = 1
    const val INTERESTED: Int = 2
    const val NOT_INTERESTED: Int = 3
    const val HAVE: Int = 4
    const val BITFIELD: Int = 5
    const val REQUEST: Int = 6
    const val PIECE: Int = 7
    const val CANCEL: Int = 8

    /** DHT extension (BEP-5). */
    const val DHT_PORT: Int = 9

    // Fast extension (BEP-6).
    const val SUGGEST: Int = 13
    const val HAVE_ALL: Int = 14
    const val HAVE_NONE: Int = 15
    const val REJECT_REQUEST: Int = 16
    const val ALLOWED_FAST: Int = 17

    /** Extension protocol (BEP-10). */
    const val EXTENDED: Int = 20

    // BitTorrent v2 hash messages (BEP-52) — defined for completeness; not modelled
    // as PeerMessage subtypes in v0.
    const val HASH_REQUEST: Int = 21
    const val HASHES: Int = 22
    const val HASH_REJECT: Int = 23
}

/**
 * Big-endian integer read/write helpers over [ByteArray] / [ByteArrayBuilder].
 * libtorrent does this with `aux::write_uint32`/`aux::read_int32` (aux_/io.hpp); on
 * Kotlin/common we have no pointer arithmetic, so these operate on explicit offsets.
 */
internal object ByteIo {
    /** Append a 32-bit big-endian value (low 32 bits of [value]) to [b]. */
    fun writeUInt32BE(b: ByteArrayBuilder, value: Long) {
        b.append(((value ushr 24) and 0xff).toByte())
        b.append(((value ushr 16) and 0xff).toByte())
        b.append(((value ushr 8) and 0xff).toByte())
        b.append((value and 0xff).toByte())
    }

    /** Append a 16-bit big-endian value (low 16 bits of [value]) to [b]. */
    fun writeUInt16BE(b: ByteArrayBuilder, value: Int) {
        b.append(((value ushr 8) and 0xff).toByte())
        b.append((value and 0xff).toByte())
    }

    /** Read a 32-bit big-endian value at [off] as an unsigned [Long] (0..2^32-1). */
    fun readUInt32BE(buf: ByteArray, off: Int): Long {
        return ((buf[off].toLong() and 0xff) shl 24) or
            ((buf[off + 1].toLong() and 0xff) shl 16) or
            ((buf[off + 2].toLong() and 0xff) shl 8) or
            (buf[off + 3].toLong() and 0xff)
    }

    /**
     * Read a 32-bit big-endian value at [off] as a signed [Int] — the natural Kotlin
     * type for piece indices, offsets and lengths (matches `aux::read_int32`).
     */
    fun readUInt32BEAsInt(buf: ByteArray, off: Int): Int {
        return ((buf[off].toInt() and 0xff) shl 24) or
            ((buf[off + 1].toInt() and 0xff) shl 16) or
            ((buf[off + 2].toInt() and 0xff) shl 8) or
            (buf[off + 3].toInt() and 0xff)
    }

    /** Read a 16-bit big-endian value at [off] as an unsigned [Int] (0..65535). */
    fun readUInt16BE(buf: ByteArray, off: Int): Int {
        return ((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)
    }
}
