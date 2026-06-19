package io.github.yuroyami.kitetorrent.alert

/**
 * Concrete peer alerts ported from include/libtorrent/alert_types.hpp and
 * src/alert.cpp. They all extend [PeerAlert], so [message] begins with the
 * `"<name> peer [ <endpoint> client: <client> ]"` peer prefix.
 *
 * Where libtorrent prints `socket_type_name(...)` the socket kind is carried as
 * a plain [socketType] string (e.g. `"TCP"`, `"uTP"`). Where it prints
 * `operation_name(op)` the operation is carried as a plain `operation` string,
 * and `error.category().name()` is carried as an `errorCategory` string.
 */

/**
 * Posted when an incoming connection passes the handshake and is associated with
 * a torrent, or an outgoing connection attempt succeeds. Ported from
 * `libtorrent::peer_connect_alert` (alert_type 23).
 *
 * libtorrent renders `<peer-prefix> <incoming|outgoing> connection to peer (<socketType>)`.
 *
 * @property direction whether the connection was incoming or outgoing.
 * @property socketType the socket kind the connection uses.
 */
data class PeerConnectAlert(
    override val torrentName: String?,
    override val endpoint: String,
    override val client: String = "Unknown",
    val direction: PeerConnectDirection,
    val socketType: String = "TCP",
) : PeerAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "peer_connect"
    override val category: Int get() = AlertCategory.connect

    override fun message(): String =
        peerPrefix() + " " + direction.label + " connection to peer (" + socketType + ")"

    companion object { const val ALERT_TYPE: Int = 23 }
}

/**
 * Generated when a peer is disconnected for any reason not covered by
 * [PeerErrorAlert]. Ported from `libtorrent::peer_disconnected_alert`
 * (alert_type 24).
 *
 * libtorrent renders
 * `<peer-prefix> disconnecting (<socketType>) [<operation>] [<errorCategory>]: <errorMessage> (reason: <reason>)`.
 *
 * @property socketType the socket kind this peer was connected over.
 * @property operation the operation/level where the error occurred (`operation_name`).
 * @property errorCategory the error category name (`error.category().name()`).
 * @property errorMessage the error description.
 * @property reason the numeric close-reason code (`close_reason_t`).
 */
data class PeerDisconnectedAlert(
    override val torrentName: String?,
    override val endpoint: String,
    override val client: String = "Unknown",
    val socketType: String = "TCP",
    val operation: String = "bittorrent",
    val errorCategory: String = "system",
    val errorMessage: String,
    val reason: Int = 0,
) : PeerAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "peer_disconnected"
    override val category: Int get() = AlertCategory.connect

    override fun message(): String =
        peerPrefix() + " disconnecting (" + socketType + ") [" + operation + "] [" +
            errorCategory + "]: " + errorMessage + " (reason: " + reason + ")"

    companion object { const val ALERT_TYPE: Int = 24 }
}

/**
 * Generated when a peer sends invalid data over the peer protocol and is
 * disconnected. Ported from `libtorrent::peer_error_alert` (alert_type 22).
 *
 * libtorrent renders `<peer-prefix> peer error [<operation>] [<errorCategory>]: <errorMessage>`.
 *
 * @property operation the low-level operation that failed (`operation_name`).
 * @property errorCategory the error category name (`error.category().name()`).
 * @property errorMessage the error description.
 */
data class PeerErrorAlert(
    override val torrentName: String?,
    override val endpoint: String,
    override val client: String = "Unknown",
    val operation: String = "bittorrent",
    val errorCategory: String = "system",
    val errorMessage: String,
) : PeerAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "peer_error"
    override val category: Int get() = AlertCategory.peer

    override fun message(): String =
        peerPrefix() + " peer error [" + operation + "] [" + errorCategory + "]: " + errorMessage

    companion object { const val ALERT_TYPE: Int = 22 }
}

/**
 * Generated when a peer is banned for sending too many corrupt pieces. Ported
 * from `libtorrent::peer_ban_alert` (alert_type 19).
 */
data class PeerBanAlert(
    override val torrentName: String?,
    override val endpoint: String,
    override val client: String = "Unknown",
) : PeerAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "peer_ban"
    override val category: Int get() = AlertCategory.peer

    override fun message(): String = peerPrefix() + " banned peer"

    companion object { const val ALERT_TYPE: Int = 19 }
}

/**
 * Posted when an incoming peer (or one about to be added) is blocked, e.g. by
 * the IP/port filter or protocol restrictions. Ported from
 * `libtorrent::peer_blocked_alert` (alert_type 54). In libtorrent the peer id is
 * always null for this alert.
 *
 * libtorrent renders `<peer-prefix>: blocked peer [<reason>]`.
 *
 * @property reason the reason the peer was blocked.
 */
data class PeerBlockedAlert(
    override val torrentName: String?,
    override val endpoint: String,
    val reason: PeerBlockReason,
) : PeerAlert() {
    override val client: String get() = "Unknown"

    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "peer_blocked"
    override val category: Int get() = AlertCategory.ipBlock

    override fun message(): String =
        peerPrefix() + ": blocked peer [" + reason.label + "]"

    companion object { const val ALERT_TYPE: Int = 54 }
}

/**
 * Generated when a peer is snubbed — it stopped sending data we requested.
 * Ported from `libtorrent::peer_snubbed_alert` (alert_type 21).
 */
data class PeerSnubbedAlert(
    override val torrentName: String?,
    override val endpoint: String,
    override val client: String = "Unknown",
) : PeerAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "peer_snubbed"
    override val category: Int get() = AlertCategory.peer

    override fun message(): String = peerPrefix() + " peer snubbed"

    companion object { const val ALERT_TYPE: Int = 21 }
}

/**
 * Generated when a block request receives a response (the block finished
 * downloading from this peer). Ported from `libtorrent::block_finished_alert`
 * (alert_type 30). Extends [PeerAlert].
 *
 * libtorrent renders `<peer-prefix> block finished downloading (piece: <piece> block: <block>)`.
 *
 * @property pieceIndex the index of the piece the block belongs to.
 * @property blockIndex the index of the block within the piece.
 */
data class BlockFinishedAlert(
    override val torrentName: String?,
    override val endpoint: String,
    override val client: String = "Unknown",
    val pieceIndex: Int,
    val blockIndex: Int,
) : PeerAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "block_finished"
    override val category: Int get() = AlertCategory.blockProgress

    override fun message(): String =
        peerPrefix() + " block finished downloading (piece: " + pieceIndex + " block: " + blockIndex + ")"

    companion object { const val ALERT_TYPE: Int = 30 }
}

/**
 * Generated when a snubbed peer starts sending data again. Ported from
 * `libtorrent::peer_unsnubbed_alert` (alert_type 20).
 */
data class PeerUnsnubbedAlert(
    override val torrentName: String?,
    override val endpoint: String,
    override val client: String = "Unknown",
) : PeerAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "peer_unsnubbed"
    override val category: Int get() = AlertCategory.peer

    override fun message(): String = peerPrefix() + " peer unsnubbed"

    companion object { const val ALERT_TYPE: Int = 20 }
}
