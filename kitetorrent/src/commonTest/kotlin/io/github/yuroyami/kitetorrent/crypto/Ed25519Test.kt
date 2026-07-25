package io.github.yuroyami.kitetorrent.crypto

import io.github.yuroyami.kitetorrent.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ed25519 ([Ed25519]) against the RFC 8032 / libgcrypt test vectors that
 * libtorrent itself uses (see `test/test_ed25519.cpp`). For each (seed, pub, sig,
 * message): deriving the key pair reproduces the published public key, signing
 * reproduces the published signature, verification accepts it, and a one-bit
 * tamper of the message is rejected.
 */
class Ed25519Test {

    private fun hex(s: String) = Hex.decode(s)

    private data class Vector(val seed: String, val pub: String, val sig: String, val msg: String)

    private val vectors = listOf(
        Vector(
            "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            "72",
        ),
        Vector(
            "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
            "af82",
        ),
        Vector(
            "0d4a05b07352a5436e180356da0ae6efa0345ff7fb1572575772e8005ed978e9",
            "e61a185bcef2613a6c7cb79763ce945d3b245d76114dd440bcf5f2dc1aa57057",
            "d9868d52c2bebce5f3fa5a79891970f309cb6591e3e1702a70276fa97c24b3a8" +
                "e58606c38c9758529da50ee31b8219cba45271c689afa60b0ea26c99db19b00c",
            "cbc77b",
        ),
        // a long message (libtorrent TST: 224)
        Vector(
            "ae1d2c6b171be24c2e413d364dcda97fa476aaf9123d3366b0be03a142fe6e7d",
            "d437f57542c681dd543487408ec7a44bd42a5fd545ce2f4c8297d67bb0b3aa7b",
            "909008f3fcfff43988aee1314b15b1822caaa8dab120bd452af494e08335b44a" +
                "94c313c4b145eadd5166eaac034e29b7e6ac7941d5961fc49d260e1c4820b00e",
            "9e6c2fc76e30f17cd8b498845da44f22d55bec150c6130b411c6339d14b39969" +
                "ab1033be687569a991a06f70b2a8a6931a777b0e4be6723cd75e5aa7532813ef" +
                "50b3d37271640fa2fb287c0355257641ea935c851c0b6ac68be72c88dfc5856f" +
                "b53543fb377b0dbf64808afcc4274aa456855ad28f61267a419bc72166b9ca73" +
                "cd3bb79bf7dd259baa75911440974b68e8ba95a78cbbe1cb6ad807a33a1cce2f" +
                "406ff7bcbd058b44a311b38ab4d4e61416c4a74d883d6a6a794abd9cf1c03902" +
                "8bf1b20e3d4990aae86f32bf06cd8349a7a884cce0165e36a0640e987b9d51",
        ),
    )

    @Test
    fun officialVectors() {
        for (v in vectors) {
            val seed = hex(v.seed)
            val msg = hex(v.msg)

            val (pub, priv) = Ed25519.createKeypair(seed)
            assertEquals(32, pub.size)
            assertEquals(64, priv.size)
            assertEquals(v.pub, Hex.encode(pub), "public key mismatch for seed ${v.seed}")

            val sig = Ed25519.sign(msg, pub, priv)
            assertEquals(64, sig.size)
            assertEquals(v.sig, Hex.encode(sig), "signature mismatch for seed ${v.seed}")

            assertTrue(Ed25519.verify(sig, msg, pub), "verify should accept for seed ${v.seed}")
        }
    }

    @Test
    fun rejectsTamperedMessage() {
        val v = vectors[0]
        val pub = hex(v.pub)
        val sig = hex(v.sig)
        val msg = hex(v.msg)
        // flip one bit of the message
        val tampered = msg.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertFalse(Ed25519.verify(sig, tampered, pub))
    }

    @Test
    fun rejectsTamperedSignature() {
        val v = vectors[1]
        val pub = hex(v.pub)
        val msg = hex(v.msg)
        val sig = hex(v.sig)
        // flip a bit in R (first half)
        val badR = sig.copyOf()
        badR[0] = (badR[0].toInt() xor 0x01).toByte()
        assertFalse(Ed25519.verify(badR, msg, pub))
        // flip a bit in S (second half)
        val badS = sig.copyOf()
        badS[40] = (badS[40].toInt() xor 0x01).toByte()
        assertFalse(Ed25519.verify(badS, msg, pub))
    }

    @Test
    fun rejectsHighSBit() {
        // a signature with the top three bits of the last byte set must be rejected
        // outright (matches libtorrent's `signature[63] & 224` short-circuit).
        val v = vectors[0]
        val pub = hex(v.pub)
        val msg = hex(v.msg)
        val sig = hex(v.sig)
        sig[63] = (sig[63].toInt() or 0xe0).toByte()
        assertFalse(Ed25519.verify(sig, msg, pub))
    }

    @Test
    fun signVerifyRoundTripArbitraryMessage() {
        // not a published vector: this only exercises the full path with our own seed
        val seed = ByteArray(32) { (it * 7 + 1).toByte() }
        val (pub, priv) = Ed25519.createKeypair(seed)
        val message = ByteArray(100) { it.toByte() }
        val sig = Ed25519.sign(message, pub, priv)
        assertTrue(Ed25519.verify(sig, message, pub))
        // a different message under the same key must fail
        val other = message.copyOf().also { it[50] = 99 }
        assertFalse(Ed25519.verify(sig, other, pub))
    }

    @Test
    fun rejectsWrongSizes() {
        val (pub, _) = Ed25519.createKeypair(ByteArray(32))
        assertFalse(Ed25519.verify(ByteArray(63), ByteArray(0), pub))
        assertFalse(Ed25519.verify(ByteArray(64), ByteArray(0), ByteArray(31)))
    }
}
