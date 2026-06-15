package io.github.yuroyami.kitetorrent.session.tracker

import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder

/**
 * Pure big-endian (network byte order) packet build/parse for the BEP-15 UDP
 * tracker protocol — the codec half of libtorrent's `udp_tracker_connection`
 * (`src/udp_tracker_connection.cpp`). Every function here is a total function of
 * its [ByteArray] argument(s): no sockets, no coroutines, no I/O. [UdpTracker]
 * layers the connect→announce transaction and the actual datagram round-trips on
 * top of these.
 *
 * BEP-15 fixes the on-wire integers as big-endian; libtorrent reaches that with its
 * `aux::write_int32` / `aux::read_int32` helpers, which this object reimplements as
 * the private `writeU16/U32/I64` and `readU16/I32/I64` primitives.
 *
 * All packets begin with an action:
 * `connect=0`, `announce=1`, `scrape=2`, `error=3`.
 */
internal object UdpTrackerCodec {

    const val ACTION_CONNECT = 0
    const val ACTION_ANNOUNCE = 1
    const val ACTION_SCRAPE = 2
    const val ACTION_ERROR = 3

    /**
     * The BEP-15 "protocol id" / connect magic, `0x41727101980`, sent as the
     * initial `connection_id` of the connect request. libtorrent writes it as two
     * 32-bit halves (`0x417` then `0x27101980`); concatenated big-endian that is
     * exactly this 64-bit constant.
     */
    const val CONNECT_MAGIC: Long = 0x41727101980L

    /** Fixed size of a connect request / connect response packet. */
    const val CONNECT_PACKET_SIZE = 16

    /**
     * Fixed size of the BEP-15 announce request (no optional URL-extension
     * trailer): 8 + 4 + 4 + 20 + 20 + 8 + 8 + 8 + 4 + 4 + 4 + 4 + 2 = 98 bytes.
     */
    const val ANNOUNCE_REQUEST_SIZE = 98

    /** Fixed size of a scrape request for a single info-hash: 8 + 4 + 4 + 20. */
    const val SCRAPE_REQUEST_SIZE = 36

    // --- builders ------------------------------------------------------------

    /**
     * Build the 16-byte connect request: `connection_id`(=magic), `action`(=0),
     * `transaction_id`. Ports `udp_tracker_connection::send_udp_connect`.
     */
    fun buildConnectRequest(transactionId: Int): ByteArray {
        val b = ByteArrayBuilder(CONNECT_PACKET_SIZE)
        writeI64(b, CONNECT_MAGIC)
        writeU32(b, ACTION_CONNECT)
        writeU32(b, transactionId)
        return b.toByteArray()
    }

    /**
     * Build the 98-byte announce request from a connect-phase [connectionId], a
     * fresh [transactionId], and the announce [req]. Field order and widths port
     * `udp_tracker_connection::send_udp_announce`:
     *
     * `connection_id`(8) `action=1`(4) `transaction_id`(4) `info_hash`(20)
     * `peer_id`(20) `downloaded`(8) `left`(8) `uploaded`(8) `event`(4) `ip`(4)
     * `key`(4) `num_want`(4) `port`(2).
     *
     * @throws IllegalArgumentException if the info-hash or peer-id is not 20 bytes.
     */
    fun buildAnnounceRequest(
        connectionId: Long,
        transactionId: Int,
        req: AnnounceRequest,
    ): ByteArray {
        val infoHash = req.infoHash.toByteArray()
        val peerId = req.peerId.toByteArray()
        require(infoHash.size == 20) { "info_hash must be 20 bytes, was ${infoHash.size}" }
        require(peerId.size == 20) { "peer_id must be 20 bytes, was ${peerId.size}" }

        val b = ByteArrayBuilder(ANNOUNCE_REQUEST_SIZE)
        writeI64(b, connectionId)
        writeU32(b, ACTION_ANNOUNCE)
        writeU32(b, transactionId)
        b.append(infoHash)
        b.append(peerId)
        writeI64(b, req.downloaded)
        writeI64(b, req.left)
        writeI64(b, req.uploaded)
        writeU32(b, req.event.code)
        writeU32(b, req.ip)
        writeU32(b, req.key)
        writeU32(b, req.numWant)
        writeU16(b, req.port)
        return b.toByteArray()
    }

    /**
     * Build the 36-byte scrape request for a single [infoHash20].
     * Ports `udp_tracker_connection::send_udp_scrape`:
     * `connection_id`(8) `action=2`(4) `transaction_id`(4) `info_hash`(20).
     */
    fun buildScrapeRequest(
        connectionId: Long,
        transactionId: Int,
        infoHash20: ByteArray,
    ): ByteArray {
        require(infoHash20.size == 20) { "info_hash must be 20 bytes, was ${infoHash20.size}" }
        val b = ByteArrayBuilder(SCRAPE_REQUEST_SIZE)
        writeI64(b, connectionId)
        writeU32(b, ACTION_SCRAPE)
        writeU32(b, transactionId)
        b.append(infoHash20)
        return b.toByteArray()
    }

    // --- parsers -------------------------------------------------------------

    /** The `connection_id` carried by a successful connect response. */
    data class ConnectResult(val connectionId: Long)

    /**
     * Parse a connect response (action=0): `action`(4) `transaction_id`(4)
     * `connection_id`(8). Ports `udp_tracker_connection::on_connect_response`,
     * which ignores packets shorter than 16 bytes.
     *
     * Returns `null` if the packet is too short, the action is not `connect`, or the
     * transaction id does not match [expectedTransactionId] — all of which
     * libtorrent treats as "ignore this datagram".
     */
    fun parseConnectResponse(packet: ByteArray, expectedTransactionId: Int): ConnectResult? {
        if (packet.size < CONNECT_PACKET_SIZE) return null
        val action = readI32(packet, 0)
        val transaction = readI32(packet, 4)
        if (transaction != expectedTransactionId) return null
        if (action != ACTION_CONNECT) return null
        return ConnectResult(readI64(packet, 8))
    }

    /**
     * Parse an announce response (action=1): `action`(4) `transaction_id`(4)
     * `interval`(4) `leechers`(4) `seeders`(4) then peers. Ports
     * `udp_tracker_connection::on_announce_response`.
     *
     * The peer stride is the [PEER_STRIDE_V4] 6 bytes (4-byte IP + 2-byte port) by
     * default; pass [ipv6] = true to decode the 18-byte (16-byte IP + 2-byte port)
     * form libtorrent uses when the tracker endpoint is IPv6.
     *
     * Returns `null` for any framing error libtorrent rejects: a packet shorter than
     * the 20-byte header, a non-`announce` action, a transaction-id mismatch, or a
     * peer region whose length is not a whole multiple of the stride.
     */
    fun parseAnnounceResponse(
        packet: ByteArray,
        expectedTransactionId: Int,
        ipv6: Boolean = false,
    ): AnnounceResponse? {
        // header is action(4) tid(4) interval(4) leechers(4) seeders(4) = 20
        if (packet.size < 20) return null
        val action = readI32(packet, 0)
        val transaction = readI32(packet, 4)
        if (transaction != expectedTransactionId) return null
        if (action != ACTION_ANNOUNCE) return null

        val interval = readI32(packet, 8)
        val leechers = readI32(packet, 12)
        val seeders = readI32(packet, 16)

        val stride = if (ipv6) PEER_STRIDE_V6 else PEER_STRIDE_V4
        val peerBytes = packet.size - 20
        if (peerBytes % stride != 0) return null
        val numPeers = peerBytes / stride

        val peers = ArrayList<PeerEndpoint>(numPeers)
        var off = 20
        repeat(numPeers) {
            val host = if (ipv6) formatIpv6(packet, off) else formatIpv4(packet, off)
            val port = readU16(packet, off + (if (ipv6) 16 else 4))
            peers.add(PeerEndpoint(host, port))
            off += stride
        }
        return AnnounceResponse(interval = interval, leechers = leechers, seeders = seeders, peers = peers)
    }

    /**
     * Parse a scrape response (action=2) for a single info-hash: `action`(4)
     * `transaction_id`(4) then per-hash `seeders/complete`(4) `completed/downloaded`(4)
     * `leechers/incomplete`(4). Ports `udp_tracker_connection::on_scrape_response`,
     * which requires at least 12 bytes of body after the 8-byte header.
     *
     * Returns `null` on a short packet, wrong action, or transaction-id mismatch.
     */
    fun parseScrapeResponse(packet: ByteArray, expectedTransactionId: Int): ScrapeResponse? {
        if (packet.size < 8) return null
        val action = readI32(packet, 0)
        val transaction = readI32(packet, 4)
        if (transaction != expectedTransactionId) return null
        if (action != ACTION_SCRAPE) return null
        if (packet.size < 8 + 12) return null
        val seeders = readI32(packet, 8)
        val completed = readI32(packet, 12)
        val leechers = readI32(packet, 16)
        return ScrapeResponse(seeders = seeders, completed = completed, leechers = leechers)
    }

    /**
     * If [packet] is a BEP-15 error response (action=3) matching
     * [expectedTransactionId], return its human-readable message (the bytes after
     * the 8-byte header, decoded as UTF-8). Otherwise `null`. libtorrent surfaces
     * this string as the `tracker_failure` error message.
     */
    fun parseErrorMessage(packet: ByteArray, expectedTransactionId: Int): String? {
        if (packet.size < 8) return null
        val action = readI32(packet, 0)
        val transaction = readI32(packet, 4)
        if (transaction != expectedTransactionId) return null
        if (action != ACTION_ERROR) return null
        return packet.decodeToString(8, packet.size)
    }

    /** Read the action id from any response packet (first 4 bytes), or -1 if too short. */
    fun peekAction(packet: ByteArray): Int = if (packet.size < 4) -1 else readI32(packet, 0)

    /** The compact-peer stride for an IPv4 tracker: 4-byte IP + 2-byte port. */
    const val PEER_STRIDE_V4 = 6

    /** The compact-peer stride for an IPv6 tracker: 16-byte IP + 2-byte port. */
    const val PEER_STRIDE_V6 = 18

    // --- big-endian primitives ----------------------------------------------

    private fun writeU16(b: ByteArrayBuilder, v: Int) {
        b.append((v ushr 8).toByte())
        b.append(v.toByte())
    }

    private fun writeU32(b: ByteArrayBuilder, v: Int) {
        b.append((v ushr 24).toByte())
        b.append((v ushr 16).toByte())
        b.append((v ushr 8).toByte())
        b.append(v.toByte())
    }

    private fun writeI64(b: ByteArrayBuilder, v: Long) {
        b.append((v ushr 56).toByte())
        b.append((v ushr 48).toByte())
        b.append((v ushr 40).toByte())
        b.append((v ushr 32).toByte())
        b.append((v ushr 24).toByte())
        b.append((v ushr 16).toByte())
        b.append((v ushr 8).toByte())
        b.append(v.toByte())
    }

    private fun readU16(p: ByteArray, off: Int): Int =
        ((p[off].toInt() and 0xFF) shl 8) or (p[off + 1].toInt() and 0xFF)

    private fun readI32(p: ByteArray, off: Int): Int =
        ((p[off].toInt() and 0xFF) shl 24) or
            ((p[off + 1].toInt() and 0xFF) shl 16) or
            ((p[off + 2].toInt() and 0xFF) shl 8) or
            (p[off + 3].toInt() and 0xFF)

    private fun readI64(p: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (p[off + i].toLong() and 0xFF)
        return v
    }

    private fun formatIpv4(p: ByteArray, off: Int): String =
        "${p[off].toInt() and 0xFF}.${p[off + 1].toInt() and 0xFF}." +
            "${p[off + 2].toInt() and 0xFF}.${p[off + 3].toInt() and 0xFF}"

    /**
     * Render 16 bytes at [off] as a (non-compressed) colon-hex IPv6 literal. The
     * tracker layer only needs an address string the peer connector can re-parse;
     * `peer.PeerAddress.parseOrNull` accepts the uncompressed form, so we do not
     * implement RFC 5952 `::` compression here.
     */
    private fun formatIpv6(p: ByteArray, off: Int): String {
        val sb = StringBuilder(39)
        for (group in 0 until 8) {
            if (group > 0) sb.append(':')
            val hi = p[off + group * 2].toInt() and 0xFF
            val lo = p[off + group * 2 + 1].toInt() and 0xFF
            sb.append(((hi shl 8) or lo).toString(16))
        }
        return sb.toString()
    }
}
