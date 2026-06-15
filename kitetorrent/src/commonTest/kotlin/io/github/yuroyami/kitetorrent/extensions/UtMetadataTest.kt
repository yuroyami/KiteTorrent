package io.github.yuroyami.kitetorrent.extensions

import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.crypto.Hasher256
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BEP-9 `ut_metadata` codec tests — verifies the exact wire bytes against
 * hand-computed bencode golden vectors and round-trips request / data / reject,
 * including the raw piece bytes appended after a `data` message's dict.
 */
class UtMetadataTest {

    // --- golden wire bytes -----------------------------------------------------

    @Test
    fun request_goldenBytes() {
        // canonical key order: msg_type < piece  -> d8:msg_typei0e5:piecei3ee
        val bytes = UtMetadata.encodeRequest(piece = 3)
        assertEquals("d8:msg_typei0e5:piecei3ee", bytes.decodeToString())
    }

    @Test
    fun reject_goldenBytes() {
        // msg_type 2 -> d8:msg_typei2e5:piecei7ee
        val bytes = UtMetadata.encodeReject(piece = 7)
        assertEquals("d8:msg_typei2e5:piecei7ee", bytes.decodeToString())
    }

    @Test
    fun data_goldenBytes_dictThenRawPayload() {
        // keys sort: msg_type < piece < total_size ('m' < 'p' < 't')
        // d8:msg_typei1e5:piecei0e10:total_sizei5ee  followed by raw "hello"
        val piece = "hello".encodeToByteArray()
        val bytes = UtMetadata.encodeData(piece = 0, totalSize = 5L, pieceData = piece)
        val expectedDict = "d8:msg_typei1e5:piecei0e10:total_sizei5ee"
        assertEquals(expectedDict + "hello", bytes.decodeToString())
        // the raw payload really is appended *after* the bencode dict
        assertEquals(expectedDict.length + piece.size, bytes.size)
    }

    // --- round trips -----------------------------------------------------------

    @Test
    fun requestRoundTrip() {
        val msg = UtMetadata.parse(UtMetadata.encodeRequest(piece = 42))
        assertNotNull(msg)
        assertEquals(UtMetadata.Type.REQUEST, msg.type)
        assertEquals(42, msg.piece)
        assertEquals(0, msg.data.size)
    }

    @Test
    fun rejectRoundTrip() {
        val msg = UtMetadata.parse(UtMetadata.encodeReject(piece = 9))
        assertNotNull(msg)
        assertEquals(UtMetadata.Type.REJECT, msg.type)
        assertEquals(9, msg.piece)
        assertEquals(0, msg.data.size)
    }

    @Test
    fun dataRoundTrip_recoversAppendedBytes() {
        val payload = Random(99).nextBytes(UtMetadata.PIECE_SIZE) // a full 16 KiB piece
        val total = 40_000L
        val wire = UtMetadata.encodeData(piece = 2, totalSize = total, pieceData = payload)

        val msg = UtMetadata.parse(wire)
        assertNotNull(msg)
        assertEquals(UtMetadata.Type.DATA, msg.type)
        assertEquals(2, msg.piece)
        assertEquals(total, msg.totalSize)
        assertContentEquals(payload, msg.data)
    }

    @Test
    fun dataRoundTrip_lastShorterPiece() {
        // a final piece can be < 16 KiB; make sure trailing-byte extraction is exact
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val wire = UtMetadata.encodeData(piece = 1, totalSize = UtMetadata.PIECE_SIZE + 5L, pieceData = payload)
        val msg = UtMetadata.parse(wire)
        assertNotNull(msg)
        assertContentEquals(payload, msg.data)
    }

    // --- malformed input -> null ----------------------------------------------

    @Test
    fun parse_rejectsNonDict() {
        assertNull(UtMetadata.parse("i5e".encodeToByteArray()))
        assertNull(UtMetadata.parse("5:hello".encodeToByteArray()))
        assertNull(UtMetadata.parse(ByteArray(0)))
        assertNull(UtMetadata.parse("not bencode".encodeToByteArray()))
    }

    @Test
    fun parse_rejectsMissingKeys() {
        // missing piece
        assertNull(UtMetadata.parse("d8:msg_typei0ee".encodeToByteArray()))
        // missing msg_type
        assertNull(UtMetadata.parse("d5:piecei0ee".encodeToByteArray()))
    }

    @Test
    fun parse_rejectsUnknownMsgType() {
        assertNull(UtMetadata.parse("d8:msg_typei5e5:piecei0ee".encodeToByteArray()))
    }

    // --- piece math ------------------------------------------------------------

    @Test
    fun pieceCountAndLength() {
        assertEquals(0, UtMetadata.pieceCount(0))
        assertEquals(1, UtMetadata.pieceCount(1))
        assertEquals(1, UtMetadata.pieceCount(UtMetadata.PIECE_SIZE))
        assertEquals(2, UtMetadata.pieceCount(UtMetadata.PIECE_SIZE + 1))
        // 40000 bytes -> ceil(40000/16384) = 3 pieces; last is 40000 - 2*16384 = 7232
        assertEquals(3, UtMetadata.pieceCount(40_000))
        assertEquals(UtMetadata.PIECE_SIZE, UtMetadata.pieceLength(0, 40_000))
        assertEquals(40_000 - 2 * UtMetadata.PIECE_SIZE, UtMetadata.pieceLength(2, 40_000))
    }
}

/**
 * [MetadataTransfer] reassembly + info-hash verification tests. The "metadata" here
 * is an arbitrary byte blob standing in for a bencoded `info` dict — what matters
 * for the codec is that the reassembled bytes hash to the expected info-hash, which
 * is exactly what `torrent::set_metadata` checks in libtorrent.
 */
class MetadataTransferTest {

    private fun fakeInfo(size: Int, seed: Int): ByteArray = Random(seed).nextBytes(size)

    @Test
    fun reassemblesMultiPieceAndVerifiesSha1() {
        val total = 40_000
        val info = fakeInfo(total, seed = 7)
        val expectedV1 = Hasher.hash(info)

        val xfer = MetadataTransfer(total)
        assertEquals(3, xfer.pieceCount)
        assertFalse(xfer.isComplete)
        assertEquals(listOf(0, 1, 2), xfer.missingPieces())

        // deliver pieces out of order, by slicing the source info the same way a
        // seeder would (offset = piece * 16K, len = min(16K, remaining))
        for (piece in intArrayOf(2, 0, 1)) {
            val off = piece * UtMetadata.PIECE_SIZE
            val len = UtMetadata.pieceLength(piece, total)
            val slice = info.copyOfRange(off, off + len)
            // exercise the full encode -> parse -> addMessage path
            val wire = UtMetadata.encodeData(piece, total.toLong(), slice)
            val msg = UtMetadata.parse(wire)!!
            assertTrue(xfer.addMessage(msg))
        }

        assertTrue(xfer.isComplete)
        assertContentEquals(info, xfer.assembled())
        assertTrue(xfer.verifySha1(expectedV1))
        assertTrue(xfer.verify(expectedV1)) // 20-byte digest dispatches to v1
    }

    @Test
    fun singlePieceUnderSixteenKiB() {
        val total = 1234
        val info = fakeInfo(total, seed = 3)
        val xfer = MetadataTransfer(total)
        assertEquals(1, xfer.pieceCount)
        assertTrue(xfer.addPiece(0, info))
        assertTrue(xfer.isComplete)
        assertTrue(xfer.verifySha1(Hasher.hash(info)))
    }

    @Test
    fun verifySha256Width() {
        val total = 2000
        val info = fakeInfo(total, seed = 5)
        val xfer = MetadataTransfer(total)
        xfer.addPiece(0, info)
        val expectedV2 = Hasher256.hash(info)
        assertTrue(xfer.verifySha256(expectedV2))
        assertTrue(xfer.verify(expectedV2)) // 32-byte digest dispatches to v2
    }

    @Test
    fun rejectsBadHash() {
        val total = 2000
        val info = fakeInfo(total, seed = 1)
        val xfer = MetadataTransfer(total)
        xfer.addPiece(0, info)
        val wrong = Hasher.hash(fakeInfo(total, seed = 2))
        assertFalse(xfer.verifySha1(wrong))
    }

    @Test
    fun incompleteDoesNotVerify() {
        val total = 40_000
        val info = fakeInfo(total, seed = 4)
        val xfer = MetadataTransfer(total)
        xfer.addPiece(0, info.copyOfRange(0, UtMetadata.PIECE_SIZE))
        assertFalse(xfer.isComplete)
        // even with the correct final hash, an incomplete buffer must not verify
        assertFalse(xfer.verifySha1(Hasher.hash(info)))
    }

    @Test
    fun rejectsOutOfRangeOrWrongSizedPiece() {
        val total = 40_000
        val xfer = MetadataTransfer(total)
        // out of range piece index
        assertFalse(xfer.addPiece(99, ByteArray(UtMetadata.PIECE_SIZE)))
        assertFalse(xfer.addPiece(-1, ByteArray(UtMetadata.PIECE_SIZE)))
        // wrong size for a full (non-last) piece
        assertFalse(xfer.addPiece(0, ByteArray(100)))
        // a piece that would overflow the total
        assertFalse(xfer.addPiece(0, ByteArray(total + 1)))
    }

    @Test
    fun reAddingPieceIsIdempotent() {
        val total = 1000
        val info = fakeInfo(total, seed = 8)
        val xfer = MetadataTransfer(total)
        assertTrue(xfer.addPiece(0, info))
        // adding the same piece again is a no-op that still reports success
        assertTrue(xfer.addPiece(0, info))
        assertTrue(xfer.isComplete)
    }
}
