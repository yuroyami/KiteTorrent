package io.github.yuroyami.kitetorrent.alert

/**
 * Alert category bitmask, ported from `libtorrent::alert_category` together with
 * the `alert::*_notification` static constants (include/libtorrent/alert.hpp).
 *
 * Each alert belongs to one or more categories. libtorrent's
 * `settings_pack::alert_mask` is a combination of these flags; only alerts whose
 * category intersects the mask are posted (except non-discardable response alerts
 * such as [SaveResumeDataAlert] and [SessionStatsAlert]).
 *
 * In libtorrent the type is `flags::bitfield_flag<std::uint32_t>` where each
 * constant is a single set bit (`0_bit`, `1_bit`, ...). Here the same bit layout
 * is carried in a plain [Int] used as a 32-bit mask. The bit *positions* are part
 * of libtorrent's ABI (they map onto the alert mask the user configures), so they
 * are reproduced exactly.
 *
 * The `*_notification` spellings are kept as aliases because that is how the
 * `alert::` base class exposes them and how most client code refers to them.
 */
object AlertCategory {

    private fun bit(n: Int): Int = 1 shl n

    /**
     * Reports an error: tracker errors/warnings, file errors, resume-data
     * failures, web-seed errors, `.torrent` file errors, listen-socket errors,
     * port-mapping errors. `alert_category::error` (bit 0).
     */
    val error: Int = bit(0)

    /** Peers sending invalid requests, getting banned or snubbed. `alert_category::peer` (bit 1). */
    val peer: Int = bit(1)

    /** Port-mapping events for NAT-PMP and UPnP. `alert_category::port_mapping` (bit 2). */
    val portMapping: Int = bit(2)

    /** Storage events: file errors and move/rename synchronization. `alert_category::storage` (bit 3). */
    val storage: Int = bit(3)

    /** All tracker events: announces, responses, warnings and errors. `alert_category::tracker` (bit 4). */
    val tracker: Int = bit(4)

    /** Low-level peer connect/disconnect events. `alert_category::connect` (bit 5). */
    val connect: Int = bit(5)

    /** Torrent or session state changes. `alert_category::status` (bit 6). */
    val status: Int = bit(6)

    /** A peer was blocked by the IP/port blocker. `alert_category::ip_block` (bit 8). */
    val ipBlock: Int = bit(8)

    /** A limit was reached that might cap the download or upload rate. `alert_category::performance_warning` (bit 9). */
    val performanceWarning: Int = bit(9)

    /** DHT node events: incoming searches, bootstrapping, etc. `alert_category::dht` (bit 10). */
    val dht: Int = bit(10)

    /** Per-torrent statistics alerts, posted ~once per second. `alert_category::stats` (bit 11). */
    val stats: Int = bit(11)

    /** Session-wide debug logging alerts. `alert_category::session_log` (bit 13). */
    val sessionLog: Int = bit(13)

    /** Per-torrent debug logging alerts. `alert_category::torrent_log` (bit 14). */
    val torrentLog: Int = bit(14)

    /** Per-peer debug logging alerts. `alert_category::peer_log` (bit 15). */
    val peerLog: Int = bit(15)

    /** Enables the incoming-request alert. `alert_category::incoming_request` (bit 16). */
    val incomingRequest: Int = bit(16)

    /** DHT debug logging. `alert_category::dht_log` (bit 17). */
    val dhtLog: Int = bit(17)

    /** Events from pure DHT operations not tied to a torrent. `alert_category::dht_operation` (bit 18). */
    val dhtOperation: Int = bit(18)

    /** Port-mapping log events (UPnP / NAT-PMP debugging). `alert_category::port_mapping_log` (bit 19). */
    val portMappingLog: Int = bit(19)

    /** Verbose piece-picker logging. `alert_category::picker_log` (bit 20). */
    val pickerLog: Int = bit(20)

    /** Files completing download. `alert_category::file_progress` (bit 21). */
    val fileProgress: Int = bit(21)

    /** Pieces completing download or failing the hash check. `alert_category::piece_progress` (bit 22). */
    val pieceProgress: Int = bit(22)

    /** Blocks uploaded to other peers. `alert_category::upload` (bit 23). */
    val upload: Int = bit(23)

    /** Individual blocks requested/downloading/finished/rejected/timed-out/cancelled. `alert_category::block_progress` (bit 24). */
    val blockProgress: Int = bit(24)

    /** The full bitmask, representing every available category. `alert_category::all`. */
    val all: Int = -1 // all 32 bits set (matches alert_category_t::all())

    // -- `alert::*_notification` aliases (the historical names on the base class) --

    /** Alias for [error]. */
    val errorNotification: Int get() = error

    /** Alias for [peer]. */
    val peerNotification: Int get() = peer

    /** Alias for [portMapping]. */
    val portMappingNotification: Int get() = portMapping

    /** Alias for [storage]. */
    val storageNotification: Int get() = storage

    /** Alias for [tracker]. */
    val trackerNotification: Int get() = tracker

    /** Alias for [connect]. */
    val connectNotification: Int get() = connect

    /** Alias for [status]. */
    val statusNotification: Int get() = status

    /** Alias for [ipBlock]. */
    val ipBlockNotification: Int get() = ipBlock

    /** Alias for [dht]. */
    val dhtNotification: Int get() = dht

    /** Alias for [stats]. */
    val statsNotification: Int get() = stats
}

/**
 * The base class that every specific notification is derived from, ported from
 * `libtorrent::alert` (include/libtorrent/alert.hpp).
 *
 * In libtorrent, alerts are produced by the session and retrieved with
 * `session::pop_alerts()`. Each concrete alert exposes a stable integer
 * [type] (its `alert_type` constant), a short literal [what], a human-readable
 * [message], and the [category] bitmask it belongs to.
 *
 * libtorrent's C++ alerts are non-copyable, short-lived objects tied to a stack
 * allocator. The KiteTorrent ports are plain immutable data classes instead, so you
 * may copy and retain them freely.
 *
 * @see AlertCategory for the [category] bit values.
 */
abstract class Alert {

    /**
     * An integer unique to this alert type, matching the C++ `alert_type`
     * static constant. It can be compared against a concrete alert's
     * `ALERT_TYPE` to determine the runtime type. Mirrors `alert::type()`.
     */
    abstract val type: Int

    /**
     * A short string literal naming the alert type (e.g. `"torrent_finished"`).
     * It carries no bundled information. Mirrors `alert::what()`, which in
     * libtorrent returns `alert_name(alert_type)`.
     */
    abstract val what: String

    /**
     * The bitmask of [AlertCategory] flags this alert belongs to. Mirrors
     * `alert::category()`, which returns the concrete alert's `static_category`.
     */
    abstract val category: Int

    /**
     * A string describing the alert and the information bundled with it. Intended
     * for debugging/development, not localization. Mirrors `alert::message()`.
     */
    abstract fun message(): String
}
