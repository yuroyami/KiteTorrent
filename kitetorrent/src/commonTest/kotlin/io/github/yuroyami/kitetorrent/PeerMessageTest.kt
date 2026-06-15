package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.protocol.Handshake
import io.github.yuroyami.kitetorrent.protocol.MessageId
import io.github.yuroyami.kitetorrent.protocol.PeerMessage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the BitTorrent peer wire-protocol codec ([Handshake], [PeerMessage]).
 *
 * The wire format is pinned directly against `bt_peer_connection.cpp`'s `write_*`
 * functions: we assert the exact 68-byte handshake layout, the four-zero keep-alive,
 * and the `request` frame's `[len=13][id=6][int32 piece][int32 begin][int32 length]`
 * byte structure. Everything else is checked by round-tripping `encode -> tryReadMessage`
 * and confirming the message decodes back to an equal value, with the reported
 * consumed-byte count matching the encoded length.
 */
class PeerMessageTest {

    // --- handshake -----------------------------------------------------------

    @Test
    fun handshakeLayoutIsExactly68Bytes() {
        val ih = Digest32.of(ByteArray(20) { (it + 1).toByte() }) // 01 02 .. 14
        val pid = Digest32.of(ByteArray(20) { (0xA0 + it).toByte() }) // a0 a1 .. b3
        val reserved = Handshake.defaultReserved()
        val wire = Handshake.encode(ih, pid, reserved)

        assertEquals(68, wire.size, "handshake must be 68 bytes")
        assertEquals(19, wire[0].toInt() and 0xff, "pstrlen must be 19")
        assertEquals("BitTorrent protocol", wire.copyOfRange(1, 20).decodeToString())

        // reserved bytes occupy [20, 28)
        val res = wire.copyOfRange(20, 28)
        assertContentEquals(reserved, res)
        // default flags: ext (byte5 0x10), dht+fast (byte7 0x05)
        assertEquals(0x10, res[5].toInt() and 0xff)
        assertEquals(0x05, res[7].toInt() and 0xff)

        // info_hash [28,48), peer_id [48,68)
        assertContentEquals(ih.toByteArray(), wire.copyOfRange(28, 48))
        assertContentEquals(pid.toByteArray(), wire.copyOfRange(48, 68))
    }

    @Test
    fun handshakeRoundTripAndFlagHelpers() {
        val ih = Hasher.hash("torrent".encodeToByteArray())
        val pid = Hasher.hash("peer".encodeToByteArray())
        val wire = Handshake.encode(ih, pid)

        val hs = Handshake.decode(wire)
        assertNotNull(hs)
        assertEquals(ih, hs.infoHash)
        assertEquals(pid, hs.peerId)
        assertTrue(hs.supportsDht, "default reserved sets DHT bit")
        assertTrue(hs.supportsFast, "default reserved sets fast bit")
        assertTrue(hs.supportsExtended, "default reserved sets extension bit")
        // re-encode should reproduce the original bytes
        assertContentEquals(wire, hs.encode())
    }

    @Test
    fun handshakeFlagBitsArePositioned() {
        // DHT only
        val dht = ByteArray(8).also { it[7] = 0x01 }
        Handshake.decode(Handshake.encode(Digest32.zeros(20), Digest32.zeros(20), dht))!!.let {
            assertTrue(it.supportsDht)
            assertTrue(!it.supportsFast)
            assertTrue(!it.supportsExtended)
        }
        // fast only
        val fast = ByteArray(8).also { it[7] = 0x04 }
        Handshake.decode(Handshake.encode(Digest32.zeros(20), Digest32.zeros(20), fast))!!.let {
            assertTrue(!it.supportsDht)
            assertTrue(it.supportsFast)
            assertTrue(!it.supportsExtended)
        }
        // extension only (byte 5)
        val ext = ByteArray(8).also { it[5] = 0x10 }
        Handshake.decode(Handshake.encode(Digest32.zeros(20), Digest32.zeros(20), ext))!!.let {
            assertTrue(!it.supportsDht)
            assertTrue(!it.supportsFast)
            assertTrue(it.supportsExtended)
        }
    }

    @Test
    fun handshakeDecodeRejectsMalformed() {
        // too short
        assertNull(Handshake.decode(ByteArray(67)))
        // wrong pstrlen
        val wire = Handshake.encode(Digest32.zeros(20), Digest32.zeros(20))
        val badLen = wire.copyOf().also { it[0] = 18 }
        assertNull(Handshake.decode(badLen))
        // wrong protocol string
        val badStr = wire.copyOf().also { it[1] = 'X'.code.toByte() }
        assertNull(Handshake.decode(badStr))
    }

    @Test
    fun handshakeIgnoresTrailingBytes() {
        val wire = Handshake.encode(Digest32.zeros(20), Digest32.max(20))
        // append a follow-on message's bytes; decode must still succeed on the first 68
        val withTrailer = wire + byteArrayOf(0, 0, 0, 1, MessageId.UNCHOKE.toByte())
        val hs = Handshake.decode(withTrailer)
        assertNotNull(hs)
        assertEquals(Digest32.max(20), hs.peerId)
    }

    // --- keep-alive & exact request layout -----------------------------------

    @Test
    fun keepAliveIsFourZeroBytes() {
        assertContentEquals(byteArrayOf(0, 0, 0, 0), PeerMessage.KeepAlive.encode())
    }

    @Test
    fun requestHasExactByteLayout() {
        val msg = PeerMessage.Request(piece = 0x01020304, begin = 0x00004000, length = 0x00004000)
        val wire = msg.encode()
        // length prefix = 13
        assertContentEquals(byteArrayOf(0, 0, 0, 13), wire.copyOfRange(0, 4))
        // id = 6
        assertEquals(MessageId.REQUEST, wire[4].toInt() and 0xff)
        assertEquals(6, wire[4].toInt() and 0xff)
        // piece (big-endian)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), wire.copyOfRange(5, 9))
        // begin = 0x4000
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x40, 0x00), wire.copyOfRange(9, 13))
        // length = 0x4000
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x40, 0x00), wire.copyOfRange(13, 17))
        assertEquals(17, wire.size) // 4 + 13
    }

    @Test
    fun dhtPortUsesUint16() {
        val wire = PeerMessage.Port(6881).encode()
        assertContentEquals(byteArrayOf(0, 0, 0, 3, MessageId.DHT_PORT.toByte()), wire.copyOfRange(0, 5))
        // 6881 = 0x1AE1
        assertEquals(0x1A, wire[5].toInt() and 0xff)
        assertEquals(0xE1, wire[6].toInt() and 0xff)
        assertEquals(7, wire.size)
    }

    // --- round-trips for every message type ----------------------------------

    private fun roundTrip(msg: PeerMessage) {
        val wire = msg.encode()
        val result = PeerMessage.tryReadMessage(wire, 0)
        assertNotNull(result, "tryReadMessage returned null for $msg")
        val (decoded, consumed) = result
        assertEquals(wire.size, consumed, "consumed byte count mismatch for $msg")
        assertEquals(msg, decoded, "round-trip mismatch for $msg")
    }

    @Test
    fun roundTripSimpleMessages() {
        roundTrip(PeerMessage.KeepAlive)
        roundTrip(PeerMessage.Choke)
        roundTrip(PeerMessage.Unchoke)
        roundTrip(PeerMessage.Interested)
        roundTrip(PeerMessage.NotInterested)
        roundTrip(PeerMessage.HaveAll)
        roundTrip(PeerMessage.HaveNone)
    }

    @Test
    fun roundTripIndexMessages() {
        roundTrip(PeerMessage.Have(42))
        roundTrip(PeerMessage.Have(0))
        roundTrip(PeerMessage.Suggest(7))
        roundTrip(PeerMessage.AllowedFast(123456))
        roundTrip(PeerMessage.Port(51413))
    }

    @Test
    fun roundTripTripletMessages() {
        roundTrip(PeerMessage.Request(10, 0, 16384))
        roundTrip(PeerMessage.Cancel(10, 16384, 16384))
        roundTrip(PeerMessage.RejectRequest(5, 32768, 16384))
    }

    @Test
    fun roundTripPiece() {
        val block = ByteArray(16384) { (it * 7).toByte() }
        roundTrip(PeerMessage.Piece(3, 0, block))
        roundTrip(PeerMessage.Piece(3, 16384, ByteArray(0))) // empty block edge case
    }

    @Test
    fun roundTripExtended() {
        roundTrip(PeerMessage.Extended(0, "d1:md11:ut_metadatai1eee".encodeToByteArray()))
        roundTrip(PeerMessage.Extended(3, ByteArray(0)))
    }

    @Test
    fun roundTripBitfield() {
        val bf = Bitfield(20)
        bf.setBit(0); bf.setBit(7); bf.setBit(8); bf.setBit(19)
        val msg = PeerMessage.Bitfield(bf)
        val wire = msg.encode()
        // length = numBytes + 1 = 3 + 1 = 4
        assertContentEquals(byteArrayOf(0, 0, 0, 4, MessageId.BITFIELD.toByte()), wire.copyOfRange(0, 5))

        val result = PeerMessage.tryReadMessage(wire, 0)
        assertNotNull(result)
        val (decoded, consumed) = result
        assertEquals(wire.size, consumed)
        assertTrue(decoded is PeerMessage.Bitfield)
        // The decoded bitfield carries the same raw bytes; bit positions preserved.
        assertContentEquals(bf.data(), decoded.bitfield.data())
        assertTrue(decoded.bitfield.getBit(0))
        assertTrue(decoded.bitfield.getBit(7))
        assertTrue(decoded.bitfield.getBit(8))
        assertTrue(decoded.bitfield.getBit(19))
        assertTrue(!decoded.bitfield.getBit(1))
    }

    // --- streaming framing ---------------------------------------------------

    @Test
    fun tryReadReturnsNullWhenIncomplete() {
        val wire = PeerMessage.Request(1, 2, 3).encode() // 17 bytes
        // fewer than 4 bytes -> null (can't even read the length)
        assertNull(PeerMessage.tryReadMessage(wire.copyOfRange(0, 3), 0))
        // header read but body not fully present -> null
        assertNull(PeerMessage.tryReadMessage(wire.copyOfRange(0, 10), 0))
    }

    @Test
    fun tryReadConsumesExactlyOneMessageAtOffset() {
        val first = PeerMessage.Have(99).encode()
        val second = PeerMessage.Unchoke.encode()
        val stream = first + second

        val r1 = PeerMessage.tryReadMessage(stream, 0)
        assertNotNull(r1)
        assertEquals(PeerMessage.Have(99), r1.first)
        assertEquals(first.size, r1.second)

        // continue from the offset the first read reported
        val r2 = PeerMessage.tryReadMessage(stream, r1.second)
        assertNotNull(r2)
        assertEquals(PeerMessage.Unchoke, r2.first)
        assertEquals(second.size, r2.second)

        // no third message
        assertNull(PeerMessage.tryReadMessage(stream, r1.second + r2.second))
    }

    @Test
    fun unknownMessageIdSurvivesRoundTrip() {
        // id 99 is not modelled; decode -> Unknown, and Unknown re-encodes identically.
        val frame = byteArrayOf(0, 0, 0, 3, 99, 0x11, 0x22)
        val r = PeerMessage.tryReadMessage(frame, 0)
        assertNotNull(r)
        val msg = r.first
        assertTrue(msg is PeerMessage.Unknown)
        assertEquals(99, msg.id)
        assertContentEquals(byteArrayOf(0x11, 0x22), msg.payload)
        assertContentEquals(frame, msg.encode())
    }
}
