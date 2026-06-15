package io.github.yuroyami.kitetorrent.session.engine

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.extensions.ExtensionHandshake
import io.github.yuroyami.kitetorrent.extensions.UtMetadata
import io.github.yuroyami.kitetorrent.generatePeerId
import io.github.yuroyami.kitetorrent.picker.PieceBlock
import io.github.yuroyami.kitetorrent.picker.PiecePicker
import io.github.yuroyami.kitetorrent.protocol.Handshake
import io.github.yuroyami.kitetorrent.protocol.PeerMessage
import io.github.yuroyami.kitetorrent.session.disk.DiskIo
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.TcpConnection
import io.github.yuroyami.kitetorrent.session.net.UtpSocketManager
import io.github.yuroyami.kitetorrent.session.net.bindUdp
import io.github.yuroyami.kitetorrent.session.net.connectTcp
import io.github.yuroyami.kitetorrent.session.peer.ByteStream
import io.github.yuroyami.kitetorrent.session.peer.PeerConnection
import io.github.yuroyami.kitetorrent.session.peer.asByteStream
import io.github.yuroyami.kitetorrent.session.tracker.AnnounceRequest
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.github.yuroyami.kitetorrent.session.tracker.PeerEndpoint
import io.github.yuroyami.kitetorrent.session.tracker.TrackerEvent
import io.github.yuroyami.kitetorrent.session.tracker.UdpTracker
import io.github.yuroyami.kitetorrent.settings.BoolSetting
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.stat.Stat
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The lifecycle state of a torrent — a small port of libtorrent's `torrent_status::state_t`. */
enum class TorrentState { CHECKING, DOWNLOADING, SEEDING, PAUSED }

/**
 * The download/upload engine for a single torrent — the live counterpart of libtorrent's
 * `torrent` (torrent.cpp). It wires the pure-core pieces on coroutines: announce → collect
 * peers → run a [PeerConnection] per peer → a [PiecePicker]-driven request pipeline →
 * write blocks to [DiskIo] → verify completed pieces → broadcast `have` → and serve blocks
 * back to peers we've unchoked.
 *
 * Tunables come from the ported [SettingsPack] (the libtorrent defaults), exactly the
 * knobs `torrent`/`peer_connection` read from `m_settings`:
 * `request_timeout`, `piece_timeout`, `request_queue_time`, `max_out_request_queue`,
 * `initial_picker_threshold`, `strict_end_game_mode`, `unchoke_interval`,
 * `optimistic_unchoke_interval`, `unchoke_slots_limit`.
 *
 * The request scheduler is a faithful port of `request_blocks.cpp` + the relevant
 * `peer_connection` machinery:
 *
 *  - **Dynamic request queue** (`update_desired_queue_size`) — each peer's pipeline
 *    depth is `request_queue_time × download-rate / block-size`, clamped to
 *    `[2, max_out_request_queue]`, with slow-start growing it by one per block
 *    until the rate stops climbing. Snubbed or end-game peers drop to 1.
 *  - **True end-game** (`request_a_block`) — when the swarm has no free block left
 *    for a peer with an empty queue, *one* block already requested from someone
 *    else is double-requested (gated by `strict_end_game_mode`); whoever delivers
 *    first wins and every other holder gets a `cancel` (`torrent::cancel_block`).
 *  - **Snubbing** (`snub_peer`) — a peer with outstanding requests that sends no
 *    payload for `piece_timeout` is snubbed: queue depth 1, *reverse* picking (it
 *    converges on common pieces instead of holding rare ones hostage), and its
 *    newest request is cancelled when it blocks a piece's completion — after a
 *    replacement is requested, so the picker can't hand the same block back.
 *  - **Choking** — a periodic [Choker] round (`unchoke_interval`) limits uploads to
 *    the fastest peers + one rotating optimistic slot; choked peers are not served.
 *  - **Rate limiting** — payload moves through the engine's [RateLimiter] (the
 *    live [io.github.yuroyami.kitetorrent.bandwidth.BandwidthManager] wiring):
 *    uploads acquire quota before the send, downloads after the receive
 *    (back-pressure on the read loop).
 *  - **µTP** — with a [UtpSocketManager] attached, outgoing connections try uTP
 *    first and fall back to TCP (`outgoing_utp` + `outgoing_tcp`).
 *  - **Verified resume**, **state machine**, **pause/resume**, **priorities** and
 *    **[sequentialDownload]** as before.
 *
 * All shared state (picker, [have], the peer set) is guarded by a single [Mutex]; sends
 * happen outside the lock. That mutex is the concurrency contract — honour it.
 */
class TorrentSession(
    val torrent: TorrentInfo,
    val disk: DiskIo,
    private val network: NetworkRuntime,
    private val scope: CoroutineScope,
    val peerId: Sha1Hash = generatePeerId(),
    val listenPort: Int = 6881,
    private val httpTracker: HttpTracker? = null,
    private val maxPeers: Int = 50,
    private val tickIntervalMs: Long = 1000,
    private val settings: SettingsPack = SettingsPack(),
    private val utp: UtpSocketManager? = null,
    private val limiter: RateLimiter? = null,
    private val torrentBandwidth: TorrentBandwidth? = null,
    private val connections: ConnectionBudget? = null,
) {
    private val infoHash: Sha1Hash = requireNotNull(torrent.infoHashV1) { "v1 info-hash required to download" }
    private val numPieces = torrent.numPieces
    private val blockSize = BLOCK_SIZE

    private val picker = PiecePicker(numPieces, blocksPerPiece = (torrent.pieceLength + blockSize - 1) / blockSize)
    private val pickRandom = Random.Default

    // settings_pack knobs (seconds-valued ones converted to ticks)
    private val maxOutRequestQueue = settings.getInt(IntSetting.MAX_OUT_REQUEST_QUEUE)
    private val requestQueueTimeSecs = settings.getInt(IntSetting.REQUEST_QUEUE_TIME)
    private val initialPickerThreshold = settings.getInt(IntSetting.INITIAL_PICKER_THRESHOLD)
    private val strictEndgame = settings.getBool(BoolSetting.STRICT_END_GAME_MODE)
    private val uploadSlots = settings.getInt(IntSetting.UNCHOKE_SLOTS_LIMIT)
    private val requestTimeoutTicks = secondsToTicks(settings.getInt(IntSetting.REQUEST_TIMEOUT))
    private val pieceTimeoutTicks = secondsToTicks(settings.getInt(IntSetting.PIECE_TIMEOUT))
    private val chokeRoundEveryTicks = secondsToTicks(settings.getInt(IntSetting.UNCHOKE_INTERVAL))
    private val optimisticEveryRounds = maxOf(
        1,
        settings.getInt(IntSetting.OPTIMISTIC_UNCHOKE_INTERVAL) / maxOf(1, settings.getInt(IntSetting.UNCHOKE_INTERVAL)),
    )

    /** The pieces we have verified on disk. */
    val have = Bitfield(numPieces)

    private val lock = Mutex()
    private val peers = LinkedHashMap<PeerConnection, PeerContext>()

    /** Per-file download priorities (0 = don't download, 4 = normal, up to 7). */
    private val filePriorities = IntArray(torrent.numFiles) { DEFAULT_PRIORITY }

    private var uploadedBytes = 0L
    private var downloadedBytes = 0L

    private var tickStarted = false
    private var tickJob: Job? = null
    private var tickCounter = 0
    private var chokeRoundCounter = 0
    private var currentOptimistic: PeerContext? = null

    @Volatile
    private var paused = false

    /** Pick pieces in index order instead of rarest-first (`torrent_flags::sequential_download`). */
    @Volatile
    var sequentialDownload: Boolean = false

    /** Current lifecycle state. */
    @Volatile
    var state: TorrentState = TorrentState.CHECKING
        private set

    /** Invoked (off-lock) whenever [state] changes. */
    var onStateChanged: ((TorrentState) -> Unit)? = null

    /** Invoked (off-lock) whenever a piece is verified and completed. */
    var onPieceVerified: ((Int) -> Unit)? = null

    init {
        val lastBlocks = (torrent.storage.pieceSize(numPieces - 1) + blockSize - 1) / blockSize
        picker.setBlocksInLastPiece(lastBlocks)
    }

    private fun secondsToTicks(seconds: Int): Int =
        maxOf(1, (seconds * 1000L / tickIntervalMs).toInt())

    // --- progress ----------------------------------------------------------------

    val bytesDownloaded: Long get() = downloadedBytes
    val bytesUploaded: Long get() = uploadedBytes
    suspend fun numHave(): Int = lock.withLock { picker.numHave() }
    suspend fun isSeeding(): Boolean = lock.withLock { picker.isSeeding() }
    suspend fun progress(): Float = lock.withLock { picker.numHave().toFloat() / numPieces }
    suspend fun numPeers(): Int = lock.withLock { peers.size }

    /** Peers currently snubbed (no payload despite outstanding requests). */
    suspend fun numSnubbedPeers(): Int = lock.withLock { peers.values.count { it.snubbed } }

    /** True while any peer is in end-game mode (double-requesting the last blocks). */
    suspend fun inEndgame(): Boolean = lock.withLock { peers.values.any { it.endgameMode } }

    // --- lifecycle ---------------------------------------------------------------

    /** Verify on-disk pieces, start the maintenance tick, announce, and connect to peers. */
    suspend fun start() {
        recheck()
        ensureTick()
        if (paused) return
        setState(if (isSeedingLocked()) TorrentState.SEEDING else TorrentState.DOWNLOADING)
        connect(announceToAllTrackers(TrackerEvent.STARTED))
    }

    /**
     * Verified resume: for each candidate piece, hash the bytes on disk and claim it only
     * if the hash matches the torrent's piece hash. [full] = true rehashes every piece
     * (use after a crash); otherwise it only checks pieces the disk layer reports present.
     */
    suspend fun recheck(full: Boolean = false) {
        setState(TorrentState.CHECKING)
        val candidates = if (full) BooleanArray(numPieces) { true } else disk.checkExistingFiles()
        for (i in 0 until numPieces) {
            if (i >= candidates.size || !candidates[i]) continue
            val expected = torrent.pieceHashV1(i) ?: continue
            if (disk.hashPiece(i).v1 == expected) {
                lock.withLock {
                    if (!picker.havePiece(i)) {
                        picker.pieceFlushed(i)
                        have.setBit(i)
                    }
                }
            }
        }
        setState(if (isSeedingLocked()) TorrentState.SEEDING else TorrentState.DOWNLOADING)
    }

    /** Connect to up to [maxPeers] of the given endpoints, each on its own coroutine. */
    fun connect(peers: List<PeerEndpoint>) {
        if (paused) return
        ensureTick()
        for (peer in peers.distinct().take(maxPeers)) scope.launch { runPeer(peer) }
    }

    /** Pause: stop requesting/serving and disconnect all peers. Idempotent. */
    suspend fun pause() {
        val closers = lock.withLock {
            paused = true
            peers.values.map { it.close }
        }
        setState(TorrentState.PAUSED)
        closers.forEach { runCatching { it() } }
    }

    /** Resume after a [pause]: re-announce and reconnect. */
    suspend fun resume() {
        if (!paused) return
        paused = false
        setState(if (isSeedingLocked()) TorrentState.SEEDING else TorrentState.DOWNLOADING)
        connect(announceToAllTrackers(TrackerEvent.STARTED))
    }

    /** Stop the maintenance tick. The owning scope should also be cancelled to drop peers. */
    fun stop() {
        tickJob?.cancel()
        tickJob = null
    }

    // --- priorities ---------------------------------------------------------------

    /** Set a single piece's priority (0 = don't download, 7 = highest). */
    suspend fun setPiecePriority(piece: Int, priority: Int) =
        lock.withLock { picker.setPiecePriority(piece, priority) }

    /**
     * Set a file's download priority. A piece shared by two files takes the *higher* of
     * the two files' priorities (libtorrent's rule), recomputed across all files.
     */
    suspend fun setFilePriority(fileIndex: Int, priority: Int) = lock.withLock {
        filePriorities[fileIndex] = priority
        val pieces = IntArray(numPieces) { 0 } // 0 = don't download until a file claims it
        for ((idx, f) in torrent.files.withIndex()) {
            val pr = filePriorities[idx]
            if (pr <= 0 || f.size <= 0) continue
            val first = (f.offset / torrent.pieceLength).toInt()
            val last = ((f.offset + f.size - 1) / torrent.pieceLength).toInt()
            for (p in first..last) if (pr > pieces[p]) pieces[p] = pr
        }
        for (p in 0 until numPieces) picker.setPiecePriority(p, pieces[p])
    }

    // --- trackers ----------------------------------------------------------------

    private suspend fun announceToAllTrackers(event: TrackerEvent): List<PeerEndpoint> {
        val req = AnnounceRequest(
            infoHash = infoHash,
            peerId = peerId,
            port = listenPort,
            uploaded = uploadedBytes,
            downloaded = downloadedBytes,
            left = torrent.totalSize - downloadedBytes,
            event = event,
            numWant = maxPeers,
        )
        val out = ArrayList<PeerEndpoint>()
        var udp: UdpTracker? = null
        for (url in torrent.allTrackers()) {
            try {
                val resp = when {
                    url.startsWith("http") -> httpTracker?.announce(url, req)
                    url.startsWith("udp") -> {
                        if (udp == null) udp = UdpTracker(network.bindUdp())
                        val (host, port) = parseHostPort(url) ?: continue
                        udp.announce(host, port, req)
                    }
                    else -> null
                }
                if (resp != null) out.addAll(resp.peers)
            } catch (_: Exception) {
                // a dead tracker shouldn't kill the announce — try the next one
            }
        }
        return out
    }

    // --- the maintenance tick: stats, snubbing, timeouts, choking -----------------

    private fun ensureTick() {
        if (tickStarted) return
        tickStarted = true
        tickJob = scope.launch {
            try {
                while (true) {
                    delay(tickIntervalMs)
                    onTick()
                }
            } catch (_: Throwable) {
                // scope cancelled — normal shutdown
            }
        }
    }

    private suspend fun onTick() {
        val cancels = ArrayList<Triple<PeerConnection, PieceBlock, Int>>()
        val sends = ArrayList<Pair<PeerContext, List<PieceBlock>>>()
        var sendKeepAlives = false
        var runChoke = false
        lock.withLock {
            tickCounter++
            for (ctx in peers.values) {
                // ---- per-second stats + dynamic queue (peer_connection::second_tick)
                val payloadThisTick = ctx.stat.lastPayloadDownloaded()
                if (ctx.slowStart && !ctx.pc.theirChoking && ctx.prevTickPayload > 0 &&
                    ctx.prevTickPayload + SLOW_START_SLACK_BYTES >= payloadThisTick
                ) {
                    ctx.slowStart = false // the rate stopped climbing — slow start is over
                }
                ctx.prevTickPayload = payloadThisTick
                ctx.stat.secondTick(tickIntervalMs.toInt())
                updateDesiredQueueSize(ctx)

                // ---- piece timeout → snub (second_tick → snub_peer)
                ctx.ticksSinceLastPiece++
                if (ctx.inflight.isNotEmpty() && ctx.ticksSinceLastPiece >= pieceTimeoutTicks) {
                    snubPeerLocked(ctx, cancels, sends)
                }

                // ---- request_timeout sweep: recycle requests stuck too long
                val it = ctx.inflight.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    e.setValue(e.value + 1)
                    if (e.value > requestTimeoutTicks) {
                        picker.abortDownload(e.key, ctx.pc)
                        cancels.add(Triple(ctx.pc, e.key, blockLength(e.key)))
                        it.remove()
                    }
                }
            }
            runChoke = tickCounter % chokeRoundEveryTicks == 0
            sendKeepAlives = tickCounter % KEEP_ALIVE_TICKS == 0
        }
        for ((ctx, blocks) in sends) sendRequests(ctx, blocks)
        for ((pc, b, len) in cancels) runCatching { pc.sendCancel(b.pieceIndex, b.blockIndex * blockSize, len) }
        if (cancels.isNotEmpty()) snapshotPeers().forEach { maybeRequest(it) } // re-pick the freed blocks
        if (sendKeepAlives) snapshotPeers().forEach { runCatching { it.pc.sendKeepAlive() } }
        if (runChoke) runChokeRound()
    }

    /**
     * Port of `update_desired_queue_size`: snubbed → 1; otherwise during slow start
     * keep the grown value, after it derive the depth from the measured payload rate
     * (`request_queue_time` seconds' worth of blocks), clamped to
     * `[MIN_REQUEST_QUEUE, max_out_request_queue]`.
     */
    private fun updateDesiredQueueSize(ctx: PeerContext) {
        if (ctx.snubbed) {
            ctx.desiredQueueSize = 1
            return
        }
        if (!ctx.slowStart) {
            val rate = ctx.stat.downloadPayloadRate()
            ctx.desiredQueueSize = (requestQueueTimeSecs.toLong() * rate / blockSize).toInt()
        }
        if (ctx.desiredQueueSize > maxOutRequestQueue) ctx.desiredQueueSize = maxOutRequestQueue
        if (ctx.desiredQueueSize < MIN_REQUEST_QUEUE) ctx.desiredQueueSize = MIN_REQUEST_QUEUE
    }

    /** The live queue cap: end-game and snubbed peers trickle one block at a time. */
    private fun effectiveQueueSize(ctx: PeerContext): Int =
        if (ctx.endgameMode || ctx.snubbed) 1 else ctx.desiredQueueSize

    /**
     * Port of `peer_connection::snub_peer`. Flags the peer, drops its queue to one,
     * and — only when its newest stalled request *blocks a piece from completing*
     * (no free blocks remain in that piece) — requests a replacement first, then
     * aborts the stalled block so other peers can pick it. Requesting before
     * aborting stops the picker handing the same block straight back, the stall
     * documented in upstream's block-request-time-outs note.
     */
    private fun snubPeerLocked(
        ctx: PeerContext,
        cancels: MutableList<Triple<PeerConnection, PieceBlock, Int>>,
        sends: MutableList<Pair<PeerContext, List<PieceBlock>>>,
    ) {
        if (!ctx.snubbed) {
            ctx.snubbed = true
            ctx.slowStart = false
        }
        ctx.desiredQueueSize = 1

        val newest = ctx.inflight.keys.lastOrNull() ?: return
        // still free blocks in that piece → it isn't blocking completion; leave it
        if (picker.numFreeBlocksIn(newest.pieceIndex) > 0) return

        // request a replacement before aborting (dqs briefly 2, like upstream)
        val replacement = pickRequestsLocked(ctx, queueCap = ctx.inflight.size + 1)
        if (replacement.isNotEmpty()) sends.add(ctx to replacement)

        picker.abortDownload(newest, ctx.pc)
        ctx.inflight.remove(newest)
        cancels.add(Triple(ctx.pc, newest, blockLength(newest)))
    }

    /** One tit-for-tat unchoke round, plus a rotating optimistic slot. */
    private suspend fun runChokeRound() {
        val toUnchoke = ArrayList<PeerContext>()
        val toChoke = ArrayList<PeerContext>()
        lock.withLock {
            chokeRoundCounter++
            val seeding = picker.isSeeding()
            val interested = peers.values.filter { it.pc.theirInterested }
            val rates = interested.associateWith { if (seeding) it.roundUploaded else it.roundDownloaded }

            // rotate the optimistic pick every few rounds; otherwise keep the prior one
            val optimistic =
                if (chokeRoundCounter % optimisticEveryRounds == 0) {
                    interested.filter { it.amChoking }.randomOrNull()
                } else {
                    currentOptimistic?.takeIf { peers.containsKey(it.pc) }
                }
            currentOptimistic = optimistic

            val unchokeSet = Choker.chooseUnchoked(rates, uploadSlots, optimistic)
            for (ctx in peers.values) {
                val shouldUnchoke = ctx in unchokeSet
                if (shouldUnchoke && ctx.amChoking) {
                    ctx.amChoking = false; toUnchoke.add(ctx)
                } else if (!shouldUnchoke && !ctx.amChoking) {
                    ctx.amChoking = true; toChoke.add(ctx)
                }
                ctx.roundDownloaded = 0
                ctx.roundUploaded = 0
            }
        }
        for (ctx in toUnchoke) runCatching { ctx.pc.sendUnchoke() }
        for (ctx in toChoke) runCatching { ctx.pc.sendChoke() }
    }

    // --- per-peer connection lifecycle -------------------------------------------

    private suspend fun runPeer(peer: PeerEndpoint) {
        if (connections != null && !connections.tryAcquire()) return // connections_limit
        var acquired = true
        try {
            // µTP first when available, TCP as the fallback (outgoing_utp/outgoing_tcp)
            val transport: Pair<ByteStream, suspend () -> Unit>? = dial(peer)
            if (transport == null) {
                connections?.release(); acquired = false
                return
            }
            val (stream, close) = transport
            val pc = PeerConnection(stream, infoHash, peerId, numPieces)
            acquired = false // runConnectedPeer's finally releases from here on
            runConnectedPeer(pc, close, releaseBudget = true) { pc.performHandshake() }
        } finally {
            if (acquired) connections?.release()
        }
    }

    private suspend fun dial(peer: PeerEndpoint): Pair<ByteStream, suspend () -> Unit>? {
        if (utp != null) {
            val stream = withTimeoutOrNull(UTP_CONNECT_TIMEOUT_MS) {
                runCatching { utp.connect(peer.host, peer.port) }.getOrNull()
            }
            if (stream != null) return stream to { utp.disconnect(stream) }
        }
        val conn = try {
            network.connectTcp(peer.host, peer.port)
        } catch (_: Exception) {
            return null
        }
        return conn.asByteStream() to { conn.close() }
    }

    /** Inbound TCP peer: the engine's accept loop read the [remote] handshake off [conn]. */
    suspend fun acceptInbound(conn: TcpConnection, remote: Handshake) =
        acceptInbound(conn.asByteStream(), { conn.close() }, remote)

    /**
     * Inbound peer over any transport (TCP or µTP). [stream] must be positioned just
     * past the remote's 68-byte handshake; [close] tears the transport down.
     */
    suspend fun acceptInbound(stream: ByteStream, close: suspend () -> Unit, remote: Handshake) {
        if (connections != null && !connections.tryAcquire()) {
            runCatching { close() } // too_many_connections
            return
        }
        val pc = PeerConnection(stream, infoHash, peerId, numPieces)
        runConnectedPeer(pc, close, releaseBudget = true) { pc.completeInboundHandshake(remote) }
    }

    private suspend fun runConnectedPeer(
        pc: PeerConnection,
        close: suspend () -> Unit,
        releaseBudget: Boolean = false,
        handshake: suspend () -> Unit,
    ) {
        if (paused) {
            runCatching { close() }
            if (releaseBudget) connections?.release()
            return
        }
        val ctx = PeerContext(pc, close)
        try {
            handshake()
            lock.withLock { peers[pc] = ctx }
            // send our bitfield (snapshot under the lock so it can't change mid-encode)
            val bf = lock.withLock { if (picker.numHave() > 0) Bitfield.fromBytes(have.data(), numPieces) else null }
            if (bf != null) pc.sendBitfield(bf)
            pc.sendInterested()
            pc.sendExtended(
                ExtensionHandshake.HANDSHAKE_ID,
                ExtensionHandshake.build(metadataSize = torrent.infoBytes.size.toLong(), listenPort = listenPort),
            )
            pc.receiveLoop { msg -> handleMessage(ctx, msg) }
        } catch (_: Exception) {
            // disconnect / protocol error
        } finally {
            lock.withLock {
                peers.remove(pc)
                if (currentOptimistic === ctx) currentOptimistic = null
                picker.clearPeer(pc)
                picker.decRefcount(pc.theirBitfield, pc)
            }
            runCatching { close() }
            if (releaseBudget) connections?.release()
        }
    }

    private suspend fun handleMessage(ctx: PeerContext, msg: PeerMessage) {
        when (msg) {
            is PeerMessage.Bitfield -> { lock.withLock { picker.incRefcount(msg.bitfield, ctx.pc) }; maybeRequest(ctx) }
            is PeerMessage.Have -> { lock.withLock { picker.incRefcount(msg.piece, ctx.pc) }; maybeRequest(ctx) }
            PeerMessage.HaveAll -> { lock.withLock { picker.incRefcountAll(ctx.pc) }; maybeRequest(ctx) }
            PeerMessage.Unchoke -> maybeRequest(ctx)
            is PeerMessage.Piece -> onBlockReceived(ctx, msg)
            PeerMessage.Interested -> onInterested(ctx)
            is PeerMessage.Request -> serveBlock(ctx, msg)
            is PeerMessage.Extended -> handleExtended(ctx, msg)
            else -> {} // choke/cancel/fast-hints handled by PeerConnection.applyIncoming
        }
    }

    /** Fast-path unchoke: if there's a free upload slot, unchoke an interested peer at once. */
    private suspend fun onInterested(ctx: PeerContext) {
        val unchoke = lock.withLock {
            val free = peers.values.count { !it.amChoking } < uploadSlots
            (ctx.amChoking && free).also { if (it) ctx.amChoking = false }
        }
        if (unchoke) runCatching { ctx.pc.sendUnchoke() }
    }

    /** Upload: serve a block to a peer we've unchoked. Choked peers get nothing. */
    private suspend fun serveBlock(ctx: PeerContext, request: PeerMessage.Request) {
        if (ctx.amChoking) return
        if (request.length <= 0 || request.length > 1 shl 17) return
        val haveIt = lock.withLock { picker.havePiece(request.piece) }
        if (!haveIt) return
        val block = disk.read(request.piece, request.begin, request.length)
        limiter?.acquireUpload(block.size, torrentBandwidth) // upload rate limit
        ctx.pc.sendPiece(request.piece, request.begin, block)
        lock.withLock {
            uploadedBytes += block.size
            ctx.roundUploaded += block.size
            ctx.stat.sentBytes(block.size, PIECE_MESSAGE_OVERHEAD)
        }
    }

    private suspend fun onBlockReceived(ctx: PeerContext, msg: PeerMessage.Piece) {
        // download rate limit: delaying the (sequential) receive loop back-pressures the sender
        limiter?.acquireDownload(msg.block.size, torrentBandwidth)

        val block = PieceBlock(msg.piece, msg.begin / blockSize)
        val cancels = ArrayList<PeerContext>()
        val fresh: Boolean
        lock.withLock {
            downloadedBytes += msg.block.size
            ctx.roundDownloaded += msg.block.size
            ctx.stat.receivedBytes(msg.block.size, PIECE_MESSAGE_OVERHEAD)
            ctx.ticksSinceLastPiece = 0
            ctx.snubbed = false
            // slow start: every arriving block grows the pipeline by one
            if (ctx.slowStart && ctx.desiredQueueSize < maxOutRequestQueue) ctx.desiredQueueSize++
            ctx.inflight.remove(block)

            fresh = !picker.havePiece(msg.piece) && !picker.isDownloaded(block)
            if (fresh) {
                // end-game dedup (torrent::cancel_block): everyone else holding this
                // block in flight gets a cancel — first delivery wins
                for (other in peers.values) {
                    if (other === ctx) continue
                    if (other.inflight.remove(block) != null) cancels.add(other)
                }
            }
        }
        for (other in cancels) runCatching {
            other.pc.sendCancel(block.pieceIndex, block.blockIndex * blockSize, blockLength(block))
        }
        if (!fresh) { // duplicate delivery (end-game race) — drop it
            maybeRequest(ctx)
            return
        }

        disk.write(msg.piece, msg.begin, msg.block)
        val complete: Boolean
        lock.withLock {
            picker.markAsFinished(block, ctx.pc)
            complete = picker.isPieceFinished(msg.piece) && !picker.havePiece(msg.piece)
        }
        if (complete) verifyPiece(msg.piece)
        maybeRequest(ctx)
    }

    /**
     * Fill a peer's request pipeline — the port of `request_a_block`
     * (request_blocks.cpp). Picks free blocks up to the peer's [effectiveQueueSize];
     * when the swarm can't supply them and this peer has nothing outstanding, one
     * *busy* block (already requested from another peer) is double-requested —
     * end-game mode. `strict_end_game_mode` keeps busy picks off the table while
     * untouched pieces still exist.
     */
    private suspend fun maybeRequest(ctx: PeerContext) {
        val pc = ctx.pc
        if (paused || pc.theirChoking) return
        val toSend: List<PieceBlock>
        lock.withLock {
            if (picker.isSeeding()) return
            toSend = pickRequestsLocked(ctx, effectiveQueueSize(ctx))
        }
        sendRequests(ctx, toSend)
    }

    /** The pick-and-mark core of [maybeRequest]; the caller holds [lock] and sends. */
    private fun pickRequestsLocked(ctx: PeerContext, queueCap: Int): List<PieceBlock> {
        val pc = ctx.pc
        val want = queueCap - ctx.inflight.size
        if (want <= 0) return emptyList()

        // gates computed at entry, exactly like request_a_block's dont_pick_busy_blocks
        val hadOutstanding = ctx.inflight.isNotEmpty()
        val dontPickBusy = hadOutstanding ||
            (strictEndgame && picker.downloadQueueSize() < picker.numWantLeft())

        val picked = picker.pickPieces(
            pc.theirBitfield, want,
            options = pickerOptionsLocked(ctx),
            random = pickRandom,
            numPeers = peers.size,
            requesterPeer = pc,
        )

        val toSend = ArrayList<PieceBlock>(minOf(want, picked.size))
        for (b in picked) {
            if (toSend.size >= want) break
            if (b in ctx.inflight) continue
            if (picker.numPeers(b) > 0) {
                // busy block: only as the single end-game request of an idle peer
                if (dontPickBusy || toSend.isNotEmpty()) continue
            }
            if (picker.markAsDownloading(b, pc)) {
                ctx.inflight[b] = 0
                toSend.add(b)
            }
        }
        // couldn't fill the queue from the swarm → end-game (request_a_block's tail)
        ctx.endgameMode = toSend.size < want
        return toSend
    }

    /** `picker_options()`: sequential / random-below-threshold / rarest (+reverse when snubbed). */
    private fun pickerOptionsLocked(ctx: PeerContext): Int {
        var options = 0
        if (sequentialDownload) {
            options = options or PiecePicker.OPTION_SEQUENTIAL
        } else if (picker.numHave() < initialPickerThreshold) {
            // too few pieces to know what's rare — pick at random, finish partials first
            options = options or PiecePicker.OPTION_PRIORITIZE_PARTIALS
        } else {
            options = options or PiecePicker.OPTION_RAREST_FIRST
            if (ctx.snubbed) options = options or PiecePicker.OPTION_REVERSE
        }
        return options
    }

    private suspend fun sendRequests(ctx: PeerContext, blocks: List<PieceBlock>) {
        for (b in blocks) runCatching {
            ctx.pc.sendRequest(b.pieceIndex, b.blockIndex * blockSize, blockLength(b))
        }
    }

    private suspend fun verifyPiece(piece: Int) {
        val expected = torrent.pieceHashV1(piece)
        if (expected != null && disk.hashPiece(piece).v1 == expected) {
            val notify = lock.withLock {
                picker.piecePassed(piece)
                have.setBit(piece)
                peers.keys.toList()
            }
            for (p in notify) runCatching { p.sendHave(piece) }
            onPieceVerified?.invoke(piece)
            if (isSeedingLocked()) setState(TorrentState.SEEDING)
        } else {
            lock.withLock { picker.weDontHave(piece) } // bad data — re-download
        }
    }

    // --- extension protocol (BEP-10) + metadata serving (BEP-9) -------------------

    private suspend fun handleExtended(ctx: PeerContext, msg: PeerMessage.Extended) {
        when (msg.extId) {
            ExtensionHandshake.HANDSHAKE_ID ->
                ExtensionHandshake.parse(msg.payload)?.utMetadataId?.let { ctx.metadataId = it }
            ExtensionHandshake.UT_METADATA_ID -> {
                val m = UtMetadata.parse(msg.payload) ?: return
                if (m.type != UtMetadata.Type.REQUEST) return
                val info = torrent.infoBytes
                val off = m.piece * METADATA_PIECE
                if (off < 0 || off >= info.size) {
                    sendMetadataTo(ctx, UtMetadata.encodeReject(m.piece)); return
                }
                val len = UtMetadata.pieceLength(m.piece, info.size)
                sendMetadataTo(ctx, UtMetadata.encodeData(m.piece, info.size.toLong(), info.copyOfRange(off, off + len)))
            }
        }
    }

    private suspend fun sendMetadataTo(ctx: PeerContext, payload: ByteArray) =
        ctx.pc.sendExtended(ctx.metadataId ?: ExtensionHandshake.UT_METADATA_ID, payload)

    // --- helpers ------------------------------------------------------------------

    private suspend fun snapshotPeers(): List<PeerContext> = lock.withLock { peers.values.toList() }

    private suspend fun isSeedingLocked(): Boolean = lock.withLock { picker.isSeeding() }

    private fun setState(s: TorrentState) {
        if (state != s) {
            state = s
            onStateChanged?.invoke(s)
        }
    }

    private fun blockLength(b: PieceBlock): Int {
        val pieceSize = torrent.storage.pieceSize(b.pieceIndex)
        return minOf(blockSize, pieceSize - b.blockIndex * blockSize)
    }

    /** All mutable per-peer state. Lives in [peers] under [lock]. */
    private class PeerContext(val pc: PeerConnection, val close: suspend () -> Unit) {
        /** Blocks requested from this peer → age in ticks (for request timeouts). */
        val inflight = LinkedHashMap<PieceBlock, Int>()
        var metadataId: Int? = null
        var amChoking: Boolean = true // BEP-3: connections start choked
        var roundDownloaded: Long = 0L
        var roundUploaded: Long = 0L

        /** Per-peer transfer statistics (`m_statistics`) — drives the dynamic queue. */
        val stat = Stat()

        /** `m_desired_queue_size` — starts at 4 like upstream, then adapts. */
        var desiredQueueSize: Int = 4

        /** `m_slow_start` — grow the queue per block until the rate stops climbing. */
        var slowStart: Boolean = true

        /** Payload bytes seen in the previous tick window (slow-start exit check). */
        var prevTickPayload: Int = 0

        /** Ticks since this peer last delivered a payload block (`m_last_piece`). */
        var ticksSinceLastPiece: Int = 0

        /** `m_snubbed` — outstanding requests but no payload for piece_timeout. */
        var snubbed: Boolean = false

        /** `m_endgame_mode` — the swarm had no free block left for this peer. */
        var endgameMode: Boolean = false
    }

    companion object {
        const val BLOCK_SIZE = 16 * 1024
        const val METADATA_PIECE = 16 * 1024
        const val DEFAULT_PRIORITY = 4

        /** `min_request_queue` — the dynamic queue never drops below this (except snub/end-game). */
        const val MIN_REQUEST_QUEUE = 2

        /** Slow start ends when a tick grows payload by less than this (upstream's 5000-byte slack). */
        const val SLOW_START_SLACK_BYTES = 5000

        /** Frame overhead of a `piece` message (4 length + 1 id + 8 piece/begin). */
        const val PIECE_MESSAGE_OVERHEAD = 13

        /** Keep-alives go out roughly every 75 s of ticks, like `write_keepalive`'s cadence. */
        const val KEEP_ALIVE_TICKS = 75

        /** How long an outgoing µTP SYN may wait before falling back to TCP. */
        const val UTP_CONNECT_TIMEOUT_MS = 3000L

        /** Parse `udp://host:port/announce` → (host, port). */
        internal fun parseHostPort(url: String): Pair<String, Int>? {
            val noScheme = url.substringAfter("://", url)
            val authority = noScheme.substringBefore('/')
            val host: String
            val portStr: String
            if (authority.startsWith("[")) {
                host = authority.substringAfter('[').substringBefore(']')
                portStr = authority.substringAfterLast(']').removePrefix(":")
            } else {
                host = authority.substringBefore(':')
                portStr = authority.substringAfter(':', "")
            }
            val port = portStr.toIntOrNull() ?: return null
            if (host.isEmpty()) return null
            return host to port
        }
    }
}
