package io.github.yuroyami.kitetorrent.alert

/**
 * Concrete tracker alerts ported from include/libtorrent/alert_types.hpp and
 * src/alert.cpp. They all extend [TrackerAlert], so [message] begins with the
 * `"<name> (<url>)[<localEndpoint>]"` tracker prefix.
 */

/**
 * Generated each time a tracker announce is sent (or attempted). Ported from
 * `libtorrent::tracker_announce_alert` (alert_type 17).
 *
 * libtorrent renders `<tracker-prefix> <v1|v2> sending announce (<event>)`.
 *
 * @property event the announce event sent to the tracker.
 * @property version the BitTorrent protocol version announced.
 */
data class TrackerAnnounceAlert(
    override val torrentName: String?,
    override val trackerUrl: String,
    override val localEndpoint: String,
    val event: TrackerEvent,
    val version: ProtocolVersion = ProtocolVersion.V1,
) : TrackerAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "tracker_announce"
    override val category: Int get() = AlertCategory.tracker

    override fun message(): String =
        trackerPrefix() + " " + version.label + " sending announce (" + event.label + ")"

    companion object { const val ALERT_TYPE: Int = 17 }
}

/**
 * Informational alert generated when a tracker announce succeeds (UDP, HTTP or
 * DHT). Ported from `libtorrent::tracker_reply_alert` (alert_type 15).
 *
 * libtorrent renders `<tracker-prefix> <v1|v2> received peers: <numPeers>`.
 *
 * @property numPeers how many peers the tracker returned.
 * @property version the BitTorrent protocol version announced.
 */
data class TrackerReplyAlert(
    override val torrentName: String?,
    override val trackerUrl: String,
    override val localEndpoint: String,
    val numPeers: Int,
    val version: ProtocolVersion = ProtocolVersion.V1,
) : TrackerAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "tracker_reply"
    override val category: Int get() = AlertCategory.tracker

    override fun message(): String =
        trackerPrefix() + " " + version.label + " received peers: " + numPeers

    companion object { const val ALERT_TYPE: Int = 15 }
}

/**
 * Generated on tracker timeouts, premature disconnects, invalid responses or a
 * non-`200 OK` HTTP response. Ported from `libtorrent::tracker_error_alert`
 * (alert_type 11).
 *
 * libtorrent renders `<tracker-prefix> <v1|v2> <error-message> "<failure-reason>" (<timesInRow>)`.
 *
 * @property timesInRow how many times in a row this tracker has failed.
 * @property errorMessage the description of why the announce failed.
 * @property failureReason the tracker's "failure reason" string, if any (may be empty).
 * @property version the BitTorrent protocol version announced.
 */
data class TrackerErrorAlert(
    override val torrentName: String?,
    override val trackerUrl: String,
    override val localEndpoint: String,
    val timesInRow: Int,
    val errorMessage: String,
    val failureReason: String = "",
    val version: ProtocolVersion = ProtocolVersion.V1,
) : TrackerAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "tracker_error"
    override val category: Int get() = AlertCategory.tracker or AlertCategory.error

    override fun message(): String =
        trackerPrefix() + " " + version.label + " " + errorMessage +
            " \"" + failureReason + "\" (" + timesInRow + ")"

    companion object { const val ALERT_TYPE: Int = 11 }
}

/**
 * Triggered when the tracker reply contains a warning field. The announce
 * usually succeeded but the tracker has a message for the client. Ported from
 * `libtorrent::tracker_warning_alert` (alert_type 12).
 *
 * libtorrent renders `<tracker-prefix> <v1|v2> warning: <warningMessage>`.
 *
 * @property warningMessage the warning text from the tracker.
 * @property version the BitTorrent protocol version announced.
 */
data class TrackerWarningAlert(
    override val torrentName: String?,
    override val trackerUrl: String,
    override val localEndpoint: String,
    val warningMessage: String,
    val version: ProtocolVersion = ProtocolVersion.V1,
) : TrackerAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "tracker_warning"
    override val category: Int get() = AlertCategory.tracker or AlertCategory.error

    override fun message(): String =
        trackerPrefix() + " " + version.label + " warning: " + warningMessage

    companion object { const val ALERT_TYPE: Int = 12 }
}

/**
 * Generated each time the DHT returns peers for a torrent. Ported from
 * `libtorrent::dht_reply_alert` (alert_type 16). In libtorrent this derives from
 * `tracker_alert` but is constructed with an empty URL and endpoint, and its
 * `message()` omits the version/url, rendering only `<prefix> received DHT peers: <n>`.
 *
 * @property numPeers the number of peers received in this packet.
 */
data class DhtReplyAlert(
    override val torrentName: String?,
    val numPeers: Int,
) : TrackerAlert() {
    override val trackerUrl: String get() = ""
    override val localEndpoint: String get() = ""

    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "dht_reply"
    override val category: Int get() = AlertCategory.dht or AlertCategory.tracker

    override fun message(): String =
        trackerPrefix() + " received DHT peers: " + numPeers

    companion object { const val ALERT_TYPE: Int = 16 }
}
