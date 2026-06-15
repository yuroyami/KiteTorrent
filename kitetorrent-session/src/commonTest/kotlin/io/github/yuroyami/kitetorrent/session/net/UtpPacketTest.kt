package io.github.yuroyami.kitetorrent.session.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure (no-socket) tests for the BEP-29 uTP header codec ([UtpHeader], [buildUtpPacket],
 * [parseUtpPacket]). Each case pins the exact on-wire byte layout against the spec /
 * libtorrent's `utp_header` field order, so the encoder/decoder are locked without
 * ever touching the network.
 */
class UtpPacketTest {

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Long) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    /** Concatenate byte arrays. */
    private fun bytes(vararg parts: ByteArray): ByteArray {
        var n = 0
        for (p in parts) n += p.size
        val out = ByteArray(n)
        var off = 0
        for (p in parts) { p.copyInto(out, off); off += p.size }
        return out
    }

    // ---- exact header layout: ST_SYN -----------------------------------------

    @Test
    fun synHeaderMatchesExactBytes() {
        // type_ver = (ST_SYN=4 << 4) | version=1 = 0x41
        val h = UtpHeader(
            type = UtpType.ST_SYN,
            extension = UtpExtension.NONE,
            connectionId = 0x1234,
            timestampMicros = 0xAABBCCDDL,
            timestampDiffMicros = 0,
            wndSize = 0,
            seqNr = 0x0001,
            ackNr = 0x0000,
        )
        val expected = bytes(
            byteArrayOf(0x41),          // type_ver: ST_SYN | v1
            byteArrayOf(0x00),          // extension: none
            be16(0x1234),               // connection_id
            be32(0xAABBCCDDL),          // timestamp_microseconds
            be32(0),                    // timestamp_difference
            be32(0),                    // wnd_size
            be16(0x0001),               // seq_nr
            be16(0x0000),               // ack_nr
        )
        assertEquals(UTP_HEADER_SIZE, expected.size)
        assertContentEquals(expected, h.encode())
    }

    // ---- exact header layout: ST_DATA with payload ---------------------------

    @Test
    fun dataPacketMatchesExactBytesAndRoundTrips() {
        // type_ver = (ST_DATA=0 << 4) | version=1 = 0x01
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val h = UtpHeader(
            type = UtpType.ST_DATA,
            extension = UtpExtension.NONE,
            connectionId = 0xBEEF,
            timestampMicros = 0x01020304L,
            timestampDiffMicros = 0x05060708L,
            wndSize = 0x0000FFFFL,
            seqNr = 0x0009,
            ackNr = 0x0007,
        )
        val packet = buildUtpPacket(h, payload)

        val expectedHeader = bytes(
            byteArrayOf(0x01),          // type_ver: ST_DATA | v1
            byteArrayOf(0x00),          // extension: none
            be16(0xBEEF),               // connection_id
            be32(0x01020304L),          // timestamp_microseconds
            be32(0x05060708L),          // timestamp_difference
            be32(0x0000FFFFL),          // wnd_size
            be16(0x0009),               // seq_nr
            be16(0x0007),               // ack_nr
        )
        assertContentEquals(bytes(expectedHeader, payload), packet)

        // parse -> identical header + payload, no SACK
        val parsed = parseUtpPacket(packet)!!
        assertEquals(h, parsed.header)
        assertContentEquals(payload, parsed.payload)
        assertNull(parsed.sack)
    }

    // ---- round-trip via decode() (header only) -------------------------------

    @Test
    fun headerDecodeIsInverseOfEncode() {
        for (type in UtpType.entries) {
            val h = UtpHeader(
                type = type,
                extension = UtpExtension.NONE,
                connectionId = 0xFFFF,           // exercise full u16
                timestampMicros = 0xFFFFFFFFL,   // exercise full u32 (sign-safe)
                timestampDiffMicros = 0x7FFFFFFFL,
                wndSize = 0x80000000L,           // high bit set
                seqNr = 0xABCD,
                ackNr = 0x1357,
            )
            val decoded = UtpHeader.decode(h.encode())
            assertEquals(h, decoded, "round-trip failed for $type")
        }
    }

    // ---- SACK extension framing ----------------------------------------------

    @Test
    fun sackExtensionFramedAndParsed() {
        val sack = byteArrayOf(0x0F, 0x00, 0x00, 0x00) // 4-byte bitmask
        val payload = byteArrayOf(1, 2, 3)
        val h = UtpHeader(
            type = UtpType.ST_DATA,
            connectionId = 0x0042,
            timestampMicros = 1,
            timestampDiffMicros = 0,
            wndSize = 0,
            seqNr = 5,
            ackNr = 4,
        )
        val packet = buildUtpPacket(h, payload, sack = sack)

        // header(20) | next_ext=0 | len=4 | sack(4) | payload(3)
        assertEquals(UTP_HEADER_SIZE + 2 + sack.size + payload.size, packet.size)
        // builder must have flipped the extension nibble to SACK
        assertEquals(UtpExtension.SACK, packet[1].toInt() and 0xFF)
        assertEquals(0, packet[UTP_HEADER_SIZE].toInt())          // next extension = end
        assertEquals(sack.size, packet[UTP_HEADER_SIZE + 1].toInt())

        val parsed = parseUtpPacket(packet)!!
        assertEquals(UtpExtension.SACK, parsed.header.extension)
        assertContentEquals(sack, parsed.sack)
        assertContentEquals(payload, parsed.payload)
        assertEquals(5, parsed.header.seqNr)
        assertEquals(4, parsed.header.ackNr)
    }

    // ---- rejects -------------------------------------------------------------

    @Test
    fun shortBufferIsRejected() {
        assertNull(UtpHeader.decode(ByteArray(UTP_HEADER_SIZE - 1)))
        assertNull(parseUtpPacket(ByteArray(3)))
    }

    @Test
    fun unknownTypeNibbleIsRejected() {
        val raw = UtpHeader(
            type = UtpType.ST_DATA, connectionId = 1, timestampMicros = 0,
            timestampDiffMicros = 0, wndSize = 0, seqNr = 0, ackNr = 0,
        ).encode()
        // NUM_TYPES == 5, so nibble 5..15 are invalid; force 0x51 (type=5).
        raw[0] = 0x51
        assertNull(UtpHeader.decode(raw))
        assertNull(parseUtpPacket(raw))
    }

    @Test
    fun truncatedExtensionIsRejected() {
        val h = UtpHeader(
            type = UtpType.ST_DATA, extension = UtpExtension.SACK, connectionId = 1,
            timestampMicros = 0, timestampDiffMicros = 0, wndSize = 0, seqNr = 0, ackNr = 0,
        )
        // header claims a SACK extension but no extension bytes follow → invalid
        assertNull(parseUtpPacket(h.encode()))
    }

    // ---- type code mapping ---------------------------------------------------

    @Test
    fun typeCodesMatchBep29() {
        assertEquals(0, UtpType.ST_DATA.code)
        assertEquals(1, UtpType.ST_FIN.code)
        assertEquals(2, UtpType.ST_STATE.code)
        assertEquals(3, UtpType.ST_RESET.code)
        assertEquals(4, UtpType.ST_SYN.code)
        assertNull(UtpType.fromCode(5))   // NUM_TYPES
        assertNull(UtpType.fromCode(15))
    }

    // ---- compare_less_wrap ---------------------------------------------------

    @Test
    fun seqLessWrapHandlesWraparound() {
        assertTrue(seqLessWrap(1, 2))
        assertTrue(!seqLessWrap(2, 1))
        // 0xFFFF is "less than" 0x0001 across the wrap boundary
        assertTrue(seqLessWrap(0xFFFF, 0x0001))
        assertTrue(!seqLessWrap(0x0001, 0xFFFF))
        assertTrue(!seqLessWrap(5, 5))    // equal is not less
    }
}
