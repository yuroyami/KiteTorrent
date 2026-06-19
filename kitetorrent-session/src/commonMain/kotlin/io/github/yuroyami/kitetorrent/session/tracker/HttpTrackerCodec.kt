package io.github.yuroyami.kitetorrent.session.tracker

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.BdecodeException
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode
import io.github.yuroyami.kitetorrent.error.LibtorrentError
import io.github.yuroyami.kitetorrent.error.TorrentException
import io.github.yuroyami.kitetorrent.util.UrlEscape

/**
 * The pure, I/O-free half of the BEP-3 / BEP-23 / BEP-48 HTTP tracker protocol —
 * the codec to libtorrent's `http_tracker_connection` (`src/http_tracker_connection.cpp`).
 *
 * Every function here is a total function of its arguments: [buildAnnounceUrl] and
 * [buildScrapeUrl] turn a request plus a base URL into the exact GET URL libtorrent
 * would emit, and [parseAnnounceResponse] / [parseScrapeResponse] turn a bencoded
 * response body into the shared [AnnounceResponse] / [ScrapeResponse] value objects.
 * [HttpTracker] layers ktor's `HttpClient` round-trip on top, so the wire-format
 * logic stays testable without a socket — mirroring how [UdpTrackerCodec] backs
 * `UdpTracker`.
 *
 * libtorrent splits the same work between two free functions in
 * `http_tracker_connection.cpp`: `http_tracker_connection::start()` bakes the query
 * string, and `parse_tracker_response()` decodes the reply. The faithful details
 * preserved here are:
 *
 *  - `info_hash` / `peer_id` are percent-encoded over their **raw 20 bytes** with
 *    [UrlEscape.escapeString] (libtorrent `escape_string({...data(), 20})`), which
 *    keeps only `-_!.~*()` and alphanumerics and lower-cases its hex digits.
 *  - the fixed announce tail is
 *    `&port&uploaded&downloaded&left&corrupt&key=%08X&event&numwant&compact=1&no_peer_id=1`,
 *    with `key` as **upper-case** 8-digit hex (the lone `%08X` in libtorrent's
 *    `snprintf`) and `event` omitted entirely when it is [TrackerEvent.NONE].
 *  - the `event` token strings are `completed|started|stopped|paused`, selected by
 *    `event_string[event - 1]` — i.e. by the enum's wire [code][TrackerEvent.code],
 *    not by declaration order.
 *  - a scrape URL is the announce URL with the **last** path segment `announce`
 *    rewritten to `scrape` (libtorrent finds the first `"announce"` substring; we
 *    match the path component to avoid mangling a host like `announce.tracker.tld`),
 *    failing with [LibtorrentError.SCRAPE_NOT_AVAILABLE] when there is none.
 *  - the response parser defaults `interval` to 1800 and `min interval` to 30,
 *    surfaces `failure reason` as [LibtorrentError.TRACKER_FAILURE], accepts both the
 *    BEP-23 compact 6-byte `peers` string and the BEP-3 dict-model `peers` list, and
 *    also reads the 18-byte compact `peers6` string.
 */
internal object HttpTrackerCodec {

    /**
     * The BEP-15 / HTTP event token for each [TrackerEvent], or `null` for
     * [TrackerEvent.NONE] (which libtorrent omits from the query string entirely).
     * libtorrent's table is `{"completed", "started", "stopped", "paused"}` indexed
     * by `event - 1`; keying off [TrackerEvent.code] reproduces exactly that mapping.
     */
    private fun eventToken(event: TrackerEvent): String? = when (event) {
        TrackerEvent.NONE -> null
        TrackerEvent.COMPLETED -> "completed"
        TrackerEvent.STARTED -> "started"
        TrackerEvent.STOPPED -> "stopped"
        TrackerEvent.PAUSED -> "paused"
    }

    /**
     * Build the announce GET URL for [req] against the tracker's [announceUrl],
     * porting the query-string assembly in `http_tracker_connection::start()`.
     *
     * The first parameter is joined with `?` or, if [announceUrl] already carries a
     * query string, `&` — exactly libtorrent's `url.find('?')` test. `compact=1` and
     * `no_peer_id=1` are always sent (BEP-23); `key` is emitted as upper-case
     * `%08X`; `corrupt` is sent as `0` to match libtorrent's always-present field.
     *
     * @throws IllegalArgumentException if the info-hash or peer-id is not 20 bytes.
     */
    fun buildAnnounceUrl(announceUrl: String, req: AnnounceRequest): String {
        val infoHash = req.infoHash.toByteArray()
        val peerId = req.peerId.toByteArray()
        require(infoHash.size == 20) { "info_hash must be 20 bytes, was ${infoHash.size}" }
        require(peerId.size == 20) { "peer_id must be 20 bytes, was ${peerId.size}" }

        val sb = StringBuilder(announceUrl.length + 256)
        sb.append(announceUrl)
        sb.append(if (announceUrl.indexOf('?') >= 0) '&' else '?')

        // info_hash + peer_id: percent-encode the RAW 20 bytes (libtorrent
        // escape_string({data(), 20})), lower-case hex per UrlEscape.
        sb.append("info_hash=").append(UrlEscape.escapeString(infoHash))
        sb.append("&peer_id=").append(UrlEscape.escapeString(peerId))

        sb.append("&port=").append(req.port)
        sb.append("&uploaded=").append(req.uploaded)
        sb.append("&downloaded=").append(req.downloaded)
        sb.append("&left=").append(req.left)
        sb.append("&corrupt=0")
        sb.append("&key=").append(upperHex8(req.key))

        eventToken(req.event)?.let { sb.append("&event=").append(it) }

        sb.append("&numwant=").append(req.numWant)
        sb.append("&compact=1")
        sb.append("&no_peer_id=1")

        // echo the tracker-supplied "tracker id" on subsequent announces, exactly as
        // http_tracker_connection::start() appends "&trackerid=" + escape_string(...)
        // when tracker_req().trackerid is non-empty. The value is percent-escaped over
        // its raw bytes like every other binary-ish field.
        if (req.trackerId.isNotEmpty()) {
            sb.append("&trackerid=").append(UrlEscape.escapeString(req.trackerId.encodeToByteArray()))
        }
        return sb.toString()
    }

    /**
     * Build the scrape GET URL for [infoHashes] against the tracker's [announceUrl],
     * porting `http_tracker_connection::start()`'s `announce`→`scrape` rewrite plus
     * the BEP-48 multi-`info_hash` query.
     *
     * @throws TorrentException with [LibtorrentError.SCRAPE_NOT_AVAILABLE] if the URL
     *   path has no `announce` segment to rewrite.
     * @throws IllegalArgumentException if any info-hash is not 20 bytes.
     */
    fun buildScrapeUrl(announceUrl: String, infoHashes: List<Sha1Hash>): String {
        val scrapeBase = announceToScrape(announceUrl)
            ?: throw TorrentException(LibtorrentError.SCRAPE_NOT_AVAILABLE)

        val sb = StringBuilder(scrapeBase.length + infoHashes.size * 64)
        sb.append(scrapeBase)
        var sep = if (scrapeBase.indexOf('?') >= 0) '&' else '?'
        for (h in infoHashes) {
            val bytes = h.toByteArray()
            require(bytes.size == 20) { "info_hash must be 20 bytes, was ${bytes.size}" }
            sb.append(sep).append("info_hash=").append(UrlEscape.escapeString(bytes))
            sep = '&'
        }
        return sb.toString()
    }

    /**
     * Rewrite the announce URL's last `announce` path segment to `scrape`, returning
     * `null` when the path contains no such segment.
     *
     * libtorrent does a blunt `url.find("announce")` substring replace; we constrain
     * the match to a whole path component (between the host and any `?`/`#`) so a
     * tracker hosted at e.g. `http://announce.example.com/x/announce` rewrites only
     * the trailing segment, not the hostname.
     */
    fun announceToScrape(announceUrl: String): String? {
        // isolate the path+query+fragment, leaving the scheme://authority prefix alone
        val schemeEnd = announceUrl.indexOf("://")
        val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val pathStart = announceUrl.indexOf('/', authorityStart)
        if (pathStart < 0) return null

        val queryStart = announceUrl.indexOfFirst(pathStart) { it == '?' || it == '#' }
        val pathEnd = if (queryStart < 0) announceUrl.length else queryStart
        val path = announceUrl.substring(pathStart, pathEnd)

        // find the last segment and require it to begin with "announce"
        val lastSlash = path.lastIndexOf('/')
        val segStart = lastSlash + 1
        val segment = path.substring(segStart)
        if (!segment.startsWith("announce")) return null

        val rewrittenPath = path.substring(0, segStart) + "scrape" + segment.substring("announce".length)
        return announceUrl.substring(0, pathStart) + rewrittenPath + announceUrl.substring(pathEnd)
    }

    /**
     * Parse a bencoded announce response [bytes] into an [AnnounceResponse], porting
     * `parse_tracker_response()` (the non-scrape branch).
     *
     * Defaults `interval` to 1800 and `seeders`/`leechers` (libtorrent
     * `complete`/`incomplete`) to `-1` when absent. Both peer encodings are handled:
     * the BEP-23 compact 6-byte `peers` string and the BEP-3 dict-model `peers` list
     * (`ip`/`port`, ignoring `peer id`); the 18-byte compact `peers6` string is also
     * read and its endpoints appended.
     *
     * @throws TorrentException [LibtorrentError.INVALID_TRACKER_RESPONSE] if the body
     *   is not a bencoded dictionary, or [LibtorrentError.TRACKER_FAILURE] if the
     *   tracker returned a `failure reason` (its text is available via the thrown
     *   [TrackerFailure], a subclass that carries the reason).
     */
    fun parseAnnounceResponse(bytes: ByteArray): AnnounceResponse {
        val root = decodeDict(bytes)

        // failure reason short-circuits, exactly like libtorrent.
        root.dictFindString("failure reason").let { failure ->
            if (failure.isValid) throw TrackerFailure(failure.stringValue())
        }

        val interval = root.dictFindIntValue("interval", AnnounceResponse.DEFAULT_INTERVAL.toLong()).toInt()
        // BEP-3 "min interval"; libtorrent defaults it to 30s when absent
        // (resp.min_interval = e.dict_find_int_value("min interval", 30)).
        val minInterval = root.dictFindIntValue(
            "min interval", AnnounceResponse.DEFAULT_MIN_INTERVAL.toLong(),
        ).toInt()
        val seeders = root.dictFindIntValue("complete", -1L).toInt()
        val leechers = root.dictFindIntValue("incomplete", -1L).toInt()

        // the optional "tracker id" we must echo on the next announce (resp.trackerid).
        val trackerIdNode = root.dictFindString("tracker id")
        val trackerId = if (trackerIdNode.isValid) trackerIdNode.stringValue() else ""

        val peers = ArrayList<PeerEndpoint>()
        decodePeers(root.dictFind("peers"), peers)
        decodeCompactPeers6(root.dictFindString("peers6"), peers)

        return AnnounceResponse(
            interval = interval,
            leechers = leechers,
            seeders = seeders,
            peers = peers,
            minInterval = minInterval,
            trackerId = trackerId,
        )
    }

    /**
     * Parse a bencoded scrape response [bytes] for [infoHash], porting the scrape
     * branch of `parse_tracker_response()`: descend `files` → `<20-byte info-hash>`
     * and read `complete` / `downloaded` / `incomplete` (each defaulting to `-1`).
     *
     * @throws TorrentException [LibtorrentError.INVALID_TRACKER_RESPONSE] (non-dict
     *   body), [LibtorrentError.TRACKER_FAILURE] (`failure reason`),
     *   [LibtorrentError.INVALID_FILES_ENTRY] (no `files` dict), or
     *   [LibtorrentError.INVALID_HASH_ENTRY] (no entry for [infoHash]).
     */
    fun parseScrapeResponse(bytes: ByteArray, infoHash: Sha1Hash): ScrapeResponse {
        val root = decodeDict(bytes)

        root.dictFindString("failure reason").let { failure ->
            if (failure.isValid) throw TrackerFailure(failure.stringValue())
        }

        val files = root.dictFindDict("files")
        if (!files.isValid) throw TorrentException(LibtorrentError.INVALID_FILES_ENTRY)

        // the key is the raw 20-byte info-hash, NOT its hex form (BEP-48).
        val entry = files.dictFind(infoHash.toByteArray())
        if (entry.type != BdecodeNode.Type.DICT) {
            throw TorrentException(LibtorrentError.INVALID_HASH_ENTRY)
        }

        return ScrapeResponse(
            seeders = entry.dictFindIntValue("complete", -1L).toInt(),
            completed = entry.dictFindIntValue("downloaded", -1L).toInt(),
            leechers = entry.dictFindIntValue("incomplete", -1L).toInt(),
        )
    }

    // --- helpers -------------------------------------------------------------

    private fun decodeDict(bytes: ByteArray): BdecodeNode {
        val root = try {
            Bdecode.decode(bytes)
        } catch (_: BdecodeException) {
            throw TorrentException(LibtorrentError.INVALID_TRACKER_RESPONSE)
        }
        if (root.type != BdecodeNode.Type.DICT) {
            throw TorrentException(LibtorrentError.INVALID_TRACKER_RESPONSE)
        }
        return root
    }

    /**
     * Decode the `peers` member into [out]. A string member is the BEP-23 compact
     * 6-byte form; a list member is the BEP-3 dict model. Anything else is ignored
     * (libtorrent clears the node), matching `parse_tracker_response()`.
     */
    private fun decodePeers(node: BdecodeNode, out: MutableList<PeerEndpoint>) {
        when (node.type) {
            BdecodeNode.Type.STRING -> decodeCompactPeers4(node, out)
            BdecodeNode.Type.LIST -> {
                for (i in 0 until node.listSize()) {
                    decodeDictPeer(node.listAt(i))?.let(out::add)
                }
            }
            else -> { /* no peers / unsupported: ignore */ }
        }
    }

    /** BEP-23 compact IPv4 peers: consecutive 4-byte IP + 2-byte big-endian port. */
    private fun decodeCompactPeers4(node: BdecodeNode, out: MutableList<PeerEndpoint>) {
        val b = node.stringBytes()
        var i = 0
        while (i + 6 <= b.size) {
            val host = "${b[i].toInt() and 0xFF}.${b[i + 1].toInt() and 0xFF}." +
                "${b[i + 2].toInt() and 0xFF}.${b[i + 3].toInt() and 0xFF}"
            val port = ((b[i + 4].toInt() and 0xFF) shl 8) or (b[i + 5].toInt() and 0xFF)
            out.add(PeerEndpoint(host, port))
            i += 6
        }
    }

    /** Compact IPv6 peers (`peers6`): consecutive 16-byte IP + 2-byte big-endian port. */
    private fun decodeCompactPeers6(node: BdecodeNode, out: MutableList<PeerEndpoint>) {
        if (node.type != BdecodeNode.Type.STRING) return
        val b = node.stringBytes()
        var i = 0
        while (i + 18 <= b.size) {
            val sb = StringBuilder(39)
            for (group in 0 until 8) {
                if (group > 0) sb.append(':')
                val hi = b[i + group * 2].toInt() and 0xFF
                val lo = b[i + group * 2 + 1].toInt() and 0xFF
                sb.append(((hi shl 8) or lo).toString(16))
            }
            val port = ((b[i + 16].toInt() and 0xFF) shl 8) or (b[i + 17].toInt() and 0xFF)
            out.add(PeerEndpoint(sb.toString(), port))
            i += 18
        }
    }

    /**
     * Extract one dict-model peer (`extract_peer_info`): requires string `ip` and int
     * `port`; the optional `peer id` is ignored since the shared [PeerEndpoint] does
     * not carry it. Returns `null` for a malformed entry (libtorrent skips it).
     */
    private fun decodeDictPeer(node: BdecodeNode): PeerEndpoint? {
        if (node.type != BdecodeNode.Type.DICT) return null
        val ip = node.dictFindString("ip")
        val port = node.dictFindInt("port")
        if (!ip.isValid || !port.isValid) return null
        return PeerEndpoint(ip.stringValue(), port.intValue().toInt())
    }

    /** Format [v]'s low 32 bits as upper-case 8-digit hex, the `key=%08X` field. */
    private fun upperHex8(v: Int): String {
        val hex = (v.toLong() and 0xFFFFFFFFL).toString(16).uppercase()
        return if (hex.length >= 8) hex else "0".repeat(8 - hex.length) + hex
    }

    /** `indexOf` over a predicate starting at [from]; -1 if no char matches. */
    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < length) {
            if (predicate(this[i])) return i
            i++
        }
        return -1
    }
}

/**
 * Thrown when a tracker returns a `failure reason` member (BEP-3) — the typed
 * counterpart of libtorrent setting `resp.failure_reason` and raising the
 * `errors::tracker_failure` code. Carries both the human-readable [reason] text the
 * tracker sent and the corresponding [error] code ([LibtorrentError.TRACKER_FAILURE]),
 * so a caller can surface the message or branch on the code uniformly with the
 * plain [TorrentException]s this codec also throws.
 *
 * (It is a distinct [Exception] rather than a subclass of [TorrentException], which
 * the core declares `final`; the [error] property gives the same code access.)
 *
 * @property reason the failure text the tracker sent.
 * @property error always [LibtorrentError.TRACKER_FAILURE].
 */
class TrackerFailure(val reason: String) : Exception(reason) {
    val error: LibtorrentError get() = LibtorrentError.TRACKER_FAILURE
}
