package io.github.yuroyami.kitetorrent.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sanity checks for the pure-Kotlin [BigInt]: arithmetic against hand-computed
 * values, modular exponentiation against textbook examples, and the fixed-width
 * big-endian (de)serialization used by MSE key export.
 */
class BigIntTest {

    private fun hx(s: String) = BigInt.fromHex(s)

    @Test
    fun addSmall() {
        assertEquals(BigInt.fromInt(7), BigInt.fromInt(3) + BigInt.fromInt(4))
        // (2^128 - 1) + 2^64
        val a = hx("ffffffffffffffffffffffffffffffff")
        val b = hx("10000000000000000")
        assertEquals(hx("10000000000000000ffffffffffffffff"), a + b)
    }

    @Test
    fun subtractWithBorrow() {
        val a = hx("10000000000000000")        // 2^64
        val b = BigInt.ONE
        assertEquals(hx("ffffffffffffffff"), a - b) // 2^64 - 1
    }

    @Test
    fun multiplyLarge() {
        val a = hx("deadbeefcafebabe1234567890")
        val b = hx("feedface0badf00d")
        assertEquals(hx("ddbf647495d63fba1af74b9fea48db717b62bb1f50"), a * b)
    }

    @Test
    fun modulo() {
        // (2^300 + 12345) mod 10^30
        val a = hx("1000000000000000000000000000000000000000000000000000000000000000000000003039")
        val m = hx("c9f2c9cd04674edea40000000") // 10^30
        assertEquals(hx("5abf13b7d0ddd396d00003039"), a.mod(m))
    }

    @Test
    fun modPowTextbookExamples() {
        // 4^13 mod 497 = 445 (classic RSA example)
        assertEquals(BigInt.fromInt(445), BigInt.fromInt(4).modPow(BigInt.fromInt(13), BigInt.fromInt(497)))
        // 7^560 mod 561 = 1 (561 Carmichael number; Fermat liar)
        assertEquals(BigInt.ONE, BigInt.fromInt(7).modPow(BigInt.fromInt(560), BigInt.fromInt(561)))
        // 2^10 mod 1000 = 24
        assertEquals(BigInt.fromInt(24), BigInt.TWO.modPow(BigInt.fromInt(10), BigInt.fromInt(1000)))
        // exponent 0 -> 1
        assertEquals(BigInt.ONE, BigInt.fromInt(99).modPow(BigInt.ZERO, BigInt.fromInt(7)))
    }

    @Test
    fun modPow768BitPrime() {
        // 2^768 mod P, with P the MSE Diffie-Hellman prime. Golden value from Python.
        val prime = hx(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
                "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
                "4FE1356D6D51C245E485B576625E7EC6F44C42E9A63A36210000000000090563"
        )
        val expected = hx(
            "36f0255dde973dcb3b399d747f23e32ed6fdb1f77598338bfdf44159c4ec64dd" +
                "aeb5f78671cbfb22106ae64c32c5bce4cfd4f5920da0ebc8b01eca9292ae3dba" +
                "1b7a4a899da181390bb3bd1659c5c9defffffffffff6fa9d"
        )
        // exponent 768, so 2 raised to the 768th power, reduced mod P.
        assertEquals(expected, BigInt.TWO.modPow(BigInt.fromInt(768), prime))

        // P > 2^255, so 2^255 mod P is just 2^255 (verifies no spurious reduction).
        val twoPow255 = hx("8000000000000000000000000000000000000000000000000000000000000000")
        assertEquals(twoPow255, BigInt.TWO.modPow(BigInt.fromInt(255), prime))
    }

    @Test
    fun fixedBytesRoundTrip() {
        val v = hx("0102030405")
        val bytes = v.toFixedBytes(8)
        assertEquals(8, bytes.size)
        // left zero-padded big-endian
        assertEquals(0, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(0, bytes[2].toInt())
        assertEquals(1, bytes[3].toInt())
        assertEquals(5, bytes[7].toInt())
        // round trip
        assertEquals(v, BigInt.fromBytes(bytes))
    }

    @Test
    fun comparisonAndBits() {
        val a = hx("ffffffff")
        val b = hx("100000000")
        assertTrue(a < b)
        assertTrue(b > a)
        assertEquals(0, a.compareTo(hx("ffffffff")))
        assertEquals(32, a.bitLength())   // 0xffffffff = 32 bits
        assertEquals(33, b.bitLength())   // 0x100000000 = 2^32 = 33 bits
        assertTrue(BigInt.fromInt(5).testBit(0))
        assertFalse(BigInt.fromInt(5).testBit(1))
        assertTrue(BigInt.fromInt(5).testBit(2))
        assertTrue(BigInt.ZERO.isZero)
    }
}
