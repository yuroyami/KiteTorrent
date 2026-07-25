package io.github.yuroyami.kitetorrent.session.net

import io.github.yuroyami.kitetorrent.session.peer.ByteStream
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * uTP (Micro Transport Protocol, BEP-29) connection over a single [UdpSocket] to one
 * remote `(host, port)`. This is a faithful **core** port of libtorrent's
 * `utp_socket_impl` (`src/utp_stream.cpp`), with enough state machine to actually move
 * bytes. It implements [ByteStream] so a `PeerConnection` runs over uTP with no
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
 * ### What is implemented (and where it is still SIMPLIFIED vs. libtorrent):
 * - **LEDBAT / delay-based congestion control.** [growCwndLocked] runs `do_ledbat()`
 *   against a 100 ms `target_delay`, opening/closing `cwnd` from one-way delay samples
 *   (`timestamp_difference_microseconds`), gated by the `cwnd_saturated` check so an
 *   unsaturated link doesn't inflate the window. Base delay is a time-bucketed rolling
 *   minimum ([updateDelayLocked]) so it tracks slow clock drift.
 * - **Retransmission / RTO timer + fast-resend.** [tick] resends the unacked window on
 *   RTO (go-back-N, exponential backoff), and SACK / duplicate-ACK loss triggers an early
 *   fast-resend. A lost SYN or FIN is also retained in the out-buffer and resent.
 * - **Selective ACK (SACK).** We both emit ([buildSackLocked]) and act on ([applySackLocked])
 *   the SACK extension.
 * - **Path-MTU discovery is conservative (shrink-only).** The MSS starts at a 1500-byte
 *   Ethernet estimate and *shrinks* toward [MIN_MSS] when sustained RTOs hint our packets
 *   are too large; we never actively probe upward (no ICMP feedback). Current value: [currentMss].
 *   libtorrent does a full `[mtu_floor, mtu_ceiling]` search with MTU probes.
 * - **Nagle coalescing** holds a sub-MSS write tail back to merge with the next write
 *   (disable via the `nagleEnabled` ctor flag); libtorrent's `m_nagle_packet` is finer-grained.
 * - **`wnd_size` flow control** is honoured: bytes in flight never exceed
 *   `min(cwnd, peerWindow)` (see [awaitWindow]).
 *
 * ### Driving the read side
 * uTP is connectionless at the socket layer: one [UdpSocket] multiplexes many
 * streams by `(connection_id)`. This class does **not** own the socket's receive
 * loop; the owner pumps inbound datagrams in by calling [onDatagram] with the raw
 * bytes of each packet addressed to this stream. [UtpSocketManager] is that owner in
 * the engine. It runs the receive loop and demultiplexes by connection id, the role
 * `utp_socket_manager` plays upstream.
 *
 * @param socket the shared UDP socket used to *send*; the owner drives receive.
 * @param remoteHost remote peer host.
 * @param remotePort remote peer port.
 * @param scope coroutine scope the background ack/feed work runs in.
 * @param nowMicros injected microsecond clock. The core is clockless, so this
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
    /** RTO floor (`min_timeout`), microseconds. Tests shorten it to force fast resends. */
    private val minTimeoutMicros: Long = DEFAULT_MIN_TIMEOUT_MICROS,
    /** Run an internal retransmission ticker. Tests that drive [tick] by hand pass false. */
    private val selfTick: Boolean = true,
    /** Internal ticker cadence (ms) when [selfTick] is on. */
    private val tickIntervalMs: Long = DEFAULT_TICK_INTERVAL_MS,
    /**
     * Coalesce sub-MSS writes (`m_nagle`). On by default, matching libtorrent's
     * `default_nagle`; pass false for latency-sensitive callers that want each write
     * flushed immediately.
     */
    private val nagleEnabled: Boolean = true,
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

    /**
     * The peer has sent a FIN (`m_in_eof`) and the sequence number at/after which no further
     * payload is valid (`m_in_eof_seq_nr`): for a FIN with no payload that's the FIN's own
     * seq_nr, otherwise the next one. We ignore any DATA whose seq_nr is past this. The
     * connection is draining. (utp_stream.cpp incoming_payload / incoming_packet eof guard.)
     */
    private var inEof = false
    private var inEofSeqNr = 0

    private val lock = Mutex()

    // ---- congestion window (LEDBAT delay control over an AIMD substrate) ---------
    /** Our advertised receive-buffer size (the `wnd_size` we send peers); fixed. */
    private val recvWindowBytes = DEFAULT_SEND_WINDOW
    private var bytesInFlight = 0

    // ---- path-MTU discovery (utp_socket_impl m_mtu / m_mtu_floor / m_mtu_ceiling) ----
    /**
     * Current maximum payload (MSS) per ST_DATA packet: libtorrent's
     * `m_mtu - header_size`. We start optimistic at [DEFAULT_MAX_PAYLOAD], a 1500-byte
     * Ethernet MTU minus IP/UDP/uTP overhead. We get no real ICMP "fragmentation
     * needed" feedback, so we *shrink toward a floor* whenever repeated loss or timeouts
     * suggest our packets are too big for the path. We never grow it back, because
     * nothing probes upward. That is a simple, safe, bounded approximation of
     * libtorrent's `[mtu_floor, mtu_ceiling]` search. [currentMss] exposes the value.
     */
    private var mss = DEFAULT_MAX_PAYLOAD
    /**
     * Consecutive RTO rounds attributed to (possible) over-MTU packets; each step past the
     * first one shrinks [mss] by [MSS_SHRINK_STEP] toward [MIN_MSS]. Reset on forward progress.
     */
    private var mtuShrinkRounds = 0

    /** Nagle hold-back buffer: a sub-MSS write tail awaiting coalescing with the next write. */
    private var naglePending: ByteArray? = null

    /**
     * The peer's last-advertised receive window (`wnd_size`): flow control. We never keep
     * more bytes in flight than `min(cwnd, peerWindow)`, so we never overrun a slow receiver.
     * Seeded optimistically until the first packet arrives with the peer's real value.
     */
    private var peerWindow = DEFAULT_SEND_WINDOW

    /**
     * Congestion window in bytes (`m_cwnd`). A timeout collapses it to one MSS and a
     * fast-resend halves it, TCP-style. The increase is LEDBAT's, not TCP's:
     * [growCwndLocked] scales each step by how far the measured queuing delay sits from
     * the 100 ms [TARGET_DELAY_MICROS], so the window shrinks once the path starts
     * queuing and uTP yields to other traffic on the same link.
     */
    private var cwnd = INIT_CWND
    /** Slow-start threshold: the cwnd at which we switch from exponential to linear growth. */
    private var ssthresh = DEFAULT_SEND_WINDOW
    /** True while in slow-start (exponential growth); false in congestion-avoidance. */
    private var slowStart = true

    // ---- LEDBAT delay-based control (the uplink-friendly part) ---------------
    /**
     * The one-way-delay measurement we owe the peer: `our_recv_time − their_send_timestamp`
     * for the last data packet we received (uint32, network units). We echo it in the
     * `timestamp_difference` field of every packet we send, so the peer can run LEDBAT on
     * the delay *we* impose on it. (utp: `reply_micro`.)
     */
    private var lastTimestampDiffToEcho = 0L

    /** Rolling-minimum base delay (= propagation + the constant clock offset, which it cancels). */
    private var baseDelay = 0L
    private var haveBase = false
    /**
     * Time-bucketed rolling-min history for [baseDelay]: each entry is the minimum delay seen
     * within one ~1-minute bucket; the base is the min across all live buckets. Bucketing by
     * time and aging out old buckets lets the base track slow clock drift. (libtorrent
     * `delay_hist` / `m_delay_base_history`.)
     */
    private val baseBuckets = ArrayDeque<Long>()
    /** Wall time (from [nowMicros]) the current (newest) bucket began; 0 until the first sample. */
    private var baseBucketStartMicros = 0L
    /** Latest queuing delay = peer-reported delay − [baseDelay], clamped ≥ 0 (microseconds). */
    private var currentQueuingDelay = 0L
    private var haveDelaySample = false

    /** Woken whenever window space frees up (an ack advanced [ackedSeqNr]). */
    private val windowSignal = Channel<Unit>(Channel.CONFLATED)

    // ---- retransmission buffer + RTT/RTO (utp_socket_impl m_outbuf + m_rtt) ---
    /**
     * One sent, not-yet-acked packet retained for retransmission: the analogue of an
     * entry in libtorrent's `m_outbuf`. We keep the type + payload (not the encoded
     * bytes) and rebuild the header on every (re)send so the ack_nr / timestamp / window
     * are always current, exactly like `resend_packet()` re-stamps before resending.
     */
    private class OutPacket(
        val seqNr: Int,
        val type: UtpType,
        val payload: ByteArray,
        var sendTimeMicros: Long,
        var transmissions: Int,
        /** The peer has selectively-acked this packet (via SACK), so we never resend it. */
        var sacked: Boolean = false,
    )

    /** In-flight packets by seq_nr; insertion order == ascending send order (oldest first). */
    private val outBuf = LinkedHashMap<Int, OutPacket>()

    /** Smoothed RTT and its mean deviation, microseconds (Jacobson/Karels; `m_rtt`). */
    private var rttMeanMicros = 0L
    private var rttVarMicros = 0L
    private var rttSamples = 0

    /** Consecutive RTO firings with no forward progress. This drives exponential backoff. */
    private var numTimeouts = 0

    /** Duplicate (non-advancing) acks seen since the last forward progress; drives fast-resend. */
    private var dupAckCount = 0

    /** Guards single-start of the internal retransmission ticker (set under [lock]). */
    private var tickerStarted = false

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
        val bytes: ByteArray
        lock.withLock {
            check(state == State.NONE) { "connect() already called (state=$state)" }
            // send_syn(): m_seq_nr = random; here we start at 1 for determinism, the
            // wire value is irrelevant as long as it's consistent. acked = seq-1.
            seqNr = 1
            ackedSeqNr = (seqNr - 1) and ACK_MASK
            ackNr = 0
            state = State.SYN_SENT
            // Retain the SYN in the out-buffer so [tick] retransmits it if it's lost
            // (a dropped SYN must not hang connect forever). It carries connection_id =
            // recv_id (intentional, see send_syn()), rebuilt in [buildPacketLocked].
            val syn = OutPacket(seqNr, UtpType.ST_SYN, EMPTY_BYTES, 0, 0)
            outBuf[seqNr] = syn
            bytes = buildPacketLocked(syn)
            seqNr = (seqNr + 1) and ACK_MASK   // SYN consumes a seq_nr
        }
        sendRaw(bytes)
        startTicker()
        connected.await()
    }

    // =========================================================================
    //  ByteStream: write
    // =========================================================================

    /**
     * Packetise [bytes] into one or more ST_DATA packets and send them, blocking on the
     * congestion+flow window so bytes-in-flight never exceed `min(cwnd, peerWindow)`.
     * Faithful to `send_pkt()` chunking (`payload_size = min(write_buffer, mtu - header)`)
     * with the discovered [mss], LEDBAT-driven window, and Nagle coalescing.
     */
    override suspend fun write(bytes: ByteArray) {
        ensureUsable()
        connected.await()
        // Nagle: we hold a sub-MSS tail back and coalesce it with the *next* write rather
        // than sending a tiny packet, unless there's nothing in flight (so a lone small
        // write isn't stalled forever). This matches the intent of send_pkt()'s
        // m_nagle_packet. We keep it simple by buffering only across write() calls.
        val toSend = if (nagleEnabled) {
            val pending = takeNaglePending()
            if (pending != null) {
                val merged = ByteArray(pending.size + bytes.size)
                pending.copyInto(merged, 0)
                bytes.copyInto(merged, pending.size)
                merged
            } else bytes
        } else bytes
        writeFrames(toSend)
    }

    /**
     * Packetise + send [bytes] in MSS chunks; with Nagle on, hold a sub-MSS tail back unless
     * [force] (used by close, which must flush everything before the FIN).
     */
    private suspend fun writeFrames(bytes: ByteArray, force: Boolean = false) {
        var off = 0
        while (off < bytes.size) {
            val remaining = bytes.size - off
            // Nagle: if the tail is smaller than a full MSS and we already have unacked data
            // in flight, stash it and return. It goes out coalesced on the next write or close.
            if (!force && nagleEnabled && remaining < currentMssSync() &&
                lock.withLock { bytesInFlight > 0 }
            ) {
                stashNaglePending(bytes.copyOfRange(off, bytes.size))
                return
            }
            val chunk = minOf(currentMssSync(), remaining)
            awaitWindow(chunk)
            val payload = bytes.copyOfRange(off, off + chunk)
            val pkt = lock.withLock {
                ensureUsableLocked()
                val s = seqNr
                seqNr = (seqNr + 1) and ACK_MASK
                bytesInFlight += chunk
                val p = OutPacket(s, UtpType.ST_DATA, payload, 0, 0)
                outBuf[s] = p          // retained for retransmission until acked
                buildPacketLocked(p)
            }
            sendRaw(pkt)
            off += chunk
        }
    }

    /** Suspend until [n] bytes of in-flight headroom exist in the fixed window. */
    private suspend fun awaitWindow(n: Int) {
        while (true) {
            val ok = lock.withLock {
                ensureUsableLocked()
                // honour the congestion window AND the peer's advertised receive window
                // (flow control); the bytesInFlight==0 escape doubles as a zero-window probe
                bytesInFlight == 0 || bytesInFlight + n <= minOf(cwnd, peerWindow)
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
            // need more bytes: check terminal conditions then wait for a signal
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
        var sackToSend: ByteArray? = null
        var fastResend: ByteArray? = null
        var newlyConnected = false
        var resetErr: Throwable? = null

        lock.withLock {
            if (state == State.CLOSED) return
            val now = nowMicros()
            // measure the one-way delay of this inbound data packet, to echo back so the
            // peer can run LEDBAT on the delay we impose (reply_micro).
            if (h.type == UtpType.ST_DATA || h.type == UtpType.ST_SYN) {
                lastTimestampDiffToEcho = (now - h.timestampMicros) and 0xFFFFFFFFL
            }
            // feed the peer's measurement of OUR delay into LEDBAT's base/queuing tracker
            if (h.timestampDiffMicros != 0L) updateDelayLocked(h.timestampDiffMicros)
            // track the peer's advertised receive window for flow control
            peerWindow = h.wndSize.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            // --- cumulative ack: free our in-flight packets up to h.ackNr --------
            var advanced = false
            if (state != State.NONE && seqLessWrap(ackedSeqNr, h.ackNr)) {
                var a = (ackedSeqNr + 1) and ACK_MASK
                val stop = (h.ackNr + 1) and ACK_MASK
                var freed = 0
                while (a != stop) {
                    outBuf.remove(a)?.let { p ->
                        // SACKed bytes already left the in-flight count; don't subtract twice
                        if (!p.sacked) bytesInFlight -= p.payload.size
                        freed += p.payload.size
                        // Karn's algorithm: only sample RTT from singly-transmitted packets
                        if (p.transmissions == 1) sampleRtt(now - p.sendTimeMicros)
                    }
                    a = (a + 1) and ACK_MASK
                }
                ackedSeqNr = h.ackNr
                numTimeouts = 0   // forward progress clears the RTO backoff
                mtuShrinkRounds = 0   // ...and the PMTU-shrink streak
                advanced = true
                growCwndLocked(freed)
            }

            // --- fast retransmit: SACK + duplicate-ACK loss detection ------------
            // A SACK tells us which packets past the hole the peer already has; mark
            // them so we never resend them, and count them as loss evidence for the
            // hole at ackedSeqNr+1. A run of non-advancing acks is the classic dup-ACK
            // signal. Either reaching the threshold resends the hole now, a full RTO
            // ahead of the timer (`experienced_loss` / `dup_ack_limit`).
            if (state == State.CONNECTED && outBuf.isNotEmpty()) {
                val sackedAbove = pkt.sack?.let { applySackLocked(h.ackNr, it) } ?: 0
                if (advanced) dupAckCount = 0
                else if (h.type == UtpType.ST_STATE || h.type == UtpType.ST_DATA) dupAckCount++
                if (dupAckCount >= DUP_ACK_LIMIT || sackedAbove >= DUP_ACK_LIMIT) {
                    pickFastResendLocked()?.let {
                        onFastResendLossLocked()   // halve cwnd (fast recovery), stay out of slow-start
                        fastResend = buildPacketLocked(it)
                        dupAckCount = 0
                    }
                }
            }

            when (h.type) {
                UtpType.ST_RESET -> {
                    // Validate the RESET: its ack_nr must not be *ahead* of the highest seq we
                    // have actually sent, or it can't be a legitimate reset of our connection
                    // (utp_stream.cpp: "invalid RESET packet, ack_nr ... (ignored)"). In FIN_SENT
                    // the FIN didn't consume a seq, so our last-sent seq is seqNr; otherwise seq-1.
                    val lastSent = if (state == State.FIN_SENT) seqNr else (seqNr - 1) and ACK_MASK
                    if (state != State.NONE && seqLessWrap(lastSent, h.ackNr)) {
                        // ack_nr ahead of anything we sent → bogus RESET, ignore it.
                    } else {
                        resetErr = UtpResetException("uTP connection reset by peer")
                        state = State.CLOSED
                    }
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
                        // Once the peer has FINed, ignore any payload past the EOF seq. The
                        // connection is draining. We still accept a STATE/FIN at exactly the
                        // EOF seq (it carries no new payload). (incoming_packet eof guard.)
                        val stateOrFin = h.type == UtpType.ST_STATE || h.type == UtpType.ST_FIN
                        val pastEof = inEof &&
                            seqLessWrap(inEofSeqNr, h.seqNr) &&
                            !(inEofSeqNr == h.seqNr && stateOrFin)
                        if (h.type == UtpType.ST_DATA && !pastEof) {
                            consumeData(h.seqNr, pkt.payload)
                            sendAck = true
                        }
                        if (h.type == UtpType.ST_FIN) {
                            // FIN in order advances ack_nr; either way we ack it.
                            if (h.seqNr == ((ackNr + 1) and ACK_MASK) || h.seqNr == ackNr) {
                                ackNr = h.seqNr
                            }
                            if (!inEof) {
                                inEof = true
                                // FIN with payload: EOF is the next seq; otherwise the FIN's own.
                                inEofSeqNr = if (pkt.payload.isNotEmpty())
                                    (h.seqNr + 1) and ACK_MASK else h.seqNr
                            }
                            remoteFinished = true
                            sendAck = true
                        }
                    }
                    ackToSend = ackNr
                }
            }
            // attach a SACK to our ack whenever we're sitting on a reorder hole, so the
            // peer can fast-resend the gap instead of waiting for its RTO.
            if (sendAck) sackToSend = buildSackLocked()
        }

        // ---- post-lock effects --------------------------------------------------
        resetErr?.let {
            errored = it
            if (!connected.isCompleted) connected.completeExceptionally(it)
            wakeAll()
            return
        }
        if (newlyConnected && !connected.isCompleted) connected.complete(Unit)
        if (newlyConnected) startTicker()   // passive opens need the RTO ticker too
        fastResend?.let { runCatching { sendRaw(it) } }
        if (sendAck) sendStateAck(ackToSend, sackToSend)
        wakeAll()
        // An ack that drained the last in-flight bytes lets a Nagle-held tail go out at once,
        // rather than waiting for the next ticker round.
        flushNaglePending()
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
     * `peer/ByteStream.kt`), so this is a plain method. Callers invoke it on the
     * concrete [UtpStream] when tearing the transport down.
     */
    fun close() {
        socketClose()
    }

    private fun socketClose() {
        scope.launch {
            // flush any Nagle-held tail before the FIN so no buffered bytes are lost.
            if (nagleEnabled) {
                val pending = takeNaglePending()
                if (pending != null && lock.withLock { state == State.CONNECTED }) {
                    runCatching { writeFrames(pending, force = true) }
                }
            }
            val finBytes: ByteArray? = lock.withLock {
                when (state) {
                    State.CLOSED, State.FIN_SENT -> return@launch  // already closing/closed
                    State.CONNECTED -> {
                        // Graceful close (send_fin → state fin_sent): the FIN carries the
                        // current seq_nr WITHOUT incrementing it (a FIN doesn't consume a
                        // sequence), and goes into the out-buffer so the RTO ticker resends
                        // it if it's lost. A dropped FIN must not leave the peer waiting.
                        val fin = OutPacket(seqNr, UtpType.ST_FIN, EMPTY_BYTES, 0, 0)
                        outBuf[seqNr] = fin
                        state = State.FIN_SENT
                        buildPacketLocked(fin)
                    }
                    // SYN_SENT/NONE: nothing acknowledged yet, just tear down locally.
                    else -> { state = State.CLOSED; null }
                }
            }
            finBytes?.let {
                runCatching { sendRaw(it) }
                startTicker()   // ensure the ticker is live to resend a lost FIN
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

    /**
     * Send a pure ST_STATE ack carrying our current [ackNr] (the deferred-ack path),
     * optionally with a Selective-ACK [sack] bitmask describing the packets we've
     * buffered past the hole.
     */
    private suspend fun sendStateAck(ack: Int, sack: ByteArray? = null) {
        // seq_nr is ignored for ST_STATE, but echo it; carry our delay measurement + window
        val (seq, diff, wnd) = lock.withLock {
            Triple(seqNr, lastTimestampDiffToEcho, currentRecvWindow())
        }
        val h = UtpHeader(
            type = UtpType.ST_STATE,
            connectionId = sendId,
            timestampMicros = nowMicros() and 0xFFFFFFFFL,
            timestampDiffMicros = diff,
            wndSize = wnd,
            seqNr = seq,
            ackNr = ack,
        )
        runCatching { sendRaw(buildUtpPacket(h, sack = sack)) }
    }

    private suspend fun sendRaw(bytes: ByteArray) = socket.send(bytes, remoteHost, remotePort)

    // =========================================================================
    //  Retransmission: RTT estimation + RTO timer (m_rtt, packet_timeout, resend)
    // =========================================================================

    /**
     * One retransmission tick: the analogue of `utp_socket_impl::tick(now)`. If the
     * oldest unacked packet has gone unacknowledged for longer than [packetTimeoutMicros],
     * an RTO has fired. We then retransmit every in-flight packet (go-back-N) and deepen
     * the backoff. After [MAX_TIMEOUTS] fruitless rounds we fail the socket, so a dead
     * peer cannot block a reader forever. Built under [lock]; the resends go out after.
     */
    suspend fun tick() {
        // A Nagle-held sub-MSS tail with no following write to merge with must not be stranded:
        // flush it once outstanding data has drained, before the RTO bookkeeping below (which
        // early-returns on an empty out-buffer and would otherwise never look at it).
        flushNaglePending()
        val resend = ArrayList<ByteArray>()
        var giveUp: Throwable? = null
        lock.withLock {
            if (state == State.CLOSED || state == State.NONE) return
            if (outBuf.isEmpty()) { numTimeouts = 0; return }
            val oldest = outBuf.values.first()           // insertion order = oldest seq
            if (nowMicros() - oldest.sendTimeMicros < packetTimeoutMicros()) return
            numTimeouts++
            if (numTimeouts > MAX_TIMEOUTS) {
                giveUp = UtpResetException("uTP timed out (no ack after $numTimeouts retransmits)")
                state = State.CLOSED
            } else {
                onTimeoutLossLocked()   // collapse cwnd to one MSS, re-enter slow-start
                // sustained RTOs hint the path can't carry our packet size: shrink the MSS
                // toward the floor (poor-man's PMTU discovery without ICMP feedback).
                shrinkMssOnLossLocked()
                // go-back-N, but never resend a packet the peer already SACKed
                for (p in outBuf.values) if (!p.sacked) resend.add(buildPacketLocked(p))
            }
        }
        giveUp?.let {
            errored = it
            if (!connected.isCompleted) connected.completeExceptionally(it)
            wakeAll()
            return
        }
        for (b in resend) runCatching { sendRaw(b) }
    }

    /** Launch the internal RTO ticker once (no-op when [selfTick] is off / already running). */
    private suspend fun startTicker() {
        if (!selfTick) return
        val begin = lock.withLock { if (tickerStarted) false else { tickerStarted = true; true } }
        if (!begin) return
        scope.launch {
            try {
                while (true) {
                    delay(tickIntervalMs)
                    if (lock.withLock { state == State.CLOSED }) break
                    tick()
                }
            } catch (_: Throwable) {
                // scope cancelled. This is a normal shutdown.
            }
        }
    }

    /**
     * Stamp [p] with the current send time + bump its transmission count, then build its
     * wire bytes with an up-to-date header (ack_nr / window / timestamp), the way
     * `resend_packet()` re-stamps before every (re)send. Must hold [lock].
     */
    private fun buildPacketLocked(p: OutPacket): ByteArray {
        p.sendTimeMicros = nowMicros()
        p.transmissions++
        val h = UtpHeader(
            type = p.type,
            connectionId = if (p.type == UtpType.ST_SYN) recvId else sendId,
            timestampMicros = nowMicros() and 0xFFFFFFFFL,
            // SYN can't echo a delay yet (no packet received); others carry our measurement
            timestampDiffMicros = if (p.type == UtpType.ST_SYN) 0 else lastTimestampDiffToEcho,
            wndSize = if (p.type == UtpType.ST_SYN) recvWindowBytes.toLong() else currentRecvWindow(),
            seqNr = p.seqNr,
            ackNr = if (p.type == UtpType.ST_SYN) 0 else ackNr,
        )
        return buildUtpPacket(h, p.payload)
    }

    /**
     * `packet_timeout()`: `max(min_timeout, srtt + 4·rttvar)`, doubled per consecutive
     * timeout (exponential backoff), capped. Before any RTT sample exists it's just the
     * floor [minTimeoutMicros] (libtorrent seeds the timer from `connect_timeout`).
     */
    private fun packetTimeoutMicros(): Long {
        val base =
            if (rttSamples == 0) minTimeoutMicros
            else maxOf(minTimeoutMicros, rttMeanMicros + 4 * rttVarMicros)
        val backed = base shl minOf(numTimeouts, MAX_BACKOFF_SHIFT)
        return minOf(backed, MAX_TIMEOUT_MICROS)
    }

    /** Fold one RTT [micros] sample into the smoothed estimate (Jacobson/Karels `m_rtt`). */
    private fun sampleRtt(micros: Long) {
        if (micros < 0) return
        if (rttSamples == 0) {
            rttMeanMicros = micros
            rttVarMicros = micros / 2
        } else {
            val delta = micros - rttMeanMicros
            rttMeanMicros += delta / 8
            rttVarMicros += (kotlin.math.abs(delta) - rttVarMicros) / 4
        }
        rttSamples++
    }

    /**
     * Build the SACK bitmask for our current reorder buffer, or null if there's no hole.
     * Bit `i` represents seq `ack_nr + 2 + i` (the bit for `ack_nr+1` is implicit, since
     * that is the packet we're missing), LSB-first within each byte, length padded to a
     * multiple of 4 bytes. Exactly the layout libtorrent reads in `incoming_packet`. Hold
     * [lock].
     */
    private fun buildSackLocked(): ByteArray? {
        if (reorder.isEmpty()) return null
        val base = (ackNr + 2) and ACK_MASK
        var maxBit = -1
        for (seq in reorder.keys) {
            val bit = (seq - base) and ACK_MASK
            if (bit in 0 until SACK_MAX_BITS && bit > maxBit) maxBit = bit
        }
        if (maxBit < 0) return null
        val nbytes = ((maxBit / 8) + 1 + 3) / 4 * 4   // pad up to a multiple of 4 bytes
        val mask = ByteArray(nbytes)
        for (seq in reorder.keys) {
            val bit = (seq - base) and ACK_MASK
            if (bit in 0 until nbytes * 8) {
                mask[bit ushr 3] = (mask[bit ushr 3].toInt() or (1 shl (bit and 7))).toByte()
            }
        }
        return mask
    }

    /**
     * Apply a received SACK: mark every in-flight packet it acknowledges as [OutPacket.sacked]
     * (so it's never resent) and return how many were acked. That count is the loss evidence
     * for the hole at `baseAck+1`. Bit `i` of the mask refers to seq `baseAck + 2 + i`.
     * Hold [lock].
     */
    private fun applySackLocked(baseAck: Int, sack: ByteArray): Int {
        var sackedAbove = 0
        val bits = minOf(sack.size * 8, SACK_MAX_BITS)
        for (i in 0 until bits) {
            if ((sack[i ushr 3].toInt() and (1 shl (i and 7))) != 0) {
                val seq = (baseAck + 2 + i) and ACK_MASK
                outBuf[seq]?.let {
                    // newly SACKed: it's left the network, so free its in-flight bytes
                    if (!it.sacked) { it.sacked = true; bytesInFlight -= it.payload.size }
                    sackedAbove++
                }
            }
        }
        return sackedAbove
    }

    /** The oldest in-flight packet the peer has *not* SACKed: the gap to fast-resend. Hold [lock]. */
    private fun pickFastResendLocked(): OutPacket? = outBuf.values.firstOrNull { !it.sacked }

    /**
     * Grow `cwnd` for [acked] freshly-acknowledged bytes: exponential in slow-start
     * (`cwnd += acked`), ~one MSS per RTT in congestion-avoidance
     * (`cwnd += MSS·acked/cwnd`). Crosses into congestion-avoidance at [ssthresh],
     * capped at [MAX_CWND]. Hold [lock].
     */
    private fun growCwndLocked(acked: Int, saturatedOverride: Boolean? = null) {
        if (acked <= 0) return
        // do_ledbat: only open the window when the upper layer is actually pushing enough
        // data to be cwnd-limited; otherwise an idle/slow writer would inflate cwnd for free.
        // (utp_stream.cpp:3245: cwnd_saturated = bytes_in_flight + acked + mss > cwnd)
        val cwndSaturated = saturatedOverride ?: (bytesInFlight + acked + mss > cwnd)
        // leave slow-start at ssthresh, or as soon as queuing delay crosses the target.
        // On a delay-driven exit, ssthresh = cwnd/2 (do_ledbat: m_ssthres = (cwnd>>16)/2).
        if (slowStart && haveDelaySample && currentQueuingDelay > TARGET_DELAY_MICROS) {
            slowStart = false
            ssthresh = maxOf(cwnd / 2, 2 * mss)
        }
        if (!cwndSaturated) {
            // not link-limited: hold the window, but still clamp to valid bounds below.
        } else if (slowStart) {
            cwnd += acked
            if (cwnd >= ssthresh) slowStart = false
        } else {
            // LEDBAT: scale the window by how far queuing delay sits from the 100 ms target.
            // off_target > 0 (below target) grows the window; < 0 (above target) shrinks it.
            // That is how uTP backs off and yields to other traffic. ~1 MSS/RTT bound.
            val offTarget = (TARGET_DELAY_MICROS - currentQueuingDelay).toDouble()
            val windowFactor = acked.toDouble() / maxOf(cwnd, mss)
            val gain = mss.toDouble() * windowFactor * (offTarget / TARGET_DELAY_MICROS)
            cwnd = (cwnd + gain).toInt()
        }
        if (cwnd < mss) cwnd = mss   // never below one MSS
        if (cwnd > MAX_CWND) cwnd = MAX_CWND
    }

    /**
     * Fold one peer-reported delay [sample] (the `timestamp_difference` they echoed for our
     * packets) into the LEDBAT estimate. We track a rolling-minimum [baseDelay], which cancels
     * the constant offset between the two unsynchronised clocks. The current queuing delay is
     * then `sample − baseDelay`. Hold [lock].
     */
    private fun updateDelayLocked(sample: Long) {
        // Time-bucketed rolling minimum (libtorrent's delay_hist): the base delay is the
        // minimum one-way delay observed across a sliding set of ~1-minute buckets. Bucketing
        // by *time* (not sample count) keeps the window's real duration constant regardless of
        // packet rate, and rotating the buckets lets the base *rise* again to track slow clock
        // drift between the two unsynchronised endpoints. A fixed all-time minimum would stay
        // pinned to one early low sample on a drifting clock.
        val now = nowMicros()
        if (baseBucketStartMicros == 0L) baseBucketStartMicros = now
        // advance buckets for any elapsed minute(s), dropping the oldest so the base can rise
        while (now - baseBucketStartMicros >= BASE_DELAY_BUCKET_MICROS) {
            if (baseBuckets.size >= BASE_DELAY_BUCKETS) baseBuckets.removeFirst()
            baseBuckets.addLast(Long.MAX_VALUE)
            baseBucketStartMicros += BASE_DELAY_BUCKET_MICROS
        }
        if (baseBuckets.isEmpty()) baseBuckets.addLast(Long.MAX_VALUE)
        // fold this sample into the current (newest) bucket's minimum
        val last = baseBuckets.removeLast()
        baseBuckets.addLast(minOf(last, sample))
        // base delay = the minimum over all live buckets
        var minOver = Long.MAX_VALUE
        for (b in baseBuckets) if (b < minOver) minOver = b
        baseDelay = minOver
        haveBase = true
        currentQueuingDelay = (sample - baseDelay).coerceAtLeast(0)
        haveDelaySample = true
    }

    /** Test seam: drive one LEDBAT delay sample + ack through the real controller. */
    internal suspend fun feedDelayAndAckForTest(sampleMicros: Long, ackedBytes: Int) = lock.withLock {
        updateDelayLocked(sampleMicros)
        // The controller test drives the delay response directly without filling the window,
        // so force the cwnd_saturated gate open, otherwise the gate (correctly) suppresses
        // growth for an unsaturated link and the test cannot observe the delay behaviour.
        growCwndLocked(ackedBytes, saturatedOverride = true)
    }

    /** Test seam: drop straight into congestion-avoidance (skip slow-start). */
    internal suspend fun forceCongestionAvoidanceForTest() = lock.withLock { slowStart = false }

    /** Test seam: set the peer's advertised receive window directly. */
    internal suspend fun feedPeerWindowForTest(wnd: Long) =
        lock.withLock { peerWindow = wnd.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() }

    /** Test seam: the effective send window = min(cwnd, peer's receive window). */
    internal suspend fun effectiveSendWindowForTest(): Int = lock.withLock { minOf(cwnd, peerWindow) }

    /** Timeout loss (`experienced_loss` via RTO): halve ssthresh, collapse to one MSS, re-slow-start. */
    private fun onTimeoutLossLocked() {
        ssthresh = maxOf(cwnd / 2, 2 * mss)
        cwnd = mss
        slowStart = true
    }

    /** Fast-resend loss (3 dup-ACKs / SACK): multiplicative decrease, stay in congestion-avoidance. */
    private fun onFastResendLossLocked() {
        ssthresh = maxOf(cwnd / 2, 2 * mss)
        cwnd = ssthresh
        slowStart = false
    }

    /** Current congestion window in bytes, for tests and observability. */
    suspend fun congestionWindowBytes(): Int = lock.withLock { cwnd }

    /** The current discovered maximum payload per packet (MSS), for tests and observability. */
    suspend fun currentMss(): Int = lock.withLock { mss }

    /** Non-suspending MSS read for the [write] hot path; the value only ever shrinks under [lock]. */
    private fun currentMssSync(): Int = mss

    /**
     * Shrink the MSS one step toward the [MIN_MSS] floor. [tick] calls this when repeated RTOs
     * suggest our packets are too large for the path, standing in for the ICMP "fragmentation
     * needed" signal we never see. The first timeout round counts as ordinary loss; only
     * sustained loss shrinks the MSS. Bounded: never below the floor, never grows back.
     * Hold [lock].
     */
    private fun shrinkMssOnLossLocked() {
        mtuShrinkRounds++
        if (mtuShrinkRounds < MTU_SHRINK_AFTER) return
        if (mss > MIN_MSS) {
            mss = maxOf(MIN_MSS, mss - MSS_SHRINK_STEP)
            if (cwnd < mss) cwnd = mss
        }
    }

    /** Take and clear the Nagle hold-back buffer, if any. Locks internally. */
    private suspend fun takeNaglePending(): ByteArray? = lock.withLock {
        val p = naglePending
        naglePending = null
        p
    }

    /** Stash a sub-MSS tail for coalescing with the next write. Locks internally. */
    private suspend fun stashNaglePending(bytes: ByteArray) = lock.withLock {
        naglePending = if (naglePending == null) bytes else (naglePending!! + bytes)
    }

    /**
     * Flush any Nagle-held sub-MSS tail once the outstanding data it was waiting to coalesce
     * with has been acknowledged. Classic Nagle holds a small segment back only while there is
     * unacked data in flight; the moment the network drains we must send it, otherwise the
     * final tail of a transfer (which has no following [write] to merge with) would never go
     * out and a reader would wait forever for bytes that never arrive. [tick] drives this (so
     * it fires within one ticker cadence even on an idle connection), and the ack path calls
     * it opportunistically. No-op unless connected, Nagle is on, and the window has drained.
     */
    private suspend fun flushNaglePending() {
        if (!nagleEnabled) return
        val pending = lock.withLock {
            if (state != State.CONNECTED || naglePending == null || bytesInFlight > 0) return
            val p = naglePending
            naglePending = null
            p
        } ?: return
        runCatching { writeFrames(pending, force = true) }
    }

    /** Bytes of receive headroom we advertise (= window minus what's buffered). */
    private fun currentRecvWindow(): Long =
        (recvWindowBytes - inbound.size).coerceAtLeast(0).toLong()

    private fun wakeAll() {
        windowSignal.trySend(Unit)
        readSignal.trySend(Unit)
    }

    private fun ensureUsable() {
        errored?.let { throw it }
        // FIN_SENT means we've begun a graceful close: no new application data may be sent.
        if (state == State.CLOSED || state == State.FIN_SENT) throw UtpEofException("uTP stream is closed")
    }

    private fun ensureUsableLocked() {
        errored?.let { throw it }
        if (state == State.CLOSED || state == State.FIN_SENT) throw UtpEofException("uTP stream is closed")
    }

    companion object {
        /** ACK_MASK from utp_stream.cpp: seq/ack numbers are 16-bit wrap counters. */
        const val ACK_MASK = 0xFFFF

        /** Our advertised receive window / the cwnd ceiling (bytes). */
        const val DEFAULT_SEND_WINDOW = 64 * 1024

        /** Initial per-packet payload (MSS) before any PMTU shrinking (1500 - IP/UDP/uTP). */
        const val DEFAULT_MAX_PAYLOAD = 1400

        /**
         * Floor the discovered MSS can shrink to (`mtu_floor`): a safe payload for the
         * IPv6 minimum MTU (1280) minus IPv6+UDP+uTP overhead (40+8+20). Below this we
         * stop shrinking, since essentially every path carries at least this.
         */
        const val MIN_MSS = 1280 - 40 - 8 - 20   // = 1212

        /** Bytes the MSS drops per shrink step when repeated loss suggests over-MTU packets. */
        const val MSS_SHRINK_STEP = 64

        /** Consecutive RTO rounds before the first MSS shrink (treat the first round as plain loss). */
        const val MTU_SHRINK_AFTER = 2

        /** Initial congestion window: two MSS, the conventional slow-start start. */
        const val INIT_CWND = 2 * DEFAULT_MAX_PAYLOAD

        /** Cap on cwnd growth (bytes). The peer's advertised `wnd_size` caps it further (see [awaitWindow]). */
        const val MAX_CWND = DEFAULT_SEND_WINDOW

        /** LEDBAT target queuing delay: 100 ms, the BEP-29 / libtorrent `target_delay`. */
        const val TARGET_DELAY_MICROS = 100_000L

        /** Duration of one base-delay bucket (~1 minute), matching libtorrent's delay-base update. */
        const val BASE_DELAY_BUCKET_MICROS = 60_000_000L

        /** Number of live base-delay buckets kept (the rolling window the base-min spans). */
        const val BASE_DELAY_BUCKETS = 13

        /** RTO floor (`min_timeout`): 500 ms, matching libtorrent's `min_timeout`. */
        const val DEFAULT_MIN_TIMEOUT_MICROS = 500_000L

        /** Internal retransmission ticker cadence (ms). */
        const val DEFAULT_TICK_INTERVAL_MS = 100L

        /** Give up (fail the socket) after this many consecutive RTO retransmits. */
        const val MAX_TIMEOUTS = 6

        /** Dup-ACK / SACKed-above threshold that triggers a fast retransmit (`dup_ack_limit`). */
        const val DUP_ACK_LIMIT = 3

        /** Max bits a SACK bitmask can carry (32 bytes × 8), per libtorrent. */
        const val SACK_MAX_BITS = 256

        /** Cap the exponential backoff shift so the timeout can't run away. */
        const val MAX_BACKOFF_SHIFT = 5

        /** Absolute ceiling on a single RTO interval (60 s). */
        const val MAX_TIMEOUT_MICROS = 60_000_000L
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
 * A deterministic microsecond source for tests. Each call returns the previous value
 * plus one. It is not a wall clock and it is not a monotonic clock.
 *
 * Do not use this in production. [UtpStream] stamps every outgoing packet with this
 * value. The peer subtracts that stamp from its own clock and echoes the difference
 * back. [UtpStream] feeds the echoed difference into its queuing-delay estimate.
 * LEDBAT then scales the congestion window by the distance between that estimate and
 * [UtpStream.TARGET_DELAY_MICROS]. A counter makes the estimate meaningless, so the
 * window stops tracking real congestion.
 *
 * Production streams must inject [MonotonicMicros].
 */
class MicrosCounter(start: Long = 0L) : () -> Long {
    private var n = start
    override fun invoke(): Long = n++
}

/**
 * A real monotonic microsecond clock. Use this for every production [UtpStream].
 *
 * RTT samples and the RTO timer then measure elapsed time rather than a call count.
 * The timestamps peers read for one-way delay also become meaningful, which is what
 * LEDBAT needs to size the congestion window. Built on
 * [kotlin.time.TimeSource.Monotonic], which every Kotlin Multiplatform target
 * provides.
 */
class MonotonicMicros : () -> Long {
    private val start = kotlin.time.TimeSource.Monotonic.markNow()
    override fun invoke(): Long = start.elapsedNow().inWholeMicroseconds
}

/** Raised by [UtpStream.readExactly] when the peer closes the stream early. */
class UtpEofException(message: String) : Exception(message)

/** Raised when the peer sends ST_RESET. */
class UtpResetException(message: String) : Exception(message)
