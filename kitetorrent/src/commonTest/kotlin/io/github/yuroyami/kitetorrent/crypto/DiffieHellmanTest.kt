package io.github.yuroyami.kitetorrent.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MSE/PE Diffie-Hellman ([DhKeyExchange]) — the core property is that both peers
 * derive the same shared secret, mirroring libtorrent's `dh_key_exchange`.
 */
class DhKeyExchangeTest {

    @Test
    fun publicKeyIs96Bytes() {
        val dh = DhKeyExchange.random(Random(1))
        assertEquals(96, dh.publicKey.size)
    }

    @Test
    fun sharedSecretAgreement() {
        val alice = DhKeyExchange.random(Random(42))
        val bob = DhKeyExchange.random(Random(1337))

        val secretA = alice.computeSecret(bob.publicKey)
        val secretB = bob.computeSecret(alice.publicKey)

        assertEquals(96, secretA.size)
        assertTrue(secretA.contentEquals(secretB))
    }

    @Test
    fun deterministicFromSecret() {
        // Same secret bytes -> same public key (g^x mod P is a pure function of x).
        val secret = ByteArray(96) { (it + 1).toByte() }
        val a = DhKeyExchange.fromSecret(secret)
        val b = DhKeyExchange.fromSecret(secret)
        assertTrue(a.publicKey.contentEquals(b.publicKey))

        // And the exchange still agrees with a different peer.
        val other = DhKeyExchange.random(Random(9))
        assertTrue(
            a.computeSecret(other.publicKey).contentEquals(other.computeSecret(a.publicKey))
        )
    }

    @Test
    fun rejectsDegenerateRemoteKey() {
        val dh = DhKeyExchange.random(Random(5))
        // remote public key of 1 is in the forbidden range [.. , 1] -> rejected
        val one = ByteArray(96).also { it[95] = 1 }
        assertFailsWith<IllegalArgumentException> { dh.computeSecret(one) }
        // zero is also rejected
        val zero = ByteArray(96)
        assertFailsWith<IllegalArgumentException> { dh.computeSecret(zero) }
    }

    @Test
    fun rejectsWrongSizeKey() {
        val dh = DhKeyExchange.random(Random(5))
        assertFailsWith<IllegalArgumentException> { dh.computeSecret(ByteArray(95)) }
    }

    @Test
    fun generatorPublicKeyMatchesManualModPow() {
        // With secret = 1, the public key is exactly g = 2, i.e. 96 bytes that are
        // all zero except the final byte = 2.
        val dh = DhKeyExchange.fromSecret(byteArrayOf(1))
        val expected = ByteArray(96).also { it[95] = 2 }
        assertTrue(dh.publicKey.contentEquals(expected))
    }
}
