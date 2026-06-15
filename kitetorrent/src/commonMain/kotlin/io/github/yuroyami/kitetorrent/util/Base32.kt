package io.github.yuroyami.kitetorrent.util

/**
 * RFC 4648 base32 encode/decode. Pure Kotlin (no platform codecs), so it works in
 * commonMain across every target.
 *
 * Port of libtorrent's `base32decode` / `base32encode_i2p` (src/escape_string.cpp).
 * In BitTorrent, base32 shows up in two places:
 *  - **v1 magnet links** historically allowed the 20-byte info-hash to be encoded as
 *    a 32-character base32 string ("xt=urn:btih:" followed by base32), and v2 magnet
 *    links / well-known network identifiers also use base32.
 *  - **i2p** addresses use a *non-standard* lowercase variant of base32.
 *
 * Two important fidelity notes about the libtorrent source this is ported from:
 *  1. libtorrent's [encode] equivalent only exists as `base32encode_i2p`, which uses
 *     the **lowercase** alphabet `a-z2-7` and emits **no padding** (i2p convention).
 *     RFC 4648 — which is what v2 magnet links and most of the world expect — uses
 *     the **uppercase** alphabet `A-Z2-7` and pads the final group to a multiple of 8
 *     characters with `=`. This object implements the RFC 4648 behaviour (uppercase +
 *     padding); the exact same bit-packing as `base32encode_i2p` is reused, only the
 *     alphabet table and the trailing `=` padding differ. See [encodeI2p] for the
 *     verbatim i2p behaviour.
 *  2. libtorrent's `base32decode` is already standard-alphabet (`A-Z2-7`) and is
 *     deliberately lenient: it upper-cases input first (so lowercase decodes too),
 *     and it maps the ambiguous digit `'1'` to the letter `'I'`. Both quirks are
 *     preserved here. On any other unexpected character it returns an empty result,
 *     exactly like the C++ (which returns `std::string()`); [decodeOrNull] surfaces
 *     that as `null` while [decode] returns an empty array, matching the C++ return.
 */
object Base32 {

    /** RFC 4648 alphabet (uppercase). Index = 5-bit value, value = output char. */
    private const val RFC_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** i2p alphabet (lowercase). Same 5-bit ordering, different glyphs. */
    private const val I2P_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    private const val PAD = '='

    // For a block of `availableInput` input bytes (1..5) the i2p code emits this many
    // base32 output characters before padding. Index by the byte count.
    // (verbatim from libtorrent's input_output_mapping{{{0, 2, 4, 5, 7, 8}}})
    private val ENCODE_OUT_FOR_INPUT = intArrayOf(0, 2, 4, 5, 7, 8)

    // For RFC 4648, a partial final group is padded out to 8 characters with '='.
    // Index by the number of *significant* output characters already written for the
    // final group (2,4,5,7) → number of '=' to append. Full groups (8) get nothing.
    private fun padCountFor(significantChars: Int): Int =
        if (significantChars == 0 || significantChars == 8) 0 else 8 - significantChars

    /**
     * Encode [bytes] to an RFC 4648 base32 string (uppercase, `=`-padded).
     *
     * @param offset start of the sub-range to encode (default 0)
     * @param length number of bytes to encode (default: to end of array)
     */
    fun encode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String =
        encodeImpl(bytes, offset, length, RFC_ALPHABET, pad = true)

    /**
     * Encode [bytes] using i2p's lowercase, **unpadded** base32 alphabet — the exact
     * behaviour of libtorrent's `base32encode_i2p`.
     */
    fun encodeI2p(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String =
        encodeImpl(bytes, offset, length, I2P_ALPHABET, pad = false)

    private fun encodeImpl(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        alphabet: String,
        pad: Boolean,
    ): String {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "range out of bounds: offset=$offset length=$length size=${bytes.size}"
        }
        if (length == 0) return ""

        val sb = StringBuilder((length + 4) / 5 * 8)
        val inbuf = IntArray(5)

        var i = offset
        val end = offset + length
        while (i < end) {
            val available = minOf(5, end - i)

            // clear input buffer, then read up to 5 bytes (matches inbuf.fill(0) + copy)
            for (j in 0 until 5) inbuf[j] = if (j < available) bytes[i + j].toInt() and 0xff else 0
            i += available

            // encode inbuf (5 bytes) -> 8 five-bit groups (verbatim bit math)
            val o0 = (inbuf[0] and 0xf8) ushr 3
            val o1 = ((inbuf[0] and 0x07) shl 2) or ((inbuf[1] and 0xc0) ushr 6)
            val o2 = (inbuf[1] and 0x3e) ushr 1
            val o3 = ((inbuf[1] and 0x01) shl 4) or ((inbuf[2] and 0xf0) ushr 4)
            val o4 = ((inbuf[2] and 0x0f) shl 1) or ((inbuf[3] and 0x80) ushr 7)
            val o5 = (inbuf[3] and 0x7c) ushr 2
            val o6 = ((inbuf[3] and 0x03) shl 3) or ((inbuf[4] and 0xe0) ushr 5)
            val o7 = inbuf[4] and 0x1f
            val outbuf = intArrayOf(o0, o1, o2, o3, o4, o5, o6, o7)

            val numOut = ENCODE_OUT_FOR_INPUT[available]
            for (j in 0 until numOut) sb.append(alphabet[outbuf[j] and 0x1f])
            if (pad) repeat(padCountFor(numOut)) { sb.append(PAD) }
        }
        return sb.toString()
    }

    /**
     * Decode an RFC 4648 base32 [s] to bytes. Returns an empty array on any invalid
     * character, mirroring libtorrent's `base32decode` returning `std::string()`.
     *
     * Lenient like the C++: accepts lowercase (upper-cased internally) and maps `'1'`
     * to `'I'`. `=` padding is honoured.
     */
    fun decode(s: String): ByteArray = decodeOrNull(s) ?: ByteArray(0)

    /**
     * Like [decode], but returns `null` (instead of an empty array) when the input
     * contains a character outside the base32 alphabet, so callers can distinguish
     * "decoded to nothing" from "malformed".
     */
    fun decodeOrNull(s: String): ByteArray? {
        if (s.isEmpty()) return ByteArray(0)

        // worst case 5 bytes out per 8 chars in
        val out = ArrayList<Byte>(s.length / 8 * 5 + 5)
        val inbuf = IntArray(8)

        var i = 0
        val n = s.length
        while (i < n) {
            val available = minOf(8, n - i)

            var padStart = if (available < 8) available else 0

            // clear + fill input (5-bit symbols), tracking where padding begins
            for (j in 0 until 8) inbuf[j] = 0
            for (j in 0 until available) {
                val c = s[i++]
                val up = if (c in 'a'..'z') c - 'a' + 'A'.code else c.code
                val v: Int = when {
                    up in 'A'.code..'Z'.code -> up - 'A'.code
                    up in '2'.code..'7'.code -> up - '2'.code + ('Z'.code - 'A'.code) + 1
                    up == PAD.code -> {
                        if (padStart == 0) padStart = j
                        0
                    }
                    // libtorrent maps the ambiguous '1' to the letter 'I'
                    up == '1'.code -> 'I'.code - 'A'.code
                    else -> return null
                }
                inbuf[j] = v and 0x1f
            }

            // decode 8 five-bit symbols -> up to 5 bytes (verbatim bit math)
            val outbuf = IntArray(5)
            outbuf[0] = (inbuf[0] shl 3) and 0xff
            outbuf[0] = outbuf[0] or (inbuf[1] ushr 2)
            outbuf[1] = ((inbuf[1] and 0x3) shl 6) and 0xff
            outbuf[1] = outbuf[1] or (inbuf[2] shl 1)
            outbuf[1] = outbuf[1] or ((inbuf[3] and 0x10) ushr 4)
            outbuf[2] = ((inbuf[3] and 0x0f) shl 4) and 0xff
            outbuf[2] = outbuf[2] or ((inbuf[4] and 0x1e) ushr 1)
            outbuf[3] = ((inbuf[4] and 0x01) shl 7) and 0xff
            outbuf[3] = outbuf[3] or ((inbuf[5] and 0x1f) shl 2)
            outbuf[3] = outbuf[3] or ((inbuf[6] and 0x18) ushr 3)
            outbuf[4] = ((inbuf[6] and 0x07) shl 5) and 0xff
            outbuf[4] = outbuf[4] or inbuf[7]

            val numOut = DECODE_OUT_FOR_PAD[padStart]
            for (j in 0 until numOut) out.add((outbuf[j] and 0xff).toByte())
        }

        val arr = ByteArray(out.size)
        for (k in out.indices) arr[k] = out[k]
        return arr
    }

    // For a final group with padding starting at symbol index `padStart` (0..8), how
    // many decoded bytes are valid. Verbatim from libtorrent's
    // input_output_mapping[] = {5, 1, 1, 2, 2, 3, 4, 4, 5}
    private val DECODE_OUT_FOR_PAD = intArrayOf(5, 1, 1, 2, 2, 3, 4, 4, 5)
}
