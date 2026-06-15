package io.github.yuroyami.kitetorrent.alert

import io.github.yuroyami.kitetorrent.Sha1Hash

/**
 * Concrete session-wide alerts (not tied to a torrent or peer) ported from
 * include/libtorrent/alert_types.hpp and src/alert.cpp. They extend [Alert]
 * directly.
 *
 * Where libtorrent prints an endpoint via `print_endpoint(address, port)` the
 * value is carried as a pre-formatted [endpoint] string plus the raw [address]
 * and [port], so callers may either reuse the formatted text or rebuild it.
 */

/**
 * Posted when the listen port succeeds to be opened on an interface. Ported from
 * `libtorrent::listen_succeeded_alert` (alert_type 49). Non-discardable.
 *
 * libtorrent renders `successfully listening on [<socketType>] <endpoint>`.
 *
 * @property address the local address libtorrent listened on.
 * @property port the port libtorrent listened on.
 * @property socketType the listen socket kind (e.g. `"TCP"`, `"uTP"`).
 * @property endpoint the formatted `address:port` (or `[address]:port` for IPv6).
 */
data class ListenSucceededAlert(
    val address: String,
    val port: Int,
    val socketType: String = "TCP",
    val endpoint: String = formatEndpoint(address, port),
) : Alert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "listen_succeeded"
    override val category: Int get() = AlertCategory.status

    override fun message(): String =
        "successfully listening on [" + socketType + "] " + endpoint

    companion object { const val ALERT_TYPE: Int = 49 }
}

/**
 * Posted when none of the ports in the listen range could be opened. Ported from
 * `libtorrent::listen_failed_alert` (alert_type 48). Non-discardable.
 *
 * libtorrent renders
 * `listening on <endpoint> (device: <listenInterface>) failed: [<operation>] [<socketType>] <errorMessage>`.
 *
 * @property listenInterface the network device or IP libtorrent tried to listen on.
 * @property address the address libtorrent attempted to listen on.
 * @property port the port libtorrent attempted to listen on.
 * @property operation the underlying operation that failed (`operation_name`).
 * @property socketType the listen socket kind.
 * @property errorMessage the system error description.
 * @property endpoint the formatted `address:port`.
 */
data class ListenFailedAlert(
    val listenInterface: String,
    val address: String,
    val port: Int,
    val operation: String,
    val socketType: String,
    val errorMessage: String,
    val endpoint: String = formatEndpoint(address, port),
) : Alert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "listen_failed"
    override val category: Int get() = AlertCategory.status or AlertCategory.error

    override fun message(): String =
        "listening on " + endpoint + " (device: " + listenInterface + ") failed: [" +
            operation + "] [" + socketType + "] " + errorMessage

    companion object { const val ALERT_TYPE: Int = 48 }
}

/**
 * Posted when the initial DHT bootstrap is done. Ported from
 * `libtorrent::dht_bootstrap_alert` (alert_type 62). Carries no payload.
 */
class DhtBootstrapAlert : Alert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "dht_bootstrap"
    override val category: Int get() = AlertCategory.dht

    override fun message(): String = "DHT bootstrap complete"

    override fun equals(other: Any?): Boolean = other is DhtBootstrapAlert
    override fun hashCode(): Int = ALERT_TYPE

    companion object { const val ALERT_TYPE: Int = 62 }
}

/**
 * Generated when a DHT node announces to an info-hash on our DHT node. Ported
 * from `libtorrent::dht_announce_alert` (alert_type 55).
 *
 * libtorrent renders `incoming dht announce: <ip>:<port> (<info-hash hex>)`.
 *
 * @property ip the announcing node's address.
 * @property port the announcing node's port.
 * @property infoHash the info-hash being announced.
 */
data class DhtAnnounceAlert(
    val ip: String,
    val port: Int,
    val infoHash: Sha1Hash,
) : Alert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "dht_announce"
    override val category: Int get() = AlertCategory.dht

    override fun message(): String =
        "incoming dht announce: " + ip + ":" + port + " (" + infoHash.toHex() + ")"

    companion object { const val ALERT_TYPE: Int = 55 }
}

/**
 * Generated when a DHT node sends a `get_peers` message to our DHT node. Ported
 * from `libtorrent::dht_get_peers_alert` (alert_type 56).
 *
 * libtorrent renders `incoming dht get_peers: <info-hash hex>`.
 *
 * @property infoHash the info-hash being queried.
 */
data class DhtGetPeersAlert(
    val infoHash: Sha1Hash,
) : Alert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "dht_get_peers"
    override val category: Int get() = AlertCategory.dht

    override fun message(): String = "incoming dht get_peers: " + infoHash.toHex()

    companion object { const val ALERT_TYPE: Int = 56 }
}

/**
 * Posted when libtorrent learns its external IP address (from a tracker or a
 * peer supporting the extension protocol). Ported from
 * `libtorrent::external_ip_alert` (alert_type 47).
 *
 * libtorrent renders `external IP received: <externalAddress>`.
 *
 * @property externalAddress the believed external IP address.
 */
data class ExternalIpAlert(
    val externalAddress: String,
) : Alert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "external_ip"
    override val category: Int get() = AlertCategory.status

    override fun message(): String = "external IP received: " + externalAddress

    companion object { const val ALERT_TYPE: Int = 47 }
}

/**
 * Posted on a UDP socket error (uTP, DHT and UDP-tracker traffic is global to
 * the session). Ported from `libtorrent::udp_error_alert` (alert_type 46).
 *
 * libtorrent renders `UDP error: <errorMessage> from: <address> op: <operation>`.
 *
 * @property address the source address associated with the error (if any).
 * @property operation the operation that failed (`operation_name`).
 * @property errorMessage the error description.
 */
data class UdpErrorAlert(
    val address: String,
    val operation: String,
    val errorMessage: String,
) : Alert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "udp_error"
    override val category: Int get() = AlertCategory.error

    override fun message(): String =
        "UDP error: " + errorMessage + " from: " + address + " op: " + operation

    companion object { const val ALERT_TYPE: Int = 46 }
}

/**
 * Posted every time an incoming connection is accepted (through any means).
 * Ported from `libtorrent::incoming_connection_alert` (alert_type 66).
 *
 * libtorrent renders `incoming connection from <endpoint> (<socketType>)`.
 *
 * @property endpoint the formatted address and port the connection came from.
 * @property socketType the socket kind the connection was accepted on.
 */
data class IncomingConnectionAlert(
    val endpoint: String,
    val socketType: String = "TCP",
) : Alert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "incoming_connection"
    override val category: Int get() = AlertCategory.peer

    override fun message(): String =
        "incoming connection from " + endpoint + " (" + socketType + ")"

    companion object { const val ALERT_TYPE: Int = 66 }
}

/**
 * Posted as a response to `post_session_stats()`. Carries a snapshot of all the
 * session-wide counters. Ported from `libtorrent::session_stats_alert`
 * (alert_type 70). Non-discardable.
 *
 * libtorrent renders `session stats (<n> values): <v0>, <v1>, ...` (the values
 * joined by `", "`).
 *
 * @property counters the raw 64-bit counter values for this snapshot.
 */
data class SessionStatsAlert(
    val counters: LongArray,
) : Alert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "session_stats"
    override val category: Int get() = AlertCategory.stats

    override fun message(): String {
        val sb = StringBuilder("session stats (")
        sb.append(counters.size)
        sb.append(" values): ")
        for (i in counters.indices) {
            if (i != 0) sb.append(", ")
            sb.append(counters[i])
        }
        return sb.toString()
    }

    // LongArray needs structural equals/hashCode for a sensible data class.
    override fun equals(other: Any?): Boolean =
        other is SessionStatsAlert && counters.contentEquals(other.counters)

    override fun hashCode(): Int = counters.contentHashCode()

    companion object { const val ALERT_TYPE: Int = 70 }
}

/**
 * Formats an address and port the way libtorrent's `print_endpoint()` does:
 * `address:port` for an IPv4-looking address, `[address]:port` when the address
 * contains a `:` (i.e. an IPv6 literal). Exposed so callers can reproduce the
 * exact endpoint text used in alert messages.
 */
internal fun formatEndpoint(address: String, port: Int): String =
    if (address.contains(':')) "[$address]:$port" else "$address:$port"
