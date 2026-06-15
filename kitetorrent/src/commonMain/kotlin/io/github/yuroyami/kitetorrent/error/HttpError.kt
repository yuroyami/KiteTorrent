package io.github.yuroyami.kitetorrent.error

/**
 * HTTP status codes recognised by libtorrent, ported from `errors::http_errors`
 * (include/libtorrent/error_code.hpp) and the `http_error_category` message
 * formatter in src/error_code.cpp.
 *
 * In libtorrent these live in a separate `http_category()` whose `message(ev)`
 * renders the numeric status followed by a space and the reason phrase, e.g.
 * `"404 Not Found"`. Unlike [LibtorrentError], the [code] values here are sparse
 * (real HTTP status codes), so this is *not* an index-addressable table; use
 * [fromCodeOrNull] for lookup.
 *
 * @property code the HTTP status code.
 * @property reason the reason phrase libtorrent appends after the status number.
 */
enum class HttpError(val code: Int, val reason: String) {
    /** 100 Continue */
    CONT(100, "Continue"),

    /** 200 OK */
    OK(200, "OK"),

    /** 201 Created */
    CREATED(201, "Created"),

    /** 202 Accepted */
    ACCEPTED(202, "Accepted"),

    /** 204 No Content */
    NO_CONTENT(204, "No Content"),

    /** 300 Multiple Choices */
    MULTIPLE_CHOICES(300, "Multiple Choices"),

    /** 301 Moved Permanently */
    MOVED_PERMANENTLY(301, "Moved Permanently"),

    /** 302 Moved Temporarily (Found) */
    MOVED_TEMPORARILY(302, "Moved Temporarily"),

    /** 304 Not Modified */
    NOT_MODIFIED(304, "Not Modified"),

    /** 400 Bad Request */
    BAD_REQUEST(400, "Bad Request"),

    /** 401 Unauthorized */
    UNAUTHORIZED(401, "Unauthorized"),

    /** 403 Forbidden */
    FORBIDDEN(403, "Forbidden"),

    /** 404 Not Found */
    NOT_FOUND(404, "Not Found"),

    /** 500 Internal Server Error */
    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),

    /** 501 Not Implemented */
    NOT_IMPLEMENTED(501, "Not Implemented"),

    /** 502 Bad Gateway */
    BAD_GATEWAY(502, "Bad Gateway"),

    /** 503 Service Unavailable */
    SERVICE_UNAVAILABLE(503, "Service Unavailable"),
    ;

    /**
     * The full category message libtorrent's `http_category().message()` would
     * produce: the status number, a space, then the [reason] phrase. e.g.
     * `HttpError.NOT_FOUND.message == "404 Not Found"`.
     */
    val message: String get() = "$code $reason"

    companion object {
        /** Look up an [HttpError] by its numeric status [code], or `null` if unknown. */
        fun fromCodeOrNull(code: Int): HttpError? = entries.firstOrNull { it.code == code }

        /**
         * Render the libtorrent `http_category().message(ev)` string for an arbitrary
         * status code. Faithful to the C++ `switch`: known codes get their reason
         * phrase, anything else falls back to `"(unknown HTTP error)"`, always
         * prefixed by the numeric code and a space.
         */
        fun messageOf(code: Int): String {
            val reason = fromCodeOrNull(code)?.reason ?: "(unknown HTTP error)"
            return "$code $reason"
        }
    }
}
