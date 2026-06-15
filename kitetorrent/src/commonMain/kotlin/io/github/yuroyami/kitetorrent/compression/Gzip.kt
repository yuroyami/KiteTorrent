package io.github.yuroyami.kitetorrent.compression

/**
 * gzip (RFC 1952) / zlib (RFC 1950) wrapper handling on top of [Inflate] — a
 * pure-Kotlin port of libtorrent's `inflate_gzip` + `gzip_header` (src/gzip.cpp).
 *
 * BitTorrent HTTP trackers and scrape endpoints frequently return their bencoded
 * responses `Content-Encoding: gzip`, so the client has to strip the wrapper and
 * inflate the raw DEFLATE payload. That is the sole job of this object.
 *
 * libtorrent only handles gzip. We additionally accept a bare zlib stream,
 * because some servers send `Content-Encoding: deflate` as a zlib stream rather
 * than raw DEFLATE, and detecting it is cheap and unambiguous (the zlib CMF/FLG
 * pair has a defined check). This is a small, clearly-marked extension; the gzip
 * path is byte-for-byte faithful to `gzip_header`.
 *
 * Errors surface as [InflateException] carrying the faithful libtorrent error
 * code — e.g. [InflateError.INVALID_GZIP_HEADER] for a malformed header,
 * [InflateError.INFLATED_DATA_TOO_LARGE] when a size cap is exceeded.
 */
object Gzip {

    // gzip header flag bits (gzip.cpp anonymous enum).
    private const val FHCRC = 0x02
    private const val FEXTRA = 0x04
    private const val FNAME = 0x08
    private const val FCOMMENT = 0x10
    private const val FRESERVED = 0xe0

    private const val GZIP_MAGIC0 = 0x1f
    private const val GZIP_MAGIC1 = 0x8b

    /** Default cap mirroring libtorrent callers (tracker responses are small). */
    const val DEFAULT_MAXIMUM_SIZE = 4 * 1024 * 1024

    /**
     * Decompress a gzip (or zlib) wrapped DEFLATE buffer and return the inflated
     * bytes.
     *
     * @param input the full gzip/zlib stream.
     * @param maximumSize reject output larger than this many bytes (libtorrent's
     *   `maximum_size`). Must be positive.
     * @throws InflateException with [InflateError.INVALID_GZIP_HEADER] if neither
     *   a valid gzip nor zlib header is present, [InflateError.INFLATED_DATA_TOO_LARGE]
     *   if the cap is hit, or one of the DEFLATE-level errors from [Inflate].
     */
    fun decompress(input: ByteArray, maximumSize: Int = DEFAULT_MAXIMUM_SIZE): ByteArray {
        require(maximumSize > 0) { "maximumSize must be > 0" }

        val headerLen = headerLength(input)
        if (headerLen < 0) throw InflateException(InflateError.INVALID_GZIP_HEADER)

        // Inflate the raw DEFLATE payload that follows the wrapper. (gzip's 8-byte
        // CRC32+ISIZE trailer and zlib's 4-byte Adler-32 trailer are ignored, as
        // in libtorrent — puff stops at the final DEFLATE block and never reads
        // them.)
        val payload = input.copyOfRange(headerLen, input.size)
        val out = Inflate.inflate(payload)

        if (out.size > maximumSize) {
            throw InflateException(InflateError.INFLATED_DATA_TOO_LARGE)
        }
        return out
    }

    /**
     * Compute the wrapper header length, or -1 if the buffer has no valid header.
     *
     * Tries gzip first (faithful to [gzipHeader]); if that fails, falls back to a
     * zlib stream check. A valid zlib stream has a 2-byte header that is the
     * payload offset.
     */
    private fun headerLength(input: ByteArray): Int {
        val gz = gzipHeader(input)
        if (gz >= 0) return gz
        return zlibHeader(input)
    }

    /**
     * Faithful port of libtorrent's `gzip_header` (src/gzip.cpp): validate the
     * gzip magic / method / flags and skip the variable-length optional fields
     * (FEXTRA, FNAME, FCOMMENT, FHCRC). Returns the number of header bytes, or -1
     * if the header is invalid or truncated.
     */
    private fun gzipHeader(input: ByteArray): Int {
        // The gzip header cannot be shorter than 10 bytes.
        if (input.size < 10) return -1

        fun u(i: Int): Int = input[i].toInt() and 0xff

        // check the magic header of gzip
        if (u(0) != GZIP_MAGIC0 || u(1) != GZIP_MAGIC1) return -1

        val method = u(2)
        val flags = u(3)

        // only DEFLATE (method 8) is supported, and the reserved flags must be clear
        if (method != 8 || (flags and FRESERVED) != 0) return -1

        // The first 10 bytes are fixed:
        //   ID1 ID2 CM FLG  MTIME(4)  XFL OS
        // `pos` is our cursor into the buffer; `remaining()` is bytes left from it.
        var pos = 10
        fun remaining(): Int = input.size - pos

        if (flags and FEXTRA != 0) {
            if (remaining() < 2) return -1
            // XLEN is little-endian: (buffer[1] << 8) | buffer[0]
            val extraLen = (u(pos + 1) shl 8) or u(pos)
            if (remaining() < extraLen + 2) return -1
            pos += extraLen + 2
        }

        if (flags and FNAME != 0) {
            if (remaining() == 0) return -1
            while (u(pos) != 0) {
                pos++
                if (remaining() == 0) return -1
            }
            pos++ // consume the NUL terminator
        }

        if (flags and FCOMMENT != 0) {
            if (remaining() == 0) return -1
            while (u(pos) != 0) {
                pos++
                if (remaining() == 0) return -1
            }
            pos++ // consume the NUL terminator
        }

        if (flags and FHCRC != 0) {
            if (remaining() < 2) return -1
            pos += 2
        }

        return pos
    }

    /**
     * Detect a zlib (RFC 1950) stream and return its 2-byte header length, or -1.
     *
     * A zlib stream begins with CMF (compression method+info) and FLG (flags).
     * For the only method DEFLATE defines (CM == 8) with a valid window
     * (CINFO <= 7), the pair must satisfy `(CMF*256 + FLG) % 31 == 0`, and FDICT
     * (bit 5 of FLG) must be clear since we don't carry a preset dictionary.
     *
     * Extension beyond libtorrent (which is gzip-only) — see the class KDoc.
     */
    private fun zlibHeader(input: ByteArray): Int {
        if (input.size < 2) return -1
        val cmf = input[0].toInt() and 0xff
        val flg = input[1].toInt() and 0xff

        val method = cmf and 0x0f
        val cinfo = (cmf ushr 4) and 0x0f
        if (method != 8 || cinfo > 7) return -1            // not DEFLATE / bad window
        if ((flg and 0x20) != 0) return -1                 // FDICT set: unsupported
        if (((cmf shl 8) or flg) % 31 != 0) return -1      // header checksum

        return 2
    }
}
