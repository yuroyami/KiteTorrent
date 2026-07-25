package io.github.yuroyami.kitetorrent.compression

/**
 * Raw DEFLATE (RFC 1951) decompressor. This is a faithful pure-Kotlin port of Mark
 * Adler's `puff()` as bundled in libtorrent (src/puff.cpp, "puff" v2.3). puff
 * is the canonical, deliberately-simple reference implementation of inflate; it
 * trades speed for being an unambiguous specification of the format. libtorrent
 * ships it (instead of pulling in zlib) precisely so it can decode gzip'd HTTP
 * tracker and scrape responses with no external dependency. That is also our
 * situation in commonMain.
 *
 * Differences from the C++, all behaviour-preserving:
 *
 *  - puff writes into a caller-supplied fixed-size buffer and returns `1`
 *    ("output space exhausted") when it's too small; libtorrent's `inflate_gzip`
 *    then doubles the buffer and re-runs the whole thing from scratch. That
 *    resize dance is an allocation strategy, not part of DEFLATE, so we drop it
 *    and grow the output array on demand. The error code `1` therefore never
 *    occurs here, which is correct. The algorithm itself never fails for lack of
 *    output; only the bounded-buffer wrapper did.
 *
 *  - puff signals "ran past the end of input" via `setjmp`/`longjmp` from deep
 *    inside `bits()`/`decode()`, which the top-level `puff()` catches and turns
 *    into return code `2`. We model that non-local exit with a private
 *    [OutOfInput] throwable caught at the top of [inflate], mapping to the same
 *    `data did not terminate` error.
 *
 *  - puff's "scan only" mode (`dest == NULL`) is omitted; we always materialise
 *    the output. libtorrent never uses scan mode for gzip.
 *
 * Bit/byte ordering follows the format exactly: bits are consumed LSB-first
 * within each input byte; Huffman codes are read MSB-first and accumulated in
 * reversed order so canonical codes compare as plain integers (see [decode]).
 *
 * This object is stateless and thread-safe; all mutable inflate state lives in
 * the private [State] created per call.
 */
object Inflate {

    // Maximums fixed by the deflate format (puff.cpp #defines).
    private const val MAXBITS = 15        // maximum bits in a code
    private const val MAXLCODES = 286     // maximum number of literal/length codes
    private const val MAXDCODES = 30      // maximum number of distance codes
    private const val MAXCODES = MAXLCODES + MAXDCODES
    private const val FIXLCODES = 288     // number of fixed literal/length codes

    // Size base for length codes 257..285 (puff `lens[]`).
    private val LENS = shortArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    // Extra bits for length codes 257..285 (puff `lext[]`).
    private val LEXT = shortArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    // Offset base for distance codes 0..29 (puff `dists[]`).
    private val DISTS = shortArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
        8193, 12289, 16385, 24577,
    )
    // Extra bits for distance codes 0..29 (puff `dext[]`).
    private val DEXT = shortArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11,
        12, 12, 13, 13,
    )
    // Permutation of the code length codes in a dynamic block (puff `order[]`).
    private val ORDER = intArrayOf(
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
    )

    /**
     * Inflate a raw DEFLATE stream (no gzip/zlib wrapper) and return the
     * decompressed bytes.
     *
     * @throws InflateException with the faithful [InflateError] on malformed or
     *   truncated input. These are the same failure cases puff returns numerically.
     */
    fun inflate(input: ByteArray): ByteArray {
        val s = State(input)
        try {
            // Process blocks until the last block (puff()'s do/while).
            var last: Int
            do {
                last = s.bits(1)            // one if last block
                val type = s.bits(2)        // block type 0..3
                val err = when (type) {
                    0 -> stored(s)
                    1 -> fixed(s)
                    2 -> dynamic(s)
                    else -> InflateError.INVALID_BLOCK_TYPE.code   // type == 3
                }
                if (err != 0) throw InflateException(InflateError.fromPuff(err))
            } while (last == 0)
        } catch (_: OutOfInput) {
            // puff's longjmp(env, 1) -> top-level returns 2.
            throw InflateException(InflateError.DATA_DID_NOT_TERMINATE)
        }
        return s.output()
    }

    /** Non-local "out of input" signal, replacing puff's setjmp/longjmp. */
    private class OutOfInput : Throwable()

    /**
     * Per-call inflate state. Holds the input cursor + bit buffer and the
     * growable output. Mirrors puff's `struct state`, except [out] grows instead
     * of being a fixed buffer, so there is no `outlen` overflow path.
     */
    private class State(private val inBuf: ByteArray) {
        // input state
        private var incnt = 0           // bytes read so far
        var bitbuf = 0                  // bit buffer
        var bitcnt = 0                  // number of valid low bits in bitbuf

        // output state (growable)
        private var out = ByteArray(256)
        var outcnt = 0
            private set

        val inLen: Int get() = inBuf.size
        val inAvailable: Int get() = inBuf.size - incnt

        fun output(): ByteArray = out.copyOf(outcnt)

        /** Read one input byte (unsigned 0..255), or signal end-of-input. */
        fun nextByte(): Int {
            if (incnt == inBuf.size) throw OutOfInput()
            return inBuf[incnt++].toInt() and 0xff
        }

        /** Peek-free raw byte read used by [stored] (with its own bounds check). */
        fun rawByte(): Int = inBuf[incnt++].toInt() and 0xff

        fun skip(n: Int) { incnt += n }

        /**
         * Return [need] bits from the input stream (puff `bits()`). Bits are
         * pulled LSB-first; this always leaves fewer than eight bits buffered.
         * Works for need == 0.
         */
        fun bits(need: Int): Int {
            // load at least `need` bits into a 32-bit accumulator
            var value = bitbuf
            while (bitcnt < need) {
                value = value or (nextByte() shl bitcnt)
                bitcnt += 8
            }
            // drop `need` bits, keep 0..7 left
            bitbuf = value ushr need
            bitcnt -= need
            // return the low `need` bits
            return value and ((1 shl need) - 1)
        }

        // --- output helpers (the part that differs from fixed-buffer puff) ---

        fun ensure(extra: Int) {
            val needed = outcnt + extra
            if (needed > out.size) {
                var cap = if (out.size == 0) 256 else out.size
                while (cap < needed) cap = cap shl 1
                out = out.copyOf(cap)
            }
        }

        fun writeByte(b: Int) {
            ensure(1)
            out[outcnt++] = b.toByte()
        }

        /** Copy [len] bytes from [dist] back in the output (LZ77 back-reference). */
        fun copyBack(dist: Int, len: Int) {
            ensure(len)
            var src = outcnt - dist
            var n = len
            // simple forward byte copy: correct for overlapping runs, unlike memcpy
            while (n-- > 0) {
                out[outcnt++] = out[src++]
            }
        }

        /** Copy [len] verbatim bytes straight from the input (stored block). */
        fun copyStored(len: Int) {
            ensure(len)
            var n = len
            while (n-- > 0) {
                out[outcnt++] = inBuf[incnt++]
            }
        }
    }

    /**
     * A canonical Huffman decode table (puff `struct huffman`): [count] holds the
     * number of symbols of each bit length, [symbol] holds the symbols sorted by
     * length (and by value within a length).
     */
    private class Huffman(maxSymbols: Int) {
        val count = ShortArray(MAXBITS + 1)
        val symbol = ShortArray(maxSymbols)
    }

    /**
     * Process a stored (uncompressed) block (puff `stored()`). Discards the
     * remaining bits of the current byte, reads LEN / ~LEN, verifies the
     * complement, then copies LEN bytes verbatim. Returns 0 on success or a
     * negative/positive puff error code.
     */
    private fun stored(s: State): Int {
        // discard leftover bits from current byte
        s.bitbuf = 0
        s.bitcnt = 0

        // need 4 header bytes
        if (s.inAvailable < 4) return InflateError.DATA_DID_NOT_TERMINATE.code
        var len = s.rawByte()
        len = len or (s.rawByte() shl 8)
        val nlen0 = s.rawByte()
        val nlen1 = s.rawByte()
        if (nlen0 != (len.inv() and 0xff) || nlen1 != ((len.inv() ushr 8) and 0xff)) {
            return InflateError.INVALID_STORED_BLOCK_LENGTH.code   // -2
        }

        // copy len bytes from in to out
        if (s.inAvailable < len) return InflateError.DATA_DID_NOT_TERMINATE.code
        s.copyStored(len)
        return 0
    }

    /**
     * Decode a single Huffman symbol from the stream using table [h] (puff's fast
     * `decode()`). Returns the symbol, or -10 if an invalid/incomplete code is
     * hit after consuming MAXBITS bits.
     *
     * Codes are stored bit-reversed in the stream, so we accumulate `code` one
     * bit at a time (building it in reverse) and compare against the running
     * `first` code for the current length. This lets canonical codes resolve with
     * plain integer comparisons.
     */
    private fun decode(s: State, h: Huffman): Int {
        var bitbuf = s.bitbuf
        var left = s.bitcnt           // bits currently buffered
        var code = 0                  // running reversed code value
        var first = 0                 // first code of the current length
        var index = 0                 // index into symbol[] of first code of this length
        var len = 1                   // current code length
        var nextIdx = 1               // index into h.count for the current length

        while (true) {
            while (left > 0) {
                left--
                code = code or (bitbuf and 1)
                bitbuf = bitbuf ushr 1
                val count = h.count[nextIdx++].toInt()
                if (code - count < first) {            // code fits this length -> symbol
                    s.bitbuf = bitbuf
                    s.bitcnt = (s.bitcnt - len) and 7
                    return h.symbol[index + (code - first)].toInt()
                }
                index += count                          // advance to next length
                first += count
                first = first shl 1
                code = code shl 1
                len++
            }
            left = (MAXBITS + 1) - len
            if (left == 0) break
            // need another input byte to keep decoding (puff longjmp on EOF)
            bitbuf = s.nextByte()
            if (left > 8) left = 8
        }
        return InflateError.INVALID_LITERAL_OR_DISTANCE_CODE.code   // -10: ran out of codes
    }

    /**
     * Build the decode tables in [h] from the per-symbol code [length]s for the
     * first [n] symbols (puff `construct()`). Returns 0 for a complete code,
     * negative for an over-subscribed code (must not be used), positive for an
     * incomplete code (usable, but [decode] may report -10).
     *
     * As in puff, `count[0]` is left holding the number of symbols with no code
     * (length 0); the dynamic-block validation relies on that.
     */
    private fun construct(h: Huffman, length: ShortArray, n: Int): Int {
        // count number of codes of each length
        for (len in 0..MAXBITS) h.count[len] = 0
        for (symbol in 0 until n) h.count[length[symbol].toInt()]++
        if (h.count[0].toInt() == n) return 0           // no codes: complete, but decode() fails

        // check for over-subscribed or incomplete set
        var left = 1                                    // one possible code of zero length
        for (len in 1..MAXBITS) {
            left = left shl 1                           // one more bit, double codes left
            left -= h.count[len].toInt()                // deduct used codes
            if (left < 0) return left                   // over-subscribed -> negative
        }                                               // left > 0 means incomplete

        // generate offsets into the symbol table for each length
        val offs = ShortArray(MAXBITS + 1)
        offs[1] = 0
        for (len in 1 until MAXBITS) {
            offs[len + 1] = (offs[len] + h.count[len]).toShort()
        }

        // place symbols in the table, sorted by length then by symbol value
        for (symbol in 0 until n) {
            val l = length[symbol].toInt()
            if (l != 0) {
                h.symbol[offs[l].toInt()] = symbol.toShort()
                offs[l]++
            }
        }
        return left
    }

    /**
     * Decode literal/length and distance codes until the end-of-block symbol
     * (puff `codes()`), using [lencode] for literals+lengths and [distcode] for
     * distances. Emits literals directly and resolves length/distance pairs into
     * LZ77 back-copies. Returns 0 on success or a negative puff error code.
     */
    private fun codes(s: State, lencode: Huffman, distcode: Huffman): Int {
        var symbol: Int
        do {
            symbol = decode(s, lencode)
            if (symbol < 0) return symbol                       // invalid symbol
            when {
                symbol < 256 -> {                               // literal byte
                    s.writeByte(symbol)
                }
                symbol > 256 -> {                               // length/distance pair
                    var sym = symbol - 257
                    if (sym >= 29) return InflateError.INVALID_LITERAL_OR_DISTANCE_CODE.code // -10
                    val len = LENS[sym].toInt() + s.bits(LEXT[sym].toInt())

                    sym = decode(s, distcode)
                    if (sym < 0) return sym                      // invalid symbol
                    val dist = DISTS[sym].toInt() + s.bits(DEXT[sym].toInt())
                    if (dist > s.outcnt) return InflateError.DISTANCE_TOO_FAR_BACK.code // -11
                    s.copyBack(dist, len)
                }
                // symbol == 256 -> end of block, loop terminates
            }
        } while (symbol != 256)
        return 0
    }

    /**
     * Process a fixed-Huffman block (puff `fixed()`). The literal/length and
     * distance code lengths are defined by the spec; we build the tables fresh
     * each call (cheap, and avoids puff's `static`/"may not be thread safe"
     * caveat).
     */
    private fun fixed(s: State): Int {
        val lengths = ShortArray(FIXLCODES)
        // literal/length table: 0..143 -> 8, 144..255 -> 9, 256..279 -> 7, 280..287 -> 8
        var symbol = 0
        while (symbol < 144) { lengths[symbol] = 8; symbol++ }
        while (symbol < 256) { lengths[symbol] = 9; symbol++ }
        while (symbol < 280) { lengths[symbol] = 7; symbol++ }
        while (symbol < FIXLCODES) { lengths[symbol] = 8; symbol++ }
        val lencode = Huffman(FIXLCODES)
        construct(lencode, lengths, FIXLCODES)

        // distance table: all 30 codes are length 5
        for (i in 0 until MAXDCODES) lengths[i] = 5
        val distcode = Huffman(MAXDCODES)
        construct(distcode, lengths, MAXDCODES)

        return codes(s, lencode, distcode)
    }

    /**
     * Process a dynamic-Huffman block (puff `dynamic()`). Reads the three counts,
     * the code-length code, then the run-length-encoded list of literal/length +
     * distance code lengths, builds both Huffman tables (with the spec's
     * tolerance for a single one-symbol incomplete code), and finally decodes the
     * data. Returns 0 on success or a negative puff error code.
     */
    private fun dynamic(s: State): Int {
        // descriptor code lengths: first nlen are literal/length, then ndist distance
        val lengths = ShortArray(MAXCODES)

        val nlen = s.bits(5) + 257
        val ndist = s.bits(5) + 1
        val ncode = s.bits(4) + 4
        if (nlen > MAXLCODES || ndist > MAXDCODES) {
            return InflateError.TOO_MANY_LENGTH_OR_DISTANCE_CODES.code   // -3
        }

        // read the code length code lengths (3 bits each, in permuted order)
        var index = 0
        while (index < ncode) {
            lengths[ORDER[index]] = s.bits(3).toShort()
            index++
        }
        while (index < 19) {
            lengths[ORDER[index]] = 0
            index++
        }

        // build the code-length Huffman code (reuse lencode storage); must be complete
        val lencode = Huffman(MAXLCODES)
        var err = construct(lencode, lengths, 19)
        if (err != 0) return InflateError.CODE_LENGTHS_CODES_INCOMPLETE.code   // -4

        // read the literal/length and distance code length list
        index = 0
        while (index < nlen + ndist) {
            var symbol = decode(s, lencode)
            if (symbol < 0) return symbol                       // invalid symbol
            if (symbol < 16) {                                  // a direct length 0..15
                lengths[index++] = symbol.toShort()
            } else {                                            // a run-length instruction
                var repeatLen = 0                               // length value to repeat (0 = zeros)
                when (symbol) {
                    16 -> {                                     // repeat previous length 3..6 times
                        if (index == 0) return InflateError.REPEAT_LENGTHS_WITH_NO_FIRST_LENGTH.code // -5
                        repeatLen = lengths[index - 1].toInt()
                        symbol = 3 + s.bits(2)
                    }
                    17 -> symbol = 3 + s.bits(3)                // repeat zero 3..10 times
                    else -> symbol = 11 + s.bits(7)             // == 18: repeat zero 11..138 times
                }
                if (index + symbol > nlen + ndist) {
                    return InflateError.REPEAT_MORE_THAN_SPECIFIED_LENGTHS.code   // -6
                }
                while (symbol-- > 0) lengths[index++] = repeatLen.toShort()
            }
        }

        // there must be an end-of-block code
        if (lengths[256].toInt() == 0) return InflateError.MISSING_END_OF_BLOCK_CODE.code   // -9

        // build literal/length table; incomplete is only OK for a single length-1 code
        err = construct(lencode, lengths, nlen)
        if (err != 0 && (err < 0 || nlen != lencode.count[0].toInt() + lencode.count[1].toInt())) {
            return InflateError.INVALID_LITERAL_LENGTH_CODE_LENGTHS.code   // -7
        }

        // build distance table from the tail of the lengths list
        val distLengths = ShortArray(MAXDCODES)
        for (i in 0 until ndist) distLengths[i] = lengths[nlen + i]
        val distcode = Huffman(MAXDCODES)
        err = construct(distcode, distLengths, ndist)
        if (err != 0 && (err < 0 || ndist != distcode.count[0].toInt() + distcode.count[1].toInt())) {
            return InflateError.INVALID_DISTANCE_CODE_LENGTHS.code   // -8
        }

        return codes(s, lencode, distcode)
    }
}
