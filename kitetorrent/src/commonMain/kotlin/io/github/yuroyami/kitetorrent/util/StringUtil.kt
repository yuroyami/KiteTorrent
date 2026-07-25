package io.github.yuroyami.kitetorrent.util

/**
 * Small, locale-independent string helpers. This is a pure-Kotlin port of the portable
 * parts of libtorrent's `string_util.cpp` and `string_util.hpp` (plus `base64encode` from
 * `escape_string.cpp`).
 *
 * These deliberately reimplement the C `ctype` predicates (`isalpha`, `isspace`, …)
 * over the ASCII range only, exactly like libtorrent does, so that behaviour is
 * **identical on every platform regardless of the C locale**. That is why libtorrent
 * wrote its own. Anything in `string_util.cpp` that depends on
 * sockets, addresses, the global RNG, locale conversion, or raw C memory management
 * (`parse_listen_interfaces`, `print_listen_interfaces`, `url_random`,
 * `allocate_string_copy`, `convert_to_native`, `is_i2p_url`, …) is intentionally not
 * ported here.
 *
 * Where libtorrent returns `std::pair<string_view, string_view>` (zero-copy views into
 * the original buffer), the Kotlin equivalents return [Pair]<String, String> of copies;
 * the splitting semantics are preserved byte-for-byte.
 */
object StringUtil {

    // ASCII control characters that libtorrent's is_space() treats as whitespace but
    // Kotlin has no escape for: form-feed (0x0C) and vertical-tab (0x0B).
    private const val FORM_FEED = '\u000C'
    private const val VERTICAL_TAB = '\u000B'

    /** libtorrent `is_alpha`: ASCII letter. */
    fun isAlpha(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z')

    /** libtorrent `is_digit` (inline in string_util.hpp): ASCII decimal digit. */
    fun isDigit(c: Char): Boolean = c in '0'..'9'

    /** libtorrent `is_print`: printable ASCII (space through '~', i.e. 32..126). */
    fun isPrint(c: Char): Boolean = c.code in 32..126

    /**
     * libtorrent `is_space`: ASCII whitespace. Note this matches the C set
     * `' ' \t \n \r \f \v`, and *not* Kotlin's [Char.isWhitespace], which is Unicode-aware.
     */
    fun isSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == FORM_FEED || c == VERTICAL_TAB

    /** libtorrent `to_lower`: ASCII-only lower-casing (leaves non-ASCII untouched). */
    fun toLower(c: Char): Char = if (c in 'A'..'Z') 'a' + (c - 'A') else c

    /** ASCII-only lower-cased copy of [s]; locale-independent, unlike [String.lowercase]. */
    fun toLowerAscii(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(toLower(c))
        return sb.toString()
    }

    /**
     * libtorrent `to_string(std::int64_t)`: decimal text of [n] with a well-defined,
     * locale-independent result. It handles [Long.MIN_VALUE] correctly. That magnitude is
     * not representable as a positive signed Long, so the digits are produced via
     * unsigned arithmetic, exactly as the C++ does.
     */
    fun toString(n: Long): String {
        if (n == 0L) return "0"
        // build digits least-significant first into a fixed buffer (max 20 digits + sign)
        val buf = CharArray(24)
        var p = buf.size
        // absolute value in unsigned space so MIN_VALUE is handled (matches the C++
        // `numeric_limits<uint64_t>::max() - uint64_t(n) + 1` trick)
        var u: ULong = if (n < 0) (0u.toULong() - n.toULong()) else n.toULong()
        do {
            buf[--p] = ('0'.code + (u % 10u).toInt()).toChar()
            u /= 10u
        } while (u != 0u.toULong())
        if (n < 0) buf[--p] = '-'
        return buf.concatToString(p, buf.size)
    }

    /**
     * libtorrent `string_begins_no_case`: true if [s] begins with [prefix], compared
     * case-insensitively over ASCII. (In the C++ the first argument is the prefix and
     * the loop runs to the prefix's NUL.)
     */
    fun stringBeginsNoCase(prefix: String, s: String): Boolean {
        if (s.length < prefix.length) return false
        for (i in prefix.indices) {
            if (toLower(prefix[i]) != toLower(s[i])) return false
        }
        return true
    }

    /** libtorrent `string_equal_no_case`: ASCII case-insensitive equality. */
    fun stringEqualNoCase(s1: String, s2: String): Boolean {
        if (s1.length != s2.length) return false
        for (i in s1.indices) if (toLower(s1[i]) != toLower(s2[i])) return false
        return true
    }

    /** libtorrent `string_ends_with`: true if [s1] ends with [s2]. */
    fun stringEndsWith(s1: String, s2: String): Boolean {
        if (s1.length < s2.length) return false
        val off = s1.length - s2.length
        for (i in s2.indices) if (s1[off + i] != s2[i]) return false
        return true
    }

    /**
     * libtorrent `strip_string`: drop leading and trailing ASCII whitespace, using the
     * full [isSpace] set (`space tab nl cr ff vt`).
     */
    fun stripString(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isSpace(s[start])) start++
        while (end > start && isSpace(s[end - 1])) end--
        return s.substring(start, end)
    }

    /**
     * libtorrent `trim` (escape_string.cpp): strips only `" \t\n\r"`. This is the narrower
     * set the `trim` helper uses (no form-feed, no vertical-tab), kept distinct from
     * [stripString] to stay faithful to both call sites.
     */
    fun trim(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isTrimWs(s[start])) start++
        while (end > start && isTrimWs(s[end - 1])) end--
        return s.substring(start, end)
    }

    private fun isTrimWs(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /**
     * libtorrent `split_string`: find separator [sep] in [last]. If found, return the
     * part before it and the remainder after it (separator dropped). If not found,
     * return ([last], "").
     */
    fun splitString(last: String, sep: Char): Pair<String, String> {
        val pos = last.indexOf(sep)
        return if (pos < 0) last to ""
        else last.substring(0, pos) to last.substring(pos + 1)
    }

    /**
     * libtorrent `split_string_quotes`: like [splitString], but if the string starts
     * with a double-quote (`"`), and the separator itself is not `"`, then separators are
     * ignored until the closing quote. Faithful port of the index-walking in the C++.
     */
    fun splitStringQuotes(last: String, sep: Char): Pair<String, String> {
        if (last.isEmpty()) return "" to ""

        var pos = 0
        if (last[0] == '"' && sep != '"') {
            // walk past the quoted region: advance until (and including) the closing '"'
            var idx = 1
            while (idx < last.length) {
                pos++
                if (last[idx] == '"') break
                idx++
            }
        }
        var foundSep = 0
        var idx = pos
        while (idx < last.length) {
            if (last[idx] == sep) {
                foundSep = 1
                break
            }
            pos++
            idx++
        }
        return last.substring(0, pos) to last.substring(pos + foundSep)
    }

    /**
     * libtorrent `parse_comma_separated_string`: split [input] on commas, trimming
     * leading and trailing ASCII whitespace from each element. Empty elements are kept
     * (the C++ pushes back the possibly-empty substring), matching the
     * `outgoing_interfaces`-style parsing.
     */
    fun parseCommaSeparatedString(input: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        val n = input.length
        while (start < n) {
            // skip leading spaces
            while (start < n && isSpace(input[start])) start++

            var end = input.indexOf(',', start)
            if (end < 0) end = n

            // skip trailing spaces
            var softEnd = end
            while (softEnd > start && isSpace(input[softEnd - 1])) softEnd--

            out.add(input.substring(start, softEnd))
            start = end + 1
        }
        return out
    }

    /** libtorrent `ltrim`: leading-whitespace-only strip. */
    fun ltrim(s: String): String {
        var start = 0
        while (start < s.length && isSpace(s[start])) start++
        return if (start == 0) s else s.substring(start)
    }

    /** libtorrent `ensure_trailing_slash` (inline in string_util.hpp). */
    fun ensureTrailingSlash(url: String): String =
        if (url.isEmpty() || url[url.length - 1] != '/') url + '/' else url

    /** libtorrent `convert_path_to_posix` (escape_string.cpp): replace every `\` with `/`. */
    fun convertPathToPosix(path: String): String = path.replace('\\', '/')

    // ---------------------------------------------------------------------------
    // base64 (from escape_string.cpp: pure, no platform deps)
    // ---------------------------------------------------------------------------

    private const val BASE64_TABLE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * libtorrent `base64encode`: standard base64 with `=` padding. Operates on raw
     * bytes (the C++ takes a `std::string` but treats it as a byte buffer).
     */
    fun base64encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        val inbuf = IntArray(3)
        var i = 0
        val n = bytes.size
        while (i < n) {
            val available = minOf(3, n - i)
            for (j in 0 until 3) inbuf[j] = if (j < available) bytes[i + j].toInt() and 0xff else 0
            i += available

            val o0 = (inbuf[0] and 0xfc) ushr 2
            val o1 = ((inbuf[0] and 0x03) shl 4) or ((inbuf[1] and 0xf0) ushr 4)
            val o2 = ((inbuf[1] and 0x0f) shl 2) or ((inbuf[2] and 0xc0) ushr 6)
            val o3 = inbuf[2] and 0x3f
            val outbuf = intArrayOf(o0, o1, o2, o3)

            for (j in 0 until available + 1) sb.append(BASE64_TABLE[outbuf[j]])
            repeat(3 - available) { sb.append('=') }
        }
        return sb.toString()
    }

    /** Convenience: base64-encode the UTF-8 bytes of [s]. */
    fun base64encode(s: String): String = base64encode(s.encodeToByteArray())
}
