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

/** Open a TCP connection to [host]:[port]. */
suspend fun NetworkRuntime.connectTcp(host: String, port: Int): TcpConnection {
    val socket = aSocket(selector).tcp().connect(InetSocketAddress(host, port))
    return TcpConnection(socket)
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

/** A real UDP socket — for UDP trackers, the DHT, and uTP. */
class UdpSocket(private val socket: BoundDatagramSocket) : DatagramTransport {
    override val localPort: Int get() = (socket.localAddress as InetSocketAddress).port

    override suspend fun send(data: ByteArray, host: String, port: Int) {
        socket.send(Datagram(Buffer().apply { write(data) }, InetSocketAddress(host, port)))
    }

    override suspend fun receive(): UdpPacket {
        val dg = socket.receive()
        val from = dg.address as InetSocketAddress
        return UdpPacket(dg.packet.readByteArray(), from.hostname, from.port)
    }

    override fun close() = socket.close()
}

/** A received datagram: payload [data] and the sender's [host]:[port]. */
class UdpPacket(val data: ByteArray, val host: String, val port: Int)

/** Bind a UDP socket on [port] (0 = any free port). */
suspend fun NetworkRuntime.bindUdp(port: Int = 0): UdpSocket =
    UdpSocket(aSocket(selector).udp().bind(InetSocketAddress("0.0.0.0", port)))
