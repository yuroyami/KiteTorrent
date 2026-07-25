package io.github.yuroyami.kitetorrent.peer

/**
 * Pure-Kotlin CRC-32C (Castagnoli): the port of the software fallback in
 * libtorrent's `src/crc32c.cpp` (the `boost::crc_optimal<32, 0x1EDC6F41,
 * 0xFFFFFFFF, 0xFFFFFFFF, true, true>` instantiation).
 *
 * libtorrent uses hardware CRC32C instructions (SSE4.2 `crc32`, ARMv8
 * `__crc32c*`) when available, but falls back to this exact Boost configuration
 * otherwise. All three code paths compute the identical checksum, so this single
 * software implementation is faithful to the observable result on every
 * platform.
 *
 * The parameters spelled out:
 *  - **polynomial** `0x1EDC6F41` (the Castagnoli / iSCSI polynomial),
 *  - **init** `0xFFFFFFFF`,
 *  - **xor-out** `0xFFFFFFFF`,
 *  - **reflect-in / reflect-out** `true` (bytes are processed LSB-first, which is
 *    why we drive the computation with the *reflected* polynomial `0x82F63B78`
 *    and shift right).
 *
 * KiteTorrent needs this only as a building block of BEP 40 peer priority
 * (see [peerPriority]); it is not a general-purpose checksum facility, but it is
 * a correct standalone CRC-32C all the same.
 */
internal object Crc32c {

    /**
     * The bit-reversed Castagnoli polynomial. `0x1EDC6F41` reflected across 32
     * bits is `0x82F63B78`; using it with a right-shifting update loop realises
     * the "reflect input/output" behaviour Boost is configured for.
     *
     * Held as a plain `val` (not `const`) because the bit pattern `0x82F63B78`
     * exceeds `Int.MAX_VALUE` and must be narrowed with [Long.toInt], which is
     * not permitted inside a `const val` initializer.
     */
    private val REVERSED_POLY: Int = 0x82F63B78.toInt()

    /**
     * Precomputed 256-entry lookup table for byte-at-a-time CRC-32C, built from
     * [REVERSED_POLY]. Entry `n` is the CRC contribution of a single input byte
     * `n` (reflected form).
     */
    private val TABLE: IntArray = IntArray(256).also { table ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) {
                c = if (c and 1 != 0) (c ushr 1) xor REVERSED_POLY else c ushr 1
            }
            table[n] = c
        }
    }

    /**
     * Compute CRC-32C over [len] bytes of [data] starting at [offset], seeded so
     * that the result matches a fresh `boost::crc_optimal` (init/xor-out
     * `0xFFFFFFFF`). The returned value is unsigned; callers that want it as a
     * 32-bit unsigned magnitude can read it through [Int.toUInt] / mask with
     * `0xFFFFFFFFL`.
     */
    fun compute(data: ByteArray, offset: Int = 0, len: Int = data.size - offset): Int {
        var crc = -1 // 0xFFFFFFFF
        val end = offset + len
        var i = offset
        while (i < end) {
            val idx = (crc xor data[i].toInt()) and 0xFF
            crc = (crc ushr 8) xor TABLE[idx]
            i++
        }
        return crc xor -1 // xor 0xFFFFFFFF
    }
}
