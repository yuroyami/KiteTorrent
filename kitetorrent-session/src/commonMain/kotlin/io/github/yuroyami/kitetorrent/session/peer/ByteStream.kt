package io.github.yuroyami.kitetorrent.session.peer

import io.github.yuroyami.kitetorrent.session.net.TcpConnection

/**
 * The minimal byte-duplex a [PeerConnection] needs: read exactly N bytes, write a
 * frame. This is the seam between the protocol state machine and the concrete
 * socket. libtorrent keeps the equivalent inside `peer_connection`'s `m_recv_buffer`
 * and `send_buffer` code, which couples the protocol to Boost.Asio. Separating it lets
 * you test [PeerConnection] against an in-memory duplex with no live sockets.
 *
 * [TcpConnection] (session.net) already provides exactly these two operations; the
 * [asByteStream] adapter wraps one without modifying it.
 */
interface ByteStream {
    /** Suspend until exactly [n] bytes have been read, then return them. */
    suspend fun readExactly(n: Int): ByteArray

    /** Write [bytes] in full (and flush, for a socket implementation). */
    suspend fun write(bytes: ByteArray)

    /** A human-readable identifier for the remote end, for logging. */
    val remoteAddress: String
}

/**
 * Adapt a proven [TcpConnection] to the [ByteStream] seam. The connection itself is
 * untouched; this is a thin forwarding wrapper so production code feeds a real
 * socket into [PeerConnection] while tests feed a fake.
 */
fun TcpConnection.asByteStream(): ByteStream = object : ByteStream {
    override suspend fun readExactly(n: Int): ByteArray = this@asByteStream.readExactly(n)
    override suspend fun write(bytes: ByteArray) = this@asByteStream.write(bytes)
    override val remoteAddress: String get() = this@asByteStream.remoteAddress
}
