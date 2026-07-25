package io.github.yuroyami.kitetorrent.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Golden tests for [LibtorrentError]. The expected (code, message) pairs are taken
 * verbatim from libtorrent's `errors::error_code_enum` (error_code.hpp) and the
 * message table in `libtorrent_error_category::message()` (src/error_code.cpp).
 * These pin down the ABI-stable integer values and the exact category strings.
 */
class LibtorrentErrorTest {

    @Test
    fun denseTableIsContiguousAndComplete() {
        // libtorrent's error_code_max is 219 (one past the last code).
        assertEquals(219, LibtorrentError.ENTRY_COUNT)
        assertEquals(219, LibtorrentError.entries.size)
        // entries[c].code == c for every code in range: the dense-table invariant.
        for (c in 0 until LibtorrentError.ENTRY_COUNT) {
            assertEquals(c, LibtorrentError.entries[c].code, "entries[$c] has wrong code")
        }
        // No enum constant carries a code outside the valid range.
        for (e in LibtorrentError.entries) {
            assertTrue(e.code in 0 until LibtorrentError.ENTRY_COUNT)
        }
    }

    @Test
    fun anchorCodesMatchLibtorrent() {
        // Explicitly-anchored values from the enum (the ones with "= N" in C++).
        assertEquals(0, LibtorrentError.NO_ERROR.code)
        assertEquals(82, LibtorrentError.RESERVED.code)
        assertEquals(119, LibtorrentError.INVALID_HASH_REJECT.code)
        assertEquals(120, LibtorrentError.DEPRECATED_120.code)
        assertEquals(124, LibtorrentError.DEPRECATED_124.code)
        assertEquals(130, LibtorrentError.MISSING_FILE_SIZES.code)
        assertEquals(144, LibtorrentError.INVALID_SAVE_PATH.code)
        assertEquals(150, LibtorrentError.HTTP_PARSE_ERROR.code)
        assertEquals(152, LibtorrentError.HTTP_FAILED_DECOMPRESS.code)
        assertEquals(160, LibtorrentError.NO_I2P_ROUTER.code)
        assertEquals(161, LibtorrentError.NO_I2P_ENDPOINT.code)
        assertEquals(170, LibtorrentError.SCRAPE_NOT_AVAILABLE.code)
        assertEquals(180, LibtorrentError.ANNOUNCE_SKIPPED.code)
        assertEquals(200, LibtorrentError.NO_ENTROPY.code)
        assertEquals(202, LibtorrentError.BLOCKED_BY_IDNA.code)
        assertEquals(210, LibtorrentError.TORRENT_UNKNOWN_VERSION.code)
        assertEquals(218, LibtorrentError.TORRENT_INVALID_PAD_FILE.code)
    }

    @Test
    fun messagesMatchCategoryTable() {
        // Spot-check a spread of messages against libtorrent_error_category::message().
        assertEquals("no error", LibtorrentError.NO_ERROR.message)
        assertEquals(
            "torrent file collides with file from another torrent",
            LibtorrentError.FILE_COLLISION.message,
        )
        assertEquals("hash check failed", LibtorrentError.FAILED_HASH_CHECK.message)
        assertEquals(
            "missing or invalid 'info' section in torrent file",
            LibtorrentError.TORRENT_MISSING_INFO.message,
        )
        assertEquals("out of memory", LibtorrentError.NO_MEMORY.message)
        assertEquals("connected to ourselves", LibtorrentError.SELF_CONNECTION.message)
        assertEquals(
            "'have'-message with higher index than the number of pieces",
            LibtorrentError.INVALID_HAVE.message,
        )
        assertEquals("Invalid HTTP header", LibtorrentError.HTTP_PARSE_ERROR.message)
        assertEquals("no i2p router is set up", LibtorrentError.NO_I2P_ROUTER.message)
        assertEquals(
            "scrape not available on tracker",
            LibtorrentError.SCRAPE_NOT_AVAILABLE.message,
        )
        assertEquals("random number generator failed", LibtorrentError.NO_ENTROPY.message)
        assertEquals(
            "a file in the v2 metadata has the pad attribute set",
            LibtorrentError.TORRENT_INVALID_PAD_FILE.message,
        )
    }

    @Test
    fun reservedAndDeprecatedSlots() {
        // The 'reserved' slot (82) has an empty message in the C++ table.
        assertEquals("", LibtorrentError.RESERVED.message)
        // So does i2p endpoint (161): the enum names it but the message array slot is "".
        assertEquals("", LibtorrentError.NO_I2P_ENDPOINT.message)
        // Deprecated NAT-PMP codes keep their old category strings.
        assertEquals("unsupported protocol version", LibtorrentError.DEPRECATED_120.message)
        assertEquals(
            "not authorized to create port map (enable NAT-PMP on your router)",
            LibtorrentError.DEPRECATED_121.message,
        )
        assertEquals("unsupported opcode", LibtorrentError.DEPRECATED_124.message)
        // Pure padding gaps are empty.
        assertEquals("", LibtorrentError.RESERVED_125.message)
        assertEquals("", LibtorrentError.RESERVED_190.message)
    }

    @Test
    fun lookupHelpers() {
        assertSame(LibtorrentError.NO_ERROR, LibtorrentError.fromCode(0))
        assertSame(LibtorrentError.TORRENT_INVALID_PAD_FILE, LibtorrentError.fromCode(218))
        assertSame(LibtorrentError.NO_ENTROPY, LibtorrentError.fromCodeOrNull(200))

        // Out-of-range behaviour mirrors message() returning "Unknown error".
        assertNull(LibtorrentError.fromCodeOrNull(-1))
        assertNull(LibtorrentError.fromCodeOrNull(219))
        assertNull(LibtorrentError.fromCodeOrNull(1000))
        assertEquals("Unknown error", LibtorrentError.messageOf(-1))
        assertEquals("Unknown error", LibtorrentError.messageOf(219))
        assertEquals("out of memory", LibtorrentError.messageOf(45))

        assertFailsWith<IllegalArgumentException> { LibtorrentError.fromCode(219) }
    }

    @Test
    fun torrentExceptionCarriesMessage() {
        val ex = TorrentException(LibtorrentError.FAILED_HASH_CHECK)
        assertSame(LibtorrentError.FAILED_HASH_CHECK, ex.error)
        assertEquals("hash check failed", ex.message)
    }
}
