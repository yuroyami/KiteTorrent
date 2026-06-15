package io.github.yuroyami.kitetorrent.bencode

/**
 * Bencode *decoder* — port of libtorrent's `bdecode()` (src/bdecode.cpp). Parses a
 * bencoded buffer into a [BdecodeNode] tree whose nodes reference back into the
 * original [ByteArray] (no copies of the payload are made until you ask for them).
 *
 * Faithful to libtorrent's limits and error semantics: a depth limit (default 100)
 * caps nesting, a token limit (default 2,000,000) caps total nodes, integers are
 * overflow-checked, and a dictionary key must be a string. libtorrent uses an
 * explicit stack to stay iterative; this port uses bounded recursion (≤ depthLimit
 * frames), which is simpler and equivalent for every real input.
 */
object Bdecode {

    const val DEFAULT_DEPTH_LIMIT = 100
    const val DEFAULT_TOKEN_LIMIT = 2_000_000

    /** Decode [buffer]; throws [BdecodeException] on malformed input. */
    fun decode(
        buffer: ByteArray,
        depthLimit: Int = DEFAULT_DEPTH_LIMIT,
        tokenLimit: Int = DEFAULT_TOKEN_LIMIT,
    ): BdecodeNode = Parser(buffer, depthLimit, tokenLimit).run {
        if (buffer.isEmpty()) fail(BdecodeError.UNEXPECTED_EOF, 0)
        val root = parseValue(0)
        root
    }

    /** Decode [buffer]; returns null instead of throwing on malformed input. */
    fun decodeOrNull(
        buffer: ByteArray,
        depthLimit: Int = DEFAULT_DEPTH_LIMIT,
        tokenLimit: Int = DEFAULT_TOKEN_LIMIT,
    ): BdecodeNode? = try {
        decode(buffer, depthLimit, tokenLimit)
    } catch (_: BdecodeException) {
        null
    }

    private class Parser(
        val buf: ByteArray,
        val depthLimit: Int,
        var tokensLeft: Int,
    ) {
        var pos = 0

        fun fail(e: BdecodeError, at: Int): Nothing = throw BdecodeException(e, at)

        private fun consumeToken(at: Int) {
            if (--tokensLeft < 0) fail(BdecodeError.LIMIT_EXCEEDED, at)
        }

        fun parseValue(depth: Int): BdecodeNode {
            if (depth >= depthLimit) fail(BdecodeError.DEPTH_EXCEEDED, pos)
            if (pos >= buf.size) fail(BdecodeError.UNEXPECTED_EOF, pos)
            consumeToken(pos)
            return when (val c = buf[pos].toInt()) {
                'i'.code -> parseInt()
                'l'.code -> parseList(depth)
                'd'.code -> parseDict(depth)
                else -> {
                    if (c in '0'.code..'9'.code) parseString()
                    else fail(BdecodeError.EXPECTED_VALUE, pos)
                }
            }
        }

        private fun parseInt(): BdecodeNode {
            val start = pos          // points at 'i'
            pos++                    // skip 'i'
            if (pos >= buf.size) fail(BdecodeError.UNEXPECTED_EOF, pos)
            var negative = false
            if (buf[pos].toInt() == '-'.code) { negative = true; pos++ }
            val digitsStart = pos
            var value = 0L
            while (pos < buf.size && buf[pos].toInt() != 'e'.code) {
                val d = buf[pos].toInt() - '0'.code
                if (d < 0 || d > 9) fail(BdecodeError.EXPECTED_DIGIT, pos)
                // overflow-checked accumulation (matches libtorrent's parse_int)
                if (value > Long.MAX_VALUE / 10) fail(BdecodeError.OVERFLOW, pos)
                value *= 10
                if (value > Long.MAX_VALUE - d) fail(BdecodeError.OVERFLOW, pos)
                value += d
                pos++
            }
            if (pos >= buf.size) fail(BdecodeError.UNEXPECTED_EOF, pos)   // no 'e'
            if (pos == digitsStart) fail(BdecodeError.EXPECTED_DIGIT, pos) // "ie" or "i-e"
            pos++                    // skip 'e'
            val result = if (negative) -value else value
            return BdecodeNode(BdecodeNode.Type.INT, buf, start, pos, intVal = result)
        }

        private fun parseString(): BdecodeNode {
            val start = pos          // points at first length digit
            var len = 0L
            while (pos < buf.size && buf[pos].toInt() != ':'.code) {
                val d = buf[pos].toInt() - '0'.code
                if (d < 0 || d > 9) fail(BdecodeError.EXPECTED_DIGIT, pos)
                if (len > Long.MAX_VALUE / 10) fail(BdecodeError.OVERFLOW, pos)
                len *= 10
                if (len > Long.MAX_VALUE - d) fail(BdecodeError.OVERFLOW, pos)
                len += d
                pos++
            }
            if (pos >= buf.size) fail(BdecodeError.EXPECTED_COLON, pos)
            // bytes available after the ':'
            val avail = buf.size - pos - 1
            if (len > avail) fail(BdecodeError.UNEXPECTED_EOF, pos)
            pos++                    // skip ':'
            val contentStart = pos
            pos += len.toInt()
            return BdecodeNode(
                BdecodeNode.Type.STRING, buf, start, pos,
                strStart = contentStart, strLen = len.toInt(),
            )
        }

        private fun parseList(depth: Int): BdecodeNode {
            val start = pos          // points at 'l'
            pos++                    // skip 'l'
            val items = ArrayList<BdecodeNode>()
            while (true) {
                if (pos >= buf.size) fail(BdecodeError.UNEXPECTED_EOF, pos)
                if (buf[pos].toInt() == 'e'.code) break
                items.add(parseValue(depth + 1))
            }
            pos++                    // skip 'e'
            return BdecodeNode(BdecodeNode.Type.LIST, buf, start, pos, items = items)
        }

        private fun parseDict(depth: Int): BdecodeNode {
            val start = pos          // points at 'd'
            pos++                    // skip 'd'
            val entries = ArrayList<BdecodeNode.DictEntry>()
            while (true) {
                if (pos >= buf.size) fail(BdecodeError.UNEXPECTED_EOF, pos)
                if (buf[pos].toInt() == 'e'.code) break
                // a dict key must be a string: only a digit may start it
                if (buf[pos].toInt() !in '0'.code..'9'.code) fail(BdecodeError.EXPECTED_DIGIT, pos)
                consumeToken(pos)
                val keyNode = parseString()
                if (pos >= buf.size) fail(BdecodeError.EXPECTED_VALUE, pos)
                if (buf[pos].toInt() == 'e'.code) fail(BdecodeError.EXPECTED_VALUE, pos)
                val value = parseValue(depth + 1)
                entries.add(BdecodeNode.DictEntry(keyNode.stringBytes(), value))
            }
            pos++                    // skip 'e'
            return BdecodeNode(BdecodeNode.Type.DICT, buf, start, pos, entries = entries)
        }
    }
}
