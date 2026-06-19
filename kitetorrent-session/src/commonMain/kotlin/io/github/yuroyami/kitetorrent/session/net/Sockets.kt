package io.github.yuroyami.kitetorrent.session.net

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.coroutines.CoroutineContext

/**
 * The socket runtime — one [SelectorManager] drives all non-blocking I/O, the way a
 * single Boost.Asio `io_context` does in libtorrent. ktor-network's selector is
 * coroutine-native, so there are no callbacks: a read simply suspends.
 */
class NetworkRuntime(context: CoroutineContext) {
    val selector = SelectorManager(context)

    /** When set, all outbound TCP [connectTcp] dials are tunnelled through this proxy (SOCKS5/HTTP). */
    var proxy: ProxyConfig? = null

    fun close() = selector.close()
}

/**
 * A TCP connection to a peer (or HTTP tracker). Thin coroutine wrapper over a
 * ktor [Socket] exposing the two operations the wire protocol needs: read exactly N
 * bytes, and write a frame. Replaces the bespoke async read/write buffers in
 * libtorrent's peer_connection.
 */
class TcpConnection(private val socket: Socket) {
    private val readCh = socket.openReadChannel()
    private val writeCh = socket.openWriteChannel(autoFlush = false)

    val remoteAddress: String get() = socket.remoteAddress.toString()

    /** Suspends until exactly [n] bytes have been read, then returns them. */
    suspend fun readExactly(n: Int): ByteArray {
        val buf = ByteArray(n)
        readCh.readFully(buf, 0, n)
        return buf
    }

    /** Write [bytes] and flush. */
    suspend fun write(bytes: ByteArray) {
        writeCh.writeFully(bytes, 0, bytes.size)
        writeCh.flush()
    }

    fun close() {
        socket.close()
    }
}

/**
 * Open a TCP connection to [host]:[port], tunnelling through [NetworkRuntime.proxy] if set.
 *
 * [connectTimeoutMs], when positive, caps the time spent establishing the connection (and, for a
 * proxied dial, the proxy negotiation too); a non-positive value (the default) waits indefinitely,
 * preserving the previous behaviour. This mirrors libtorrent's `socks5::on_connect_timeout`
 * 10-second guard on the control connection. On expiry the partially-opened socket is closed and a
 * [kotlinx.coroutines.TimeoutCancellationException] propagates.
 */
suspend fun NetworkRuntime.connectTcp(
    host: String,
    port: Int,
    connectTimeoutMs: Long = 0L,
    /**
     * Dial [host]:[port] *directly*, ignoring [NetworkRuntime.proxy]. Used to reach the proxy
     * itself — e.g. opening a SOCKS5 UDP-ASSOCIATE control connection — which must not be tunnelled
     * through the very proxy it is establishing (that would dead-loop the negotiation).
     */
    bypassProxy: Boolean = false,
): TcpConnection {
    suspend fun dial(): TcpConnection {
        val p = if (bypassProxy) null else proxy
        if (p != null) {
            val sock = aSocket(selector).tcp().connect(InetSocketAddress(p.host, p.port))
            val conn = TcpConnection(sock)
            try {
                when (p) {
                    is Socks5Config -> socks5Connect(conn, host, port, p)
                    is Socks4Config -> socks4Connect(conn, host, port, p)
                    is HttpProxyConfig -> httpConnect(conn, host, port, p)
                }
            } catch (e: Throwable) {
                conn.close()
                throw e
            }
            return conn
        }
        val socket = aSocket(selector).tcp().connect(InetSocketAddress(host, port))
        return TcpConnection(socket)
    }

    if (connectTimeoutMs <= 0L) return dial()
    var opened: TcpConnection? = null
    try {
        return withTimeout(connectTimeoutMs) { dial().also { opened = it } }
    } catch (e: TimeoutCancellationException) {
        opened?.close()
        throw e
    }
}

/**
 * A listening TCP socket — accepts inbound peer connections (libtorrent's
 * `listen_socket_t`). Used by the engine to receive connections and by tests to
 * stand up a loopback seeder.
 */
class TcpServer(private val serverSocket: io.ktor.network.sockets.ServerSocket) {
    /** The actually-bound port (useful when binding to port 0 for an ephemeral port). */
    val localPort: Int get() = (serverSocket.localAddress as InetSocketAddress).port

    /** Suspend until a peer connects, then return the connection. */
    suspend fun accept(): TcpConnection = TcpConnection(serverSocket.accept())

    fun close() = serverSocket.close()
}

/** Bind a listening TCP socket on [host]:[port] (port 0 = an ephemeral free port). */
suspend fun NetworkRuntime.bindTcp(port: Int, host: String = "0.0.0.0"): TcpServer =
    TcpServer(aSocket(selector).tcp().bind(InetSocketAddress(host, port)))

/**
 * A datagram (host, port) source/sink — the seam consumers (DHT, UDP trackers, uTP)
 * program against. [UdpSocket] is the real implementation; a demultiplexer (see
 * `UtpSocketManager`) hands out *virtual* transports over one shared socket, the way
 * libtorrent's `session_impl` routes one listen socket to uTP, DHT and trackers.
 */
interface DatagramTransport {
    /** The actually-bound local port (useful when bound to port 0). */
    val localPort: Int

    /** Send [data] to [host]:[port]. */
    suspend fun send(data: ByteArray, host: String, port: Int)

    /** Suspend until a datagram arrives; return its bytes and sender host/port. */
    suspend fun receive(): UdpPacket

    fun close()
}

/**
 * A real UDP socket — for UDP trackers, the DHT, and uTP.
 *
 * When [socks5] is set, this socket is *proxied*: outbound datagrams are SOCKS5-wrapped (RFC 1928
 * §7) and physically sent to the proxy's UDP relay, and inbound datagrams are unwrapped so the
 * caller sees the true origin `(host, port)` — the destination IP never leaves the host in clear.
 * This is the seam that routes the engine's shared uTP/DHT/UDP-tracker socket through the proxy
 * without touching [UtpSocketManager]/[UtpStream] (they keep sending/receiving on a [UdpSocket]).
 * Mirrors libtorrent's `udp_socket`, which wraps/unwraps via its embedded `socks5` helper on the
 * exact same datagrams when a SOCKS5 proxy with an open UDP association is configured.
 *
 * When [socks5] is null the behaviour is byte-identical to a plain unproxied socket.
 */
class UdpSocket(
    private val socket: BoundDatagramSocket,
    /**
     * The live SOCKS5 UDP association whose relay this socket sends through; null = direct.
     * The association owns its *own* relay [UdpSocket] (created without an association, so there
     * is no recursion): wrapped datagrams physically transit `relay`, while this socket's
     * send/receive present the un-proxied (host, port, payload) view to callers.
     */
    private val socks5: Socks5UdpAssociation? = null,
) : DatagramTransport {
    /** The raw ktor socket, so a proxied view can be layered over an already-bound socket. */
    internal val bound: BoundDatagramSocket get() = socket

    /**
     * Return a *proxied* view of this socket that SOCKS5-wraps every send through [association]'s
     * relay and unwraps every receive — reusing this socket's underlying ktor socket, so there is
     * exactly one physical socket (the association's relay) carrying the wrapped traffic. The
     * non-proxy [UdpSocket] is left untouched. Used by the engine to hand uTP/DHT a proxied socket.
     */
    internal fun proxiedThrough(association: Socks5UdpAssociation): UdpSocket =
        UdpSocket(socket, association)

    override val localPort: Int get() = (socket.localAddress as InetSocketAddress).port

    override suspend fun send(data: ByteArray, host: String, port: Int) {
        val s = socks5
        if (s != null) {
            // wrap with the SOCKS5 UDP request header and forward to the proxy relay endpoint.
            sendRaw(s.wrap(host, port, data), s.relayHost, s.relayPort)
        } else {
            sendRaw(data, host, port)
        }
    }

    override suspend fun receive(): UdpPacket {
        val s = socks5
        if (s != null) {
            // datagrams arrive from the relay; unwrap to recover the true origin. A malformed /
            // fragmented packet (unwrap == null) is dropped and we wait for the next, exactly as
            // libtorrent's socks5_unwrap returns false and the caller loops.
            while (true) {
                val raw = receiveRaw()
                val dg = s.unwrap(raw.data) ?: continue
                return UdpPacket(dg.payload, dg.host, dg.port)
            }
        }
        return receiveRaw()
    }

    /** Raw (un-proxied) send straight to [host]:[port] — also used to reach the proxy relay. */
    private suspend fun sendRaw(data: ByteArray, host: String, port: Int) {
        socket.send(Datagram(Buffer().apply { write(data) }, InetSocketAddress(host, port)))
    }

    /** Raw (un-proxied) receive of the next datagram off the wire. */
    private suspend fun receiveRaw(): UdpPacket {
        val dg = socket.receive()
        val from = dg.address as InetSocketAddress
        return UdpPacket(dg.packet.readByteArray(), from.hostname, from.port)
    }

    /**
     * Close the socket. For a proxied socket this also tears down the SOCKS5 association (its kept-
     * alive control connection — the proxy drops the relay when it closes anyway), so an ephemeral
     * proxied socket leaks nothing. The association's `close()` re-closes this same underlying
     * socket, which is harmless. A direct socket just closes the socket.
     */
    override fun close() {
        socks5?.control?.close()
        socket.close()
    }
}

/** A received datagram: payload [data] and the sender's [host]:[port]. */
class UdpPacket(val data: ByteArray, val host: String, val port: Int)

/**
 * Bind a UDP socket on [host]:[port] (port 0 = any free port). [host] defaults to the IPv4 wildcard
 * `0.0.0.0`; pass `"::"` to bind the IPv6 (dual-stack on most OSes) wildcard, the way libtorrent's
 * `udp_socket::bind` honours the listen interface's address family.
 */
suspend fun NetworkRuntime.bindUdp(port: Int = 0, host: String = "0.0.0.0"): UdpSocket =
    UdpSocket(aSocket(selector).udp().bind(InetSocketAddress(host, port)))
