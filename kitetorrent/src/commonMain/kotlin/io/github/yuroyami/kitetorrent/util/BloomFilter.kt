package io.github.yuroyami.kitetorrent.util

import io.github.yuroyami.kitetorrent.Sha1Hash
import kotlin.math.ln

/**
 * A fixed-size Bloom filter keyed by 20-byte hashes — pure-Kotlin port of
 * libtorrent's `bloom_filter<N>` (bloom_filter.hpp) plus the free functions
 * `set_bits` / `has_bits` / `count_zero_bits` (bloom_filter.cpp).
 *
 * libtorrent uses this as a counting-free, two-hash Bloom filter in two places:
 *  - the DHT `get_peers` response, where seed/peer presence is summarised as two
 *    256-byte filters (`N == 256`), and
 *  - tracker scrape aggregation.
 *
 * The filter never produces a false negative: anything passed to [set] is always
 * reported by [find]. It may produce false positives, whose probability rises as
 * the filter fills; [size] estimates how many distinct keys have been inserted.
 *
 * ### Hashing
 * libtorrent does **not** rehash the key. It takes the first four bytes of the
 * (already-random) hash and forms two little-endian 16-bit indices:
 *
 * ```
 * idx1 = k[0] | (k[1] << 8)
 * idx2 = k[2] | (k[3] << 8)
 * ```
 *
 * each reduced modulo the bit count `N * 8`, then sets/tests those two bits. The
 * 16-bit width caps the addressable space at 65536 bits (`N <= 8192`); for the
 * 256-byte DHT filter (2048 bits) the modulo keeps every index in range.
 *
 * The backing bytes are bit-for-bit compatible with libtorrent's `to_string` /
 * `from_string`, so a filter can be serialised into a DHT message and parsed back
 * with [toBytes] / [fromBytes].
 *
 * @param numBytes the filter width in bytes (`N` in `bloom_filter<N>`). The DHT
 *   uses 256; the constructor accepts any positive size.
 */
class BloomFilter(val numBytes: Int) {

    init {
        require(numBytes > 0) { "bloom filter size must be positive, was $numBytes" }
    }

    private val bits = ByteArray(numBytes)

    /** Number of addressable bits, i.e. `numBytes * 8`. */
    val bitCount: Int get() = numBytes * 8

    /**
     * Records [key] in the filter. Faithful to `set_bits`: the two 16-bit indices
     * drawn from the first four bytes of [key] are reduced mod [bitCount] and set.
     * After this call [find] of the same key is guaranteed to return true.
     */
    fun set(key: Sha1Hash) {
        val (idx1, idx2) = indices(key)
        bits[idx1 ushr 3] = (bits[idx1 ushr 3].toInt() or (1 shl (idx1 and 7))).toByte()
        bits[idx2 ushr 3] = (bits[idx2 ushr 3].toInt() or (1 shl (idx2 and 7))).toByte()
    }

    /**
     * Tests whether [key] is (probably) present. Faithful to `has_bits`: returns
     * true only if **both** index bits are set. No false negatives; false positives
     * grow with load.
     */
    fun find(key: Sha1Hash): Boolean {
        val (idx1, idx2) = indices(key)
        return (bits[idx1 ushr 3].toInt() and (1 shl (idx1 and 7))) != 0 &&
            (bits[idx2 ushr 3].toInt() and (1 shl (idx2 and 7))) != 0
    }

    /** Clears every bit, returning the filter to empty. */
    fun clear() {
        bits.fill(0)
    }

    /**
     * Estimates the number of distinct keys inserted, matching `bloom_filter::size`.
     *
     * With `m = bitCount` total bits and `c` of them still zero, the maximum-
     * likelihood estimate of the inserted-element count for a two-hash Bloom filter
     * is `ln(c / m) / (2 * ln(1 - 1/m))`. The zero count is clamped to `m - 1` so
     * the logarithm never hits zero on a saturated filter.
     */
    fun size(): Double {
        val m = bitCount
        val c = minOf(countZeroBits(), m - 1)
        return ln(c.toDouble() / m.toDouble()) / (2.0 * ln(1.0 - 1.0 / m.toDouble()))
    }

    /** Number of zero bits — the population count of cleared bits across [bits]. */
    fun countZeroBits(): Int {
        var ret = 0
        for (i in 0 until numBytes) {
            val v = bits[i].toInt() and 0xff
            ret += ZERO_BITS_IN_NIBBLE[v and 0xf]
            ret += ZERO_BITS_IN_NIBBLE[(v ushr 4) and 0xf]
        }
        return ret
    }

    /**
     * A defensive copy of the raw backing bytes, matching `bloom_filter::to_string`.
     * Suitable for placing directly into a DHT message.
     */
    fun toBytes(): ByteArray = bits.copyOf()

    /**
     * Overwrites this filter's bits from [data], matching `bloom_filter::from_string`.
     * [data] must be exactly [numBytes] long.
     */
    fun fromBytes(data: ByteArray) {
        require(data.size == numBytes) {
            "bloom filter expects $numBytes bytes, got ${data.size}"
        }
        data.copyInto(bits, 0, 0, numBytes)
    }

    /** Computes the two little-endian 16-bit bit indices, reduced mod [bitCount]. */
    private fun indices(key: Sha1Hash): Pair<Int, Int> {
        // key[i] is already unsigned (0..255) via Digest32.get.
        val idx1 = (key[0] or (key[1] shl 8)) % bitCount
        val idx2 = (key[2] or (key[3] shl 8)) % bitCount
        return idx1 to idx2
    }

    companion object {
        /**
         * The DHT advertises seed/peer presence with 256-byte (2048-bit) filters;
         * see `dht::get_peers` and BEP 33.
         */
        const val DHT_SIZE: Int = 256

        /**
         * Number of *zero* bits in each 4-bit nibble value 0..15 — the lookup table
         * from `count_zero_bits`. Index `n` holds `4 - popcount(n)`.
         */
        private val ZERO_BITS_IN_NIBBLE = intArrayOf(
            // 0000 0001 0010 0011 0100 0101 0110 0111
            4, 3, 3, 2, 3, 2, 2, 1,
            // 1000 1001 1010 1011 1100 1101 1110 1111
            3, 2, 2, 1, 2, 1, 1, 0,
        )

        /** Builds a filter from serialised [data], inferring its size from the length. */
        fun fromBytes(data: ByteArray): BloomFilter {
            val bf = BloomFilter(data.size)
            bf.fromBytes(data)
            return bf
        }
    }
}
