package io.github.yuroyami.kitetorrent.crypto

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash

/**
 * Message Stream Encryption / Protocol Encryption (MSE/PE). This is the pure-crypto
 * half of the obfuscated BitTorrent handshake.
 *
 * This is a port of the key-derivation and framing constants that libtorrent
 * spreads across `src/pe_crypto.cpp` ([io.github.yuroyami.kitetorrent.crypto.Rc4],
 * `rc4_init`'s 1024-byte discard) and the `init_pe_rc4_handler` / `write_pe3_sync`
 * / `write_pe_vc_cryptofield` helpers in `src/bt_peer_connection.cpp`. Only the
 * byte transforms and key schedule live here. Reading and writing the handshake over
 * a socket needs I/O, so it lives in the session artifact instead (`peer/Mse.kt`).
 *
 * The MSE handshake (see the spec at https://wiki.vuze.com/w/Message_Stream_Encryption):
 * after a Diffie-Hellman exchange (see [DhKeyExchange]) both peers share a 96-byte
 * secret `S`. The torrent's v1 info-hash acts as the stream key `SKEY`. From `(S, SKEY)`
 * each side derives two RC4 keys and obfuscation hashes:
 *
 *  - `keyA = SHA1("keyA" + S + SKEY)`: the outgoing peer's encrypt key
 *  - `keyB = SHA1("keyB" + S + SKEY)`: the outgoing peer's decrypt key
 *
 * The roles swap by direction: the initiator (outgoing) encrypts with `keyA` and
 * decrypts with `keyB`; the receiver (incoming) does the reverse, so the two RC4
 * streams line up. Each RC4 stream discards its first 1024 bytes after keying
 * (libtorrent's `rc4_handler::set_*_key`).
 *
 * The handshake is located/obfuscated on the wire with three SHA-1 hashes keyed
 * by the literal prefixes `"req1"`, `"req2"`, `"req3"`:
 *
 *  - sync hash, `SHA1("req1" + S)`, from [reqSyncHash]
 *  - obfuscated SKEY, `SHA1("req2" + SKEY) xor SHA1("req3" + S)`, from
 *    [obfuscatedStreamKeyHash]
 *
 * The verification constant ([VERIFICATION_CONSTANT]) is 8 zero bytes, RC4-encrypted
 * and matched by the peer to confirm the keys agree.
 */
object MseCrypto {

    /** Size in bytes of the DH shared secret `S` and each public key (768 bits). */
    const val SECRET_LEN: Int = DhKeyExchange.KEY_LEN

    /**
     * Number of leading RC4 keystream bytes discarded immediately after keying.
     * libtorrent's `rc4_handler::set_incoming_key` / `set_outgoing_key` run
     * (and throw away) exactly 1024 bytes of output before any real data.
     */
    const val RC4_DISCARD: Int = 1024

    // --- crypto_provide / crypto_select bitfield values -------------------------
    // From settings_pack::pe_plaintext/pe_rc4/pe_both (settings_pack.hpp). These
    // are the values written into the 32-bit crypto_provide (initiator offers) /
    // crypto_select (receiver chooses) field of the MSE handshake.

    /** Plaintext (no stream encryption) allowed: bit value 1. */
    const val CRYPTO_PLAINTEXT: Int = 1

    /** Full RC4 stream encryption allowed: bit value 2. */
    const val CRYPTO_RC4: Int = 2

    /** Both plaintext and RC4 offered: `CRYPTO_PLAINTEXT or CRYPTO_RC4` = 3. */
    const val CRYPTO_BOTH: Int = CRYPTO_PLAINTEXT or CRYPTO_RC4

    /**
     * The MSE verification constant (VC): 8 zero bytes. After the crypto field is
     * negotiated, both peers send this RC4-encrypted; finding 8 decrypted zero
     * bytes confirms the derived keys match (libtorrent's `sh_vc` /
     * `errors::invalid_encryption_constant`). A fresh array is returned each call
     * so callers may not mutate shared state.
     */
    val VERIFICATION_CONSTANT: ByteArray get() = ByteArray(VC_LEN)

    /** Length of the verification constant, in bytes. */
    const val VC_LEN: Int = 8

    // ASCII literals used to key the SHA-1 derivations. Kept as byte arrays so we
    // never depend on a platform charset (these are pure ASCII in libtorrent).
    private val KEY_A = byteArrayOf('k'.code.toByte(), 'e'.code.toByte(), 'y'.code.toByte(), 'A'.code.toByte())
    private val KEY_B = byteArrayOf('k'.code.toByte(), 'e'.code.toByte(), 'y'.code.toByte(), 'B'.code.toByte())
    private val REQ1 = byteArrayOf('r'.code.toByte(), 'e'.code.toByte(), 'q'.code.toByte(), '1'.code.toByte())
    private val REQ2 = byteArrayOf('r'.code.toByte(), 'e'.code.toByte(), 'q'.code.toByte(), '2'.code.toByte())
    private val REQ3 = byteArrayOf('r'.code.toByte(), 'e'.code.toByte(), 'q'.code.toByte(), '3'.code.toByte())

    /**
     * `keyA = SHA1("keyA" + S + SKEY)`: the 20-byte RC4 key used to *encrypt* by
     * the initiating (outgoing) peer and to *decrypt* by the receiving peer.
     *
     * @param secret the 96-byte DH shared secret `S` (big-endian, left-zero-padded
     *   as produced by [DhKeyExchange.computeSecret]).
     * @param streamKey the 20-byte v1 info-hash `SKEY`.
     */
    fun keyA(secret: ByteArray, streamKey: Sha1Hash): Sha1Hash =
        deriveKey(KEY_A, secret, streamKey)

    /**
     * `keyB = SHA1("keyB" + S + SKEY)`: the 20-byte RC4 key used to *decrypt* by
     * the initiating (outgoing) peer and to *encrypt* by the receiving peer.
     */
    fun keyB(secret: ByteArray, streamKey: Sha1Hash): Sha1Hash =
        deriveKey(KEY_B, secret, streamKey)

    private fun deriveKey(prefix: ByteArray, secret: ByteArray, streamKey: Sha1Hash): Sha1Hash {
        require(secret.size == SECRET_LEN) {
            "MSE shared secret must be $SECRET_LEN bytes, was ${secret.size}"
        }
        return Hasher()
            .update(prefix)
            .update(secret)
            .update(streamKey.toByteArray())
            .final()
    }

    /**
     * The sync hash `SHA1("req1" + S)` (libtorrent `write_pe3_sync`). The initiator
     * writes this 20-byte hash so the receiver can find the start of the encrypted
     * handshake by scanning for it in the byte stream.
     */
    fun reqSyncHash(secret: ByteArray): Sha1Hash {
        require(secret.size == SECRET_LEN) {
            "MSE shared secret must be $SECRET_LEN bytes, was ${secret.size}"
        }
        return Hasher().update(REQ1).update(secret).final()
    }

    /**
     * The XOR mask used to obfuscate the stream-key hash: `SHA1("req3" + S)`.
     * This is libtorrent's `dh_key_exchange::m_xor_mask` (computed in
     * `compute_secret`) and is reproduced by [DhKeyExchange] as well; provided here
     * so the whole MSE derivation can be done from raw `S` bytes.
     */
    fun req3XorMask(secret: ByteArray): Sha1Hash {
        require(secret.size == SECRET_LEN) {
            "MSE shared secret must be $SECRET_LEN bytes, was ${secret.size}"
        }
        return Hasher().update(REQ3).update(secret).final()
    }

    /** `SHA1("req2" + SKEY)`: the un-obfuscated stream-key hash. */
    fun req2StreamKeyHash(streamKey: Sha1Hash): Sha1Hash =
        Hasher().update(REQ2).update(streamKey.toByteArray()).final()

    /**
     * The obfuscated stream-key hash written on the wire:
     * `SHA1("req2" + SKEY) xor SHA1("req3" + S)` (libtorrent `write_pe3_sync`'s
     * `obfsc_hash`). The receiver recovers `SHA1("req2" + SKEY)` by XOR-ing the
     * received value with [req3XorMask] and matches it against each candidate
     * torrent's info-hash.
     */
    fun obfuscatedStreamKeyHash(secret: ByteArray, streamKey: Sha1Hash): Sha1Hash =
        req2StreamKeyHash(streamKey) xor req3XorMask(secret)

    /**
     * Build the RC4 handshake encryption context for one connection, given the DH
     * shared secret, the stream key (v1 info-hash), and whether this peer is the
     * one that initiated the connection ([outgoing]).
     *
     * Mirrors `init_pe_rc4_handler(secret, stream_key, outgoing)` in
     * `bt_peer_connection.cpp`:
     *  - outgoing peer: encrypt with `keyA`, decrypt with `keyB`
     *  - incoming peer: encrypt with `keyB`, decrypt with `keyA`
     *
     * so that one side's outgoing stream is the other side's incoming stream.
     */
    fun rc4(secret: ByteArray, streamKey: Sha1Hash, outgoing: Boolean): MseEncryption {
        val a = keyA(secret, streamKey)
        val b = keyB(secret, streamKey)
        return if (outgoing) {
            MseEncryption(encryptKey = a, decryptKey = b)
        } else {
            MseEncryption(encryptKey = b, decryptKey = a)
        }
    }
}

/**
 * Holds the two keyed RC4 streams for one MSE connection, the port of libtorrent's
 * `rc4_handler`. There is one [Rc4] instance per direction. Each is keyed, then its
 * first [MseCrypto.RC4_DISCARD] (1024) keystream bytes are discarded, exactly as
 * `rc4_handler::set_outgoing_key` and `set_incoming_key` do.
 *
 * RC4 is symmetric, so "encrypt" and "decrypt" are the same XOR-with-keystream
 * operation; they differ only in which stream (and thus which key) they advance.
 * The streams are stateful: every [encrypt]/[decrypt] call moves the keystream
 * forward, so calls must happen in the same order on both peers.
 *
 * Construct via [MseCrypto.rc4]; the public constructor takes the already-derived
 * 20-byte encrypt/decrypt keys directly (useful for tests).
 */
class MseEncryption(encryptKey: Sha1Hash, decryptKey: Sha1Hash) {

    private val out = Rc4(encryptKey.toByteArray()).also { it.discard(MseCrypto.RC4_DISCARD) }
    private val inc = Rc4(decryptKey.toByteArray()).also { it.discard(MseCrypto.RC4_DISCARD) }

    /**
     * Encrypt [data] with the outgoing keystream, returning a new array; advances
     * the outgoing stream by `data.size` bytes. The input is not modified.
     */
    fun encrypt(data: ByteArray): ByteArray = out.process(data)

    /**
     * Decrypt [data] with the incoming keystream, returning a new array; advances
     * the incoming stream by `data.size` bytes. The input is not modified.
     */
    fun decrypt(data: ByteArray): ByteArray = inc.process(data)
}
