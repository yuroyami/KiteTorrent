package io.github.yuroyami.kitetorrent.session.tracker

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.error.LibtorrentError
import io.github.yuroyami.kitetorrent.error.TorrentException
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.ProxyConfig
import io.github.yuroyami.kitetorrent.session.net.TcpConnection
import io.github.yuroyami.kitetorrent.session.net.connectTcp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * HTTP(S) tracker client, the network half of libtorrent's
 * `http_tracker_connection`. All the protocol logic (building the announce URL with
 * percent-encoded binary info-hash/peer-id, parsing the bencoded response incl.
 * BEP-23 compact peers) lives in the pure, tested [HttpTrackerCodec]; this class is
 * just the ktor-client GET around it.
 *
 * The [client] is injected so the platform engine (CIO on JVM/Android, Darwin on iOS)
 * is chosen by the caller, and so it can be mocked in tests.
 */
class HttpTracker(
    private val client: HttpClient,
    /**
     * Hard cap on a tracker reply body, mirroring how libtorrent bounds the HTTP
     * receive buffer (`settings_pack::max_http_recv_buffer_size`, default 2 MiB).
     * A tracker that streams more than this is rejected with [LibtorrentError.PACKET_TOO_LARGE]
     * rather than letting a hostile/broken endpoint OOM the client.
     */
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    /**
     * When set, plain `http://` tracker requests travel through [NetworkRuntime.proxy] (the
     * proxy negotiation lives in [connectTcp], so SOCKS5/SOCKS4/HTTP all work) instead of going out
     * on the injected [client] directly. This is the port of libtorrent routing
     * `http_tracker_connection`'s `http_connection` through the proxy. Null (or no proxy
     * configured) leaves the direct [client] path byte-identical. `https://` always uses [client]
     * (TLS can't run over the raw tunnel here), matching the lowest-risk wiring.
     */
    private val network: NetworkRuntime? = null,
) {

    /** Announce to [announceUrl] and parse the swarm response. */
    suspend fun announce(announceUrl: String, req: AnnounceRequest): AnnounceResponse {
        val url = HttpTrackerCodec.buildAnnounceUrl(announceUrl, req)
        val body = fetch(url)
        return HttpTrackerCodec.parseAnnounceResponse(body)
    }

    /** Scrape swarm stats for [infoHashes] from the tracker behind [announceUrl]. */
    suspend fun scrape(announceUrl: String, infoHashes: List<Sha1Hash>): Map<Sha1Hash, ScrapeResponse> {
        val scrapeUrl = HttpTrackerCodec.announceToScrape(announceUrl) ?: return emptyMap()
        val url = HttpTrackerCodec.buildScrapeUrl(scrapeUrl, infoHashes)
        val body = fetch(url)
        return infoHashes.associateWith { HttpTrackerCodec.parseScrapeResponse(body, it) }
    }

    /**
     * GET [url], enforce a non-error HTTP status and the [maxResponseBytes] cap, and
     * return the raw body bytes. Ports the guards `http_tracker_connection::on_response`
     * applies before handing the body to `parse_tracker_response`: it bails out with the
     * HTTP status as the error when `parser.status_code() != 200`, and the underlying
     * `http_connection` never buffers more than `max_http_recv_buffer_size` bytes.
     *
     * @throws TorrentException [LibtorrentError.HTTP_ERROR] on a non-2xx status, or
     *   [LibtorrentError.PACKET_TOO_LARGE] when the body (or its advertised
     *   `Content-Length`) exceeds [maxResponseBytes].
     */
    private suspend fun fetch(url: String): ByteArray {
        // when a proxy is configured, tunnel plain http:// through it (connectTcp does the
        // SOCKS5/SOCKS4/HTTP-CONNECT negotiation); https:// stays on the direct ktor client.
        val net = network
        if (net?.proxy != null && url.startsWith("http://", ignoreCase = true)) {
            return fetchThroughProxy(url, net.proxy!!)
        }

        val response: HttpResponse = client.get(url)

        // validate the HTTP status before bdecoding, exactly as libtorrent rejects
        // any non-200 reply before touching the body.
        if (!response.status.isSuccess()) {
            throw TorrentException(LibtorrentError.HTTP_ERROR)
        }

        // reject on the advertised length first, so we never even pull an oversized body.
        response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { declared ->
            if (declared > maxResponseBytes) throw TorrentException(LibtorrentError.PACKET_TOO_LARGE)
        }

        val body = response.readRawBytes()
        if (body.size > maxResponseBytes) throw TorrentException(LibtorrentError.PACKET_TOO_LARGE)
        return body
    }

    /**
     * GET a plain `http://` [url] over a proxy tunnel: [connectTcp] dials the target (negotiating
     * the proxy), then a minimal HTTP/1.1 request is written and the status line / headers / body
     * read back. Applies the same non-200 and [maxResponseBytes] guards as the direct [fetch] path.
     * Mirrors how libtorrent's `http_connection` speaks HTTP over its proxied socket. `proxy` is
     * non-null here (the caller gated on it); it routes [connectTcp] through that proxy.
     */
    private suspend fun fetchThroughProxy(url: String, @Suppress("UNUSED_PARAMETER") proxy: ProxyConfig): ByteArray {
        val net = network ?: throw TorrentException(LibtorrentError.HTTP_ERROR)
        val (host, port, path) = splitHttpUrl(url)
        val conn = net.connectTcp(host, port)
        try {
            val req = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host)
                if (port != 80) append(':').append(port)
                append("\r\n")
                append("User-Agent: KiteTorrent\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            conn.write(req.encodeToByteArray())
            return readHttpResponse(conn)
        } finally {
            conn.close()
        }
    }

    /**
     * Read a full HTTP/1.1 response off [conn]: status line + headers, then the body. The status is
     * checked for 2xx (else [LibtorrentError.HTTP_ERROR]); `Content-Length`, when present, bounds the
     * body (and is rejected up front past [maxResponseBytes]); otherwise the body is read until the
     * peer closes (`Connection: close`). `Transfer-Encoding: chunked` is decoded. Any short read
     * past [maxResponseBytes] is rejected with [LibtorrentError.PACKET_TOO_LARGE].
     */
    private suspend fun readHttpResponse(conn: TcpConnection): ByteArray {
        // --- status line + headers up to the blank line ---
        val headerText = StringBuilder()
        while (true) {
            val ch = conn.readExactly(1)[0].toInt() and 0xFF
            headerText.append(ch.toChar())
            if (ch == '\n'.code && headerText.endsWith("\r\n\r\n")) break
            if (headerText.length > 64 * 1024) throw TorrentException(LibtorrentError.HTTP_ERROR)
        }
        val lines = headerText.toString().split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw TorrentException(LibtorrentError.HTTP_ERROR)
        if (code !in 200..299) throw TorrentException(LibtorrentError.HTTP_ERROR)

        var contentLength: Int? = null
        var chunked = false
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (name) {
                "content-length" -> contentLength = value.toIntOrNull()
                "transfer-encoding" -> if (value.lowercase().contains("chunked")) chunked = true
            }
        }
        contentLength?.let { if (it > maxResponseBytes) throw TorrentException(LibtorrentError.PACKET_TOO_LARGE) }

        val body = when {
            chunked -> readChunked(conn)
            contentLength != null -> conn.readExactly(contentLength)
            else -> readUntilClose(conn)
        }
        if (body.size > maxResponseBytes) throw TorrentException(LibtorrentError.PACKET_TOO_LARGE)
        return body
    }

    /** Read a `Transfer-Encoding: chunked` body to completion, enforcing [maxResponseBytes]. */
    private suspend fun readChunked(conn: TcpConnection): ByteArray {
        val out = ArrayList<Byte>()
        while (true) {
            // chunk-size line (hex, optional ;ext), terminated by CRLF
            val sizeLine = StringBuilder()
            while (true) {
                val ch = conn.readExactly(1)[0].toInt() and 0xFF
                if (ch == '\n'.code) break
                if (ch != '\r'.code) sizeLine.append(ch.toChar())
            }
            val size = sizeLine.toString().substringBefore(';').trim().toIntOrNull(16)
                ?: throw TorrentException(LibtorrentError.HTTP_ERROR)
            if (size == 0) {
                // consume the trailing CRLF after the final zero chunk
                conn.readExactly(2)
                break
            }
            val chunk = conn.readExactly(size)
            for (b in chunk) out.add(b)
            if (out.size > maxResponseBytes) throw TorrentException(LibtorrentError.PACKET_TOO_LARGE)
            conn.readExactly(2) // CRLF after the chunk data
        }
        return out.toByteArray()
    }

    /** Read until the peer closes the connection (no Content-Length), enforcing [maxResponseBytes]. */
    private suspend fun readUntilClose(conn: TcpConnection): ByteArray {
        val out = ArrayList<Byte>()
        try {
            while (true) {
                val b = conn.readExactly(1)
                out.add(b[0])
                if (out.size > maxResponseBytes) throw TorrentException(LibtorrentError.PACKET_TOO_LARGE)
            }
        } catch (e: TorrentException) {
            throw e // a PACKET_TOO_LARGE we raised: propagate it, don't swallow as EOF
        } catch (_: Throwable) {
            // EOF: the peer closed the stream. The body is complete.
        }
        return out.toByteArray()
    }

    /** Split a plain `http://host[:port]/path?query` into (host, port, request-target). */
    private fun splitHttpUrl(url: String): Triple<String, Int, String> {
        val afterScheme = url.substringAfter("://")
        val authority = afterScheme.substringBefore('/')
        val pathAndQuery = afterScheme.substring(authority.length).ifEmpty { "/" }
        val host: String
        val port: Int
        if (authority.startsWith("[")) { // [v6]:port
            host = authority.substringAfter('[').substringBefore(']')
            port = authority.substringAfterLast(']').removePrefix(":").toIntOrNull() ?: 80
        } else {
            val idx = authority.lastIndexOf(':')
            if (idx >= 0) {
                host = authority.substring(0, idx)
                port = authority.substring(idx + 1).toIntOrNull() ?: 80
            } else {
                host = authority
                port = 80
            }
        }
        return Triple(host, port, pathAndQuery)
    }

    companion object {
        /** Default response-body cap: 2 MiB, libtorrent's `max_http_recv_buffer_size` default. */
        const val DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
