package io.github.yuroyami.kitetorrent.crypto

import io.github.yuroyami.kitetorrent.Hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RC4 ([Rc4]) checked against the canonical public test vectors (Wikipedia /
 * RFC 6229). RC4 is symmetric, so the same routine encrypts and decrypts.
 */
class Rc4Test {

    private fun ascii(s: String) = ByteArray(s.length) { s[it].code.toByte() }

    @Test
    fun knownVectorKeyPlaintext() {
        // key "Key", plaintext "Plaintext" -> BBF316E8D940AF0AD3
        val ct = Rc4(ascii("Key")).process(ascii("Plaintext"))
        assertEquals("bbf316e8d940af0ad3", Hex.encode(ct))
    }

    @Test
    fun knownVectorWikiPedia() {
        // key "Wiki", plaintext "pedia" -> 1021BF0420
        val ct = Rc4(ascii("Wiki")).process(ascii("pedia"))
        assertEquals("1021bf0420", Hex.encode(ct))
    }

    @Test
    fun knownVectorSecretAttack() {
        // key "Secret", plaintext "Attack at dawn" -> 45A01F645FC35B383552544B9BF5
        val ct = Rc4(ascii("Secret")).process(ascii("Attack at dawn"))
        assertEquals("45a01f645fc35b383552544b9bf5", Hex.encode(ct))
    }

    @Test
    fun rfc6229Keystream() {
        // RFC 6229, key = 0x0102030405 ; first 16 keystream bytes XOR'd into zeros.
        val key = byteArrayOf(1, 2, 3, 4, 5)
        val ks = Rc4(key).process(ByteArray(16))
        assertEquals("b2396305f03dc027ccc3524a0a1118a8", Hex.encode(ks))
    }

    @Test
    fun roundTrip() {
        val key = Random(7).nextBytes(20)
        val plain = Random(11).nextBytes(500)
        val cipher = Rc4(key).process(plain)
        val recovered = Rc4(key).process(cipher)
        assertTrue(plain.contentEquals(recovered))
        // ciphertext should not equal plaintext (keystream is non-trivial)
        assertTrue(!plain.contentEquals(cipher))
    }

    @Test
    fun streamingMatchesOneShot() {
        // Processing in chunks must match processing all at once (stateful keystream).
        val key = ascii("Key")
        val data = Random(3).nextBytes(257)
        val oneShot = Rc4(key).process(data)

        val cipher = Rc4(key)
        val out = ByteArray(data.size)
        var off = 0
        for (chunk in intArrayOf(1, 16, 100, 140)) {
            val part = cipher.process(data.copyOfRange(off, off + chunk))
            part.copyInto(out, off)
            off += chunk
        }
        assertTrue(oneShot.contentEquals(out))
    }
}
