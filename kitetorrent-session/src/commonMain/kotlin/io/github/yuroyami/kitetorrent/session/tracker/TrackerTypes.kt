package io.github.yuroyami.kitetorrent.session.tracker

import io.github.yuroyami.kitetorrent.PeerId
import io.github.yuroyami.kitetorrent.Sha1Hash

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
 */
data class AnnounceResponse(
    val interval: Int,
    val leechers: Int,
    val seeders: Int,
    val peers: List<PeerEndpoint>,
)

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
