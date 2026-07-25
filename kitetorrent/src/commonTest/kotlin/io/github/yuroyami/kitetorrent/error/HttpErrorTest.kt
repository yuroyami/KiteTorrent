package io.github.yuroyami.kitetorrent.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Golden tests for [HttpError], pinned to libtorrent's `errors::http_errors`
 * (error_code.hpp) and the `http_error_category::message()` formatter
 * (src/error_code.cpp), which renders `"<code> <reason>"`.
 */
class HttpErrorTest {

    @Test
    fun codesMatchLibtorrent() {
        assertEquals(100, HttpError.CONT.code)
        assertEquals(200, HttpError.OK.code)
        assertEquals(204, HttpError.NO_CONTENT.code)
        assertEquals(301, HttpError.MOVED_PERMANENTLY.code)
        assertEquals(302, HttpError.MOVED_TEMPORARILY.code)
        assertEquals(304, HttpError.NOT_MODIFIED.code)
        assertEquals(404, HttpError.NOT_FOUND.code)
        assertEquals(503, HttpError.SERVICE_UNAVAILABLE.code)
    }

    @Test
    fun messageMatchesCategoryFormat() {
        // "<code> <reason>": exactly what http_category().message(ev) builds.
        assertEquals("200 OK", HttpError.OK.message)
        assertEquals("301 Moved Permanently", HttpError.MOVED_PERMANENTLY.message)
        assertEquals("302 Moved Temporarily", HttpError.MOVED_TEMPORARILY.message)
        assertEquals("404 Not Found", HttpError.NOT_FOUND.message)
        assertEquals("500 Internal Server Error", HttpError.INTERNAL_SERVER_ERROR.message)
        assertEquals("503 Service Unavailable", HttpError.SERVICE_UNAVAILABLE.message)
    }

    @Test
    fun lookupAndUnknownFallback() {
        assertSame(HttpError.NOT_FOUND, HttpError.fromCodeOrNull(404))
        assertNull(HttpError.fromCodeOrNull(418)) // I'm a teapot, not in libtorrent's set

        // Unknown codes fall back to "(unknown HTTP error)" but keep the numeric prefix.
        assertEquals("418 (unknown HTTP error)", HttpError.messageOf(418))
        assertEquals("404 Not Found", HttpError.messageOf(404))
    }
}
