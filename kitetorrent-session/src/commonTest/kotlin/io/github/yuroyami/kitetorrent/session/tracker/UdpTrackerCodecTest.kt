package io.github.yuroyami.kitetorrent.session.tracker

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure (no-network) tests for the BEP-15 UDP tracker codec. Every case asserts the
 * exact on-wire byte layout against the spec / libtorrent's
 * `udp_tracker_connection.cpp`, so the encoder and decoder are pinned without ever
 * touching a socket.
 */
class UdpTrackerCodecTest {

    // ---- helpers to build expected big-endian byte sequences -----------------

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun be64(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (v ushr ((7 - i) * 8)).toByte()
        return out
    }

    /** A 20-byte digest filled with [start], [start]+1, … (distinct, recognisable). */
    private fun ramp20(start: Int): ByteArray = ByteArray(20) { (start + it).toByte() }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val b = ByteArrayBuilder()
        for (p in parts) b.append(p)
        return b.toByteArray()
    }

    // ---- connect request -----------------------------------------------------

    @Test
    fun connectRequestMatchesBep15Layout() {
        val tid = 0x12345678
        val packet = UdpTrackerCodec.buildConnectRequest(tid)

        // 16 bytes: magic(8) action=0(4) transaction_id(4)
        val expected = concat(
            be64(0x41727101980L),       // protocol_id / connect magic
            be32(0),                    // action = connect
            be32(tid),                  // transaction_id
        )
        assertEquals(16, packet.size)
        assertContentEquals(expected, packet)

        // The magic must serialise as the two halves libtorrent writes: 0x417, 0x27101980.
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x04, 0x17), packet.copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(0x27, 0x10.toByte(), 0x19, 0x80.toByte()), packet.copyOfRange(4, 8))
    }

    // ---- announce request ----------------------------------------------------

    @Test
    fun announceRequestMatchesBep15Layout() {
        val connectionId = 0x0102030405060708L
        val tid = 0x7FFFFFFE
        val infoHash = ramp20(0)    // 00 01 .. 13
        val peerId = ramp20(100)    // 64 65 .. 77

        val req = AnnounceRequest(
            infoHash = Digest32.sha1(infoHash),
            peerId = Digest32.sha1(peerId),
            port = 6881,
            uploaded = 0xAABBCCDDL,
            downloaded = 0x1122334455L,
            left = 0x99L,
            event = TrackerEvent.STARTED,
            numWant = 50,
            key = 0xDEADBEEF.toInt(),
            ip = 0,
        )

        val packet = UdpTrackerCodec.buildAnnounceRequest(connectionId, tid, req)

        // 98 bytes, in exact field order from send_udp_announce:
        // connection_id(8) action=1(4) transaction_id(4) info_hash(20) peer_id(20)
        // downloaded(8) left(8) uploaded(8) event(4) ip(4) key(4) num_want(4) port(2)
        val expected = concat(
            be64(connectionId),
            be32(1),                    // action = announce
            be32(tid),
            infoHash,
            peerId,
            be64(0x1122334455L),        // downloaded
            be64(0x99L),                // left
            be64(0xAABBCCDDL),          // uploaded
            be32(2),                    // event = started
            be32(0),                    // ip
            be32(0xDEADBEEF.toInt()),   // key
            be32(50),                   // num_want
            be16(6881),                 // port
        )

        assertEquals(98, packet.size)
        assertEquals(UdpTrackerCodec.ANNOUNCE_REQUEST_SIZE, packet.size)
        assertContentEquals(expected, packet)
    }

    @Test
    fun announceRequestEncodesNegativeNumWantSentinel() {
        // BEP-15 uses num_want = -1 to mean "tracker default"; it must serialise
        // as 0xFFFFFFFF, not be clamped.
        val req = AnnounceRequest(
            infoHash = Digest32.sha1(ramp20(0)),
            peerId = Digest32.sha1(ramp20(0)),
            port = 1,
            uploaded = 0,
            downloaded = 0,
            left = 0,
            numWant = -1,
        )
        val packet = UdpTrackerCodec.buildAnnounceRequest(1L, 1, req)
        // num_want sits at offset 92 (98 - 2(port) - 4(num_want))
        assertContentEquals(byteArrayOf(-1, -1, -1, -1), packet.copyOfRange(92, 96))
    }

    @Test
    fun eventCodesMatchProtocol() {
        // none=0, completed=1, started=2, stopped=3 — fixed by BEP-15 / event_t ordinals.
        assertEquals(0, TrackerEvent.NONE.code)
        assertEquals(1, TrackerEvent.COMPLETED.code)
        assertEquals(2, TrackerEvent.STARTED.code)
        assertEquals(3, TrackerEvent.STOPPED.code)
    }

    // ---- scrape request ------------------------------------------------------

    @Test
    fun scrapeRequestMatchesBep15Layout() {
        val connectionId = -0x1L // 0xFFFFFFFFFFFFFFFF, exercises the sign bit
        val tid = 0x0BADF00D
        val infoHash = ramp20(7)

        val packet = UdpTrackerCodec.buildScrapeRequest(connectionId, tid, infoHash)

        val expected = concat(
            be64(connectionId),
            be32(2),    // action = scrape
            be32(tid),
            infoHash,
        )
        assertEquals(36, packet.size)
        assertContentEquals(expected, packet)
    }

    // ---- announce response parse --------------------------------------------

    @Test
    fun parsesAnnounceResponseWithIpv4Peers() {
        val tid = 0x44332211
        // action=1, transaction, interval=1800, leechers=5, seeders=42, then two peers
        val response = concat(
            be32(1),                            // action = announce
            be32(tid),
            be32(1800),                         // interval
            be32(5),                            // leechers (incomplete)
            be32(42),                           // seeders (complete)
            byteArrayOf(10, 0, 0, 1), be16(6881),   // 10.0.0.1:6881
            byteArrayOf(-64, -88, 1, 100), be16(51413), // 192.168.1.100:51413
        )

        val resp = UdpTrackerCodec.parseAnnounceResponse(response, tid)
        assertTrue(resp != null)
        assertEquals(1800, resp.interval)
        assertEquals(5, resp.leechers)
        assertEquals(42, resp.seeders)
        assertEquals(2, resp.peers.size)
        assertEquals(PeerEndpoint("10.0.0.1", 6881), resp.peers[0])
        assertEquals(PeerEndpoint("192.168.1.100", 51413), resp.peers[1])
    }

    @Test
    fun parsesAnnounceResponseWithNoPeers() {
        val tid = 1
        val response = concat(be32(1), be32(tid), be32(900), be32(0), be32(0))
        val resp = UdpTrackerCodec.parseAnnounceResponse(response, tid)
        assertTrue(resp != null)
        assertEquals(900, resp.interval)
        assertTrue(resp.peers.isEmpty())
    }

    @Test
    fun announceResponseRejectsTransactionMismatch() {
        val response = concat(be32(1), be32(0x11111111), be32(1800), be32(0), be32(0))
        // expected a different transaction id → ignored (null), per on_receive
        assertNull(UdpTrackerCodec.parseAnnounceResponse(response, 0x22222222))
    }

    @Test
    fun announceResponseRejectsWrongAction() {
        // action=2 (scrape) cannot be parsed as an announce response
        val tid = 7
        val response = concat(be32(2), be32(tid), be32(1800), be32(0), be32(0))
        assertNull(UdpTrackerCodec.parseAnnounceResponse(response, tid))
    }

    @Test
    fun announceResponseRejectsPartialPeerRecord() {
        // peer region is 7 bytes — not a multiple of the 6-byte stride → invalid length
        val tid = 9
        val response = concat(
            be32(1), be32(tid), be32(1800), be32(0), be32(0),
            byteArrayOf(10, 0, 0, 1, 0x1A, 0x00, 0x55), // 7 stray bytes
        )
        assertNull(UdpTrackerCodec.parseAnnounceResponse(response, tid))
    }

    @Test
    fun announceResponseRejectsTooShortHeader() {
        val tid = 3
        val response = concat(be32(1), be32(tid), be32(1800)) // only 12 bytes, need 20
        assertNull(UdpTrackerCodec.parseAnnounceResponse(response, tid))
    }

    @Test
    fun parsesAnnounceResponseWithIpv6Peer() {
        val tid = 0x21
        // one IPv6 peer: 2001:db8::1 then port 6881 (18-byte stride)
        val ipv6 = ByteArray(16).also {
            it[0] = 0x20; it[1] = 0x01; it[2] = 0x0D; it[3] = 0xB8.toByte(); it[15] = 0x01
        }
        val response = concat(
            be32(1), be32(tid), be32(1800), be32(1), be32(2),
            ipv6, be16(6881),
        )
        val resp = UdpTrackerCodec.parseAnnounceResponse(response, tid, ipv6 = true)
        assertTrue(resp != null)
        assertEquals(1, resp.peers.size)
        assertEquals("2001:db8:0:0:0:0:0:1", resp.peers[0].host)
        assertEquals(6881, resp.peers[0].port)
    }

    // ---- connect response parse ---------------------------------------------

    @Test
    fun parsesConnectResponse() {
        val tid = 0x55667788
        val connectionId = 0x0102030405060708L
        val response = concat(be32(0), be32(tid), be64(connectionId))
        val result = UdpTrackerCodec.parseConnectResponse(response, tid)
        assertTrue(result != null)
        assertEquals(connectionId, result.connectionId)
    }

    @Test
    fun connectResponseRejectsShortPacketAndWrongTransaction() {
        val tid = 0x55667788
        // too short (15 bytes)
        assertNull(UdpTrackerCodec.parseConnectResponse(ByteArray(15), tid))
        // right size, wrong transaction id
        val response = concat(be32(0), be32(0x00000001), be64(123L))
        assertNull(UdpTrackerCodec.parseConnectResponse(response, tid))
    }

    // ---- scrape response parse ----------------------------------------------

    @Test
    fun parsesScrapeResponse() {
        val tid = 0x10
        // action=2, transaction, seeders=100, completed=2000, leechers=30
        val response = concat(be32(2), be32(tid), be32(100), be32(2000), be32(30))
        val resp = UdpTrackerCodec.parseScrapeResponse(response, tid)
        assertTrue(resp != null)
        assertEquals(100, resp.seeders)
        assertEquals(2000, resp.completed)
        assertEquals(30, resp.leechers)
    }

    // ---- error response parse -----------------------------------------------

    @Test
    fun parsesErrorMessage() {
        val tid = 0x99
        val msg = "connection_id mismatch"
        val response = concat(be32(3), be32(tid), msg.encodeToByteArray())
        assertEquals(msg, UdpTrackerCodec.parseErrorMessage(response, tid))
        // a non-error packet yields null
        val announce = concat(be32(1), be32(tid), be32(1800), be32(0), be32(0))
        assertNull(UdpTrackerCodec.parseErrorMessage(announce, tid))
    }

    @Test
    fun peekActionReadsLeadingActionField() {
        assertEquals(0, UdpTrackerCodec.peekAction(concat(be32(0), be32(1))))
        assertEquals(1, UdpTrackerCodec.peekAction(concat(be32(1), be32(1))))
        assertEquals(3, UdpTrackerCodec.peekAction(concat(be32(3), be32(1))))
        assertEquals(-1, UdpTrackerCodec.peekAction(byteArrayOf(0, 0))) // too short
    }
}
