package io.github.yuroyami.kitetorrent.extensions

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.crypto.Hasher256
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder

/**
 * BEP-9 "Extension for Peers to Send Metadata Files" (`ut_metadata`). This is the
 * pure message codec, ported from libtorrent's `ut_metadata.cpp`
 * (src/ut_metadata.cpp, `ut_metadata_peer_plugin::write_metadata_packet` and
 * `on_extended`).
 *
 * The metadata that this protocol exchanges is the bencoded `info` dictionary of a
 * torrent (the same bytes whose SHA-1 is the v1 info-hash). It is sliced into
 * 16 KiB pieces and pulled from peers a piece at a time.
 *
 * ### Wire format
 * An `ut_metadata` message is the *payload* of a BEP-10 extended message (id 20)
 * carried under the extension id the remote peer advertised for `ut_metadata` in
 * its extension handshake. That payload is:
 *
 * ```
 * <bencode dict>[<raw 16 KiB metadata piece>]
 * ```
 *
 * The bencode dict always has:
 * - `msg_type`: 0 = request, 1 = data, 2 = reject
 * - `piece`: the 0-based 16 KiB piece index
 *
 * and, for a `data` message, also:
 * - `total_size`: the total length in bytes of the whole metadata (`info`) blob.
 *
 * For a `data` message the raw bytes of that one piece are appended *after* the
 * bencode dict (this is why a plain bdecode of the buffer stops at the end of the
 * dict, because the trailing bytes are the payload). libtorrent recovers them with
 * `body.subspan(msg.data_section().size())`, i.e. everything past the bencoded
 * dict; [parse] does the same using [BdecodeNode.dataSection].
 *
 * This class is the codec only. The request scheduling and rate limiting that the
 * C++ peer plugin adds lives elsewhere. [MetadataTransfer] is the small
 * reassembly + verification helper.
 */
object UtMetadata {

    /** Size of one metadata piece, 16 KiB, written as `16 * 1024` in libtorrent. */
    const val PIECE_SIZE: Int = 16 * 1024

    /**
     * The `msg_type` field, the port of the anonymous `enum class msg_t : uint8_t`
     * in `ut_metadata.cpp`. The integer values are part of the wire format.
     */
    enum class Type(val value: Int) {
        /** `msg_type` 0: ask a peer for a metadata piece. */
        REQUEST(0),

        /** `msg_type` 1: a metadata piece, with its raw bytes appended after the dict. */
        DATA(1),

        /**
         * `msg_type` 2: "I do not have that piece, or will not send it". libtorrent
         * calls this `dont_have`; BEP-9 names it `reject`.
         */
        REJECT(2);

        companion object {
            /** The [Type] for wire value [v], or `null` if it is not one of 0/1/2. */
            fun fromValue(v: Int): Type? = when (v) {
                0 -> REQUEST
                1 -> DATA
                2 -> REJECT
                else -> null
            }
        }
    }

    /**
     * A decoded `ut_metadata` message.
     *
     * @property type the [Type] (request / data / reject).
     * @property piece the 0-based 16 KiB piece index.
     * @property totalSize for a [Type.DATA] message, the total metadata length the
     *   sender advertises; `0` / meaningless for request and reject. libtorrent
     *   reads it with `dict_find_int_value("total_size", 0)`.
     * @property data for a [Type.DATA] message, the raw piece bytes that followed
     *   the bencode dict; an empty array otherwise.
     */
    data class Message(
        val type: Type,
        val piece: Int,
        val totalSize: Long = 0L,
        val data: ByteArray = EMPTY,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Message) return false
            return type == other.type &&
                piece == other.piece &&
                totalSize == other.totalSize &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var h = type.hashCode()
            h = 31 * h + piece
            h = 31 * h + totalSize.hashCode()
            h = 31 * h + data.contentHashCode()
            return h
        }

        override fun toString(): String =
            "UtMetadata.Message(type=$type, piece=$piece, totalSize=$totalSize, data.size=${data.size})"

        companion object {
            private val EMPTY = ByteArray(0)
        }
    }

    // --- encoding --------------------------------------------------------------

    /**
     * Encode a `request` message: `d8:msg_typei0e5:piecei<n>ee`.
     *
     * libtorrent additionally writes `total_size` on a request when it already has
     * valid metadata; that field is ignored by a requester and is not part of the
     * BEP-9 request format, so it is omitted here. Use [encode] if you need to
     * reproduce that exactly.
     */
    fun encodeRequest(piece: Int): ByteArray = encode(Type.REQUEST, piece)

    /**
     * Encode a `data` message: the bencode dict
     * `d8:msg_typei1e5:piecei<n>e10:total_sizei<s>ee`, followed by the raw
     * [pieceData] bytes appended verbatim.
     *
     * Mirrors `write_metadata_packet(msg_t::piece, piece)`: it writes `msg_type`,
     * `piece` and `total_size`, then `append_const_send_buffer`s the piece bytes.
     *
     * @param piece the 0-based piece index.
     * @param totalSize the total length of the whole metadata blob.
     * @param pieceData the raw bytes of this piece (≤ [PIECE_SIZE]).
     */
    fun encodeData(piece: Int, totalSize: Long, pieceData: ByteArray): ByteArray =
        encode(Type.DATA, piece, totalSize, pieceData)

    /**
     * Encode a `reject` (`dont_have`) message:
     * `d8:msg_typei2e5:piecei<n>ee`.
     */
    fun encodeReject(piece: Int): ByteArray = encode(Type.REJECT, piece)

    /**
     * General encoder. Builds the `{msg_type, piece [, total_size]}` dict and, for
     * [Type.DATA], appends [pieceData] after it.
     *
     * `total_size` is emitted when [includeTotalSize] is true (always forced on for
     * [Type.DATA], to match libtorrent and BEP-9). [pieceData] is appended only for
     * [Type.DATA]; it is ignored for request/reject.
     */
    fun encode(
        type: Type,
        piece: Int,
        totalSize: Long = 0L,
        pieceData: ByteArray = ByteArray(0),
        includeTotalSize: Boolean = (type == Type.DATA),
    ): ByteArray {
        val dict = Entry.dict()
        // Insert in canonical key order anyway; Bencode.encode re-sorts regardless.
        dict["msg_type"] = Entry.of(type.value.toLong())
        dict["piece"] = Entry.of(piece.toLong())
        if (includeTotalSize || type == Type.DATA) {
            dict["total_size"] = Entry.of(totalSize)
        }

        val out = ByteArrayBuilder(64 + if (type == Type.DATA) pieceData.size else 0)
        Bencode.encodeInto(out, dict)
        if (type == Type.DATA && pieceData.isNotEmpty()) {
            out.append(pieceData)
        }
        return out.toByteArray()
    }

    // --- decoding --------------------------------------------------------------

    /**
     * Parse a `ut_metadata` message [payload] (the body of the extended message,
     * *not* including the 4-byte length / id-20 / extended-id wire framing).
     *
     * Faithful to `on_extended`: the buffer is bdecoded; it must be a dict; both
     * `msg_type` and `piece` must be present integer keys, otherwise the message is
     * invalid. libtorrent disconnects; we return `null` instead. For a `data`
     * message, the appended raw bytes are everything past the end of the bencoded
     * dict (`payload[dataSection.size .. end]`), and `total_size` is read with a
     * default of 0.
     *
     * @return the decoded [Message], or `null` if [payload] is not a well-formed
     *   `ut_metadata` message (not a dict, missing keys, or an unknown `msg_type`).
     */
    fun parse(payload: ByteArray): Message? {
        val root: BdecodeNode = Bdecode.decodeOrNull(payload) ?: return null
        if (root.type != BdecodeNode.Type.DICT) return null

        val typeNode = root.dictFindInt("msg_type")
        val pieceNode = root.dictFindInt("piece")
        if (!typeNode.isValid || !pieceNode.isValid) return null

        val type = Type.fromValue(typeNode.intValue().toInt()) ?: return null
        val piece = pieceNode.intValue().toInt()
        val totalSize = root.dictFindIntValue("total_size", 0L)

        val data: ByteArray = if (type == Type.DATA) {
            // The raw piece bytes follow the bencoded dict. dataSection() gives the
            // exact bytes the dict was parsed from (including framing), so its
            // length is the offset of the appended payload. This is the same arithmetic as
            // libtorrent's body.subspan(msg.data_section().size()).
            val dictLen = root.dataSection().size
            if (dictLen < payload.size) payload.copyOfRange(dictLen, payload.size) else ByteArray(0)
        } else {
            ByteArray(0)
        }

        return Message(type, piece, totalSize, data)
    }

    /** Number of 16 KiB pieces needed to carry a metadata blob of [totalSize] bytes. */
    fun pieceCount(totalSize: Int): Int =
        if (totalSize <= 0) 0 else (totalSize + PIECE_SIZE - 1) / PIECE_SIZE

    /** The length of metadata piece [piece] for a blob of [totalSize] bytes. */
    fun pieceLength(piece: Int, totalSize: Int): Int {
        val offset = piece * PIECE_SIZE
        return minOf(totalSize - offset, PIECE_SIZE)
    }
}

/**
 * Reassembles the metadata (`info`) blob from incoming [UtMetadata] `data` pieces
 * and verifies it against the expected info-hash. This is the codec-side counterpart of
 * `ut_metadata_plugin::received_metadata` / `metadata_request` in
 * `ut_metadata.cpp`.
 *
 * Construct it once the total size is known (from the first `data` message's
 * `total_size`, or from the peer's `metadata_size` handshake field), feed pieces
 * with [addPiece], then once [isComplete] call [verify] to check the assembled
 * bytes against the torrent's info-hash. libtorrent does the equivalent check via
 * `torrent::set_metadata` (which hashes and compares); the expensive-invariant
 * block at the top of `ut_metadata_plugin::metadata()` shows the exact hashes used:
 * `hasher(info) == v1` and `hasher256(info) == v2`.
 *
 * This helper is deliberately self-contained and does no networking; it just owns
 * the buffer and the per-piece "have" bitfield.
 *
 * @param totalSize the full length of the metadata blob, in bytes (must be > 0).
 */
class MetadataTransfer(val totalSize: Int) {

    init {
        require(totalSize > 0) { "metadata totalSize must be positive, was $totalSize" }
    }

    private val buffer = ByteArray(totalSize)

    /** The number of 16 KiB pieces this metadata spans. */
    val pieceCount: Int = UtMetadata.pieceCount(totalSize)

    private val have = BooleanArray(pieceCount)
    private var haveCount = 0

    /** True once every piece has been received. */
    val isComplete: Boolean get() = haveCount == pieceCount

    /** True if piece [piece] has already been stored. */
    fun hasPiece(piece: Int): Boolean = piece in 0 until pieceCount && have[piece]

    /** The list of piece indices still missing. Use it to drive [UtMetadata.encodeRequest]. */
    fun missingPieces(): List<Int> {
        val out = ArrayList<Int>()
        for (i in 0 until pieceCount) if (!have[i]) out.add(i)
        return out
    }

    /**
     * Store the bytes of one metadata piece. Mirrors the validity checks in
     * `received_metadata`:
     *  - `piece` must be in range,
     *  - the piece must not overflow the total ((`piece*16K + len > total`) is
     *    rejected),
     *  - and a piece other than the last must be exactly [UtMetadata.PIECE_SIZE]
     *    long (libtorrent enforces this implicitly because it sizes the buffer from
     *    `total_size`).
     *
     * Re-adding an already-present piece is a no-op that still returns `true`.
     *
     * @return `true` if the piece was accepted (or already present); `false` if it
     *   was out of range or the wrong size for its position.
     */
    fun addPiece(piece: Int, data: ByteArray): Boolean {
        if (piece < 0 || piece >= pieceCount) return false
        val offset = piece * UtMetadata.PIECE_SIZE
        val expected = UtMetadata.pieceLength(piece, totalSize)
        // libtorrent: if (piece * 16*1024 + buf.size() > metadata().size()) invalid
        if (offset.toLong() + data.size.toLong() > totalSize.toLong()) return false
        if (data.size != expected) return false
        if (have[piece]) return true
        data.copyInto(buffer, offset)
        have[piece] = true
        haveCount++
        return true
    }

    /**
     * Convenience: accept a parsed [UtMetadata.Message]. Only [UtMetadata.Type.DATA]
     * messages contribute; anything else returns `false`.
     */
    fun addMessage(msg: UtMetadata.Message): Boolean =
        if (msg.type == UtMetadata.Type.DATA) addPiece(msg.piece, msg.data) else false

    /**
     * The assembled metadata bytes. Only meaningful once [isComplete]; before then
     * it still returns the (partially-filled) buffer so callers can hash-check
     * incrementally if they wish.
     */
    fun assembled(): ByteArray = buffer.copyOf()

    /**
     * Verify the assembled metadata against an expected **v1** (SHA-1) info-hash, that
     * is `hasher(info).final() == info_hashes().v1`. Returns `false` if not yet
     * [isComplete].
     */
    fun verifySha1(expected: Sha1Hash): Boolean {
        if (!isComplete) return false
        return Hasher.hash(buffer) == expected
    }

    /**
     * Verify the assembled metadata against an expected **v2** (SHA-256) info-hash, that
     * is `hasher256(info).final() == info_hashes().v2`. Returns `false` if not yet
     * [isComplete].
     */
    fun verifySha256(expected: Sha256Hash): Boolean {
        if (!isComplete) return false
        return Hasher256.hash(buffer) == expected
    }

    /**
     * Verify against whichever info-hash width [expected] is: a 20-byte digest is
     * treated as a v1 (SHA-1) hash, a 32-byte digest as a v2 (SHA-256) hash. Any
     * other width returns `false`.
     */
    fun verify(expected: Digest32): Boolean = when (expected.size) {
        20 -> verifySha1(expected)
        32 -> verifySha256(expected)
        else -> false
    }
}
