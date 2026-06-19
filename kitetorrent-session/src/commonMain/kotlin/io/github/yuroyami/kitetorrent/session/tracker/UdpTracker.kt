package io.github.yuroyami.kitetorrent.session.tracker

import io.github.yuroyami.kitetorrent.error.LibtorrentError
import io.github.yuroyami.kitetorrent.error.TorrentException
import io.github.yuroyami.kitetorrent.session.net.UdpPacket
import io.github.yuroyami.kitetorrent.session.net.UdpSocket
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

/**
 * A BEP-15 UDP tracker client — the live half of libtorrent's
 * `udp_tracker_connection` (`src/udp_tracker_connection.cpp`), driven over the
 * proven [UdpSocket]. The fixed-layout packet build/parse lives in
 * [UdpTrackerCodec]; this class owns the *transaction*: the mandatory two-step
 * connect→announce handshake, the random transaction ids, connection-id caching,
 * and the "ignore datagrams that aren't ours" filtering.
 *
 * libtorrent retransmits a UDP request a few times with a growing timeout before
 * giving up (`tracker_completion_timeout` / `tracker_receive_timeout`). We do the
 * same: each request is attempted up to [maxAttempts] times, with the per-attempt
 * receive bounded by an exponentially growing timeout starting at [baseTimeoutMs]
 * — the BEP-15 recommended `15 * 2^n` back-off, capped so a single announce cannot
 * hang the caller. Cancellation propagates through structured concurrency: cancel
 * the calling coroutine and every `receive()` unwinds.
 *
 * BEP-15 caches a tracker's `connection_id` for ~60 seconds; libtorrent keeps a
 * process-wide `m_connection_cache` keyed by tracker address. We keep an
 * instance-level cache keyed by `host:port`, refreshed by [connect] and reused by
 * [announce]/[scrape] until it expires after [connectionIdTtlMs].
 *
 * @property udp the bound UDP socket to send from and receive on. Ownership stays
 *   with the caller — this class never closes it.
 * @property random the source of transaction ids (and so, indirectly, of the
 *   anti-spoofing guarantee). Inject a seeded [Random] in tests.
 */
class UdpTracker(
    private val udp: UdpSocket,
    private val random: Random = Random.Default,
    private val maxAttempts: Int = 4,
    private val baseTimeoutMs: Long = 15_000L,
    private val connectionIdTtlMs: Long = 60_000L,
    private val nowMs: () -> Long = { 0L },
) {

    /** A cached BEP-15 connection id and the wall-clock instant it stops being valid. */
    private class CachedConnection(val connectionId: Long, val expiresAtMs: Long)

    private val connectionCache = HashMap<String, CachedConnection>()

    /**
     * Announce to the UDP tracker at [host]:[port], performing the BEP-15
     * connect→announce two-step (reusing a cached connection id when one is still
     * valid), and return the parsed [AnnounceResponse].
     *
     * Mirrors `udp_tracker_connection::start_announce` → `send_udp_announce` →
     * `on_announce_response`.
     *
     * @throws TorrentException if the tracker returns a BEP-15 error packet, or if
     *   no valid response arrives within [maxAttempts] attempts.
     */
    suspend fun announce(host: String, port: Int, req: AnnounceRequest): AnnounceResponse {
        val connectionId = obtainConnectionId(host, port)
        val transactionId = newTransactionId()
        val request = UdpTrackerCodec.buildAnnounceRequest(connectionId, transactionId, req)
        val response = transact(host, port, request, transactionId, UdpTrackerCodec.ACTION_ANNOUNCE)
        // libtorrent picks the peer stride from the *endpoint family* it sent the
        // announce to (on_announce_response branches on the resolved address): an IPv6
        // tracker returns 18-byte (16+2) compact peers, an IPv4 tracker 6-byte (4+2).
        // We key off whether [host] is an IPv6 literal.
        return UdpTrackerCodec.parseAnnounceResponse(response, transactionId, ipv6 = isIpv6Host(host))
            ?: throw TorrentException(LibtorrentError.INVALID_TRACKER_RESPONSE_LENGTH)
    }

    /**
     * Scrape the swarm statistics for [req]'s info-hash from the UDP tracker at
     * [host]:[port]. Mirrors `send_udp_scrape` → `on_scrape_response`.
     *
     * @throws TorrentException on a tracker error packet or no valid response.
     */
    suspend fun scrape(host: String, port: Int, req: AnnounceRequest): ScrapeResponse {
        val connectionId = obtainConnectionId(host, port)
        val transactionId = newTransactionId()
        val request = UdpTrackerCodec.buildScrapeRequest(
            connectionId, transactionId, req.infoHash.toByteArray(),
        )
        val response = transact(host, port, request, transactionId, UdpTrackerCodec.ACTION_SCRAPE)
        return UdpTrackerCodec.parseScrapeResponse(response, transactionId)
            ?: throw TorrentException(LibtorrentError.INVALID_TRACKER_RESPONSE_LENGTH)
    }

    /**
     * Return a valid connection id for [host]:[port], reusing the cache when the
     * cached entry has not yet expired, otherwise performing a fresh BEP-15 connect
     * exchange. Ports the cache lookup at the top of `start_announce` plus
     * `send_udp_connect` / `on_connect_response`.
     */
    private suspend fun obtainConnectionId(host: String, port: Int): Long {
        val key = "$host:$port"
        val cached = connectionCache[key]
        if (cached != null && nowMs() < cached.expiresAtMs) return cached.connectionId

        val transactionId = newTransactionId()
        val request = UdpTrackerCodec.buildConnectRequest(transactionId)
        val response = transact(host, port, request, transactionId, UdpTrackerCodec.ACTION_CONNECT)
        val result = UdpTrackerCodec.parseConnectResponse(response, transactionId)
            ?: throw TorrentException(LibtorrentError.INVALID_TRACKER_RESPONSE_LENGTH)

        connectionCache[key] = CachedConnection(result.connectionId, nowMs() + connectionIdTtlMs)
        return result.connectionId
    }

    /**
     * Send [request] to [host]:[port] and return the first datagram that is a valid
     * response of [expectedAction] for [transactionId], retransmitting up to
     * [maxAttempts] times with a growing per-attempt timeout.
     *
     * This is the heart of the UDP transaction loop. Within one attempt we keep
     * draining [UdpSocket.receive] — discarding datagrams whose transaction id does
     * not match (the spoofing/late-reply guard from `on_receive`) — until either the
     * matching response arrives, a tracker error packet arrives (surfaced as a
     * [TorrentException]), or the attempt's timeout elapses and we retransmit.
     */
    private suspend fun transact(
        host: String,
        port: Int,
        request: ByteArray,
        transactionId: Int,
        expectedAction: Int,
    ): ByteArray {
        var attempt = 0
        var timeout = baseTimeoutMs
        while (attempt < maxAttempts) {
            udp.send(request, host, port)
            val reply = try {
                withTimeout(timeout) { awaitMatching(transactionId, expectedAction) }
            } catch (e: TimeoutCancellationException) {
                null
            }
            if (reply != null) {
                // a BEP-15 error packet maps to errors::tracker_failure, exactly as
                // udp_tracker_connection::on_receive does. The tracker's human-readable
                // message (reply.errorMessage) is informational only — libtorrent merely
                // logs it — so the carried error is the faithful error code.
                if (reply.isError) throw TorrentException(LibtorrentError.TRACKER_FAILURE)
                return reply.packet
            }
            attempt++
            timeout *= 2
        }
        // exhausted all retransmissions — errors::timed_out, as in on_timeout
        throw TorrentException(LibtorrentError.TIMED_OUT)
    }

    /** The outcome of waiting for a datagram: either the matching payload or a tracker error. */
    private class Reply(val packet: ByteArray, val isError: Boolean, val errorMessage: String?)

    /**
     * Suspend, draining datagrams, until one matches [transactionId] as either an
     * [expectedAction] response or a BEP-15 error packet. Datagrams shorter than the
     * 8-byte header, or whose transaction id is not ours, are dropped and we keep
     * waiting (the loop in `udp_tracker_connection::on_receive`). The enclosing
     * [withTimeout] bounds how long this can block.
     */
    private suspend fun awaitMatching(transactionId: Int, expectedAction: Int): Reply {
        while (true) {
            val datagram: UdpPacket = udp.receive()
            val packet = datagram.data
            val action = UdpTrackerCodec.peekAction(packet)

            val err = UdpTrackerCodec.parseErrorMessage(packet, transactionId)
            if (err != null) return Reply(packet, isError = true, errorMessage = err)

            if (action == expectedAction) {
                // confirm the transaction id matches before accepting, mirroring on_receive
                if (matchesTransaction(packet, transactionId)) {
                    return Reply(packet, isError = false, errorMessage = null)
                }
            }
            // otherwise: stray/late/spoofed datagram — ignore and keep waiting
        }
    }

    /** True if [packet] carries [transactionId] in its 4..8 header slot. */
    private fun matchesTransaction(packet: ByteArray, transactionId: Int): Boolean {
        if (packet.size < 8) return false
        val tid = ((packet[4].toInt() and 0xFF) shl 24) or
            ((packet[5].toInt() and 0xFF) shl 16) or
            ((packet[6].toInt() and 0xFF) shl 8) or
            (packet[7].toInt() and 0xFF)
        return tid == transactionId
    }

    /**
     * Draw a non-zero random transaction id, porting
     * `udp_tracker_connection::update_transaction_id` (which avoids 0 because the
     * protocol treats 0 as "uninitialised").
     */
    private fun newTransactionId(): Int {
        var id = random.nextInt()
        if (id == 0) id = 1
        return id
    }

    /**
     * Whether [host] is an IPv6 literal, used to choose the BEP-15 compact-peer stride
     * (18 bytes for v6, 6 for v4). An IPv6 literal is the only host form that contains a
     * colon — IPv4 dotted-quads and DNS names never do — so a `:` is a sufficient test.
     * A bracketed literal (`[2001:db8::1]`) also qualifies.
     */
    private fun isIpv6Host(host: String): Boolean = host.indexOf(':') >= 0
}
