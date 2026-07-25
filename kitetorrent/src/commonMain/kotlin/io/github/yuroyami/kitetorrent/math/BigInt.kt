package io.github.yuroyami.kitetorrent.math

/**
 * Minimal pure-Kotlin unsigned big integer, base 2^32 (limbs are [UInt] stored in
 * an [IntArray] little-endian: limb 0 is least significant). Non-negative values
 * only. That is all KiteTorrent's crypto stack needs (MSE Diffie-Hellman modular
 * exponentiation over a fixed 768-bit prime, and the ed25519 field/scalar arithmetic).
 *
 * There is no `BigInteger` in the Kotlin common standard library, so libtorrent's
 * reliance on `boost::multiprecision` (see `src/pe_crypto.cpp`) has to be reimplemented
 * from scratch. This class provides exactly the primitives required: comparison,
 * add / subtract / multiply, schoolbook long-division remainder (`mod`), modular
 * multiplication and modular exponentiation (`modPow`, the analogue of
 * `boost::multiprecision::powm`), plus fixed-width big-endian byte (de)serialization.
 *
 * It favours clarity and correctness over speed. The numbers involved are small
 * (<= ~768 bits for DH, <= ~512 bits intermediate for the field), so the O(n^2)
 * multiply and bit-at-a-time division are perfectly adequate.
 *
 * Works on every KMP target: only [IntArray], [UInt]/[ULong] limb math and
 * stdlib helpers are used.
 */
class BigInt private constructor(
    /** Little-endian magnitude limbs, base 2^32, no leading-zero limbs (except ZERO == empty). */
    internal val mag: IntArray,
) : Comparable<BigInt> {

    val isZero: Boolean get() = mag.isEmpty()

    /** Number of significant bits (0 for zero). */
    fun bitLength(): Int {
        if (mag.isEmpty()) return 0
        val top = mag[mag.size - 1].toUInt()
        return (mag.size - 1) * 32 + (32 - top.countLeadingZeroBits())
    }

    /** Test bit [i] (0 = LSB). */
    fun testBit(i: Int): Boolean {
        if (i < 0) return false
        val limb = i ushr 5
        if (limb >= mag.size) return false
        return (mag[limb] ushr (i and 31)) and 1 == 1
    }

    // ---- comparison ----

    override fun compareTo(other: BigInt): Int = compareMag(mag, other.mag)

    override fun equals(other: Any?): Boolean =
        other is BigInt && mag.contentEquals(other.mag)

    override fun hashCode(): Int = mag.contentHashCode()

    // ---- arithmetic ----

    operator fun plus(other: BigInt): BigInt = BigInt(addMag(mag, other.mag))

    /** Subtraction; requires `this >= other` (non-negative result). */
    operator fun minus(other: BigInt): BigInt {
        require(compareMag(mag, other.mag) >= 0) { "BigInt subtraction would be negative" }
        return BigInt(subMag(mag, other.mag))
    }

    operator fun times(other: BigInt): BigInt {
        if (mag.isEmpty() || other.mag.isEmpty()) return ZERO
        return BigInt(mulMag(mag, other.mag))
    }

    /** Remainder `this mod m` (m must be > 0). Result in [0, m). */
    fun mod(m: BigInt): BigInt {
        require(!m.mag.isEmpty()) { "modulus must be non-zero" }
        if (compareMag(mag, m.mag) < 0) return this
        return BigInt(remMag(mag, m.mag))
    }

    /** (this * other) mod m. */
    fun modMul(other: BigInt, m: BigInt): BigInt = (this * other).mod(m)

    /**
     * Modular exponentiation: (this ^ exp) mod m, via right-to-left
     * square-and-multiply. Equivalent to boost's `powm(base, exp, mod)`.
     */
    fun modPow(exp: BigInt, m: BigInt): BigInt {
        require(!m.mag.isEmpty()) { "modulus must be non-zero" }
        if (m == ONE) return ZERO
        var result = ONE
        var base = this.mod(m)
        val ebits = exp.bitLength()
        var i = 0
        while (i < ebits) {
            if (exp.testBit(i)) result = result.modMul(base, m)
            i++
            if (i < ebits) base = base.modMul(base, m)
        }
        return result
    }

    // ---- serialization ----

    /**
     * Big-endian byte representation, exactly [width] bytes, zero-padded on the
     * left (most-significant side). Mirrors libtorrent's `export_key`, which
     * right-justifies the magnitude into a fixed 96-byte field.
     * Requires the value to fit in [width] bytes.
     */
    fun toFixedBytes(width: Int): ByteArray {
        val out = ByteArray(width)
        var i = 0
        // little-endian byte index 0 == LSB
        while (i < mag.size * 4) {
            val b = (mag[i ushr 2] ushr ((i and 3) * 8)) and 0xff
            if (b != 0) require(i < width) { "value does not fit in $width bytes" }
            if (i < width) out[width - 1 - i] = b.toByte()
            i++
        }
        return out
    }

    companion object {
        val ZERO: BigInt = BigInt(IntArray(0))
        val ONE: BigInt = BigInt(intArrayOf(1))
        val TWO: BigInt = BigInt(intArrayOf(2))

        fun fromInt(v: Int): BigInt {
            require(v >= 0) { "negative" }
            return if (v == 0) ZERO else BigInt(intArrayOf(v))
        }

        fun fromLong(v: Long): BigInt {
            require(v >= 0) { "negative" }
            if (v == 0L) return ZERO
            val lo = v.toInt()
            val hi = (v ushr 32).toInt()
            return if (hi == 0) BigInt(intArrayOf(lo)) else BigInt(intArrayOf(lo, hi))
        }

        /** Parse a big-endian unsigned byte array (any length, leading zeros allowed). */
        fun fromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): BigInt {
            val limbCount = (length + 3) / 4
            if (limbCount == 0) return ZERO
            val m = IntArray(limbCount)
            // bytes are big-endian; build little-endian limbs
            var bitPos = 0
            var i = offset + length - 1
            while (i >= offset) {
                val byteVal = bytes[i].toInt() and 0xff
                val limb = bitPos ushr 5
                val shift = bitPos and 31
                m[limb] = m[limb] or (byteVal shl shift)
                bitPos += 8
                i--
            }
            return BigInt(strip(m))
        }

        /** Parse a hex string (no 0x prefix). */
        fun fromHex(hex: String): BigInt {
            val clean = if (hex.startsWith("0x") || hex.startsWith("0X")) hex.substring(2) else hex
            val padded = if (clean.length and 1 == 1) "0$clean" else clean
            val bytes = ByteArray(padded.length / 2)
            var i = 0
            while (i < bytes.size) {
                val hi = hexVal(padded[i * 2])
                val lo = hexVal(padded[i * 2 + 1])
                bytes[i] = ((hi shl 4) or lo).toByte()
                i++
            }
            return fromBytes(bytes)
        }

        private fun hexVal(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw IllegalArgumentException("bad hex char $c")
        }

        // ---------- raw limb helpers (little-endian IntArray, treated as UInt) ----------

        private fun strip(a: IntArray): IntArray {
            var n = a.size
            while (n > 0 && a[n - 1] == 0) n--
            return if (n == a.size) a else a.copyOf(n)
        }

        private fun compareMag(a: IntArray, b: IntArray): Int {
            if (a.size != b.size) return if (a.size < b.size) -1 else 1
            var i = a.size - 1
            while (i >= 0) {
                val x = a[i].toUInt()
                val y = b[i].toUInt()
                if (x != y) return if (x < y) -1 else 1
                i--
            }
            return 0
        }

        private fun addMag(a: IntArray, b: IntArray): IntArray {
            val (big, small) = if (a.size >= b.size) a to b else b to a
            val out = IntArray(big.size + 1)
            var carry = 0UL
            var i = 0
            while (i < small.size) {
                val sum = big[i].toUInt().toULong() + small[i].toUInt().toULong() + carry
                out[i] = sum.toInt()
                carry = sum shr 32
                i++
            }
            while (i < big.size) {
                val sum = big[i].toUInt().toULong() + carry
                out[i] = sum.toInt()
                carry = sum shr 32
                i++
            }
            out[big.size] = carry.toInt()
            return strip(out)
        }

        /** a - b, requires a >= b. */
        private fun subMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size)
            var borrow = 0L
            var i = 0
            while (i < b.size) {
                val diff = a[i].toUInt().toLong() - b[i].toUInt().toLong() - borrow
                out[i] = diff.toInt()
                borrow = if (diff < 0) 1L else 0L
                i++
            }
            while (i < a.size) {
                val diff = a[i].toUInt().toLong() - borrow
                out[i] = diff.toInt()
                borrow = if (diff < 0) 1L else 0L
                i++
            }
            return strip(out)
        }

        private fun mulMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size + b.size)
            var i = 0
            while (i < a.size) {
                val ai = a[i].toUInt().toULong()
                var carry = 0UL
                var j = 0
                while (j < b.size) {
                    val idx = i + j
                    val cur = out[idx].toUInt().toULong()
                    val prod = ai * b[j].toUInt().toULong() + cur + carry
                    out[idx] = prod.toInt()
                    carry = prod shr 32
                    j++
                }
                out[i + b.size] = (out[i + b.size].toUInt().toULong() + carry).toInt()
                i++
            }
            return strip(out)
        }

        /**
         * Schoolbook bit-at-a-time remainder: returns a mod m, with `a >= m`.
         * Simple and obviously correct; the operands here are at most a few
         * thousand bits, so the linear-in-bits cost is fine.
         */
        private fun remMag(a: IntArray, m: IntArray): IntArray {
            var rem = IntArray(0)
            val totalBits = a.size * 32
            var i = totalBits - 1
            while (i >= 0) {
                // rem <<= 1
                rem = shl1(rem)
                // bring down bit i of a
                val bit = (a[i ushr 5] ushr (i and 31)) and 1
                if (bit == 1) {
                    if (rem.isEmpty()) rem = intArrayOf(1)
                    else rem[0] = rem[0] or 1
                }
                // if rem >= m: rem -= m
                if (compareMag(rem, m) >= 0) rem = subMag(rem, m)
                i--
            }
            return rem
        }

        /** Shift a left by 1 bit. */
        private fun shl1(a: IntArray): IntArray {
            if (a.isEmpty()) return a
            val out = IntArray(a.size + 1)
            var carry = 0
            var i = 0
            while (i < a.size) {
                val v = a[i].toUInt()
                out[i] = ((v shl 1) or carry.toUInt()).toInt()
                carry = (v shr 31).toInt()
                i++
            }
            out[a.size] = carry
            return strip(out)
        }
    }
}
