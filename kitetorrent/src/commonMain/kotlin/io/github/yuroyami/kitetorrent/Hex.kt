package io.github.yuroyami.kitetorrent

/**
 * Hexadecimal encode/decode. Pure Kotlin (no platform codecs), so it works in
 * commonMain across every target. Port of libtorrent's `aux::to_hex` /
 * `aux::from_hex` (hex.cpp).
 *
 * Output is always lowercase, matching libtorrent. Decoding accepts either case.
 */
object Hex {

    private const val DIGITS = "0123456789abcdef"

    /** Encode [bytes] (optionally a sub-range) as a lowercase hex string. */
    fun encode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        val sb = StringBuilder(length * 2)
        var i = offset
        val end = offset + length
        while (i < end) {
            val v = bytes[i].toInt() and 0xff
            sb.append(DIGITS[v ushr 4])
            sb.append(DIGITS[v and 0x0f])
            i++
        }
        return sb.toString()
    }

    /**
     * Decode a hex string to bytes. Returns null if [s] has odd length or
     * contains a non-hex character — mirroring libtorrent's `from_hex` returning
     * false rather than throwing on malformed input.
     */
    fun decodeOrNull(s: String): ByteArray? {
        if (s.length and 1 != 0) return null
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < out.size) {
            val hi = nibble(s[i * 2])
            val lo = nibble(s[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
            i++
        }
        return out
    }

    /** Decode a hex string to bytes, throwing [IllegalArgumentException] if malformed. */
    fun decode(s: String): ByteArray =
        decodeOrNull(s) ?: throw IllegalArgumentException("not a valid hex string: $s")

    private fun nibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
