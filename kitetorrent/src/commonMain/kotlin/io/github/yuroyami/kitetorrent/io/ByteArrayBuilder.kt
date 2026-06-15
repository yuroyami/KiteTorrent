package io.github.yuroyami.kitetorrent.io

/**
 * Grow-on-demand byte buffer. Pure Kotlin (no platform OutputStream), so it works
 * in commonMain across every target, and it stores into a contiguous primitive
 * array rather than boxing into a `MutableList<Byte>`. The bencode encoder and the
 * peer wire-protocol codec both build their output through this.
 */
class ByteArrayBuilder(initialCapacity: Int = 64) {
    private var buf: ByteArray = ByteArray(initialCapacity.coerceAtLeast(16))
    private var written: Int = 0

    val size: Int get() = written

    fun append(b: Byte) {
        if (written == buf.size) grow(written + 1)
        buf[written++] = b
    }

    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (length == 0) return
        if (written + length > buf.size) grow(written + length)
        bytes.copyInto(buf, written, offset, offset + length)
        written += length
    }

    /** Append the low byte of each char in [s] — for pure-ASCII tokens (bencode digits, ':'). */
    fun appendAscii(s: String) {
        val n = s.length
        if (n == 0) return
        if (written + n > buf.size) grow(written + n)
        var w = written
        for (i in 0 until n) buf[w++] = s[i].code.toByte()
        written = w
    }

    fun toByteArray(): ByteArray = buf.copyOf(written)

    private fun grow(minCapacity: Int) {
        var newCap = (buf.size * 2).coerceAtLeast(16)
        while (newCap < minCapacity) newCap *= 2
        buf = buf.copyOf(newCap)
    }
}
