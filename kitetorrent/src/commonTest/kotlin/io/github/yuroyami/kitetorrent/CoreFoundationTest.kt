package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.crypto.Hasher256
import io.github.yuroyami.kitetorrent.crypto.Sha1
import io.github.yuroyami.kitetorrent.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Foundation tests. The hash engines are checked against the canonical FIPS
 * 180-1 and 180-4 test vectors. If these pass, info-hash and piece-hash computation
 * is byte-for-byte compatible with libtorrent and every other BitTorrent client.
 */
class CoreFoundationTest {

    private fun ascii(s: String) = ByteArray(s.length) { s[it].code.toByte() }

    @Test
    fun sha1_fips_vectors() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Hex.encode(Sha1.hash(ByteArray(0))))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Hex.encode(Sha1.hash(ascii("abc"))))
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            Hex.encode(Sha1.hash(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))),
        )
    }

    @Test
    fun sha1_million_a() {
        val h = Sha1()
        val chunk = ByteArray(1000) { 'a'.code.toByte() }
        repeat(1000) { h.update(chunk) }
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", Hex.encode(h.digest()))
    }

    @Test
    fun sha256_fips_vectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hex.encode(Sha256.hash(ByteArray(0))),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hex.encode(Sha256.hash(ascii("abc"))),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Hex.encode(Sha256.hash(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))),
        )
    }

    @Test
    fun incremental_update_matches_oneshot() {
        val data = ByteArray(500) { (it * 7 + 3).toByte() }
        val oneShot = Sha1.hash(data)
        val inc = Sha1()
        var off = 0
        for (step in intArrayOf(1, 63, 64, 65, 100, 207)) {
            inc.update(data, off, step); off += step
        }
        inc.update(data, off, data.size - off)
        assertTrue(oneShot.contentEquals(inc.digest()))
    }

    @Test
    fun hasher_yields_typed_digest() {
        val h: Sha1Hash = Hasher.hash(ascii("abc"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", h.toHex())
        assertEquals(20, h.size)
        val h2: Sha256Hash = Hasher256.hash(ascii("abc"))
        assertEquals(32, h2.size)
    }

    @Test
    fun digest_hex_roundtrip_and_compare() {
        val a = Digest32.fromHex("0000000000000000000000000000000000000001")
        val b = Digest32.fromHex("0000000000000000000000000000000000000002")
        assertTrue(a < b)
        assertTrue(b > a)
        assertEquals(a, Digest32.fromHex("0000000000000000000000000000000000000001"))
        assertEquals("0000000000000000000000000000000000000002", b.toHex())
        assertTrue(Digest32.zeros(20).isAllZeros())
        assertFalse(a.isAllZeros())
    }

    @Test
    fun digest_xor_and_clz() {
        val a = Digest32.fromHex("ff00000000000000000000000000000000000000")
        val b = Digest32.fromHex("0f00000000000000000000000000000000000000")
        assertEquals("f000000000000000000000000000000000000000", (a xor b).toHex())
        assertEquals(0, a.countLeadingZeroes())
        assertEquals(4, b.countLeadingZeroes())
        assertEquals(160, Digest32.zeros(20).countLeadingZeroes())
        assertEquals(159, Digest32.fromHex("0000000000000000000000000000000000000001").countLeadingZeroes())
    }

    @Test
    fun bitfield_basics() {
        val bf = Bitfield(10)
        assertEquals(10, bf.size())
        assertTrue(bf.noneSet())
        bf.setBit(0); bf.setBit(9)
        assertTrue(bf[0]); assertTrue(bf[9]); assertFalse(bf[5])
        assertEquals(2, bf.count())
        assertEquals(0, bf.findFirstSet())
        bf.clearBit(0)
        assertEquals(9, bf.findFirstSet())
        bf.setAll()
        assertTrue(bf.allSet())
        assertEquals(10, bf.count())
        assertEquals(-1, bf.findLastClear())
    }

    @Test
    fun bitfield_wire_roundtrip() {
        // 12 bits -> 2 bytes. Set bits 0,1,2,3 and 8 -> bytes 0xF0, 0x80.
        val bf = Bitfield(12)
        for (i in 0..3) bf.setBit(i)
        bf.setBit(8)
        val wire = bf.data()
        assertEquals(2, wire.size)
        assertEquals(0xF0, wire[0].toInt() and 0xff)
        assertEquals(0x80, wire[1].toInt() and 0xff)
        // round-trip back in
        val bf2 = Bitfield.fromBytes(wire, 12)
        assertEquals(bf, bf2)
    }

    @Test
    fun bitfield_resize_preserves_and_fills() {
        val bf = Bitfield(4, value = true)
        assertEquals(4, bf.count())
        bf.resize(8, value = true)
        assertEquals(8, bf.count())
        bf.resize(12, value = false)
        assertEquals(8, bf.count())
    }
}
