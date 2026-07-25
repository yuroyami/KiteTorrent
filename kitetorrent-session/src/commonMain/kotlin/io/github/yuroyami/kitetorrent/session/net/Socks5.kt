package io.github.yuroyami.kitetorrent.session.net

import io.github.yuroyami.kitetorrent.peer.PeerAddress

/** Raised when a proxy negotiation fails. */
class ProxyException(message: String) : Exception(message)

/** A proxy `connectTcp` tunnels outbound dials through. See [Socks5Config], [HttpProxyConfig]. */
sealed interface ProxyConfig {
    val host: String
    val port: Int
    val username: String?
    val password: String?
}

/**
 * SOCKS5 proxy configuration (`settings_pack::proxy_*`). [username]/[password] enable the
 * RFC-1929 username/password auth method; leave them null for a no-auth proxy.
 */
data class Socks5Config(
    override val host: String,
    override val port: Int,
    override val username: String? = null,
    override val password: String? = null,
) : ProxyConfig

/**
 * HTTP CONNECT proxy configuration. [username]/[password] enable HTTP Basic
 * `Proxy-Authorization`; leave them null for an open proxy.
 */
data class HttpProxyConfig(
    override val host: String,
    override val port: Int,
    override val username: String? = null,
    override val password: String? = null,
) : ProxyConfig

/**
 * SOCKS4 / SOCKS4a proxy configuration. [username] becomes the SOCKS4 USERID; [password] is
 * unused (SOCKS4 has no password auth). Hostname targets use the SOCKS4a extension.
 */
data class Socks4Config(
    override val host: String,
    override val port: Int,
    override val username: String? = null,
    override val password: String? = null,
) : ProxyConfig

/**
 * Perform a SOCKS5 (RFC 1928) CONNECT handshake over an already-open connection to the proxy,
 * tunnelling to [targetHost]:[targetPort]. After this returns the [proxy] connection carries
 * raw bytes to/from the target, so a [io.github.yuroyami.kitetorrent.session.peer.PeerConnection]
 * runs over it unchanged. Supports no-auth and (when [auth] carries credentials) RFC-1929
 * username/password auth. Throws [ProxyException] on any negotiation failure.
 */
internal suspend fun socks5Connect(
    proxy: TcpConnection,
    targetHost: String,
    targetPort: Int,
    auth: Socks5Config,
) {
    // greeting: version 5, the auth methods we offer (no-auth, and user/pass if we have creds)
    val methods = if (auth.username != null) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00)
    proxy.write(byteArrayOf(0x05, methods.size.toByte(), *methods))

    val sel = proxy.readExactly(2)
    if (sel[0].toInt() != 0x05) throw ProxyException("SOCKS5: bad version in method reply")
    when (sel[1].toInt() and 0xFF) {
        0x00 -> {} // no auth
        0x02 -> { // RFC 1929 username/password
            val u = (auth.username ?: "").encodeToByteArray()
            val p = (auth.password ?: "").encodeToByteArray()
            proxy.write(byteArrayOf(0x01, u.size.toByte(), *u, p.size.toByte(), *p))
            val ar = proxy.readExactly(2)
            if (ar[1].toInt() != 0x00) throw ProxyException("SOCKS5: username/password auth rejected")
        }
        0xFF -> throw ProxyException("SOCKS5: no acceptable auth method")
        else -> throw ProxyException("SOCKS5: unexpected auth method ${sel[1]}")
    }

    // CONNECT request: VER, CMD=connect, RSV, ATYP, DST.ADDR, DST.PORT
    val addr = encodeSocksAddr(targetHost)
    proxy.write(byteArrayOf(0x05, 0x01, 0x00, *addr, (targetPort ushr 8).toByte(), targetPort.toByte()))

    // reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT
    val head = proxy.readExactly(4)
    if (head[1].toInt() != 0x00) throw ProxyException("SOCKS5: CONNECT failed (reply code ${head[1].toInt() and 0xFF})")
    val bndLen = when (head[3].toInt() and 0xFF) {
        0x01 -> 4
        0x04 -> 16
        0x03 -> proxy.readExactly(1)[0].toInt() and 0xFF
        else -> throw ProxyException("SOCKS5: unknown bound-address type")
    }
    proxy.readExactly(bndLen + 2) // consume BND.ADDR + BND.PORT; the tunnel is now open
}

/**
 * Perform an HTTP CONNECT (RFC 7231) handshake over an open connection to the proxy, tunnelling
 * to [targetHost]:[targetPort]. After a `200` reply [proxy] carries raw bytes to the target.
 * Sends HTTP Basic `Proxy-Authorization` when [auth] carries credentials. Throws on non-200.
 */
internal suspend fun httpConnect(
    proxy: TcpConnection,
    targetHost: String,
    targetPort: Int,
    auth: HttpProxyConfig,
) {
    val req = StringBuilder()
    req.append("CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
    req.append("Host: $targetHost:$targetPort\r\n")
    if (auth.username != null) {
        val creds = base64("${auth.username}:${auth.password ?: ""}".encodeToByteArray())
        req.append("Proxy-Authorization: Basic $creds\r\n")
    }
    req.append("\r\n")
    proxy.write(req.toString().encodeToByteArray())

    // read the status line + headers up to the blank line
    val sb = StringBuilder()
    while (true) {
        val ch = proxy.readExactly(1)[0].toInt() and 0xFF
        sb.append(ch.toChar())
        if (ch == '\n'.code && sb.endsWith("\r\n\r\n")) break
        if (sb.length > 16384) throw ProxyException("HTTP proxy response too large")
    }
    val statusLine = sb.toString().substringBefore("\r\n")
    val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        ?: throw ProxyException("HTTP proxy: malformed status line '$statusLine'")
    if (code != 200) throw ProxyException("HTTP CONNECT failed: $statusLine")
}

/**
 * Perform a SOCKS4 (or SOCKS4a, for hostname targets) CONNECT over an open connection to the
 * proxy, tunnelling to [targetHost]:[targetPort]. After a request-granted (0x5A) reply the
 * connection carries raw bytes to the target. SOCKS4 has no IPv6 support. Throws on rejection.
 */
internal suspend fun socks4Connect(
    proxy: TcpConnection,
    targetHost: String,
    targetPort: Int,
    auth: Socks4Config,
) {
    val userId = (auth.username ?: "").encodeToByteArray()
    val ip = PeerAddress.parseOrNull(targetHost)
    val portHi = (targetPort ushr 8).toByte()
    val portLo = targetPort.toByte()
    when {
        ip != null && !ip.isV6 ->
            proxy.write(byteArrayOf(0x04, 0x01, portHi, portLo, *ip.bytes, *userId, 0x00))
        ip != null && ip.isV6 ->
            throw ProxyException("SOCKS4 does not support IPv6 targets")
        else -> { // SOCKS4a: invalid 0.0.0.x DST.IP signals "resolve this hostname"
            val h = targetHost.encodeToByteArray()
            proxy.write(byteArrayOf(0x04, 0x01, portHi, portLo, 0, 0, 0, 1, *userId, 0x00, *h, 0x00))
        }
    }
    val reply = proxy.readExactly(8) // VN(0), CD, DSTPORT(2), DSTIP(4)
    if (reply[1].toInt() != 0x5A) throw ProxyException("SOCKS4: request rejected (code ${reply[1].toInt() and 0xFF})")
}

/**
 * A live SOCKS5 UDP ASSOCIATE relay (RFC 1928 §6–§7). The control TCP connection ([control]) is
 * held open for the lifetime of the association (the proxy shuts the UDP relay down when that
 * connection closes), while datagrams flow over [relay], a plain [UdpSocket].
 *
 * The engine routes uTP / DHT / UDP-tracker traffic through this object: it [wrap]s an outbound
 * payload with the SOCKS5 request header and sends it to [relayHost]:[relayPort], and [unwrap]s
 * inbound datagrams (which carry the SOCKS5 reply header) back into (host, port, payload).
 *
 * This mirrors libtorrent's `socks5` helper in `udp_socket.cpp`, whose `wrap`/`socks5_unwrap`
 * functions build and parse the exact same `RSV[2] FRAG ATYP ADDR PORT DATA` framing.
 */
class Socks5UdpAssociation internal constructor(
    /** The kept-alive control connection; closing it ends the association. */
    val control: TcpConnection,
    /** The UDP socket all relayed datagrams are sent on / received from. */
    val relay: UdpSocket,
    /** The relay endpoint host the proxy told us to send wrapped datagrams to. */
    val relayHost: String,
    /** The relay endpoint port the proxy told us to send wrapped datagrams to. */
    val relayPort: Int,
) {
    /**
     * Build a SOCKS5 UDP request datagram: `RSV(0x0000) FRAG(0x00) ATYP DST.ADDR DST.PORT DATA`
     * targeting [targetHost]:[targetPort] with body [payload]. The result is ready to be sent
     * to [relayHost]:[relayPort]. FRAG is always 0 (no fragmentation), matching libtorrent.
     */
    fun wrap(targetHost: String, targetPort: Int, payload: ByteArray): ByteArray {
        val addr = encodeSocksAddr(targetHost)
        val header = byteArrayOf(
            0x00, 0x00,                 // RSV
            0x00,                       // FRAG
            *addr,                      // ATYP + DST.ADDR
            (targetPort ushr 8).toByte(), targetPort.toByte(), // DST.PORT
        )
        return header + payload
    }

    /** Send [payload] to [targetHost]:[targetPort] through the relay (a thin [wrap] + UDP send). */
    suspend fun send(targetHost: String, targetPort: Int, payload: ByteArray) {
        relay.send(wrap(targetHost, targetPort, payload), relayHost, relayPort)
    }

    /**
     * Parse a datagram received on [relay] off the relay, stripping the SOCKS5 reply header
     * (`RSV[2] FRAG ATYP ADDR PORT`). Returns the origin (host, port) and the inner payload, or
     * `null` if the datagram is too short, fragmented (FRAG != 0), or otherwise malformed: the
     * same packets libtorrent's `socks5_unwrap` returns `false` for and the caller drops.
     */
    fun unwrap(datagram: ByteArray): Socks5UdpDatagram? {
        // minimum IPv4 header: RSV(2) + FRAG(1) + ATYP(1) + addr(4) + port(2) = 10, plus >=1 data
        if (datagram.size <= 10) return null
        var p = 2 // skip RSV
        val frag = datagram[p].toInt() and 0xFF; p += 1
        if (frag != 0) return null // fragmentation unsupported
        val atyp = datagram[p].toInt() and 0xFF; p += 1
        val host: String
        when (atyp) {
            0x01 -> { // IPv4
                host = ipv4ToString(datagram, p); p += 4
            }
            0x04 -> { // IPv6: needs 4 + 16 + 2 = 22 header bytes minimum
                if (datagram.size <= 22) return null
                host = ipv6ToString(datagram, p); p += 16
            }
            0x03 -> { // domain name
                val len = datagram[p].toInt() and 0xFF; p += 1
                if (p + len + 2 > datagram.size) return null
                host = datagram.decodeToString(p, p + len); p += len
            }
            else -> return null
        }
        if (p + 2 > datagram.size) return null
        val port = ((datagram[p].toInt() and 0xFF) shl 8) or (datagram[p + 1].toInt() and 0xFF); p += 2
        val payload = datagram.copyOfRange(p, datagram.size)
        return Socks5UdpDatagram(host, port, payload)
    }

    /**
     * Receive one datagram from the relay and unwrap it. Returns `null` when the datagram is not a
     * valid SOCKS5-wrapped packet (e.g. fragmented), so the caller can simply loop again.
     */
    suspend fun receive(): Socks5UdpDatagram? = unwrap(relay.receive().data)

    /** Close the relay socket and the control connection, ending the UDP association. */
    fun close() {
        relay.close()
        control.close()
    }
}

/** The decoded origin and payload of an inbound SOCKS5-wrapped UDP datagram (see [Socks5UdpAssociation.unwrap]). */
class Socks5UdpDatagram(val host: String, val port: Int, val payload: ByteArray)

/**
 * Open a SOCKS5 UDP ASSOCIATE (RFC 1928 §7) relay through [config]: perform the auth handshake and
 * the `CMD=UDP ASSOCIATE` exchange on a freshly-dialled control connection, then learn the relay
 * `BND.ADDR:BND.PORT` from the reply. [relay] is the local UDP socket relayed datagrams travel on;
 * the returned [Socks5UdpAssociation] adopts it and closes it. The control connection
 * is kept alive (the proxy drops the relay if it closes), so the caller must keep the association
 * object reachable and [Socks5UdpAssociation.close] it on shutdown.
 *
 * Per RFC 1928, the DST.ADDR/DST.PORT in the request is the local UDP endpoint the client will send
 * from; `0.0.0.0:0` ("don't restrict") is sent here. libtorrent does the same unless it has a
 * concrete local endpoint to advertise. If the returned `BND.ADDR` is the wildcard `0.0.0.0`, the
 * proxy means "reuse my address", so [config].host is substituted (a standard client behaviour).
 *
 * Cross-ref `socks5::on_connected` → `socks_forward_udp` → `connect2`/`read_bindaddr` in
 * `udp_socket.cpp`. Throws [ProxyException] on any negotiation failure.
 */
internal suspend fun socks5UdpAssociate(
    control: TcpConnection,
    relay: UdpSocket,
    config: Socks5Config,
): Socks5UdpAssociation {
    // --- auth method negotiation (identical to socks5Connect) ---
    val methods = if (config.username != null) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00)
    control.write(byteArrayOf(0x05, methods.size.toByte(), *methods))

    val sel = control.readExactly(2)
    if (sel[0].toInt() != 0x05) throw ProxyException("SOCKS5: bad version in method reply")
    when (sel[1].toInt() and 0xFF) {
        0x00 -> {} // no auth
        0x02 -> { // RFC 1929 username/password
            val u = (config.username ?: "").encodeToByteArray()
            val pw = (config.password ?: "").encodeToByteArray()
            control.write(byteArrayOf(0x01, u.size.toByte(), *u, pw.size.toByte(), *pw))
            val ar = control.readExactly(2)
            if (ar[1].toInt() != 0x00) throw ProxyException("SOCKS5: username/password auth rejected")
        }
        0xFF -> throw ProxyException("SOCKS5: no acceptable auth method")
        else -> throw ProxyException("SOCKS5: unexpected auth method ${sel[1]}")
    }

    // --- UDP ASSOCIATE request: VER, CMD=3, RSV, ATYP=IPv4, 0.0.0.0, port 0 ---
    control.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))

    // --- reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT ---
    val head = control.readExactly(4)
    if (head[0].toInt() != 0x05) throw ProxyException("SOCKS5: bad version in ASSOCIATE reply")
    if (head[1].toInt() != 0x00) throw ProxyException("SOCKS5: UDP ASSOCIATE failed (reply code ${head[1].toInt() and 0xFF})")
    val relayHost: String = when (head[3].toInt() and 0xFF) {
        0x01 -> { val a = control.readExactly(4); ipv4ToString(a, 0) }
        0x04 -> { val a = control.readExactly(16); ipv6ToString(a, 0) }
        0x03 -> {
            val len = control.readExactly(1)[0].toInt() and 0xFF
            control.readExactly(len).decodeToString()
        }
        else -> throw ProxyException("SOCKS5: unknown bound-address type in ASSOCIATE reply")
    }
    val portBytes = control.readExactly(2)
    val relayPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

    // If the proxy returned the wildcard address, RFC 1928 says reuse the proxy's address.
    val effectiveHost = run {
        val parsed = PeerAddress.parseOrNull(relayHost)
        if (parsed != null && parsed.isUnspecified) config.host else relayHost
    }

    return Socks5UdpAssociation(control, relay, effectiveHost, relayPort)
}

/** Render the 4 bytes at [off] of [b] as a dotted-quad IPv4 string. */
private fun ipv4ToString(b: ByteArray, off: Int): String =
    "${b[off].toInt() and 0xFF}.${b[off + 1].toInt() and 0xFF}." +
        "${b[off + 2].toInt() and 0xFF}.${b[off + 3].toInt() and 0xFF}"

/** Render the 16 bytes at [off] of [b] as a (non-compressed) colon-grouped IPv6 string. */
private fun ipv6ToString(b: ByteArray, off: Int): String {
    val sb = StringBuilder(39)
    for (g in 0 until 8) {
        if (g != 0) sb.append(':')
        val hi = b[off + g * 2].toInt() and 0xFF
        val lo = b[off + g * 2 + 1].toInt() and 0xFF
        sb.append(((hi shl 8) or lo).toString(16))
    }
    return sb.toString()
}

/** Standard Base64 of [data] (for HTTP Basic auth): no stdlib dependency, every KMP target. */
private fun base64(data: ByteArray): String {
    val tab = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val out = StringBuilder((data.size + 2) / 3 * 4)
    var i = 0
    while (i < data.size) {
        val b0 = data[i].toInt() and 0xFF
        val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
        val n = (b0 shl 16) or (b1 shl 8) or b2
        out.append(tab[(n ushr 18) and 0x3F])
        out.append(tab[(n ushr 12) and 0x3F])
        out.append(if (i + 1 < data.size) tab[(n ushr 6) and 0x3F] else '=')
        out.append(if (i + 2 < data.size) tab[n and 0x3F] else '=')
        i += 3
    }
    return out.toString()
}

/** Encode a target host into the SOCKS5 address field: IPv4 (ATYP 1), IPv6 (4), else domain (3). */
private fun encodeSocksAddr(host: String): ByteArray {
    val ip = PeerAddress.parseOrNull(host)
    return when {
        ip != null && !ip.isV6 -> byteArrayOf(0x01, *ip.bytes)        // IPv4 literal
        ip != null && ip.isV6 -> byteArrayOf(0x04, *ip.bytes)         // IPv6 literal
        else -> {                                                     // hostname → let the proxy resolve
            val h = host.encodeToByteArray()
            byteArrayOf(0x03, h.size.toByte(), *h)
        }
    }
}
