package io.github.yuroyami.kitetorrent.session.tracker

import io.github.yuroyami.kitetorrent.PeerId
import io.github.yuroyami.kitetorrent.Sha1Hash
import kotlin.random.Random

/**
 * The transport-neutral tracker request/response contract shared by the HTTP
 * (BEP-3 / BEP-23) and UDP (BEP-15) tracker clients.
 *
 * This is the KiteTorrent port of the fields libtorrent threads through its
 * `tracker_request` / `tracker_response` structs (`tracker_manager.hpp`). Those
 * structs carry a great deal of HTTP-specific and session-bookkeeping state; here
 * we keep only the protocol payload that both wire formats actually exchange, so
 * the same value object drives either client.
 *
 * NOTE (cross-agent contract): these types are the agreed seam between the UDP and
 * HTTP tracker modules. If the HTTP tracker module also declares them, only one
 * copy must survive — they are intentionally defined once, here, in the shared
 * `...session.tracker` package. The UDP client ([UdpTracker]) consumes them.
 */

/**
 * The announce `event`, porting libtorrent's `enum class event_t` (`tracker_manager.hpp`).
 *
 * The [ordinal][code] of each entry is significant: it is exactly the integer
 * libtorrent writes into the BEP-15 announce packet (`write_int32(req.event, out)`)
 * and the string it maps to in the HTTP `&event=` query parameter. The values are
 * therefore fixed by the wire protocol: `none=0, completed=1, started=2, stopped=3`.
 * libtorrent additionally defines `paused=4`, which it only ever uses internally to
 * tweak request parameters (it is never sent as an event code); we mirror it so the
 * enum is faithful, but the UDP encoder clamps any code it does not recognise to
 * `none` the way a tracker would interpret an out-of-range value.
 */
enum class TrackerEvent(val code: Int) {
    NONE(0),
    COMPLETED(1),
    STARTED(2),
    STOPPED(3),
    PAUSED(4),
}

/**
 * A single peer endpoint returned by a tracker — an IP address (in dotted-quad or
 * colon-hex string form) and a TCP port. This is the flattened equivalent of
 * libtorrent's `ipv4_peer_entry` / `ipv6_peer_entry` (`peer.hpp`); KiteTorrent's
 * peer layer keeps addresses as host strings (see `peer.PeerAddress`), so the
 * compact 6-byte (v4) / 18-byte (v6) tracker encodings are decoded into strings here.
 *
 * @property host the peer's IP literal, e.g. `"10.0.0.1"` or `"2001:db8::1"`.
 * @property port the peer's TCP port (1..65535).
 */
data class PeerEndpoint(val host: String, val port: Int)

/**
 * The parameters of a tracker announce, independent of whether it will be sent over
 * HTTP or UDP. Mirrors the announce-relevant subset of libtorrent's
 * `tracker_request`.
 *
 * @property infoHash the 20-byte v1 info-hash identifying the torrent.
 * @property peerId our 20-byte peer id.
 * @property port the TCP port we are listening on for incoming peers (`listen_port`).
 * @property uploaded total bytes uploaded this session.
 * @property downloaded total bytes downloaded this session.
 * @property left bytes still needed to complete (0 once we are a seed).
 * @property event the announce [event][TrackerEvent].
 * @property numWant maximum peers to receive; libtorrent's `num_want`. The BEP-15
 *   sentinel for "tracker's default" is `-1`, which is the default here.
 * @property key a random per-torrent identifier the tracker uses to recognise us
 *   across IP changes (BEP-15 `key`, libtorrent `req.key`). Defaults to 0.
 * @property ip the IPv4 address to advertise to the tracker, as a 32-bit value in
 *   host byte order, or 0 for "use the source address of the packet" (BEP-15 `IP`).
 *   libtorrent fills this from `settings_pack::announce_ip`; 0 is the common case.
 * @property trackerId the opaque `&trackerid=` value an HTTP tracker previously
 *   handed us in a `tracker id` response field, echoed back on subsequent announces
 *   (libtorrent `tracker_req().trackerid`). Empty means "none captured yet" and the
 *   parameter is omitted from the query. UDP trackers have no such field, so this is
 *   ignored by the UDP codec.
 */
data class AnnounceRequest(
    val infoHash: Sha1Hash,
    val peerId: PeerId,
    val port: Int,
    val uploaded: Long,
    val downloaded: Long,
    val left: Long,
    val event: TrackerEvent = TrackerEvent.NONE,
    val numWant: Int = -1,
    val key: Int = 0,
    val ip: Int = 0,
    val trackerId: String = "",
)

/**
 * A tracker's response to an announce — the announce-relevant subset of
 * libtorrent's `tracker_response`.
 *
 * @property interval seconds the client should wait before re-announcing
 *   (libtorrent `resp.interval`).
 * @property leechers number of incomplete peers (downloaders) the tracker knows of,
 *   i.e. libtorrent's `resp.incomplete`. `-1` if the tracker did not report it.
 * @property seeders number of complete peers (seeds), libtorrent's `resp.complete`.
 *   `-1` if not reported.
 * @property peers the peer endpoints the tracker returned.
 * @property minInterval the minimum seconds the client must wait before
 *   re-announcing, even when re-announcing early on a peer-shortage or a manual
 *   trigger (libtorrent `resp.min_interval`, BEP-3 `min interval`). Defaults to
 *   [DEFAULT_MIN_INTERVAL] (30s) when the tracker omits it, matching libtorrent.
 * @property trackerId the opaque `tracker id` the HTTP tracker asked us to echo on
 *   the next announce (libtorrent `resp.trackerid`). Empty when not present; UDP
 *   trackers never set it.
 */
data class AnnounceResponse(
    val interval: Int,
    val leechers: Int,
    val seeders: Int,
    val peers: List<PeerEndpoint>,
    val minInterval: Int = DEFAULT_MIN_INTERVAL,
    val trackerId: String = "",
) {
    companion object {
        /**
         * libtorrent's default `min interval` when the tracker omits it
         * (`e.dict_find_int_value("min interval", 30)`).
         */
        const val DEFAULT_MIN_INTERVAL = 30

        /**
         * libtorrent's default `interval` when the tracker omits it
         * (`e.dict_find_int_value("interval", 1800)`).
         */
        const val DEFAULT_INTERVAL = 1800
    }
}

/**
 * A tracker's response to a scrape — the per-info-hash swarm statistics, porting the
 * arguments libtorrent passes to `request_callback::tracker_scrape_response`
 * (complete, incomplete, downloaded, downloaders).
 *
 * @property seeders complete peers (`complete`).
 * @property completed total times the torrent has been downloaded to completion
 *   (`downloaded`).
 * @property leechers incomplete peers (`incomplete`).
 */
data class ScrapeResponse(
    val seeders: Int,
    val completed: Int,
    val leechers: Int,
)

/**
 * Per-URL announce bookkeeping — the KiteTorrent port of the live state libtorrent
 * carries in `aux::announce_entry` / `aux::announce_infohash`
 * (`include/libtorrent/aux_/announce_entry.hpp`). The session module owns the
 * announce *loop* and tier logic; this struct is the mutable state it threads per
 * tracker URL so the loop can decide *when* to (re-)announce and *what* to send.
 *
 * It is a plain mutable holder rather than a data class because the loop mutates it
 * in place across announces, exactly as libtorrent mutates the fields of an
 * `announce_entry` between calls.
 *
 * @property url the tracker's announce URL (libtorrent `announce_entry::url`).
 * @property tier the tier this tracker belongs to; trackers are tried tier by tier,
 *   lowest first (BEP-12, libtorrent `announce_entry::tier`).
 * @property fails consecutive failed announces; reset to 0 on success. libtorrent
 *   stores this as a 7-bit field (`announce_infohash::fails`), so it saturates at 127.
 * @property isWorking whether the most recent announce succeeded — libtorrent derives
 *   `is_working()` as `fails == 0`, which we mirror via [markWorking]/[markFailed].
 * @property nextAnnounceEpochSecs the wall-clock epoch-second instant the loop may
 *   next announce to this tracker (libtorrent `announce_infohash::next_announce`),
 *   `0` meaning "as soon as possible".
 * @property minAnnounceEpochSecs the earliest epoch-second the loop may re-announce
 *   even for an early/forced refresh (libtorrent `announce_infohash::min_announce`),
 *   derived from a response's [AnnounceResponse.minInterval].
 * @property lastEvent the last [TrackerEvent] we sent this tracker, so the loop can
 *   send `started` exactly once and decide whether `completed` is still owed.
 * @property completeSent whether we have already sent a `completed` event to this
 *   tracker (libtorrent `announce_infohash::complete_sent`); prevents re-sending it.
 * @property startSent whether we have already sent a `started` event
 *   (libtorrent `announce_infohash::start_sent`).
 * @property trackerId the last `tracker id` string this tracker handed back, to be
 *   echoed on the next announce (libtorrent `announce_entry::trackerid`). Empty if none.
 */
class AnnounceEntry(
    val url: String,
    val tier: Int = 0,
    var fails: Int = 0,
    var isWorking: Boolean = true,
    var nextAnnounceEpochSecs: Long = 0L,
    var minAnnounceEpochSecs: Long = 0L,
    var lastEvent: TrackerEvent = TrackerEvent.NONE,
    var completeSent: Boolean = false,
    var startSent: Boolean = false,
    var trackerId: String = "",
) {
    /**
     * Record a successful announce against this tracker at [nowEpochSecs]: clear the
     * failure counter, mark working, and schedule the next announce [interval] seconds
     * out with the floor at [minInterval] (libtorrent sets both `next_announce` and
     * `min_announce` on a good response). Captures any [trackerId] echoed.
     */
    fun markWorking(nowEpochSecs: Long, interval: Int, minInterval: Int, trackerId: String = this.trackerId) {
        fails = 0
        isWorking = true
        this.trackerId = trackerId
        nextAnnounceEpochSecs = nowEpochSecs + interval.toLong().coerceAtLeast(0L)
        minAnnounceEpochSecs = nowEpochSecs + minInterval.toLong().coerceAtLeast(0L)
    }

    /**
     * Record a failed announce at [nowEpochSecs], porting `announce_infohash::failed`:
     * bump [fails] (saturating at 127), mark not-working, and push [nextAnnounceEpochSecs]
     * out by the exponential back-off in [TrackerBackoff.nextDelaySecs]. [retryIntervalSecs]
     * is libtorrent's `retry_interval` floor (e.g. a tracker-supplied retry hint).
     */
    fun markFailed(
        nowEpochSecs: Long,
        backoffRatio: Int = TrackerBackoff.DEFAULT_BACKOFF_RATIO,
        retryIntervalSecs: Int = 0,
    ) {
        if (fails < (1 shl 7) - 1) fails++
        isWorking = false
        val delay = TrackerBackoff.nextDelaySecs(fails, backoffRatio, retryIntervalSecs)
        nextAnnounceEpochSecs = nowEpochSecs + delay
    }
}

/**
 * The exponential failure back-off libtorrent applies between failed announces,
 * ported from `announce_infohash::failed` (`src/announce_entry.cpp`). Exposed as a
 * pure helper so the session's announce loop can compute the next-announce instant
 * without duplicating the formula. The loop owns *when* to call it; this just does
 * the arithmetic.
 */
object TrackerBackoff {
    /** libtorrent `tracker_retry_delay_min` — never retry sooner than 5 seconds. */
    const val MIN_DELAY_SECS = 5

    /** libtorrent `tracker_retry_delay_max` — never wait more than 60 minutes. */
    const val MAX_DELAY_SECS = 60 * 60

    /** libtorrent `settings_pack::tracker_backoff` default of 250 (percent). */
    const val DEFAULT_BACKOFF_RATIO = 250

    /**
     * The seconds to wait before the next announce after [fails] consecutive
     * failures, porting libtorrent's
     * `max(retry_interval, min(tracker_retry_delay_max, tracker_retry_delay_min + fails² * tracker_retry_delay_min * backoff_ratio / 100))`.
     *
     * With the default [backoffRatio] of 250 the sequence is 17, 55, 117, 205, ...
     * seconds (capped at one hour), exactly libtorrent's curve. [retryIntervalSecs]
     * is the lower bound libtorrent's `retry_interval` argument imposes (0 = none).
     *
     * @param fails consecutive failures so far (>= 1 on a real failure; clamped to >= 0).
     */
    fun nextDelaySecs(
        fails: Int,
        backoffRatio: Int = DEFAULT_BACKOFF_RATIO,
        retryIntervalSecs: Int = 0,
    ): Long {
        val f = fails.coerceAtLeast(0).toLong()
        val failSquare = f * f
        val backoff = MIN_DELAY_SECS + failSquare * MIN_DELAY_SECS * backoffRatio / 100
        val capped = minOf(MAX_DELAY_SECS.toLong(), backoff)
        return maxOf(retryIntervalSecs.toLong(), capped)
    }
}

/**
 * The per-torrent `&key=` value libtorrent threads into every announce
 * (`torrent::tracker_key()`), a stable 32-bit identifier the tracker uses to
 * recognise us across IP changes. libtorrent derives it deterministically from
 * object addresses; that is impossible (and pointless) on KMP, so we generate a
 * random non-prediction-relevant 32-bit value once per torrent and reuse it — the
 * wire semantics ("the same opaque key on every announce for this torrent") are
 * identical. The old default of `0` is replaced by [generate].
 */
object TrackerKey {
    /** Draw a fresh random 32-bit announce key for a torrent. */
    fun generate(random: Random = Random.Default): Int = random.nextInt()
}
