package io.github.yuroyami.kitetorrent.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Vectors for the string/encoding utilities ported from libtorrent's
 * `escape_string.cpp` / `string_util.cpp`.
 *
 * The base32 cases are the canonical RFC 4648 §10 test vectors; the base64 cases are
 * the RFC 4648 §10 base64 vectors. The url-escape cases are hand-derived from
 * libtorrent's `unreserved_chars` safe set. If these pass, magnet-link and tracker-URL
 * encoding is byte-for-byte compatible with libtorrent.
 */
class StringEncodingTest {

    private fun ascii(s: String) = ByteArray(s.length) { s[it].code.toByte() }

    // ---- Base32 (RFC 4648) ----------------------------------------------------

    @Test
    fun base32_encode_rfc4648_vectors() {
        assertEquals("", Base32.encode(ascii("")))
        assertEquals("MY======", Base32.encode(ascii("f")))
        assertEquals("MZXQ====", Base32.encode(ascii("fo")))
        assertEquals("MZXW6===", Base32.encode(ascii("foo")))
        assertEquals("MZXW6YQ=", Base32.encode(ascii("foob")))
        assertEquals("MZXW6YTB", Base32.encode(ascii("fooba")))
        assertEquals("MZXW6YTBOI======", Base32.encode(ascii("foobar")))
    }

    @Test
    fun base32_decode_rfc4648_vectors() {
        assertContentEquals(ascii(""), Base32.decode(""))
        assertContentEquals(ascii("f"), Base32.decode("MY======"))
        assertContentEquals(ascii("fo"), Base32.decode("MZXQ===="))
        assertContentEquals(ascii("foo"), Base32.decode("MZXW6==="))
        assertContentEquals(ascii("foob"), Base32.decode("MZXW6YQ="))
        assertContentEquals(ascii("fooba"), Base32.decode("MZXW6YTB"))
        assertContentEquals(ascii("foobar"), Base32.decode("MZXW6YTBOI======"))
    }

    @Test
    fun base32_roundtrip_binary() {
        // 20-byte info-hash-shaped payload encodes to 32 chars and round-trips.
        val info = ByteArray(20) { (it * 11 + 7).toByte() }
        val enc = Base32.encode(info)
        assertEquals(32, enc.length) // 20 bytes = 4 full 5-byte groups -> 4*8 = 32 chars, no padding
        assertContentEquals(info, Base32.decode(enc))
    }

    @Test
    fun base32_decode_is_lenient_like_libtorrent() {
        // lowercase decodes the same as uppercase (C++ upper-cases the input first)
        assertContentEquals(ascii("foobar"), Base32.decode("mzxw6ytboi======"))
        // libtorrent maps the ambiguous digit '1' to the letter 'I'
        assertContentEquals(Base32.decode("MI======"), Base32.decode("M1======"))
        // an out-of-alphabet character yields empty (C++ returns std::string())
        assertContentEquals(ByteArray(0), Base32.decode("!!!!"))
        // ...and null via the *OrNull variant so callers can tell malformed from empty
        assertEquals(null, Base32.decodeOrNull("!!!!"))
    }

    @Test
    fun base32_i2p_alphabet_is_lowercase_unpadded() {
        // i2p uses lowercase a-z2-7 and emits no padding
        assertEquals("my", Base32.encodeI2p(ascii("f")))
        assertEquals("mzxw6ytboi", Base32.encodeI2p(ascii("foobar")))
    }

    // ---- URL escape -----------------------------------------------------------

    @Test
    fun url_escape_string_encodes_reserved_and_slash() {
        // escape_string (offset 11) escapes space AND '/'
        assertEquals("a%20b%2fc", UrlEscape.escape("a b/c"))
        // the unreserved special set is preserved verbatim
        assertEquals("foo.bar~baz-qux_1!", UrlEscape.escape("foo.bar~baz-qux_1!"))
        // '(' ')' '*' kept; '&' '=' '?' ':' escaped
        assertEquals("(a)*%3d%26%3f%3a", UrlEscape.escape("(a)*=&?:"))
    }

    @Test
    fun url_escape_path_keeps_slash() {
        // escape_path (offset 10) keeps '/', still escapes space
        assertEquals("a%20b/c", UrlEscape.escapePath("a b/c"))
        assertEquals("/path/to/file", UrlEscape.escapePath("/path/to/file"))
    }

    @Test
    fun url_escape_binary_infohash() {
        // a binary info-hash escapes every byte (none are in the safe set)
        val h = ByteArray(20) { 0x12.toByte() }
        assertEquals("%12".repeat(20), UrlEscape.escapeString(h))
        // NUL is always escaped, never passed through
        assertEquals("%00", UrlEscape.escapeString(byteArrayOf(0)))
    }

    @Test
    fun url_unescape_roundtrip_and_plus() {
        assertEquals("a b/c", UrlEscape.unescape("a%20b%2fc"))
        assertEquals("a b/c", UrlEscape.unescape("a%20b%2Fc")) // uppercase hex too
        // '+' decodes to a space
        assertEquals("a b c", UrlEscape.unescape("a+b+c"))
        // escape -> unescape round-trips arbitrary text
        val s = "héllo world/?&=:"
        assertEquals(s, UrlEscape.unescape(UrlEscape.escape(s)))
    }

    @Test
    fun url_unescape_stops_on_malformed() {
        // a '%' at end of input: decoding stops, returns what was accumulated
        assertEquals("ab", UrlEscape.unescape("ab%"))
        // non-hex after '%': stops at the '%'
        assertEquals("ab", UrlEscape.unescape("ab%zz"))
    }

    @Test
    fun url_needs_encoding() {
        // reserved chars and %,+ are considered fine
        assertFalse(UrlEscape.needsEncoding("http://x.com/a?b=c&d=e"))
        assertFalse(UrlEscape.needsEncoding("already%20encoded"))
        // a raw space or other illegal char needs encoding
        assertTrue(UrlEscape.needsEncoding("has space"))
        assertTrue(UrlEscape.needsEncoding("quote'here"))
    }

    // ---- StringUtil -----------------------------------------------------------

    @Test
    fun base64_rfc4648_vectors() {
        assertEquals("", StringUtil.base64encode(ascii("")))
        assertEquals("Zg==", StringUtil.base64encode(ascii("f")))
        assertEquals("Zm8=", StringUtil.base64encode(ascii("fo")))
        assertEquals("Zm9v", StringUtil.base64encode(ascii("foo")))
        assertEquals("Zm9vYg==", StringUtil.base64encode(ascii("foob")))
        assertEquals("Zm9vYmE=", StringUtil.base64encode(ascii("fooba")))
        assertEquals("Zm9vYmFy", StringUtil.base64encode(ascii("foobar")))
        assertEquals("TWFu", StringUtil.base64encode(ascii("Man")))
    }

    @Test
    fun ctype_predicates() {
        assertTrue(StringUtil.isAlpha('a'))
        assertTrue(StringUtil.isAlpha('Z'))
        assertFalse(StringUtil.isAlpha('0'))
        assertTrue(StringUtil.isDigit('7'))
        assertFalse(StringUtil.isDigit('a'))
        assertTrue(StringUtil.isPrint(' '))
        assertTrue(StringUtil.isPrint('~'))
        assertFalse(StringUtil.isPrint('\n'))
        assertTrue(StringUtil.isSpace(' '))
        assertTrue(StringUtil.isSpace('\t'))
        assertTrue(StringUtil.isSpace('\u000C')) // form-feed
        assertTrue(StringUtil.isSpace('\u000B')) // vertical-tab
        assertFalse(StringUtil.isSpace('x'))
        assertEquals('a', StringUtil.toLower('A'))
        assertEquals('z', StringUtil.toLower('z'))
        assertEquals("abc!", StringUtil.toLowerAscii("ABc!"))
    }

    @Test
    fun to_string_locale_independent() {
        assertEquals("0", StringUtil.toString(0))
        assertEquals("12345", StringUtil.toString(12345))
        assertEquals("-12345", StringUtil.toString(-12345))
        assertEquals("9223372036854775807", StringUtil.toString(Long.MAX_VALUE))
        // the MIN_VALUE edge case the C++ specifically guards against
        assertEquals("-9223372036854775808", StringUtil.toString(Long.MIN_VALUE))
    }

    @Test
    fun no_case_compares() {
        assertTrue(StringUtil.stringEqualNoCase("HeLLo", "hello"))
        assertFalse(StringUtil.stringEqualNoCase("Hello", "Help"))
        assertTrue(StringUtil.stringBeginsNoCase("HTTP", "http://x"))
        assertFalse(StringUtil.stringBeginsNoCase("https", "http://x"))
        assertTrue(StringUtil.stringEndsWith("file.torrent", ".torrent"))
        assertFalse(StringUtil.stringEndsWith("file.txt", ".torrent"))
    }

    @Test
    fun strip_and_split() {
        assertEquals("hello", StringUtil.stripString("  \t hello \n "))
        assertEquals("a" to "b,c", StringUtil.splitString("a,b,c", ','))
        assertEquals("nosep" to "", StringUtil.splitString("nosep", ','))
        // quoted split ignores the separator inside the quotes
        assertEquals("\"a,b\"" to "c", StringUtil.splitStringQuotes("\"a,b\",c", ','))
        assertEquals(
            listOf("eth0", "eth1", "127.0.0.1"),
            StringUtil.parseCommaSeparatedString("eth0, eth1, 127.0.0.1"),
        )
    }

    @Test
    fun path_and_slash_helpers() {
        assertEquals("a/b/c", StringUtil.convertPathToPosix("a\\b\\c"))
        assertEquals("dir/", StringUtil.ensureTrailingSlash("dir"))
        assertEquals("dir/", StringUtil.ensureTrailingSlash("dir/"))
        assertEquals("/", StringUtil.ensureTrailingSlash(""))
    }
}
