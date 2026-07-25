package io.github.yuroyami.kitetorrent.session.peer

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.protocol.Handshake
import io.github.yuroyami.kitetorrent.protocol.PeerMessage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the parse/build logic of [PeerConnection] against an in-memory [FakeDuplex]:
 * the handshake exchange + validation, message framing in [PeerConnection.receiveLoop],
 * and the state transitions the wire protocol mandates. No sockets are involved. The
 * fake serves preloaded bytes and records what was written.
 */
class PeerConnectionTest {

    private fun hash(seed: Int): Sha1Hash =
        Digest32.of(ByteArray(20) { ((it + seed) and 0xff).toByte() })

    private val infoHash = hash(1)
    private val ourPeerId = hash(100)
    private val theirPeerId = hash(200)
    private val numPieces = 20

    // ---- handshake ------------------------------------------------------------

    @Test
    fun performHandshake_writesOurHandshakeAndReturnsRemoteId() = runSuspendTest {
        // The peer replies with a valid handshake on the SAME info-hash.
        val peerReserved = Handshake.defaultReserved()
        val peerHandshake = Handshake.encode(infoHash, theirPeerId, peerReserved)
        val fake = FakeDuplex(inbound = peerHandshake)
        val conn = PeerConnection(fake, infoHash, ourPeerId, numPieces)

        val result = conn.performHandshake()

        // returned the peer's id + reserved
        assertEquals(theirPeerId, result.peerId)
        assertContentEquals(peerReserved, result.reserved)
        assertEquals(theirPeerId, conn.remotePeerId)

        // default reserved advertises DHT + Fast + extension protocol (BEP-5/6/10).
        assertTrue(result.supportsDht)
        assertTrue(result.supportsFast)
        assertTrue(result.supportsExtended)

        // we wrote exactly our own 68-byte handshake with our info-hash + peer-id.
        val outBytes = fake.written.toByteArray()
        assertEquals(Handshake.LENGTH, outBytes.size)
        val decodedOurs = Handshake.decode(outBytes)!!
        assertEquals(infoHash, decodedOurs.infoHash)
        assertEquals(ourPeerId, decodedOurs.peerId)
    }

    @Test
    fun performHandshake_rejectsWrongInfoHash() = runSuspendTest {
        val wrongInfoHash = hash(42) // different torrent
        val peerHandshake = Handshake.encode(wrongInfoHash, theirPeerId)
        val fake = FakeDuplex(inbound = peerHandshake)
        val conn = PeerConnection(fake, infoHash, ourPeerId, numPieces)

        val ex = assertFailsWith<HandshakeException> {
            conn.performHandshake()
        }
        assertTrue(ex.message!!.contains("info-hash mismatch"))
        // peer-id must not have been recorded on a failed handshake.
        assertEquals(null, conn.remotePeerId)
    }

    @Test
    fun performHandshake_rejectsNonBittorrentProtocol() = runSuspendTest {
        // 68 bytes of garbage: wrong pstrlen byte ⇒ Handshake.decode returns null.
        val garbage = ByteArray(Handshake.LENGTH) { 0x55 }
        val fake = FakeDuplex(inbound = garbage)
        val conn = PeerConnection(fake, infoHash, ourPeerId, numPieces)

        assertFailsWith<HandshakeException> { conn.performHandshake() }
    }

    // ---- receive loop: framing + decode --------------------------------------

    @Test
    fun receiveLoop_decodesBitfieldAndPieceMessages() = runSuspendTest {
        // Build a bitfield where the peer has pieces 0, 3 and 19 (last). The wire
        // payload must be exactly ceil(20/8)=3 bytes.
        val theirBits = Bitfield(numPieces).apply {
            setBit(0); setBit(3); setBit(19)
        }
        val bitfieldFrame = PeerMessage.Bitfield(theirBits).encode()

        // A piece message: 4 bytes of block at offset 16 of piece 3.
        val block = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val pieceFrame = PeerMessage.Piece(piece = 3, begin = 16, block = block).encode()

        // Feed: bitfield, then a keep-alive (len 0), then the piece.
        val keepAlive = PeerMessage.KeepAlive.encode()
        val fake = FakeDuplex(inbound = bitfieldFrame + keepAlive + pieceFrame)
        val conn = PeerConnection(fake, infoHash, ourPeerId, numPieces)

        val received = mutableListOf<PeerMessage>()
        conn.receiveLoopCatching { received.add(it) }

        // three messages decoded in order (loop ends when inbound is exhausted).
        assertEquals(3, received.size)

        val bf = received[0]
        assertTrue(bf is PeerMessage.Bitfield)
        assertTrue((bf).bitfield[0])
        assertTrue((bf).bitfield[3])
        assertTrue((bf).bitfield[19])
        assertFalse((bf).bitfield[1])

        assertTrue(received[1] is PeerMessage.KeepAlive)

        val pc = received[2]
        assertTrue(pc is PeerMessage.Piece)
        assertEquals(3, (pc).piece)
        assertEquals(16, (pc).begin)
        assertContentEquals(block, (pc).block)

        // the bitfield was applied to connection state.
        assertTrue(conn.state.bitfieldReceived)
        assertTrue(conn.theirBitfield[0])
        assertTrue(conn.theirBitfield[3])
        assertTrue(conn.theirBitfield[19])
        assertEquals(3, conn.state.theirPieceCount)
        assertFalse(conn.state.theirSeed)
    }

    @Test
    fun receiveLoop_flipsChokeAndInterestFlags() = runSuspendTest {
        // peer sends: unchoke, interested. Both start at their defaults.
        val frames = PeerMessage.Unchoke.encode() + PeerMessage.Interested.encode()
        val fake = FakeDuplex(inbound = frames)
        val conn = PeerConnection(fake, infoHash, ourPeerId, numPieces)

        // defaults: they are choking us, not interested.
        assertTrue(conn.theirChoking)
        assertFalse(conn.theirInterested)

        conn.receiveLoopCatching { /* ignore */ }

        assertFalse(conn.theirChoking)   // unchoke flipped it
        assertTrue(conn.theirInterested) // interested flipped it
    }

    @Test
    fun receiveLoop_haveAllMarksSeedAndHaveUpdatesBitfield() = runSuspendTest {
        // have_all ⇒ peer is a seed; then a (redundant) have for piece 5.
        val frames = PeerMessage.HaveAll.encode() + PeerMessage.Have(5).encode()
        val fake = FakeDuplex(inbound = frames)
        val conn = PeerConnection(fake, infoHash, ourPeerId, numPieces)

        conn.receiveLoopCatching { /* ignore */ }

        assertTrue(conn.state.bitfieldReceived)
        assertTrue(conn.state.theirSeed)
        assertEquals(numPieces, conn.state.theirPieceCount)
        assertTrue(conn.state.theyHave(5))
    }

    @Test
    fun receiveLoop_rejectsWrongSizedBitfield() = runSuspendTest {
        // A bitfield sized for a DIFFERENT piece count (8 pieces ⇒ 1 byte) is invalid
        // for our 20-piece torrent (expects 3 bytes) ⇒ ProtocolException.
        val wrongBits = Bitfield(8).apply { setBit(0) }
        val badFrame = PeerMessage.Bitfield(wrongBits).encode()
        val fake = FakeDuplex(inbound = badFrame)
        val conn = PeerConnection(fake, infoHash, ourPeerId, numPieces)

        // receiveLoopCatching rethrows protocol violations (does NOT swallow them).
        assertFailsWith<ProtocolException> {
            conn.receiveLoopCatching { /* ignore */ }
        }
    }

    @Test
    fun receiveLoop_rejectsOversizedFrame() = runSuspendTest {
        // A length prefix declaring a 2 MiB body, over the 1 MiB cap ⇒ ProtocolException
        // before any body is read.
        val twoMiB = 2 * 1024 * 1024
        val header = byteArrayOf(
            ((twoMiB ushr 24) and 0xff).toByte(),
            ((twoMiB ushr 16) and 0xff).toByte(),
            ((twoMiB ushr 8) and 0xff).toByte(),
            (twoMiB and 0xff).toByte(),
        )
        val fake = FakeDuplex(inbound = header)
        val conn = PeerConnection(fake, infoHash, ourPeerId, numPieces)

        assertFailsWith<ProtocolException> {
            conn.receiveLoop { /* ignore */ }
        }
    }

    // ---- outgoing senders -----------------------------------------------------

    @Test
    fun senders_writeEncodedFramesAndFlipOurFlags() = runSuspendTest {
        val fake = FakeDuplex()
        val conn = PeerConnection(fake, infoHash, ourPeerId, numPieces)

        // defaults: we choke them, are not interested.
        assertTrue(conn.amChoking)
        assertFalse(conn.amInterested)

        conn.sendInterested()
        assertTrue(conn.amInterested)
        conn.sendUnchoke()
        assertFalse(conn.amChoking)
        conn.sendRequest(piece = 7, begin = 0, length = 16384)

        // The written bytes must re-parse, in order, to exactly those three frames.
        val written = fake.written.toByteArray()
        val msgs = drainFrames(written)
        assertEquals(3, msgs.size)
        assertTrue(msgs[0] is PeerMessage.Interested)
        assertTrue(msgs[1] is PeerMessage.Unchoke)
        val req = msgs[2]
        assertTrue(req is PeerMessage.Request)
        assertEquals(7, (req).piece)
        assertEquals(0, (req).begin)
        assertEquals(16384, (req).length)
    }

    @Test
    fun sendPiece_roundTripsThroughTryReadMessage() = runSuspendTest {
        val fake = FakeDuplex()
        val conn = PeerConnection(fake, infoHash, ourPeerId, numPieces)

        val block = ByteArray(16384) { (it and 0xff).toByte() }
        conn.sendPiece(piece = 2, begin = 32768, block = block)

        val msgs = drainFrames(fake.written.toByteArray())
        assertEquals(1, msgs.size)
        val p = msgs[0]
        assertTrue(p is PeerMessage.Piece)
        assertEquals(2, (p).piece)
        assertEquals(32768, (p).begin)
        assertContentEquals(block, (p).block)
    }

    /** Re-frame a byte stream of length-prefixed messages back into [PeerMessage]s. */
    private fun drainFrames(bytes: ByteArray): List<PeerMessage> {
        val out = mutableListOf<PeerMessage>()
        var off = 0
        while (off < bytes.size) {
            val (msg, consumed) = PeerMessage.tryReadMessage(bytes, off)
                ?: error("truncated frame at offset $off")
            out.add(msg)
            off += consumed
        }
        return out
    }
}
