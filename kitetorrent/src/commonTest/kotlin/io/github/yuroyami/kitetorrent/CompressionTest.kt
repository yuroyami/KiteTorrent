package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.compression.Gzip
import io.github.yuroyami.kitetorrent.compression.Inflate
import io.github.yuroyami.kitetorrent.compression.InflateError
import io.github.yuroyami.kitetorrent.compression.InflateException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Golden tests for the DEFLATE / gzip decompressor ([Inflate], [Gzip]).
 *
 * Every vector here was produced by the canonical zlib reference implementation
 * (Python's `zlib` / `gzip`, which wrap the same zlib C library libtorrent would
 * otherwise link) or hand-derived directly from RFC 1951, then verified to
 * round-trip. They cover all three DEFLATE block types (stored, fixed-Huffman and
 * dynamic-Huffman), plus the gzip and zlib wrappers and the main error paths.
 */
class CompressionTest {

    // --- helpers -----------------------------------------------------------

    /** Decode a lowercase-hex string (no separators) into bytes. */
    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = s[i * 2].digitToInt(16)
            val lo = s[i * 2 + 1].digitToInt(16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun str(bytes: ByteArray): String = bytes.decodeToString()

    // --- stored (uncompressed) block, hand-derived from RFC 1951 ----------

    @Test
    fun inflate_storedBlock() {
        // BFINAL=1, BTYPE=00 -> 0x01; LEN=2 (02 00); NLEN=~LEN (fd ff); data "Hi".
        val deflate = hex("010200fdff4869")
        assertEquals("Hi", str(Inflate.inflate(deflate)))
    }

    @Test
    fun inflate_emptyStoredBlock() {
        // BFINAL=1 BTYPE=00; LEN=0 (00 00); NLEN=ffff; no data -> empty output.
        val deflate = byteArrayOf(0x01, 0x00, 0x00, 0xff.toByte(), 0xff.toByte())
        assertContentEquals(ByteArray(0), Inflate.inflate(deflate))
        assertEquals(0, Inflate.inflate(hex("010000ffff")).size)
    }

    // --- fixed-Huffman block ----------------------------------------------

    @Test
    fun inflate_fixedHuffman_abc() {
        // zlib Z_FIXED strategy on "abc".
        val deflate = hex("4b4c4a0600")
        assertEquals("abc", str(Inflate.inflate(deflate)))
    }

    // --- dynamic-Huffman block (227 compressed bytes -> 1141 plaintext) ----

    @Test
    fun inflate_dynamicHuffman() {
        val deflate = hex(
            "7552410ec33008fb4abe9668d13a29dd2aad275edf354e8381ee122162c01897bae794dbb6e454b7efab7ddee951db2f" +
                "87f799d735a77282faa368bc3dc9054823d6bf7de9c5630030687de6853f914699ccb973789905125b628cc55e085136" +
                "a57aa203531c86ebd1bb7701452571d384b7403c24d8bdcee2c920168f266e12849dd110f546cb2b77e1a90913246d68" +
                "11bd95eaf2e7ac517493a96e9eb51ed9cdaa4492232495b891dac3d6b3c06a285a99f7bc93d2fb9cf3c4823a325a6931" +
                "11a6163c4909713dad25e30dbd35fcdaaa91b963203271611f9ea0deb08a3117a7f601"
        )
        val expected =
            "beta alpha epsilon delta delta gamma beta beta eta alpha alpha beta delta delta alpha " +
                "delta eta delta theta epsilon alpha gamma eta zeta epsilon gamma delta zeta beta beta eta " +
                "beta zeta zeta epsilon alpha theta beta eta beta epsilon zeta delta beta alpha delta " +
                "epsilon beta delta beta eta epsilon theta zeta gamma zeta zeta delta epsilon beta gamma " +
                "delta gamma theta eta epsilon delta zeta alpha delta alpha zeta eta epsilon beta delta " +
                "zeta delta theta eta theta gamma epsilon gamma delta epsilon eta eta zeta delta gamma " +
                "theta beta alpha beta gamma gamma eta beta eta eta theta epsilon alpha beta epsilon zeta " +
                "beta epsilon eta gamma theta alpha epsilon gamma beta epsilon delta gamma zeta gamma alpha " +
                "zeta theta alpha beta zeta epsilon delta alpha delta beta beta theta beta gamma gamma " +
                "theta gamma epsilon eta delta delta epsilon eta zeta theta theta beta delta delta beta " +
                "zeta alpha delta delta alpha beta alpha delta beta alpha zeta beta delta epsilon theta " +
                "delta gamma theta delta theta eta delta beta beta eta zeta eta eta theta alpha beta alpha " +
                "eta zeta beta delta delta delta theta gamma eta gamma epsilon theta delta beta theta beta"
        // (Reconstructed deterministically from the same seed used to build the vector.)
        val out = str(Inflate.inflate(deflate))
        assertEquals(1141, out.length)
        assertEquals(expected, out)
    }

    // --- gzip wrapper ------------------------------------------------------

    @Test
    fun gzip_helloWorld() {
        // `gzip` of "hello world" (mtime zeroed). 10-byte header, fixed-Huffman
        // payload, 8-byte CRC32+ISIZE trailer.
        val gz = hex(
            "1f8b080000000000" + "02ff" +
                "cb48cdc9c95728cf2fca490100" +
                "85114a0d0b000000"
        )
        assertEquals("hello world", str(Gzip.decompress(gz)))
    }

    // --- zlib wrapper (extension beyond stock libtorrent) -----------------

    @Test
    fun zlib_helloWorld() {
        // `zlib.compress("hello world", 9)`: 2-byte header (78 da), DEFLATE
        // payload, 4-byte Adler-32 trailer.
        val zb = hex("78dacb48cdc9c95728cf2fca4901001a0b045d")
        assertEquals("hello world", str(Gzip.decompress(zb)))
    }

    // --- error paths -------------------------------------------------------

    @Test
    fun inflate_invalidBlockType_throws() {
        // BFINAL=1, BTYPE=11 (reserved) -> 0b111 = 0x07.
        val ex = assertFailsWith<InflateException> {
            Inflate.inflate(byteArrayOf(0x07))
        }
        assertEquals(InflateError.INVALID_BLOCK_TYPE, ex.error)
    }

    @Test
    fun inflate_truncatedStored_throws() {
        // BTYPE=00 then a truncated length header -> "did not terminate".
        val ex = assertFailsWith<InflateException> {
            Inflate.inflate(byteArrayOf(0x01, 0x05))
        }
        assertEquals(InflateError.DATA_DID_NOT_TERMINATE, ex.error)
    }

    @Test
    fun inflate_storedBadComplement_throws() {
        // LEN=2 but NLEN is wrong (0000 instead of fdff).
        val ex = assertFailsWith<InflateException> {
            Inflate.inflate(hex("0102000000"))
        }
        assertEquals(InflateError.INVALID_STORED_BLOCK_LENGTH, ex.error)
    }

    @Test
    fun gzip_badHeader_throws() {
        // Wrong magic bytes, and too short to be a valid zlib stream either.
        val ex = assertFailsWith<InflateException> {
            Gzip.decompress(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09))
        }
        assertEquals(InflateError.INVALID_GZIP_HEADER, ex.error)
    }

    @Test
    fun gzip_exceedsMaximumSize_throws() {
        // "hello world" inflates to 11 bytes; cap at 5 -> rejected.
        val gz = hex(
            "1f8b080000000000" + "02ff" +
                "cb48cdc9c95728cf2fca490100" +
                "85114a0d0b000000"
        )
        val ex = assertFailsWith<InflateException> {
            Gzip.decompress(gz, maximumSize = 5)
        }
        assertEquals(InflateError.INFLATED_DATA_TOO_LARGE, ex.error)
    }
}
