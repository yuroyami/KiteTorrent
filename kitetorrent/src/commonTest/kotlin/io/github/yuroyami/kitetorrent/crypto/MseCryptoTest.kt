package io.github.yuroyami.kitetorrent.crypto

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MSE/PE ([MseCrypto] / [MseEncryption]) key-derivation and framing tests.
 *
 * A separate tool (CPython `hashlib`) produced the golden SHA-1 values below, over
 * the documented MSE inputs (`SHA1("keyA"|"keyB"|"req1"|"req2"|"req3" + ...)`) for a
 * fixed deterministic shared secret `S` and stream key `SKEY`. The test therefore
 * pins the actual derivation, not just self-consistency.
 */
class MseCryptoTest {

    // Deterministic 96-byte shared secret S = (i*7+3) mod 256, i in 0..95.
    private val secret = ByteArray(96) { ((it * 7 + 3) and 0xff).toByte() }

    // The 20-byte v1 info-hash SKEY = (i*11+5) mod 256 as a Sha1Hash (an opaque
    // 20-byte digest here).
    private val skey = Digest32.of(ByteArray(20) { ((it * 11 + 5) and 0xff).toByte() })

    @Test
    fun keyADerivationGolden() {
        // SHA1("keyA" + S + SKEY)
        assertEquals(
            "a3cf4334b16a794d1a2f40b8ac5637b52d406e4d",
            MseCrypto.keyA(secret, skey).toHex()
        )
    }

    @Test
    fun keyBDerivationGolden() {
        // SHA1("keyB" + S + SKEY)
        assertEquals(
            "653d722854577af821a6b87067477b4344d9c1a5",
            MseCrypto.keyB(secret, skey).toHex()
        )
    }

    @Test
    fun reqSyncHashGolden() {
        // SHA1("req1" + S)
        assertEquals(
            "17fe8d6fb29b3ade48969ce03ba6a7eea841e03e",
            MseCrypto.reqSyncHash(secret).toHex()
        )
    }

    @Test
    fun req2StreamKeyHashGolden() {
        // SHA1("req2" + SKEY)
        assertEquals(
            "530c9d1c161159cafc250a2983a46e61114071d0",
            MseCrypto.req2StreamKeyHash(skey).toHex()
        )
    }

    @Test
    fun req3XorMaskGolden() {
        // SHA1("req3" + S)  (== dh_key_exchange::m_xor_mask)
        assertEquals(
            "a5a27610e3ddbbb5bcffff17afe439ff14a6ad85",
            MseCrypto.req3XorMask(secret).toHex()
        )
    }

    @Test
    fun obfuscatedStreamKeyHashGolden() {
        // SHA1("req2" + SKEY) xor SHA1("req3" + S)
        assertEquals(
            "f6aeeb0cf5cce27f40daf53e2c40579e05e6dc55",
            MseCrypto.obfuscatedStreamKeyHash(secret, skey).toHex()
        )
    }

    @Test
    fun obfuscationRecoversStreamKeyHash() {
        // Receiver recovers SHA1("req2"+SKEY) by XOR-ing the on-wire obfuscated
        // value with HASH("req3", S), as libtorrent does to locate the torrent.
        val onWire = MseCrypto.obfuscatedStreamKeyHash(secret, skey)
        val recovered = onWire xor MseCrypto.req3XorMask(secret)
        assertEquals(MseCrypto.req2StreamKeyHash(skey).toHex(), recovered.toHex())
    }

    @Test
    fun derivationIsDeterministic() {
        assertEquals(MseCrypto.keyA(secret, skey).toHex(), MseCrypto.keyA(secret, skey).toHex())
        assertEquals(MseCrypto.keyB(secret, skey).toHex(), MseCrypto.keyB(secret, skey).toHex())
    }

    @Test
    fun keyAandKeyBDiffer() {
        assertTrue(MseCrypto.keyA(secret, skey).toHex() != MseCrypto.keyB(secret, skey).toHex())
    }

    @Test
    fun verificationConstantIsEightZeroBytes() {
        val vc = MseCrypto.VERIFICATION_CONSTANT
        assertEquals(8, vc.size)
        assertEquals(MseCrypto.VC_LEN, vc.size)
        assertTrue(vc.all { it == 0.toByte() })
    }

    @Test
    fun verificationConstantIsFreshCopy() {
        // Must not leak shared mutable state.
        val a = MseCrypto.VERIFICATION_CONSTANT
        a[0] = 0x7f
        assertTrue(MseCrypto.VERIFICATION_CONSTANT.all { it == 0.toByte() })
    }

    @Test
    fun cryptoConstants() {
        assertEquals(1, MseCrypto.CRYPTO_PLAINTEXT)
        assertEquals(2, MseCrypto.CRYPTO_RC4)
        assertEquals(3, MseCrypto.CRYPTO_BOTH)
        assertEquals(MseCrypto.CRYPTO_PLAINTEXT or MseCrypto.CRYPTO_RC4, MseCrypto.CRYPTO_BOTH)
        assertEquals(1024, MseCrypto.RC4_DISCARD)
    }

    @Test
    fun rc4RoundTripWithDiscardAcrossPeers() {
        // The initiator's outgoing stream must be the receiver's incoming stream.
        // Build both contexts from the same (S, SKEY); encrypt on one side,
        // decrypt on the mirrored side -> original plaintext.
        val initiator = MseCrypto.rc4(secret, skey, outgoing = true)
        val receiver = MseCrypto.rc4(secret, skey, outgoing = false)

        val plaintext = ByteArray(200) { ((it * 3 + 1) and 0xff).toByte() }

        val ct = initiator.encrypt(plaintext)
        assertTrue(!ct.contentEquals(plaintext), "ciphertext should differ from plaintext")
        val pt = receiver.decrypt(ct)
        assertEquals(Hex.encode(plaintext), Hex.encode(pt))

        // And the reverse direction.
        val ct2 = receiver.encrypt(plaintext)
        val pt2 = initiator.decrypt(ct2)
        assertEquals(Hex.encode(plaintext), Hex.encode(pt2))
    }

    @Test
    fun rc4DiscardActuallySkips1024Bytes() {
        // An MseEncryption built from keyA must produce the SAME keystream as a raw
        // Rc4(keyA) that has manually discarded 1024 bytes. Verify by XOR-ing a
        // known plaintext both ways.
        val mse = MseEncryption(encryptKey = MseCrypto.keyA(secret, skey), decryptKey = MseCrypto.keyB(secret, skey))

        val raw = Rc4(MseCrypto.keyA(secret, skey).toByteArray())
        raw.discard(1024)

        val data = ByteArray(64) { 0 }
        assertEquals(Hex.encode(raw.process(data)), Hex.encode(mse.encrypt(data)))
    }

    @Test
    fun rc4RoundTripWithRandomData() {
        val rng = Random(0xBEEF)
        val initiator = MseCrypto.rc4(secret, skey, outgoing = true)
        val receiver = MseCrypto.rc4(secret, skey, outgoing = false)

        repeat(8) {
            val chunk = ByteArray(rng.nextInt(1, 300)).also { rng.nextBytes(it) }
            val round = receiver.decrypt(initiator.encrypt(chunk))
            assertEquals(Hex.encode(chunk), Hex.encode(round))
        }
    }

    @Test
    fun encryptDoesNotMutateInput() {
        val mse = MseCrypto.rc4(secret, skey, outgoing = true)
        val data = ByteArray(16) { 0x41 }
        val copy = data.copyOf()
        mse.encrypt(data)
        assertTrue(data.contentEquals(copy), "encrypt must not mutate its input")
    }
}
