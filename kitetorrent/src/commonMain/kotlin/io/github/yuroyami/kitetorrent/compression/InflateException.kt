package io.github.yuroyami.kitetorrent.compression

/**
 * Error codes for DEFLATE / gzip inflation, a direct port of the numeric return
 * values of libtorrent's `puff()` (src/puff.cpp) and the matching
 * `gzip_errors::error_code_enum` (include/libtorrent/gzip.hpp).
 *
 * The integer [code] is exactly the value `puff()` returns (plus two gzip-level
 * codes that `puff()` itself never produces), so anyone cross-referencing the
 * C++ source can map an exception straight back to the original branch. The
 * [message] strings are copied verbatim from `gzip_error_category::message`
 * (src/gzip.cpp) so behaviour matches libtorrent's diagnostics.
 *
 * Note on numbering: libtorrent's `gzip_error_category` re-maps puff's negative
 * codes onto a *positive* enum (its `inflate_gzip` switch turns puff -1..-11
 * into `invalid_block_type` .. `distance_too_far_back_in_block`). Here we keep
 * puff's own signed codes because they're the ground truth of the algorithm;
 * the human-readable text is what libtorrent shows to callers.
 */
enum class InflateError(val code: Int, val message: String) {
    /** puff 2: `bits()`/`decode()` ran off the end of the input (longjmp path). */
    DATA_DID_NOT_TERMINATE(2, "available inflate data did not terminate"),

    /** puff 1: only reachable with a bounded output buffer; we grow instead. */
    SPACE_EXHAUSTED(1, "output space exhausted before completing inflate"),

    /** puff -1: block type field was 3 (reserved). */
    INVALID_BLOCK_TYPE(-1, "invalid block type (type == 3)"),

    /** puff -2: stored block LEN did not match ~LEN. */
    INVALID_STORED_BLOCK_LENGTH(-2, "stored block length did not match one's complement"),

    /** puff -3: dynamic header declared more codes than the format allows. */
    TOO_MANY_LENGTH_OR_DISTANCE_CODES(-3, "dynamic block code description: too many length or distance codes"),

    /** puff -4: the code-length code itself was not a complete set. */
    CODE_LENGTHS_CODES_INCOMPLETE(-4, "dynamic block code description: code lengths codes incomplete"),

    /** puff -5: a repeat-previous (16) instruction with no previous length. */
    REPEAT_LENGTHS_WITH_NO_FIRST_LENGTH(-5, "dynamic block code description: repeat lengths with no first length"),

    /** puff -6: a run-length instruction overran the declared symbol count. */
    REPEAT_MORE_THAN_SPECIFIED_LENGTHS(-6, "dynamic block code description: repeat more than specified lengths"),

    /** puff -7: literal/length code lengths formed an invalid (over/incomplete) set. */
    INVALID_LITERAL_LENGTH_CODE_LENGTHS(-7, "dynamic block code description: invalid literal/length code lengths"),

    /** puff -8: distance code lengths formed an invalid set. */
    INVALID_DISTANCE_CODE_LENGTHS(-8, "dynamic block code description: invalid distance code lengths"),

    /** puff -9: the dynamic block had no end-of-block symbol. */
    MISSING_END_OF_BLOCK_CODE(-9, "dynamic block code description: missing end-of-block code"),

    /** puff -10: the decoder hit an invalid Huffman code in a fixed/dynamic block. */
    INVALID_LITERAL_OR_DISTANCE_CODE(-10, "invalid literal/length or distance code in fixed or dynamic block"),

    /** puff -11: a back-reference distance pointed before the start of output. */
    DISTANCE_TOO_FAR_BACK(-11, "distance is too far back in fixed or dynamic block"),

    /** gzip-level: the gzip header magic/method/flags were malformed. */
    INVALID_GZIP_HEADER(100, "invalid gzip header"),

    /** gzip-level: inflated output would exceed the caller-supplied maximum. */
    INFLATED_DATA_TOO_LARGE(101, "inflated data too large"),

    /** Catch-all, matching libtorrent's `unknown_gzip_error`. */
    UNKNOWN(999, "unknown gzip error"),
    ;

    companion object {
        /**
         * Map a raw `puff()` return value to the corresponding [InflateError].
         * Mirrors the `switch (ret)` in libtorrent's `inflate_gzip`.
         */
        fun fromPuff(code: Int): InflateError = when (code) {
            2 -> DATA_DID_NOT_TERMINATE
            1 -> SPACE_EXHAUSTED
            -1 -> INVALID_BLOCK_TYPE
            -2 -> INVALID_STORED_BLOCK_LENGTH
            -3 -> TOO_MANY_LENGTH_OR_DISTANCE_CODES
            -4 -> CODE_LENGTHS_CODES_INCOMPLETE
            -5 -> REPEAT_LENGTHS_WITH_NO_FIRST_LENGTH
            -6 -> REPEAT_MORE_THAN_SPECIFIED_LENGTHS
            -7 -> INVALID_LITERAL_LENGTH_CODE_LENGTHS
            -8 -> INVALID_DISTANCE_CODE_LENGTHS
            -9 -> MISSING_END_OF_BLOCK_CODE
            -10 -> INVALID_LITERAL_OR_DISTANCE_CODE
            -11 -> DISTANCE_TOO_FAR_BACK
            else -> UNKNOWN
        }
    }
}

/**
 * Thrown by [Inflate] and [Gzip] when decompression fails. Carries the
 * faithful libtorrent [error] so callers can branch on the exact failure mode
 * (e.g. distinguish a bad gzip header from a truncated stream).
 */
class InflateException(val error: InflateError) : Exception(error.message)
