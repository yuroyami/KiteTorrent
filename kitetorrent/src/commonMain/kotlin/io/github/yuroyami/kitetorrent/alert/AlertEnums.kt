package io.github.yuroyami.kitetorrent.alert

/**
 * The BitTorrent protocol version carried by tracker alerts, ported from
 * `libtorrent::protocol_version` (include/libtorrent/info_hash.hpp). libtorrent
 * renders it as the literal `"v1"` or `"v2"` inside several tracker `message()`s.
 */
enum class ProtocolVersion(val label: String) {
    /** BitTorrent v1 (SHA-1 info-hash). */
    V1("v1"),

    /** BitTorrent v2 (SHA-256 info-hash). */
    V2("v2");
}

/**
 * The tracker announce event, ported from `libtorrent::event_t`
 * (include/libtorrent/tracker_manager.hpp). The [label] strings match the
 * `event_str[]` table used by `tracker_announce_alert::message()`.
 */
enum class TrackerEvent(val label: String) {
    /** Periodic announce, no state transition. */
    NONE("none"),

    /** The download just completed. */
    COMPLETED("completed"),

    /** The first announce for this torrent. */
    STARTED("started"),

    /** A graceful shutdown announce. */
    STOPPED("stopped"),

    /** The torrent was paused. */
    PAUSED("paused");
}

/**
 * The direction of a peer connection, ported from
 * `libtorrent::peer_connect_alert::direction_t`. Rendered as `"incoming"` /
 * `"outgoing"` by `peer_connect_alert::message()`.
 */
enum class PeerConnectDirection(val label: String) {
    /** An accepted incoming connection. */
    IN("incoming"),

    /** A successful outgoing connection attempt. */
    OUT("outgoing");
}

/**
 * The torrent state, ported from `libtorrent::torrent_status::state_t`
 * (include/libtorrent/torrent_status.hpp). The [label] strings match the
 * `state_str[]` table used by `state_changed_alert::message()`.
 *
 * Note the enum *ordinal* matters: `state_changed_alert::message()` indexes
 * `state_str[state]` by the raw enum value, so the declaration order here is
 * the libtorrent order. (The historical `queued_for_checking == 0` slot was
 * removed long ago, so `checking_files` is value 1, etc.; the printed table
 * still carries the leading `"checking (q)"` entry, reproduced here.)
 */
enum class TorrentState(val label: String) {
    /** Queued for checking (legacy slot, value 0). */
    CHECKING_QUEUED("checking (q)"),

    /** Verifying already-downloaded data against piece hashes. */
    CHECKING_FILES("checking"),

    /** Downloading the `.torrent` metadata via the metadata extension. */
    DOWNLOADING_METADATA("dl metadata"),

    /** Downloading pieces. */
    DOWNLOADING("downloading"),

    /** All wanted pieces are present, but not seeding yet. */
    FINISHED("finished"),

    /** Seeding to other peers. */
    SEEDING("seeding"),

    /** Allocating files on disk (legacy). */
    ALLOCATING("allocating"),

    /** Resuming a partially-checked torrent. */
    CHECKING_RESUME_DATA("checking (r)");
}

/**
 * Performance-warning codes, ported from
 * `libtorrent::performance_alert::performance_warning_t`. The [label] strings are
 * the `warning_str[]` table from `performance_warning_str()` in src/alert.cpp.
 */
enum class PerformanceWarning(val label: String) {
    OUTSTANDING_DISK_BUFFER_LIMIT_REACHED("max outstanding disk writes reached"),
    OUTSTANDING_REQUEST_LIMIT_REACHED("max outstanding piece requests reached"),
    UPLOAD_LIMIT_TOO_LOW("upload limit too low (download rate will suffer)"),
    DOWNLOAD_LIMIT_TOO_LOW("download limit too low (upload rate will suffer)"),
    SEND_BUFFER_WATERMARK_TOO_LOW("send buffer watermark too low (upload rate will suffer)"),
    TOO_MANY_OPTIMISTIC_UNCHOKE_SLOTS("too many optimistic unchoke slots"),
    TOO_HIGH_DISK_QUEUE_LIMIT(
        "the disk queue limit is too high compared to the cache size. The disk queue eats into the cache size"
    ),
    AIO_LIMIT_REACHED("outstanding AIO operations limit reached"),

    /** Deprecated slot (`bittyrant_with_no_uplimit`), empty in libtorrent. */
    DEPRECATED_BITTYRANT(""),
    TOO_FEW_OUTGOING_PORTS("too few ports allowed for outgoing connections"),
    TOO_FEW_FILE_DESCRIPTORS("too few file descriptors are allowed for this process. connection limit lowered");
}

/**
 * The reason a peer was blocked, ported from
 * `libtorrent::peer_blocked_alert::reason_t`. The [label] strings are the
 * `reason_str[]` table from `peer_blocked_alert::message()`.
 */
enum class PeerBlockReason(val label: String) {
    IP_FILTER("ip_filter"),
    PORT_FILTER("port_filter"),
    I2P_MIXED("i2p_mixed"),
    PRIVILEGED_PORTS("privileged_ports"),
    UTP_DISABLED("utp_disabled"),
    TCP_DISABLED("tcp_disabled"),
    INVALID_LOCAL_INTERFACE("invalid_local_interface"),
    SSRF_MITIGATION("ssrf_mitigation");
}
