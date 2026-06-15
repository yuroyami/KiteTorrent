package io.github.yuroyami.kitetorrent.util

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.crypto.Sha1
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [BloomFilter] — the port of libtorrent's `bloom_filter<N>`
 * (bloom_filter.hpp / bloom_filter.cpp).
 *
 * Golden index values are computed by hand from `set_bits` / `has_bits`:
 * two little-endian 16-bit indices drawn from the first four hash bytes, each
 * reduced mod `numBytes * 8`.
 */
class BloomFilterTest {

    /** A 20-byte hash whose first four bytes are fixed; the rest are zero. */
    private fun key(b0: Int, b1: Int, b2: Int, b3: Int): Sha1Hash {
        val raw = ByteArray(20)
        raw[0] = b0.toByte()
        raw[1] = b1.toByte()
        raw[2] = b2.toByte()
        raw[3] = b3.toByte()
        return Digest32.of(raw)
    }

    @Test
    fun emptyFilterIsAllZero() {
        val bf = BloomFilter(BloomFilter.DHT_SIZE)
        assertEquals(256, bf.numBytes)
        assertEquals(2048, bf.bitCount)
        assertEquals(2048, bf.countZeroBits())
        assertContentEquals(ByteArray(256), bf.toBytes())
        // nothing has been added, so nothing is found
        assertFalse(bf.find(key(0x01, 0x02, 0x03, 0x04)))
    }

    @Test
    fun setThenFindHandComputedBits() {
        // first 4 bytes 01 02 03 04, N = 256 (2048 bits):
        //   idx1 = 0x01 | (0x02<<8) = 513   -> byte 64 bit 1 (0x02)
        //   idx2 = 0x03 | (0x04<<8) = 1027  -> byte 128 bit 3 (0x08)
        val bf = BloomFilter(BloomFilter.DHT_SIZE)
        val k = key(0x01, 0x02, 0x03, 0x04)
        assertFalse(bf.find(k))
        bf.set(k)
        assertTrue(bf.find(k))

        val bytes = bf.toBytes()
        assertEquals(0x02, bytes[64].toInt() and 0xff)
        assertEquals(0x08, bytes[128].toInt() and 0xff)
        // exactly two bits set => 2046 zero bits
        assertEquals(2046, bf.countZeroBits())
    }

    @Test
    fun noFalseNegativesForManyKeys() {
        // Everything inserted must always be found back (defining property).
        val bf = BloomFilter(BloomFilter.DHT_SIZE)
        val keys = (0 until 200).map { Sha1Hash.of(Sha1.hash("peer-$it".encodeToByteArray())) }
        for (k in keys) bf.set(k)
        for (k in keys) assertTrue(bf.find(k), "inserted key must be found: $k")
    }

    @Test
    fun unrelatedKeyUsuallyAbsentOnLightlyLoadedFilter() {
        // With a single key in a 2048-bit filter, an unrelated key is very unlikely
        // to collide on BOTH of its two bits. Use keys with deliberately distinct
        // first-4-byte prefixes so the indices differ.
        val bf = BloomFilter(BloomFilter.DHT_SIZE)
        bf.set(key(0x10, 0x20, 0x30, 0x40))
        assertFalse(bf.find(key(0x11, 0x21, 0x31, 0x41)))
        assertFalse(bf.find(key(0xAA, 0xBB, 0xCC, 0xDD)))
    }

    @Test
    fun collidingPrefixIsAFalsePositive() {
        // Only the first four bytes drive the hash, so two keys sharing them are
        // indistinguishable to the filter — a deterministic false positive.
        val bf = BloomFilter(BloomFilter.DHT_SIZE)
        bf.set(key(0x07, 0x08, 0x09, 0x0a))
        // different tail, identical prefix
        val raw = ByteArray(20) { 0xFF.toByte() }
        raw[0] = 0x07.toByte()
        raw[1] = 0x08.toByte()
        raw[2] = 0x09.toByte()
        raw[3] = 0x0a.toByte()
        assertTrue(bf.find(Digest32.of(raw)))
    }

    @Test
    fun toBytesFromBytesRoundTrip() {
        val bf = BloomFilter(BloomFilter.DHT_SIZE)
        bf.set(key(0x01, 0x02, 0x03, 0x04))
        bf.set(key(0xFE, 0xFF, 0x00, 0x01))
        val serialized = bf.toBytes()

        val restored = BloomFilter.fromBytes(serialized)
        assertEquals(256, restored.numBytes)
        assertContentEquals(serialized, restored.toBytes())
        assertTrue(restored.find(key(0x01, 0x02, 0x03, 0x04)))
        assertTrue(restored.find(key(0xFE, 0xFF, 0x00, 0x01)))

        // mutating the returned array must not affect the filter (defensive copy)
        serialized.fill(0)
        assertTrue(bf.find(key(0x01, 0x02, 0x03, 0x04)))
    }

    @Test
    fun clearEmptiesTheFilter() {
        val bf = BloomFilter(64)
        bf.set(key(0x01, 0x02, 0x03, 0x04))
        assertTrue(bf.find(key(0x01, 0x02, 0x03, 0x04)))
        bf.clear()
        assertFalse(bf.find(key(0x01, 0x02, 0x03, 0x04)))
        assertEquals(64 * 8, bf.countZeroBits())
    }

    @Test
    fun moduloKeepsIndicesInRangeForSmallFilter() {
        // A tiny 8-byte (64-bit) filter must still place big 16-bit indices safely.
        val bf = BloomFilter(8)
        val k = key(0xFF, 0xFF, 0xFF, 0xFF) // idx1 = idx2 = 65535 % 64 = 63
        bf.set(k)
        assertTrue(bf.find(k))
        // 65535 % 64 == 63 -> the single highest bit of the last byte
        assertEquals(0x80, bf.toBytes()[7].toInt() and 0xff)
        assertEquals(63, bf.countZeroBits())
    }

    @Test
    fun sizeEstimateGrowsWithLoad() {
        val empty = BloomFilter(BloomFilter.DHT_SIZE)
        // An empty filter: c == m is clamped to m-1, giving a tiny but finite estimate.
        val emptySize = empty.size()
        assertTrue(emptySize >= 0.0 && emptySize < 1.0, "empty estimate was $emptySize")

        val bf = BloomFilter(BloomFilter.DHT_SIZE)
        for (i in 0 until 100) {
            bf.set(Sha1Hash.of(Sha1.hash("node-$i".encodeToByteArray())))
        }
        val est = bf.size()
        // 100 distinct keys, two bits each, in 2048 bits: the MLE should land in a
        // sane neighbourhood of the true count (generously bounded for collisions).
        assertTrue(est in 50.0..200.0, "size estimate for ~100 keys was $est")
    }
}
