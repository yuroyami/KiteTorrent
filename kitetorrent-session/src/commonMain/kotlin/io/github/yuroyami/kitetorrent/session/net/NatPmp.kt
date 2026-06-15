package io.github.yuroyami.kitetorrent.session.net

import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Pure big-endian (network byte order) packet build/parse for the NAT-PMP protocol
 * (RFC 6886) — the codec half of libtorrent's `natpmp` (`src/natpmp.cpp`). Every
 * function here is a total function of its arguments: no sockets, no coroutines, no
 * I/O. The live [NatPmp] class layers the actual UDP round-trips on top.
 *
 * NAT-PMP is the simpler ancestor of PCP; libtorrent's `natpmp.cpp` speaks both and
 * falls back from PCP to NAT-PMP. We port only the NAT-PMP wire format
 * (`version == version_natpmp`, i.e. version byte 0) — the fixed 2-byte / 12-byte
 * requests and the 12-byte / 16-byte responses the C++ builds in
 * `send_get_ip_address_request` / `send_map_request` and parses in `on_reply`.
 *
 * All multi-byte integers are big-endian, matching the C++ `aux::write_uint16` /
 * `write_uint32` and `read_uint16` / `read_uint32` helpers.
 */
internal object NatPmpCodec {

    /** NAT-PMP protocol version, written as the first byte of every packet (`version_natpmp`). */
    const val VERSION_NATPMP = 0

    /** Opcode 0: request the gateway's external (public) IPv4 address. NAT-PMP only. */
    const val OPCODE_EXTERNAL_ADDRESS = 0

    /** Opcode 1: map a UDP port (`m.protocol == udp ? 1 : 2` in `send_map_request`). */
    const val OPCODE_MAP_UDP = 1

    /** Opcode 2: map a TCP port. */
    const val OPCODE_MAP_TCP = 2

    /**
     * In a NAT-PMP response the opcode field is the request opcode + 128
     * (`cmd - 128` in `on_reply`); so the public-IP reply carries opcode 128, the UDP
     * map reply 129, and the TCP map reply 130.
     */
    const val RESPONSE_OPCODE_OFFSET = 128

    /** A NAT-PMP external-address *request* is exactly 2 bytes: `version`(1) `opcode=0`(1). */
    const val EXTERNAL_ADDRESS_REQUEST_SIZE = 2

    /**
     * A NAT-PMP map *request* is exactly 12 bytes:
     * `version`(1) `opcode`(1) `reserved`(2) `private_port`(2) `requested_public_port`(2)
     * `lifetime`(4).
     */
    const val MAP_REQUEST_SIZE = 12

    /**
     * A NAT-PMP external-address *response* is 12 bytes:
     * `version`(1) `opcode=128`(1) `result`(2) `seconds_since_epoch`(4) `external_ip`(4).
     */
    const val EXTERNAL_ADDRESS_RESPONSE_SIZE = 12

    /**
     * A NAT-PMP map *response* is 16 bytes:
     * `version`(1) `opcode`(1) `result`(2) `seconds_since_epoch`(4) `private_port`(2)
     * `mapped_public_port`(2) `lifetime`(4).
     */
    const val MAP_RESPONSE_SIZE = 16

    // --- builders ------------------------------------------------------------

    /**
     * Build the 2-byte external-address request (`version`(1) `opcode=0`(1)). Ports
     * `natpmp::send_get_ip_address_request`, which writes `version_natpmp` then the
     * public-IP-address opcode 0.
     */
    fun buildExternalAddressRequest(): ByteArray {
        val b = ByteArrayBuilder(EXTERNAL_ADDRESS_REQUEST_SIZE)
        b.append(VERSION_NATPMP.toByte())
        b.append(OPCODE_EXTERNAL_ADDRESS.toByte())
        return b.toByteArray()
    }

    /**
     * Build the 12-byte map request. Ports the `version == version_natpmp` branch of
     * `natpmp::send_map_request`:
     *
     * `version`(1) `opcode`(1, 1=UDP / 2=TCP) `reserved`(2, 0) `private_port`(2)
     * `requested_public_port`(2) `lifetime`(4).
     *
     * A [lifetimeSeconds] of 0 requests deletion of the mapping (libtorrent sends a
     * zero TTL for `portmap_action::del`).
     *
     * @param tcp `true` selects opcode 2 (TCP), `false` opcode 1 (UDP).
     */
    fun buildMapRequest(
        internalPort: Int,
        externalPort: Int,
        tcp: Boolean,
        lifetimeSeconds: Int,
    ): ByteArray {
        val b = ByteArrayBuilder(MAP_REQUEST_SIZE)
        b.append(VERSION_NATPMP.toByte())
        b.append((if (tcp) OPCODE_MAP_TCP else OPCODE_MAP_UDP).toByte())
        writeU16(b, 0)                  // reserved
        writeU16(b, internalPort)       // private port
        writeU16(b, externalPort)       // requested public port
        writeU32(b, lifetimeSeconds)    // port mapping lifetime
        return b.toByteArray()
    }

    // --- parsers -------------------------------------------------------------

    /** The external IPv4 address carried by a successful public-IP response. */
    data class ExternalAddressResult(val resultCode: Int, val address: String) {
        /** A NAT-PMP `result` of 0 means success; any other value is an error. */
        val isSuccess: Boolean get() = resultCode == 0
    }

    /**
     * Parse a NAT-PMP external-address response (opcode 128). Ports the
     * `version == version_natpmp && cmd == 128` branch of `natpmp::on_reply`:
     * after the 1-byte version, 1-byte opcode, 2-byte result and 4-byte epoch the
     * router writes the 4-byte external IPv4 address (`read_v4_address`).
     *
     * Returns `null` for any framing error `on_reply` rejects: a packet that is not
     * exactly [EXTERNAL_ADDRESS_RESPONSE_SIZE] bytes, a non-NAT-PMP version byte, or
     * an opcode that is not 128.
     */
    fun parseExternalAddressResponse(packet: ByteArray): ExternalAddressResult? {
        if (packet.size != EXTERNAL_ADDRESS_RESPONSE_SIZE) return null
        if ((packet[0].toInt() and 0xFF) != VERSION_NATPMP) return null
        val opcode = packet[1].toInt() and 0xFF
        if (opcode != RESPONSE_OPCODE_OFFSET + OPCODE_EXTERNAL_ADDRESS) return null
        val result = readU16(packet, 2)
        // bytes 4..8 are seconds-since-epoch (ignored, as in on_reply); 8..12 are the IP
        val ip = formatIpv4(packet, 8)
        return ExternalAddressResult(result, ip)
    }

    /** The outcome of a NAT-PMP map response: the result code and the granted mapping. */
    data class MapResult(
        val resultCode: Int,
        val tcp: Boolean,
        val internalPort: Int,
        val externalPort: Int,
        val lifetimeSeconds: Long,
    ) {
        /** A NAT-PMP `result` of 0 means success; any other value is an error. */
        val isSuccess: Boolean get() = resultCode == 0
    }

    /**
     * Parse a NAT-PMP map response (opcode 129=UDP / 130=TCP). Ports the map branch
     * of `natpmp::on_reply`:
     *
     * `version`(1) `opcode`(1) `result`(2) `seconds_since_epoch`(4) `private_port`(2)
     * `mapped_public_port`(2) `lifetime`(4).
     *
     * The protocol is derived from the opcode exactly as the C++ does
     * (`cmd - 128 == 1 ? udp : tcp`). Returns `null` for any framing error
     * `on_reply` rejects: a packet that is not exactly [MAP_RESPONSE_SIZE] bytes, a
     * non-NAT-PMP version byte, or an opcode that is neither 129 nor 130.
     */
    fun parseMapResponse(packet: ByteArray): MapResult? {
        if (packet.size != MAP_RESPONSE_SIZE) return null
        if ((packet[0].toInt() and 0xFF) != VERSION_NATPMP) return null
        val opcode = packet[1].toInt() and 0xFF
        val protoCode = opcode - RESPONSE_OPCODE_OFFSET
        if (protoCode != OPCODE_MAP_UDP && protoCode != OPCODE_MAP_TCP) return null
        val result = readU16(packet, 2)
        // bytes 4..8 are seconds-since-epoch (ignored, as in on_reply)
        val privatePort = readU16(packet, 8)
        val publicPort = readU16(packet, 10)
        val lifetime = readU32(packet, 12)
        // protocol = (cmd - 128 == 1) ? udp : tcp
        val tcp = protoCode == OPCODE_MAP_TCP
        return MapResult(result, tcp, privatePort, publicPort, lifetime)
    }

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

    private fun readU16(p: ByteArray, off: Int): Int =
        ((p[off].toInt() and 0xFF) shl 8) or (p[off + 1].toInt() and 0xFF)

    /** Read a 32-bit unsigned big-endian value into a [Long] (lifetimes can exceed Int range). */
    private fun readU32(p: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (p[off + i].toLong() and 0xFF)
        return v
    }

    private fun formatIpv4(p: ByteArray, off: Int): String =
        "${p[off].toInt() and 0xFF}.${p[off + 1].toInt() and 0xFF}." +
            "${p[off + 2].toInt() and 0xFF}.${p[off + 3].toInt() and 0xFF}"
}

/**
 * A NAT-PMP client — the live half of libtorrent's `natpmp` (`src/natpmp.cpp`),
 * speaking the NAT-PMP (RFC 6886) wire format over the proven [UdpSocket]. The
 * fixed-layout packet build/parse lives in [NatPmpCodec]; this class owns the UDP
 * round-trip to the gateway (always port **5351**, the NAT-PMP server port
 * `udp::endpoint(*route, 5351)` in `natpmp::start`) and the retransmission loop.
 *
 * libtorrent retransmits a NAT-PMP request with a *linear* back-off
 * (`m_send_timer.expires_after(milliseconds(250 * m_retry_count))`) and gives up
 * after 9 attempts (`m_retry_count >= 9` in `resend_request`). We reproduce that:
 * each request is sent up to [maxAttempts] times, the n-th attempt waiting
 * `n * [retryStepMs]` ms for a reply before retransmitting. Cancellation propagates
 * through structured concurrency.
 *
 * This class deliberately omits the full mapping table, expiry refresh timer and
 * PCP fallback of the C++ — those belong to the engine's port-mapping manager. It
 * exposes the two request/response transactions a caller needs: query the external
 * address, and add (or, with `lifetimeSeconds = 0`, delete) a single mapping.
 *
 * @property udp the bound UDP socket to send from and receive on. Ownership stays
 *   with the caller — this class never closes it.
 * @property gateway the default-route gateway IPv4 address (e.g. `"192.168.1.1"`),
 *   as libtorrent obtains from `get_gateway` / `enum_routes`.
 */
class NatPmp(
    private val udp: UdpSocket,
    private val gateway: String,
    private val maxAttempts: Int = 9,
    private val retryStepMs: Long = 250L,
) {
    /** The fixed NAT-PMP server port on the gateway (`udp::endpoint(*route, 5351)`). */
    private val natPort = 5351

    /**
     * Query the gateway for its external (public) IPv4 address (opcode 0). Mirrors
     * `natpmp::send_get_ip_address_request` → the `cmd == 128` branch of `on_reply`.
     *
     * Returns the dotted-quad address string, or `null` if the gateway never
     * answered within [maxAttempts] retransmissions or replied with a non-zero
     * result code.
     */
    suspend fun externalAddress(): String? {
        val request = NatPmpCodec.buildExternalAddressRequest()
        val reply = transact(request) { packet ->
            NatPmpCodec.parseExternalAddressResponse(packet)
        } ?: return null
        return if (reply.isSuccess) reply.address else null
    }

    /**
     * Request a port mapping (opcode 1 for UDP, 2 for TCP). Mirrors the
     * `version_natpmp` branch of `natpmp::send_map_request` → the map branch of
     * `on_reply`.
     *
     * On success returns the **public port the gateway actually granted** (which can
     * differ from the requested [externalPort] — NAT-PMP routers may assign a
     * different port, and `on_reply` adopts `public_port` from the response). Returns
     * `null` if the gateway never answered within [maxAttempts] retransmissions or
     * replied with a non-zero result code.
     *
     * @param lifetimeSeconds the requested lease duration; libtorrent defaults to
     *   3600s (`configured_ttl > 0 ? configured_ttl : 3600`). Pass 0 to delete the
     *   mapping.
     */
    suspend fun map(
        internalPort: Int,
        externalPort: Int,
        tcp: Boolean,
        lifetimeSeconds: Int = 3600,
    ): Int? {
        val request = NatPmpCodec.buildMapRequest(internalPort, externalPort, tcp, lifetimeSeconds)
        val reply = transact(request) { packet ->
            // only accept a response whose protocol matches the request, mirroring the
            // (protocol != i->protocol) filter in on_reply's mapping lookup
            val parsed = NatPmpCodec.parseMapResponse(packet)
            if (parsed != null && parsed.tcp == tcp && parsed.internalPort == internalPort) parsed else null
        } ?: return null
        return if (reply.isSuccess) reply.externalPort else null
    }

    /**
     * Send [request] to the gateway and return the first datagram (from the gateway)
     * that [accept] parses into a non-null result, retransmitting with linear
     * back-off up to [maxAttempts] times. Datagrams from any other source, or that
     * fail to parse, are dropped and we keep waiting — mirroring the
     * `m_remote != m_nat_endpoint` guard and the mapping-table filter in
     * `natpmp::on_reply`.
     */
    private suspend fun <T> transact(request: ByteArray, accept: (ByteArray) -> T?): T? {
        var attempt = 1
        while (attempt <= maxAttempts) {
            udp.send(request, gateway, natPort)
            val reply = try {
                // linear back-off: the n-th attempt waits n * retryStepMs, as in send_map_request
                withTimeout(retryStepMs * attempt) { awaitMatching(accept) }
            } catch (e: TimeoutCancellationException) {
                null
            }
            if (reply != null) return reply
            attempt++
        }
        return null
    }

    /**
     * Suspend, draining datagrams, until one arrives *from the gateway* that [accept]
     * recognises. Datagrams from a different host are discarded (the
     * `received packet from wrong IP` guard in `on_reply`); the enclosing
     * [withTimeout] bounds the wait.
     */
    private suspend fun <T> awaitMatching(accept: (ByteArray) -> T?): T {
        while (true) {
            val datagram: UdpPacket = udp.receive()
            if (datagram.host != gateway) continue
            val parsed = accept(datagram.data)
            if (parsed != null) return parsed
        }
    }
}
