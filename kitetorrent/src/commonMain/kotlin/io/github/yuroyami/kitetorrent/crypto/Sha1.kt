package io.github.yuroyami.kitetorrent.crypto

/**
 * Incremental SHA-1 (FIPS 180-1). Pure-Kotlin port of libtorrent's bundled
 * public-domain SHA-1 (src/sha1.cpp). libtorrent uses this when no platform crypto
 * backend (CommonCrypto, CNG, libcrypto, gcrypt) is available, which is also our
 * situation in commonMain.
 *
 * Byte-for-byte compatible with the reference: verified against the FIPS test
 * vectors ("abc", the 56-char string, and a million 'a's) in the test suite.
 *
 * This is not a cryptographically hardened implementation. BitTorrent uses SHA-1 as a
 * content checksum (info-hashes, piece hashes), not for security.
 */
class Sha1 {

    private val h = intArrayOf(
        0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476, 0xC3D2E1F0.toInt(),
    )
    private val block = ByteArray(64)
    private var blockLen = 0
    private var totalBytes = 0L

    /** Feed [length] bytes from [data] starting at [offset]. Returns this for chaining. */
    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Sha1 {
        var i = offset
        val end = offset + length
        totalBytes += length
        while (i < end) {
            val take = minOf(64 - blockLen, end - i)
            data.copyInto(block, blockLen, i, i + take)
            blockLen += take
            i += take
            if (blockLen == 64) {
                processBlock(block)
                blockLen = 0
            }
        }
        return this
    }

    fun update(b: Byte): Sha1 = update(byteArrayOf(b))

    /** Finish and return the 20-byte digest. The instance must not be reused after. */
    fun digest(): ByteArray {
        val bitLen = totalBytes * 8
        update(byteArrayOf(0x80.toByte()))
        val pad = if (blockLen <= 56) 56 - blockLen else 120 - blockLen
        if (pad > 0) update(ByteArray(pad))
        val lenBytes = ByteArray(8)
        for (j in 0 until 8) lenBytes[j] = (bitLen ushr (56 - j * 8)).toByte()
        update(lenBytes)
        // blockLen is now 0; emit the state big-endian
        val out = ByteArray(20)
        for (j in 0 until 5) {
            out[j * 4] = (h[j] ushr 24).toByte()
            out[j * 4 + 1] = (h[j] ushr 16).toByte()
            out[j * 4 + 2] = (h[j] ushr 8).toByte()
            out[j * 4 + 3] = h[j].toByte()
        }
        return out
    }

    private fun processBlock(buf: ByteArray) {
        val w = IntArray(80)
        for (t in 0 until 16) {
            val o = t * 4
            w[t] = ((buf[o].toInt() and 0xff) shl 24) or
                ((buf[o + 1].toInt() and 0xff) shl 16) or
                ((buf[o + 2].toInt() and 0xff) shl 8) or
                (buf[o + 3].toInt() and 0xff)
        }
        for (t in 16 until 80) {
            w[t] = (w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16]).rotateLeft(1)
        }

        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]
        for (t in 0 until 80) {
            val f: Int
            val k: Int
            when {
                t < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                t < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                t < 60 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt() }
                else -> { f = b xor c xor d; k = 0xCA62C1D6.toInt() }
            }
            val temp = a.rotateLeft(5) + f + e + k + w[t]
            e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e
    }

    companion object {
        /** One-shot SHA-1 of [data]. */
        fun hash(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray =
            Sha1().update(data, offset, length).digest()
    }
}
