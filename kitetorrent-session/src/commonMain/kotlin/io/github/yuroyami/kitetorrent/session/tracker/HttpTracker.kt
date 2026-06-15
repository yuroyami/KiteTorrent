package io.github.yuroyami.kitetorrent.session.tracker

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes

/**
 * HTTP(S) tracker client — the network half of libtorrent's
 * `http_tracker_connection`. All the protocol logic (building the announce URL with
 * percent-encoded binary info-hash/peer-id, parsing the bencoded response incl.
 * BEP-23 compact peers) lives in the pure, tested [HttpTrackerCodec]; this class is
 * just the ktor-client GET around it.
 *
 * The [client] is injected so the platform engine (CIO on JVM/Android, Darwin on iOS)
 * is chosen by the caller, and so it can be mocked in tests.
 */
class HttpTracker(private val client: HttpClient) {

    /** Announce to [announceUrl] and parse the swarm response. */
    suspend fun announce(announceUrl: String, req: AnnounceRequest): AnnounceResponse {
        val url = HttpTrackerCodec.buildAnnounceUrl(announceUrl, req)
        val body = client.get(url).readRawBytes()
        return HttpTrackerCodec.parseAnnounceResponse(body)
    }

    /** Scrape swarm stats for [infoHashes] from the tracker behind [announceUrl]. */
    suspend fun scrape(announceUrl: String, infoHashes: List<Sha1Hash>): Map<Sha1Hash, ScrapeResponse> {
        val scrapeUrl = HttpTrackerCodec.announceToScrape(announceUrl) ?: return emptyMap()
        val url = HttpTrackerCodec.buildScrapeUrl(scrapeUrl, infoHashes)
        val body = client.get(url).readRawBytes()
        return infoHashes.associateWith { HttpTrackerCodec.parseScrapeResponse(body, it) }
    }
}
