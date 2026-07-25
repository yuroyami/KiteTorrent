package io.github.yuroyami.kitetorrent.crypto

import io.github.yuroyami.kitetorrent.math.BigInt
import kotlin.random.Random

/**
 * Diffie-Hellman key exchange for BitTorrent Message Stream Encryption (MSE/PE).
 * Pure-Kotlin port of `dh_key_exchange` in libtorrent's `src/pe_crypto.cpp`.
 *
 * MSE fixes the group: a 768-bit safe prime P (the same prime libtorrent embeds
 * as `dh_prime`) and generator g = 2. Each peer picks a random 96-byte (768-bit)
 * secret `x`, publishes `g^x mod P` (96 bytes, big-endian, left-zero-padded), and
 * derives the shared secret `remote^x mod P`. Both peers arrive at the same value
 * `g^(x_a * x_b) mod P`.
 *
 * The shared-secret bytes are then fed (with the literal "req3" prefix) into SHA-1
 * to form the obfuscation XOR mask used later in the MSE handshake; [req3XorMask]
 * reproduces libtorrent's `hasher(req3).update(buffer).final()`.
 *
 * Bignum support (modular exponentiation) comes from [BigInt]. There is no
 * `BigInteger` in the Kotlin common stdlib, so libtorrent's `boost::multiprecision`
 * usage is reimplemented from scratch.
 */
class DhKeyExchange private constructor(
    private val secret: BigInt,
) {
    /** This peer's public key: `g^secret mod P`, exactly 96 bytes (big-endian). */
    val publicKey: ByteArray = GENERATOR.modPow(secret, PRIME).toFixedBytes(KEY_LEN)

    /**
     * Compute the shared secret from the remote peer's 96-byte public key.
     * Returns `remote^secret mod P` as 96 big-endian bytes.
     *
     * Faithful to libtorrent, degenerate remote keys are rejected: any value
     * outside `[2, P-2]` collapses the shared secret into a tiny subgroup and
     * would let a man-in-the-middle pin it, so this throws [IllegalArgumentException]
     * (libtorrent's `compute_secret` returns false).
     */
    fun computeSecret(remotePublicKey: ByteArray): ByteArray {
        require(remotePublicKey.size == KEY_LEN) {
            "remote public key must be $KEY_LEN bytes, was ${remotePublicKey.size}"
        }
        val remote = BigInt.fromBytes(remotePublicKey)
        require(remote >= BigInt.TWO && remote < PRIME_MINUS_ONE) {
            "degenerate remote public key rejected"
        }
        return remote.modPow(secret, PRIME).toFixedBytes(KEY_LEN)
    }

    companion object {
        /** Size of public keys and the shared secret, in bytes (768 bits). */
        const val KEY_LEN = 96

        /**
         * The MSE 768-bit prime P, identical to libtorrent's `dh_prime`
         * (`src/pe_crypto.cpp`). This is the Diffie-Hellman prime from the
         * BitTorrent MSE spec.
         */
        val PRIME: BigInt = BigInt.fromHex(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
                "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
                "4FE1356D6D51C245E485B576625E7EC6F44C42E9A63A36210000000000090563"
        )

        /** MSE generator g = 2. */
        val GENERATOR: BigInt = BigInt.TWO

        private val PRIME_MINUS_ONE: BigInt = PRIME - BigInt.ONE

        /**
         * Create an exchange with a fresh random 96-byte secret (libtorrent's
         * default constructor: `aux::random_bytes` then `import_bits`).
         */
        fun random(rng: Random = Random.Default): DhKeyExchange {
            val bytes = ByteArray(KEY_LEN)
            rng.nextBytes(bytes)
            return fromSecret(bytes)
        }

        /**
         * Create an exchange from an explicit secret (big-endian bytes, any
         * length up to 96). Primarily for deterministic tests; the secret is
         * reduced mod P implicitly by the exponentiation.
         */
        fun fromSecret(secret: ByteArray): DhKeyExchange =
            DhKeyExchange(BigInt.fromBytes(secret))
    }
}
