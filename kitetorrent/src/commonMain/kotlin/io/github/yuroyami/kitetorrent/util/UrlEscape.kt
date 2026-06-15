package io.github.yuroyami.kitetorrent.util

/**
 * URL percent-encoding / decoding, pure Kotlin so it runs in commonMain on every
 * target.
 *
 * Direct port of libtorrent's `escape_string` / `escape_path` / `unescape_string` /
 * `need_encoding` (src/escape_string.cpp). These drive tracker GET requests (where the
 * 20-byte binary info-hash and peer-id are percent-encoded into the query string) and
 * magnet/URL parsing.
 *
 * libtorrent keeps a single `unreserved_chars` table and indexes into it at different
 * offsets so the three operations share one set:
 *
 * ```
 * index:  0..1   "%+"
 *         2..10  ";?:@=&,$/"          (reserved)
 *        11..18  "-_!.~*()"           (unreserved specials; note ' is excluded
 *                                      because some buggy trackers choke on it)
 *        19..    A-Z a-z 0-9          (alphanumerics)
 * ```
 *
 *  - [escape] (libtorrent `escape_string`) starts at offset **11**: it keeps only
 *    `-_!.~*()` and the alphanumerics, percent-encoding everything else — including
 *    `/`, `:`, `?`, `&`, `%` and so on. This is what you use to encode an individual
 *    query-string *value*.
 *  - [escapePath] (libtorrent `escape_path`) starts at offset **10**: identical to
 *    [escape] except `/` is *also* left intact, so a whole path can be escaped without
 *    mangling its separators.
 *  - [needsEncoding] (libtorrent `need_encoding`) starts at offset **0**: returns true
 *    if the string contains anything outside the *full* table (so `%`, `+`, and the
 *    reserved set all count as "fine"). Used to decide whether a URL even needs
 *    touching.
 *
 * A subtle detail faithfully reproduced from the C++: the table test also requires
 * `*str != 0`, i.e. a NUL byte is *always* escaped (to `%00`) even though C's
 * `strchr` would otherwise treat the terminating NUL as a match.
 *
 * Output hex digits are lowercase, matching libtorrent's `aux::hex_chars`.
 */
object UrlEscape {

    private const val HEX = "0123456789abcdef"

    // The unreserved_chars table from escape_string.cpp, minus the trailing NUL.
    private const val TABLE =
        "%+" +                                                  // 0..1
        ";?:@=&,\$/" +                                          // 2..10 (reserved)
        "-_!.~*()" +                                            // 11..18 (unreserved specials)
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" + // alphanumerics
        "0123456789"

    private const val OFFSET_ESCAPE_STRING = 11
    private const val OFFSET_ESCAPE_PATH = 10
    private const val OFFSET_NEED_ENCODING = 0

    /**
     * Percent-encode the text [s] (libtorrent `escape_string`). Everything except
     * `-_!.~*()` and `[A-Za-z0-9]` is escaped.
     *
     * The string is first converted to its UTF-8 bytes, then each *byte* is encoded —
     * so non-ASCII characters round-trip correctly through [unescape] when its result
     * is decoded back from UTF-8.
     */
    fun escape(s: String): String = escapeString(s.encodeToByteArray())

    /**
     * Percent-encode arbitrary raw [bytes] (libtorrent `escape_string` over a
     * `char const*`). This is the form used for binary values such as the 20-byte
     * info-hash or peer-id in a tracker announce query.
     */
    fun escapeString(bytes: ByteArray): String =
        escapeImpl(bytes, OFFSET_ESCAPE_STRING)

    /**
     * Percent-encode the path text [s] (libtorrent `escape_path`). Same as [escape]
     * but `/` is preserved.
     */
    fun escapePath(s: String): String = escapePathBytes(s.encodeToByteArray())

    /** Percent-encode raw [bytes] as a path (libtorrent `escape_path`); `/` preserved. */
    fun escapePathBytes(bytes: ByteArray): String =
        escapeImpl(bytes, OFFSET_ESCAPE_PATH)

    private fun escapeImpl(bytes: ByteArray, offset: Int): String {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            // mirror: strchr(unreserved_chars + offset, *str) && *str != 0
            if (v != 0 && isInTableFrom(v, offset)) {
                sb.append(v.toChar())
            } else {
                sb.append('%')
                sb.append(HEX[v ushr 4])
                sb.append(HEX[v and 0x0f])
            }
        }
        return sb.toString()
    }

    /**
     * True if [s] (interpreted as its UTF-8 bytes) contains any character that would
     * have to be percent-encoded in a URL — libtorrent `need_encoding`. `%`, `+` and
     * the reserved set do *not* count as needing encoding.
     */
    fun needsEncoding(s: String): Boolean = needsEncoding(s.encodeToByteArray())

    /** Byte-array form of [needsEncoding] (libtorrent `need_encoding`). */
    fun needsEncoding(bytes: ByteArray): Boolean {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v == 0 || !isInTableFrom(v, OFFSET_NEED_ENCODING)) return true
        }
        return false
    }

    /**
     * Decode a percent-encoded string [s] (libtorrent `unescape_string`).
     *
     * `+` is decoded to a space and `%XX` to the corresponding byte; the resulting
     * bytes are then interpreted as UTF-8. Mirroring the C++ error handling, a
     * malformed escape (`%` at end of input, or a non-hex digit after `%`) causes
     * decoding to **stop** and return whatever was accumulated so far rather than
     * throwing. Use [unescapeBytes] if you need the raw decoded bytes (e.g. for a
     * binary info-hash) without the UTF-8 step.
     */
    fun unescape(s: String): String = unescapeBytes(s).decodeToString()

    /**
     * Like [unescape] but returns the raw decoded bytes. libtorrent's
     * `unescape_string` operates byte-wise on a `std::string`; this is the faithful
     * byte-level form, which matters when the decoded payload is binary (an info-hash
     * percent-encoded into a magnet/tracker URL is not valid UTF-8).
     */
    fun unescapeBytes(s: String): ByteArray {
        // s is treated as ASCII/byte-wise like the C++ string_view; characters above
        // 0xff cannot appear in a well-formed percent-encoded URL, but if present we
        // take their low byte to stay byte-faithful to std::string iteration.
        val out = ByteArray(s.length) // upper bound; %XX shrinks it, '+'/literal keep 1:1
        var w = 0
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '+' -> {
                    out[w++] = ' '.code.toByte()
                    i++
                }
                c != '%' -> {
                    out[w++] = (c.code and 0xff).toByte()
                    i++
                }
                else -> {
                    // c == '%'
                    if (i + 1 >= n) break // invalid_escaped_string: stop, keep so far
                    val high = hexDigit(s[i + 1])
                    if (high < 0) break
                    if (i + 2 >= n) break
                    val low = hexDigit(s[i + 2])
                    if (low < 0) break
                    out[w++] = ((high shl 4) or low).toByte()
                    i += 3
                }
            }
        }
        return if (w == out.size) out else out.copyOf(w)
    }

    /** Mirror of `strchr(unreserved_chars + offset, c)` for a single byte value. */
    private fun isInTableFrom(byteValue: Int, offset: Int): Boolean {
        val ch = byteValue.toChar()
        var i = offset
        val len = TABLE.length
        while (i < len) {
            if (TABLE[i] == ch) return true
            i++
        }
        return false
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }
}
