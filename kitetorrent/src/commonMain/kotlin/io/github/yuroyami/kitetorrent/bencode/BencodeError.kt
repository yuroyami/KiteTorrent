package io.github.yuroyami.kitetorrent.bencode

/**
 * Bdecode error codes — a direct port of libtorrent's `bdecode_errors::error_code_enum`
 * (bdecode.hpp). [BdecodeException] carries one of these plus the byte offset where
 * parsing failed, mirroring libtorrent's `error_code` + `error_pos`.
 */
enum class BdecodeError(val message: String) {
    NO_ERROR("no error"),
    EXPECTED_DIGIT("expected digit in bencoded string"),
    EXPECTED_COLON("expected colon in bencoded string"),
    UNEXPECTED_EOF("unexpected end of file in bencoded string"),
    EXPECTED_VALUE("expected value (list, dict, int or string) in bencoded string"),
    DEPTH_EXCEEDED("bencoded recursion depth limit exceeded"),
    LIMIT_EXCEEDED("bencoded item count limit exceeded"),
    OVERFLOW("integer overflow"),
}

/** Thrown by [Bdecode.decode] when the input is not valid bencoding. */
class BdecodeException(
    val error: BdecodeError,
    /** Byte offset into the buffer where the failure occurred. */
    val position: Int,
) : Exception("${error.message} (at byte $position)")
