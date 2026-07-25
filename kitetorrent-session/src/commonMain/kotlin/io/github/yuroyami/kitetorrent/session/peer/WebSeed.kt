package io.github.yuroyami.kitetorrent.session.peer

import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.util.UrlEscape
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes

/**
 * One HTTP byte range to fetch from a web seed: the fully-qualified [url] plus an
 * **inclusive** `[firstByte, lastByte]` interval, ready to drop straight into a
 * `Range: bytes=$firstByte-$lastByte` header.
 *
 * This is the pure, network-free output of [WebSeed.rangesFor]. See that function for
 * how a BitTorrent `(piece, offset, length)` request becomes a list of these.
 */
data class HttpRange(
    /** Absolute URL of the file this range lives in. */
    val url: String,
    /** First byte of the range *within that file* (0-based, inclusive). */
    val firstByte: Long,
    /** Last byte of the range *within that file* (inclusive), i.e. `offset + size - 1`. */
    val lastByte: Long,
) {
    /** Number of bytes this range covers. */
    val length: Long get() = lastByte - firstByte + 1

    /** The HTTP/1.1 byte-range spec value, e.g. `bytes=0-16383` (RFC 7233 §2.1). */
    val rangeHeaderValue: String get() = "bytes=$firstByte-$lastByte"
}

/**
 * A BEP-19 *web seed* (`url-list`, "GetRight" style): a plain HTTP(S) server that
 * hosts the torrent's files so a client can download them with ordinary ranged GETs
 * instead of the BitTorrent wire protocol.
 *
 * Coroutine port of libtorrent's `web_peer_connection` (src/web_peer_connection.cpp)
 * and its base `web_connection_base` (src/web_connection_base.cpp). libtorrent runs a
 * web seed as a full pseudo-`peer_connection`: it fabricates a have-all bitfield,
 * lets the normal piece picker schedule blocks, then in `write_request` turns each
 * BitTorrent block request into one or more HTTP `GET … Range: bytes=a-b` requests, one
 * per file the block overlaps, and re-assembles the response bytes back into the piece.
 * This class keeps that *request-mapping + fetch* core and drops the peer-connection
 * machinery (choke/interest, the receive-buffer state machine, redirect-following,
 * pad-file zero-filling), which belong to the orchestrator above.
 *
 * **URL construction** mirrors `web_peer_connection`'s constructor and `write_request`:
 *  - **Single-file torrent**: the file *is* the torrent. libtorrent uses the seed URL
 *    as-is, except when the URL ends in `/` it appends the (already-encoded) torrent
 *    name (web_peer_connection.cpp:108-121). So here, [baseUrl] ending in `/` →
 *    `baseUrl + escapedName`, otherwise [baseUrl] verbatim.
 *  - **Multi-file torrent**: the seed URL is a directory. libtorrent ensures a
 *    trailing slash (web_peer_connection.cpp:101-107) then appends each file's
 *    percent-encoded path (`escape_file_path`, web_peer_connection.cpp:497). So here,
 *    each URL is `baseUrl(/) + escapePath(relativeFilePath)`.
 *
 * **Block → range mapping** ([rangesFor]) reproduces `file_storage::map_block`
 * (src/file_storage.cpp:485): the request's absolute torrent offset is
 * `piece * pieceLength + offset`, and the run of `length` bytes is sliced at file
 * boundaries, yielding one [HttpRange] per file touched. A block that straddles a
 * file boundary therefore produces multiple ranges, fetched and concatenated in
 * order. libtorrent does the same: it issues multiple `GET`s and joins the payloads.
 *
 * Threading. [fetchBlock]/[downloadPiece] suspend on the injected [client] only; they
 * hold no mutable state, so a single [WebSeed] may be shared across coroutines.
 *
 * @param client the ktor [HttpClient] (engine chosen by the caller: CIO on
 *   JVM/Android, Darwin on iOS), injected so it can be mocked.
 * @param baseUrl the web-seed URL from the torrent's `url-list`.
 * @param torrent the parsed torrent whose files this seed serves.
 */
class WebSeed(
    private val client: HttpClient,
    baseUrl: String,
    private val torrent: TorrentInfo,
) {
    /**
     * The seed URL, normalised once up-front the way libtorrent does in the
     * `web_peer_connection` constructor: a trailing slash is ensured for the
     * multi-file case (so file paths append cleanly), and for a single-file torrent a
     * `/`-terminated URL gets the escaped torrent name appended.
     */
    val url: String = WebSeedMapper.normalizeBaseUrl(baseUrl, torrent)

    /**
     * Map a BitTorrent block request to the list of HTTP byte ranges that satisfy it.
     * This is the pure, network-free core of [fetchBlock]. It delegates to [WebSeedMapper],
     * the testable port of the URL + `Range` computation in
     * `web_peer_connection::write_request` together with `file_storage::map_block`.
     *
     * The request covers `length` bytes starting at absolute torrent offset
     * `piece * pieceLength + offset`. For a single-file torrent that is a single range
     * against [url]. For a multi-file torrent the span is cut at every file boundary
     * it crosses, producing one [HttpRange] per file (so a boundary-straddling block
     * yields ≥2 ranges); the per-byte offsets in each range are *file-relative*, as
     * HTTP `Range` demands.
     *
     * Padding files (BEP-47) are included as ranges against their synthesised URL for
     * completeness, but a real fetch would supply zeroes for them rather than hitting
     * the network (libtorrent's `handle_padfile`); callers that fetch over HTTP should
     * skip [io.github.yuroyami.kitetorrent.torrent.FileEntry.isPadFile] entries.
     *
     * @throws IllegalArgumentException on an out-of-range or negative request.
     */
    fun rangesFor(piece: Int, offset: Int, length: Int): List<HttpRange> =
        WebSeedMapper.rangesFor(torrent, url, piece, offset, length)

    /**
     * Fetch one BitTorrent block (`length` bytes at `(piece, offset)`) from this web
     * seed, issuing a ranged HTTP `GET` per file the block overlaps ([rangesFor]) and
     * concatenating the bodies in order. Mirrors how `web_peer_connection` re-assembles
     * `incoming_payload` fragments into a single block.
     *
     * The returned array is exactly [length] bytes when the server honours every
     * range; a misbehaving server is the caller's concern (libtorrent validates the
     * `Content-Range` against the request in `on_receive`, web_peer_connection.cpp:949).
     */
    suspend fun fetchBlock(piece: Int, offset: Int, length: Int): ByteArray {
        val ranges = rangesFor(piece, offset, length)
        if (ranges.isEmpty()) return ByteArray(0)
        // The common single-range case avoids the builder copy.
        if (ranges.size == 1) return fetchRange(ranges[0])

        val out = ByteArrayBuilder(length)
        for (range in ranges) out.append(fetchRange(range))
        return out.toByteArray()
    }

    /**
     * Download an entire piece by fetching it as a single contiguous block (offset 0,
     * the full [TorrentInfo.storage] piece size). The final piece is shorter than
     * [TorrentInfo.pieceLength]; [io.github.yuroyami.kitetorrent.torrent.FileStorage.pieceSize]
     * gives the exact length. Multi-file pieces still fan out to per-file ranges
     * inside [fetchBlock].
     */
    suspend fun downloadPiece(piece: Int): ByteArray =
        fetchBlock(piece, 0, torrent.storage.pieceSize(piece))

    /** Issue the ranged GET for a single [HttpRange] and return the raw body. */
    private suspend fun fetchRange(range: HttpRange): ByteArray =
        client.get(range.url) {
            header("Range", range.rangeHeaderValue)
        }.readRawBytes()
}

/**
 * The pure, network-free URL + byte-range arithmetic behind [WebSeed], factored out
 * so it can be tested without constructing an [HttpClient] (which needs a platform
 * engine absent from the common test source set). Direct port of the relevant parts
 * of `web_peer_connection` (src/web_peer_connection.cpp) and `file_storage::map_block`
 * (src/file_storage.cpp).
 */
internal object WebSeedMapper {

    /**
     * Normalise the seed URL exactly as the `web_peer_connection` constructor does
     * (web_peer_connection.cpp:99-121). See [WebSeed.url].
     */
    fun normalizeBaseUrl(baseUrl: String, torrent: TorrentInfo): String {
        if (torrent.storage.isSingleFile) {
            // single-file: a URL ending in '/' is missing the filename, so append the
            // escaped torrent name (web_peer_connection.cpp:117-120). The torrent name
            // itself is the single file's name.
            return if (baseUrl.endsWith("/")) baseUrl + UrlEscape.escapePath(torrent.name) else baseUrl
        }
        // multi-file: the URL is a directory; ensure a trailing slash so file paths
        // append cleanly (ensure_trailing_slash, web_peer_connection.cpp:105-106).
        return if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    }

    /**
     * Slice a `(piece, offset, length)` BitTorrent request into per-file HTTP ranges.
     * [normalizedUrl] is the already-[normalizeBaseUrl]'d seed URL. See
     * [WebSeed.rangesFor] for the full contract.
     */
    fun rangesFor(
        torrent: TorrentInfo,
        normalizedUrl: String,
        piece: Int,
        offset: Int,
        length: Int,
    ): List<HttpRange> {
        require(piece >= 0 && piece < torrent.numPieces) {
            "piece $piece out of range [0,${torrent.numPieces})"
        }
        require(offset >= 0) { "negative offset $offset" }
        require(length >= 0) { "negative length $length" }
        if (length == 0) return emptyList()

        // Absolute offset of the request within the torrent's concatenated byte
        // stream. See file_storage.cpp:499 (piece * piece_length + offset).
        val absStart = piece.toLong() * torrent.pieceLength + offset
        require(absStart + length <= torrent.totalSize) {
            "request [$absStart,${absStart + length}) past torrent end ${torrent.totalSize}"
        }

        val files = torrent.files

        // Single-file fast path. libtorrent special-cases this (web_peer_connection
        // .cpp:421): the whole request is one range against the single file URL, whose
        // byte offsets ARE the torrent offsets.
        if (torrent.storage.isSingleFile) {
            return listOf(HttpRange(normalizedUrl, absStart, absStart + length - 1))
        }

        // Multi-file: walk the file list, slicing the [absStart, absStart+length) span
        // at file boundaries. This is the loop in file_storage::map_block (file_storage.cpp
        // :507-531). `files` are in canonical order with monotonically increasing
        // offsets, so a linear scan from the file containing absStart suffices.
        val out = ArrayList<HttpRange>()
        var remaining = length.toLong()
        var cursor = absStart
        // find the first file the request starts in (largest offset <= cursor)
        var i = files.indexOfLast { it.offset <= cursor }
        if (i < 0) i = 0
        while (remaining > 0 && i < files.size) {
            val file = files[i]
            val fileEnd = file.offset + file.size
            if (cursor < fileEnd) {
                val inFileStart = cursor - file.offset
                val take = minOf(file.size - inFileStart, remaining)
                out.add(
                    HttpRange(
                        url = fileUrl(normalizedUrl, torrent.name, file.path),
                        firstByte = inFileStart,
                        lastByte = inFileStart + take - 1,
                    ),
                )
                cursor += take
                remaining -= take
            }
            i++
        }
        return out
    }

    /**
     * The URL for a multi-file entry whose torrent-relative [filePath] is
     * `name/dir/file`. libtorrent strips the implied root directory (it appends
     * `file_path(index)`, which is already relative to the save dir, not the torrent
     * name) and percent-encodes it with `escape_path` (web_peer_connection.cpp:497),
     * preserving `/` separators. [normalizedUrl] already carries a trailing slash.
     */
    private fun fileUrl(normalizedUrl: String, torrentName: String, filePath: String): String =
        normalizedUrl + UrlEscape.escapePath(relativePath(torrentName, filePath))

    private fun relativePath(torrentName: String, filePath: String): String {
        // FileEntry.path includes the torrent name as the leading directory for
        // multi-file torrents (see FileStorage / TorrentInfo.parseFiles). The web-seed
        // path is relative to the directory the base URL points at, which already
        // corresponds to that root, so drop the leading "name/".
        val prefix = "$torrentName/"
        return if (filePath.startsWith(prefix)) filePath.substring(prefix.length) else filePath
    }
}
