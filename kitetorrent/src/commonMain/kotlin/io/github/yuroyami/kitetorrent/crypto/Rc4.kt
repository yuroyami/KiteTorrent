package io.github.yuroyami.kitetorrent.crypto

/**
 * RC4 (ARC4) stream cipher. Pure-Kotlin port of the libTomCrypt-derived
 * `rc4_init` / `rc4_encrypt` in libtorrent's `src/pe_crypto.cpp`, used by the
 * Message Stream Encryption / Protocol Encryption (MSE/PE) handshake.
 *
 * RC4 is symmetric: the same operation both encrypts and decrypts (it XORs the
 * keystream into the data), so [process] serves both directions. The cipher is
 * stateful, because each call to [process] advances the keystream, exactly like
 * libtorrent's `rc4_handler`, which keeps separate incoming and outgoing [Rc4]
 * states per connection.
 *
 * Note on MSE: libtorrent discards the first 1024 bytes of keystream after keying
 * (`set_incoming_key` / `set_outgoing_key`). That discard is a property of the
 * MSE handler, not of RC4 itself, so it lives in the protocol layer; [discard]
 * is provided as a convenience for that.
 *
 * Validated against the canonical RFC 6229 / Wikipedia RC4 test vectors.
 */
class Rc4(key: ByteArray) {

    // Permutation state S (256 bytes), held as Int (0..255) to avoid signed-byte pain.
    private val s = IntArray(256)
    private var x = 0
    private var y = 0

    init {
        require(key.isNotEmpty()) { "RC4 key must not be empty" }
        // libtorrent caps the key at the state size (256). Keys longer than that
        // are truncated; in practice MSE keys are 20 bytes (a SHA-1 digest).
        val keyLen = if (key.size > 256) 256 else key.size

        // Identity permutation.
        for (i in 0 until 256) s[i] = i

        // Key-scheduling algorithm (KSA).
        var j = 0
        var k = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[k].toInt() and 0xff)) and 0xff
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            k++
            if (k == keyLen) k = 0
        }
    }

    /**
     * Encrypt or decrypt [data], returning a new array of the same length.
     * Advances the internal keystream position. The input array is not modified.
     */
    fun process(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var i = x
        var jj = y
        for (n in data.indices) {
            i = (i + 1) and 0xff
            jj = (jj + s[i]) and 0xff
            val tmp = s[i]; s[i] = s[jj]; s[jj] = tmp
            val ks = s[(s[i] + s[jj]) and 0xff]
            out[n] = (data[n].toInt() xor ks).toByte()
        }
        x = i
        y = jj
        return out
    }

    /**
     * Run the keystream forward over [count] bytes and throw the output away.
     * MSE keys the cipher then discards the first 1024 bytes of keystream
     * (see `rc4_handler::set_incoming_key` in pe_crypto.cpp).
     */
    fun discard(count: Int) {
        var i = x
        var jj = y
        var n = count
        while (n-- > 0) {
            i = (i + 1) and 0xff
            jj = (jj + s[i]) and 0xff
            val tmp = s[i]; s[i] = s[jj]; s[jj] = tmp
        }
        x = i
        y = jj
    }
}
