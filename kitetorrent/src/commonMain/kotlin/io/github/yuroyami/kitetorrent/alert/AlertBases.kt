package io.github.yuroyami.kitetorrent.alert

/**
 * Base class for alerts associated with a specific torrent, ported from
 * `libtorrent::torrent_alert` (include/libtorrent/alert_types.hpp).
 *
 * In libtorrent this holds a `torrent_handle`; KiteTorrent v0 has no handle type
 * yet, so it carries just the torrent's display [torrentName] (which is all the
 * C++ `message()` actually renders — `torrent_alert::message()` returns
 * `torrent_name()`, or `" - "` when the handle is invalid).
 *
 * @property torrentName the torrent's name, or `null` when the handle would be
 *   invalid (rendered as `" - "`, matching libtorrent).
 */
abstract class TorrentAlert : Alert() {

    abstract val torrentName: String?

    /**
     * The text `torrent_alert::message()` produces: the torrent name, or `" - "`
     * when there is no valid handle. Concrete subclasses append to this.
     */
    protected fun torrentPrefix(): String = torrentName ?: " - "

    override fun message(): String = torrentPrefix()
}

/**
 * Base class for alerts referring to a specific peer, ported from
 * `libtorrent::peer_alert` (include/libtorrent/alert_types.hpp).
 *
 * libtorrent renders `torrent_alert::message() + " peer [ " + print_endpoint(endpoint)
 * + " client: " + identify_client(pid) + " ]"`. KiteTorrent v0 has no peer-id
 * client-identification yet, so [client] defaults to a placeholder; the endpoint
 * is carried verbatim as a printable [endpoint] string.
 *
 * @property endpoint the peer's address and port, already formatted (e.g. `"1.2.3.4:6881"`).
 * @property client the identified client string for the peer id, if known.
 */
abstract class PeerAlert : TorrentAlert() {

    abstract val endpoint: String
    open val client: String get() = "Unknown"

    /** The text `peer_alert::message()` produces. Concrete subclasses append to this. */
    protected fun peerPrefix(): String =
        torrentPrefix() + " peer [ " + endpoint + " client: " + client + " ]"

    override fun message(): String = peerPrefix()
}

/**
 * Base class for alerts associated with a specific tracker, ported from
 * `libtorrent::tracker_alert` (include/libtorrent/alert_types.hpp). Derives from
 * [TorrentAlert] because a tracker is always tied to a torrent.
 *
 * libtorrent renders `torrent_alert::message() + " (" + tracker_url() + ")" + "["
 * + print_endpoint(local_endpoint) + "]"`.
 *
 * @property trackerUrl the tracker URL being announced to.
 * @property localEndpoint the local listen interface endpoint, already formatted.
 */
abstract class TrackerAlert : TorrentAlert() {

    abstract val trackerUrl: String
    abstract val localEndpoint: String

    /** The text `tracker_alert::message()` produces. Concrete subclasses append to this. */
    protected fun trackerPrefix(): String =
        torrentPrefix() + " (" + trackerUrl + ")" + "[" + localEndpoint + "]"

    override fun message(): String = trackerPrefix()
}
