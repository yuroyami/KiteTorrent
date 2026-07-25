package io.github.yuroyami.kitetorrent.session.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Demultiplexes one UDP socket across many [UtpStream]s: the port of libtorrent's
 * `utp_socket_manager` (src/utp_socket_manager.cpp) plus the session-level routing
 * that shares the listen socket between uTP and the DHT.
 *
 * libtorrent runs uTP, the DHT and UDP trackers over the *same* listen socket;
 * `session_impl::on_udp_packet` sniffs each datagram and hands uTP-shaped ones
 * (`incoming_packet`) to the manager, which routes by connection id, turning
 * `ST_SYN`s with no matching stream into new incoming connections. This class does
 * the same:
 *
 *  - [start] pumps [socket]; a datagram is uTP iff its first byte decodes as
 *    `version == 1` with a known packet type. A bencoded DHT message starts with
 *    `'d'` = 0x64, which fails that test. libtorrent discriminates the same way.
 *  - uTP datagrams are routed to the [UtpStream] registered under
 *    `(host, port, connection-id)`; an unmatched `ST_SYN` becomes a **passive open**:
 *    a new stream is created, fed the SYN, and handed to [onIncomingConnection].
 *  - everything else lands in the inbox behind [dhtTransport], a virtual
 *    [DatagramTransport] the [io.github.yuroyami.kitetorrent.session.dht.DhtNode]
 *    consumes unchanged.
 *
 * Connection-id arithmetic (see [UtpStream]): an active open with id `I` sends on
 * `I` and receives on `I-1`; its SYN advertises `I-1`. The passive side derives
 * send `C`/receive `C+1` from the SYN's id `C`. Inbound routing therefore keys on
 * the receiver's id: `I-1` for streams we opened, `C+1` for accepted ones.
 */
class UtpSocketManager(
    private val socket: UdpSocket,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val streams = HashMap<String, UtpStream>()
    private val keys = HashMap<UtpStream, String>()
    private var nextConnectionId = 0x4000 // arbitrary start; wraps within 16 bits

    /** One real monotonic clock shared by every stream. It drives RTT and the RTO timer. */
    private val clock = MonotonicMicros()

    /** Datagrams that are not uTP (the DHT's bencoded packets), behind [dhtTransport]. */
    private val otherInbox = Channel<UdpPacket>(Channel.UNLIMITED)

    /**
     * The manager calls this (in a fresh coroutine) for each accepted inbound uTP
     * connection, once its SYN is processed. The engine then reads the BitTorrent
     * handshake off the stream and routes by info-hash, exactly like a TCP accept.
     */
    var onIncomingConnection: (suspend (UtpStream) -> Unit)? = null

    /** The local UDP port (== the engine's listen port when bound there). */
    val localPort: Int get() = socket.localPort

    /** Live stream count (for tests/observability). */
    suspend fun numStreams(): Int = lock.withLock { streams.size }

    /** Start the receive/dispatch loop. */
    fun start(): Job = scope.launch {
        while (isActive) {
            val pkt = try {
                socket.receive()
            } catch (_: Throwable) {
                break // socket closed
            }
            if (isUtp(pkt.data)) dispatch(pkt) else otherInbox.trySend(pkt)
        }
        otherInbox.close()
    }

    /** Actively open a uTP connection to [host]:[port] (suspends until connected). */
    suspend fun connect(host: String, port: Int): UtpStream {
        val stream: UtpStream
        lock.withLock {
            val id = nextConnectionId
            nextConnectionId = (nextConnectionId + 2) and 0xFFFF
            stream = UtpStream(socket, host, port, scope, nowMicros = clock, connectionId = id)
            val key = key(port, (id - 1) and 0xFFFF) // we receive on id-1
            streams[key] = stream
            keys[stream] = key
        }
        try {
            stream.connect()
        } catch (e: Throwable) {
            // also runs under cancellation (a dial timeout), so it must still unregister
            withContext(NonCancellable) { disconnect(stream) }
            throw e
        }
        return stream
    }

    /** Close [stream] and drop its routing entry. */
    suspend fun disconnect(stream: UtpStream) {
        lock.withLock { keys.remove(stream)?.let { streams.remove(it) } }
        stream.close()
    }

    /**
     * A virtual [DatagramTransport] carrying every non-uTP datagram, for the DHT.
     * `close()` is a no-op, because the manager owns the real socket.
     */
    fun dhtTransport(): DatagramTransport = object : DatagramTransport {
        override val localPort: Int get() = socket.localPort
        override suspend fun send(data: ByteArray, host: String, port: Int) =
            socket.send(data, host, port)
        override suspend fun receive(): UdpPacket = otherInbox.receive()
        override fun close() {}
    }

    /** Close the underlying socket; the receive loop then winds down. */
    fun close() = socket.close()

    // --- internal routing ----------------------------------------------------

    private suspend fun dispatch(pkt: UdpPacket) {
        val header = UtpHeader.decode(pkt.data) ?: return
        val existing = lock.withLock { streams[key(pkt.port, header.connectionId)] }
        if (existing != null) {
            existing.onDatagram(pkt.data)
            return
        }
        if (header.type != UtpType.ST_SYN) return // not ours and not an open, so drop it

        // passive open: the acceptor receives on (SYN id + 1)
        val stream = UtpStream(socket, pkt.host, pkt.port, scope, nowMicros = clock)
        val key = key(pkt.port, (header.connectionId + 1) and 0xFFFF)
        val fresh = lock.withLock {
            if (streams.containsKey(key)) {
                false // duplicate SYN for a connection we already accepted
            } else {
                streams[key] = stream
                keys[stream] = key
                true
            }
        }
        if (!fresh) return
        stream.onDatagram(pkt.data) // processes the SYN, sends the ack
        onIncomingConnection?.let { handler -> scope.launch { handler(stream) } }
    }

    /**
     * Routing key: remote **port + connection id**, deliberately not the host
     * string. The same address can arrive as `"127.0.0.1"` on dial but
     * `"localhost"` on receive (resolver-dependent), and the 16-bit id is the real
     * discriminator (libtorrent's `utp_socket_manager` also keys its socket map by
     * id). A cross-host (port, id) collision just drops that stream's packets.
     */
    private fun key(port: Int, connectionId: Int) = "$port/$connectionId"

    companion object {
        /**
         * True if [data] looks like a uTP packet: 20-byte header, version nibble 1,
         * known type nibble. A DHT/bencode datagram starts with `'d'` (0x64 →
         * version 4) and always fails this check. `session_impl` sniffs the same way.
         */
        fun isUtp(data: ByteArray): Boolean {
            if (data.size < UTP_HEADER_SIZE) return false
            val b0 = data[0].toInt() and 0xFF
            return (b0 and 0x0F) == UTP_VERSION && (b0 ushr 4) <= 4
        }
    }
}
