package io.github.yuroyami.kitetorrent.session.net

import io.github.yuroyami.kitetorrent.session.peer.ByteStream
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * uTP (Micro Transport Protocol, BEP-29) connection over a single [UdpSocket] to one
 * remote `(host, port)`. This is a faithful **core** port of libtorrent's
 * `utp_socket_impl` (`src/utp_stream.cpp`) — enough state machine to actually move
 * bytes — and it implements [ByteStream] so a `PeerConnection` runs over uTP with no
 * changes (the same seam [TcpConnection.asByteStream] satisfies).
 *
 * ### What is faithful
 * - **Connection-id assignment.** For an outgoing connect, libtorrent's
 *   `new_utp_socket()` picks `send_id = random()` and `recv_id = send_id - 1`; the
 *   SYN is then sent with `connection_id = recv_id` ("using recv_id here is
 *   intentional", `send_syn()`), and every subsequent packet we send carries
 *   `send_id`. Incoming packets must carry our `recv_id`. We reproduce this exactly.
 * - **Sequence/ack numbers** are 16-bit wrap-around counters compared with
 *   `compare_less_wrap` (= [seqLessWrap]); `ACK_MASK = 0xffff`. `seq_nr` only
 *   advances for packets that carry a sequence (SYN/DATA/FIN), matching the C++
 *   comment "m_seq_nr is only incremented when sending packets with data payload".
 * - **Handshake.** `connect()` sends ST_SYN in state `syn_sent`; the first
 *   ST_STATE/ST_DATA whose `ack_nr == (seq_nr-1)` transitions to `connected` and
 *   initialises `ack_nr` from the peer's `seq_nr` (`case state_t::syn_sent`).
 * - **In-order reassembly + reorder buffer.** [consumeData] mirrors
 *   `consume_incoming_data()`: a packet at `(ack_nr+1)` is delivered and drains any
 *   contiguous run from the reorder map; anything else is stashed by `seq_nr`.
 * - **Cumulative ack of our send buffer.** Incoming `ack_nr` frees every in-flight
 *   packet up to it (`for (ack_nr = acked+1 .. next_ack) m_outbuf.remove(...)`),
 *   releasing send-window space.
 * - **FIN / RESET.** ST_FIN advances `ack_nr` in order and signals EOF to readers;
 *   ST_RESET errors the socket. [close] sends ST_FIN.
 *
 * ### What is SIMPLIFIED (vs. libtorrent) — see also the class-level notes the
 * assignment asked to pin down:
 * - **LEDBAT / delay-based congestion control is omitted.** libtorrent runs
 *   `do_ledbat()` against a 100 ms `target_delay`, growing/shrinking `m_cwnd` from
 *   one-way delay samples (`timestamp_difference_microseconds`). We use a **fixed
 *   byte window** ([sendWindowBytes]) of bytes-in-flight instead. We still fill in
 *   the timestamp fields and parse the peer's, but we do not act on delay.
 * - **No retransmission / RTO timer, no fast-resend, no duplicate-ACK loss
 *   detection.** Delivery relies on the cumulative ACK; a genuinely lost packet is
 *   not resent. (libtorrent: `packet_timeout()`, `resend_packet()`, `dup_ack_limit`,
 *   `experienced_loss()`.) Acceptable on loopback / low-loss links; noted as a gap.
 * - **No Selective ACK (SACK) generation.** We *parse* an inbound SACK extension
 *   (via [parseUtpPacket]) but never emit one and never resend from it.
 * - **No MTU / Path-MTU discovery.** Payload is capped at a fixed
 *   [maxPayload]; libtorrent searches `[mtu_floor, mtu_ceiling]` with MTU probes.
 * - **No Nagle coalescing.** Each [write] chunk becomes its own ST_DATA packet(s).
 * - **No `wnd_size` flow control honoured for sending.** We advertise our own
 *   window but ignore the peer's advertised `wnd_size` when deciding to send
 *   (the fixed [sendWindowBytes] is the only limit).
 *
 * ### Driving the read side
 * uTP is connectionless at the socket layer: one [UdpSocket] multiplexes many
 * streams by `(connection_id)`. This class does **not** own the socket's receive
 * loop; the owner pumps inbound datagrams in by calling [onDatagram] with the raw
 * bytes of each packet addressed to this stream. (A full `utp_socket_manager` would
 * demultiplex by connection id; that router is out of scope here.)
 *
 * @param socket the shared UDP socket used to *send*; the owner drives receive.
 * @param remoteHost remote peer host.
 * @param remotePort remote peer port.
 * @param scope coroutine scope the background ack/feed work runs in.
 * @param nowMicros injected microsecond clock — the core is clockless, so this
 *   defaults to a monotonically increasing counter ([MicrosCounter]). Production may
 *   pass a real monotonic clock so `timestamp_difference` is meaningful to peers.
 * @param connectionId optional fixed `send_id` (the SYN goes out on `send_id - 1`);
 *   defaults to a value derived from the clock so two streams differ. Injectable for
 *   deterministic tests.
 */
class UtpStream(
    private val socket: UdpSocket,
    private val remoteHost: String,
    private val remotePort: Int,
    private val scope: CoroutineScope,
    private val nowMicros: () -> Long = MicrosCounter(),
    connectionId: Int = ((nowMicros() and 0xFFFF).toInt()),
) : ByteStream {

    // ---- connection ids (utp_socket_impl ctor + new_utp_socket) --------------
    // Outgoing: send_id = chosen id, recv_id = send_id - 1. The SYN advertises
    // recv_id; all later packets we send carry send_id; inbound must match recv_id.
    // var (not val): a passive open re-derives these from the inbound SYN's id.
    private var sendId: Int = connectionId and ACK_MASK
    private var recvId: Int = (connectionId - 1) and ACK_MASK

    // ---- sequence state (guarded by [lock]) ----------------------------------
    private var seqNr: Int = 0            // next seq_nr we will send
    private var ackNr: Int = 0            // last seq_nr we received in order
    private var ackedSeqNr: Int = 0       // highest of our packets the peer acked
    private var state: State = State.NONE

    private enum class State { NONE, SYN_SENT, CONNECTED, FIN_SENT, CLOSED }

    /** Completes when the SYN is acknowledged (transition to CONNECTED), or fails. */
    private val connected = CompletableDeferred<Unit>()

    /** Set once a FIN is received in order or the socket is reset/closed. */
    @Volatile private var remoteFinished = false
    @Volatile private var errored: Throwable? = null

    private val lock = Mutex()

    // ---- send window (SIMPLIFIED fixed-window congestion control) ------------
    /** Fixed bytes-in-flight ceiling, in lieu of LEDBAT `m_cwnd`. */
    private val sendWindowBytes = DEFAULT_SEND_WINDOW
    /** Max payload per ST_DATA packet, in lieu of MTU discovery. */
    private val maxPayload = DEFAULT_MAX_PAYLOAD
    private var bytesInFlight = 0
    /** Outstanding sent DATA packets by seq_nr → payload length, for ack accounting. */
    private val outstanding = HashMap<Int, Int>()
    /** Woken whenever window space frees up (an ack advanced [ackedSeqNr]). */
    private val windowSignal = Channel<Unit>(Channel.CONFLATED)

    // ---- receive reassembly --------------------------------------------------
    /** Reorder buffer: out-of-order payloads keyed by their seq_nr. */
    private val reorder = HashMap<Int, ByteArray>()
    /** Delivered, in-order bytes waiting for [readExactly] to drain them. */
    private val inbound = ArrayDeque<Byte>()
    /** Woken whenever [inbound] gains bytes or the stream reaches EOF/error. */
    private val readSignal = Channel<Unit>(Channel.CONFLATED)

    override val remoteAddress: String get() = "$remoteHost:$remotePort"

    // =========================================================================
    //  Handshake
    // =========================================================================

    /**
     * Perform the active open: send ST_SYN and suspend until the peer's ST_STATE
     * (or first ST_DATA) acknowledges it. Faithful to `send_syn()` +
     * `case state_t::syn_sent`.
     *
     * The owner must already be pumping inbound datagrams into [onDatagram] (or start
     * doing so concurrently) so the handshake can complete.
     */
    suspend fun connect() {
        val synSeq: Int
        lock.withLock {
            check(state == State.NONE) { "connect() already called (state=$state)" }
            // send_syn(): m_seq_nr = random; here we start at 1 for determinism, the
            // wire value is irrelevant as long as it's consistent. acked = seq-1.
            seqNr = 1
            ackedSeqNr = (seqNr - 1) and ACK_MASK
            ackNr = 0
            synSeq = seqNr
            state = State.SYN_SENT
        }
        // The SYN carries connection_id = recv_id (intentional, see send_syn()).
        val syn = UtpHeader(
            type = UtpType.ST_SYN,
            connectionId = recvId,
            timestampMicros = nowMicros() and 0xFFFFFFFFL,
            timestampDiffMicros = 0,
            wndSize = sendWindowBytes.toLong(),
            seqNr = synSeq,
            ackNr = 0,
        )
        sendRaw(buildUtpPacket(syn))
        lock.withLock { seqNr = (seqNr + 1) and ACK_MASK }   // SYN consumes a seq_nr
        connected.await()
    }

    // =========================================================================
    //  ByteStream: write
    // =========================================================================

    /**
     * Packetise [bytes] into one or more ST_DATA packets and send them, blocking on
     * the fixed send window so bytes-in-flight never exceeds [sendWindowBytes].
     * Faithful to `send_pkt()` chunking (`payload_size = min(write_buffer, mtu -
     * header)`), minus LEDBAT/Nagle/MTU.
     */
    override suspend fun write(bytes: ByteArray) {
        ensureUsable()
        connected.await()
        var off = 0
        while (off < bytes.size) {
            val chunk = minOf(maxPayload, bytes.size - off)
            awaitWindow(chunk)
            val payload = bytes.copyOfRange(off, off + chunk)
            val (seq, ack) = lock.withLock {
                ensureUsableLocked()
                val s = seqNr
                seqNr = (seqNr + 1) and ACK_MASK
                bytesInFlight += chunk
                outstanding[s] = chunk
                s to ackNr
            }
            val h = UtpHeader(
                type = UtpType.ST_DATA,
                connectionId = sendId,
                timestampMicros = nowMicros() and 0xFFFFFFFFL,
                timestampDiffMicros = 0,
                wndSize = currentRecvWindow(),
                seqNr = seq,
                ackNr = ack,
            )
            sendRaw(buildUtpPacket(h, payload))
            off += chunk
        }
    }

    /** Suspend until [n] bytes of in-flight headroom exist in the fixed window. */
    private suspend fun awaitWindow(n: Int) {
        while (true) {
            val ok = lock.withLock {
                ensureUsableLocked()
                bytesInFlight == 0 || bytesInFlight + n <= sendWindowBytes
            }
            if (ok) return
            windowSignal.receive()
        }
    }

    // =========================================================================
    //  ByteStream: read
    // =========================================================================

    /**
     * Suspend until exactly [n] reassembled, in-order payload bytes are available,
     * then return them. Throws if the stream errored or hit EOF before [n] bytes.
     */
    override suspend fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var filled = 0
        while (filled < n) {
            val took = lock.withLock {
                var k = 0
                while (filled + k < n && inbound.isNotEmpty()) {
                    out[filled + k] = inbound.removeFirst()
                    k++
                }
                k
            }
            filled += took
            if (filled == n) break
            // need more bytes — check terminal conditions then wait for a signal
            errored?.let { throw it }
            val eof = lock.withLock { remoteFinished && inbound.isEmpty() }
            if (eof) throw UtpEofException("uTP stream closed by peer after $filled/$n bytes")
            readSignal.receive()
        }
        return out
    }

    // =========================================================================
    //  Inbound packet pump (called by the socket owner / demuxer)
    // =========================================================================

    /**
     * Feed one raw inbound uTP datagram (header + extensions + payload) addressed to
     * this stream. Drives the full state machine: handshake completion, in-order
     * reassembly, cumulative ack of our send buffer, FIN and RESET. Malformed or
     * mis-addressed packets are dropped silently (return without effect), mirroring
     * the many `return false/true` rejects in `incoming_packet()`.
     *
     * Faithful structure: validate → ack-our-outbuf → per-type/state handling.
     */
    suspend fun onDatagram(raw: ByteArray) {
        val pkt = parseUtpPacket(raw) ?: return
        val h = pkt.header
        if (h.version != UTP_VERSION) return                       // "version != 1 → ignore"
        // SYN has reversed ids; every other type must match our recv_id.
        if (h.type != UtpType.ST_SYN && h.connectionId != recvId) return

        // Reactions computed under lock; deferred ACK / signals fired after unlock.
        var sendAck = false
        var ackToSend = 0
        var newlyConnected = false
        var resetErr: Throwable? = null

        lock.withLock {
            if (state == State.CLOSED) return
            // --- cumulative ack: free our in-flight packets up to h.ackNr --------
            if (state != State.NONE && seqLessWrap(ackedSeqNr, h.ackNr)) {
                var a = (ackedSeqNr + 1) and ACK_MASK
                val stop = (h.ackNr + 1) and ACK_MASK
                while (a != stop) {
                    outstanding.remove(a)?.let { bytesInFlight -= it }
                    a = (a + 1) and ACK_MASK
                }
                ackedSeqNr = h.ackNr
            }

            when (h.type) {
                UtpType.ST_RESET -> {
                    resetErr = UtpResetException("uTP connection reset by peer")
                    state = State.CLOSED
                }

                UtpType.ST_SYN -> {
                    // Passive open: an inbound connection. Derive our ids from the SYN
                    // (acceptor send_id = SYN's advertised id; recv_id = that + 1, so the
                    // initiator's send_id matches), record the SYN seq as our ack, and
                    // reply with an ST_STATE that acks the SYN. (utp: incoming_connection)
                    if (state == State.NONE) {
                        sendId = h.connectionId
                        recvId = (h.connectionId + 1) and ACK_MASK
                        ackNr = h.seqNr
                        if (seqNr == 0) seqNr = 1
                        state = State.CONNECTED
                        sendAck = true
                        ackToSend = ackNr
                        newlyConnected = true
                    }
                }

                UtpType.ST_STATE, UtpType.ST_DATA, UtpType.ST_FIN -> {
                    if (state == State.SYN_SENT) {
                        // wait for the ack to our SYN: ack_nr == seq_nr-1
                        if (h.ackNr == ((seqNr - 1) and ACK_MASK)) {
                            state = State.CONNECTED
                            // only progress ack_nr on ST_DATA; otherwise seq-1
                            ackNr = if (h.type == UtpType.ST_DATA) h.seqNr
                                    else (h.seqNr - 1) and ACK_MASK
                            newlyConnected = true
                        }
                    }
                    if (state == State.CONNECTED || state == State.FIN_SENT) {
                        if (h.type == UtpType.ST_DATA) {
                            consumeData(h.seqNr, pkt.payload)
                            sendAck = true
                        }
                        if (h.type == UtpType.ST_FIN) {
                            // FIN in order advances ack_nr; either way we ack it.
                            if (h.seqNr == ((ackNr + 1) and ACK_MASK) || h.seqNr == ackNr) {
                                ackNr = h.seqNr
                            }
                            remoteFinished = true
                            sendAck = true
                        }
                    }
                    ackToSend = ackNr
                }
            }
        }

        // ---- post-lock effects --------------------------------------------------
        resetErr?.let {
            errored = it
            if (!connected.isCompleted) connected.completeExceptionally(it)
            wakeAll()
            return
        }
        if (newlyConnected && !connected.isCompleted) connected.complete(Unit)
        if (sendAck) sendStateAck(ackToSend)
        wakeAll()
    }

    /**
     * In-order delivery + reorder buffering, faithful to `consume_incoming_data()`.
     * A packet whose `seq_nr == ack_nr+1` is delivered and we then drain any
     * contiguous run already sitting in the reorder map; anything else is stashed by
     * `seq_nr` (duplicates / already-delivered packets are dropped). Must be called
     * holding [lock].
     */
    private fun consumeData(seq: Int, payload: ByteArray) {
        if (seq == ((ackNr + 1) and ACK_MASK)) {
            if (payload.isNotEmpty()) deliver(payload)
            ackNr = (ackNr + 1) and ACK_MASK
            // drain contiguous reordered packets
            while (true) {
                val next = (ackNr + 1) and ACK_MASK
                val p = reorder.remove(next) ?: break
                if (p.isNotEmpty()) deliver(p)
                ackNr = next
            }
        } else {
            // out of order: ignore if already delivered or a duplicate, else stash
            if (!seqLessWrap(ackNr, seq)) return     // <= ack_nr → already had it
            if (reorder.containsKey(seq)) return
            reorder[seq] = payload
        }
    }

    private fun deliver(payload: ByteArray) {
        for (b in payload) inbound.addLast(b)
    }

    // =========================================================================
    //  Close
    // =========================================================================

    /**
     * Graceful close: send ST_FIN (faithful to `send_fin()` → state `fin_sent`) and
     * mark the stream closed locally. Idempotent. Does not wait for the peer's FIN.
     *
     * Note: [ByteStream] does not declare `close()` (the seam is read/write only, see
     * `peer/ByteStream.kt`), so this is a plain method — call it on the concrete
     * [UtpStream] when tearing the transport down.
     */
    fun close() {
        socketClose()
    }

    private fun socketClose() {
        scope.launch {
            val (seq, ack, doFin) = lock.withLock {
                if (state == State.CLOSED) return@launch
                val send = state == State.CONNECTED || state == State.SYN_SENT
                val s = seqNr
                val a = ackNr
                state = State.CLOSED
                Triple(s, a, send)
            }
            if (doFin) {
                val fin = UtpHeader(
                    type = UtpType.ST_FIN,
                    connectionId = sendId,
                    timestampMicros = nowMicros() and 0xFFFFFFFFL,
                    timestampDiffMicros = 0,
                    wndSize = currentRecvWindow(),
                    seqNr = seq,
                    ackNr = ack,
                )
                runCatching { sendRaw(buildUtpPacket(fin)) }
            }
            if (!connected.isCompleted) {
                connected.completeExceptionally(UtpEofException("uTP stream closed before connect"))
            }
            wakeAll()
        }
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** Send a pure ST_STATE ack carrying our current [ackNr] (the deferred-ack path). */
    private suspend fun sendStateAck(ack: Int) {
        val seq = lock.withLock { seqNr }   // seq_nr ignored for ST_STATE, but echo it
        val h = UtpHeader(
            type = UtpType.ST_STATE,
            connectionId = sendId,
            timestampMicros = nowMicros() and 0xFFFFFFFFL,
            timestampDiffMicros = 0,
            wndSize = currentRecvWindow(),
            seqNr = seq,
            ackNr = ack,
        )
        runCatching { sendRaw(buildUtpPacket(h)) }
    }

    private suspend fun sendRaw(bytes: ByteArray) = socket.send(bytes, remoteHost, remotePort)

    /** Bytes of receive headroom we advertise (= window minus what's buffered). */
    private fun currentRecvWindow(): Long =
        (sendWindowBytes - inbound.size).coerceAtLeast(0).toLong()

    private fun wakeAll() {
        windowSignal.trySend(Unit)
        readSignal.trySend(Unit)
    }

    private fun ensureUsable() {
        errored?.let { throw it }
        if (state == State.CLOSED) throw UtpEofException("uTP stream is closed")
    }

    private fun ensureUsableLocked() {
        errored?.let { throw it }
        if (state == State.CLOSED) throw UtpEofException("uTP stream is closed")
    }

    companion object {
        /** ACK_MASK from utp_stream.cpp — seq/ack numbers are 16-bit wrap counters. */
        const val ACK_MASK = 0xFFFF

        /** Fixed send window (bytes in flight). Stand-in for LEDBAT `m_cwnd`. */
        const val DEFAULT_SEND_WINDOW = 64 * 1024

        /** Fixed per-packet payload cap. Stand-in for MTU discovery (1500 - IP/UDP/uTP). */
        const val DEFAULT_MAX_PAYLOAD = 1400
    }
}

/**
 * `compare_less_wrap(lhs, rhs, ACK_MASK)` from `utp_stream.cpp`: true iff [lhs] is
 * "less than" [rhs] in 16-bit sequence space, treating the shorter walking direction
 * as the ordering. The exact C++ logic:
 * ```
 * dist_down = (lhs - rhs) & mask;
 * dist_up   = (rhs - lhs) & mask;
 * return dist_up < dist_down;
 * ```
 */
fun seqLessWrap(lhs: Int, rhs: Int, mask: Int = UtpStream.ACK_MASK): Boolean {
    val distDown = (lhs - rhs) and mask
    val distUp = (rhs - lhs) and mask
    return distUp < distDown
}

/**
 * The default injected microsecond clock for [UtpStream]: a plain monotonically
 * increasing counter, so the core stays clockless and deterministic. Each call
 * returns the previous value plus one. Not a real wall/monotonic clock — peers that
 * compute one-way delay from our timestamps will see meaningless deltas, which is
 * fine because this core does not run delay-based congestion control anyway.
 */
class MicrosCounter(start: Long = 0L) : () -> Long {
    private var n = start
    override fun invoke(): Long = n++
}

/** Raised by [UtpStream.readExactly] when the peer closes the stream early. */
class UtpEofException(message: String) : Exception(message)

/** Raised when the peer sends ST_RESET. */
class UtpResetException(message: String) : Exception(message)
