package io.github.yuroyami.kitetorrent.session.engine

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.alert.Alert
import io.github.yuroyami.kitetorrent.alert.HashFailedAlert
import io.github.yuroyami.kitetorrent.alert.PeerBanAlert
import io.github.yuroyami.kitetorrent.alert.PeerConnectAlert
import io.github.yuroyami.kitetorrent.alert.PeerConnectDirection
import io.github.yuroyami.kitetorrent.alert.PeerDisconnectedAlert
import io.github.yuroyami.kitetorrent.alert.PeerErrorAlert
import io.github.yuroyami.kitetorrent.alert.PieceFinishedAlert
import io.github.yuroyami.kitetorrent.alert.TorrentErrorAlert
import io.github.yuroyami.kitetorrent.alert.TorrentFinishedAlert
import io.github.yuroyami.kitetorrent.alert.TrackerErrorAlert
import io.github.yuroyami.kitetorrent.alert.TrackerReplyAlert
import io.github.yuroyami.kitetorrent.torrent.HashPicker
import io.github.yuroyami.kitetorrent.torrent.Merkle
import io.github.yuroyami.kitetorrent.torrent.MerkleTree
import io.github.yuroyami.kitetorrent.extensions.ExtensionHandshake
import io.github.yuroyami.kitetorrent.extensions.UtMetadata
import io.github.yuroyami.kitetorrent.extensions.UtPex
import io.github.yuroyami.kitetorrent.generatePeerId
import io.github.yuroyami.kitetorrent.peer.IpFilter
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
import io.github.yuroyami.kitetorrent.session.peer.Mse
import io.github.yuroyami.kitetorrent.session.peer.PeerConnection
import io.github.yuroyami.kitetorrent.session.peer.asByteStream
import io.github.yuroyami.kitetorrent.settings.EncPolicy
import io.github.yuroyami.kitetorrent.session.tracker.AnnounceEntry
import io.github.yuroyami.kitetorrent.session.tracker.AnnounceRequest
import io.github.yuroyami.kitetorrent.session.tracker.AnnounceResponse
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.github.yuroyami.kitetorrent.session.tracker.PeerEndpoint
import io.github.yuroyami.kitetorrent.session.tracker.TrackerEvent
import io.github.yuroyami.kitetorrent.session.tracker.UdpTracker
import io.github.yuroyami.kitetorrent.settings.BoolSetting
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.stat.Stat
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.peer.PeerAddress
import io.github.yuroyami.kitetorrent.torrent.resume.AddTorrentParams
import io.github.yuroyami.kitetorrent.torrent.resume.Endpoint
import io.github.yuroyami.kitetorrent.torrent.resume.TorrentFlags
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
    var maxPeers: Int = 50, // per-torrent connection cap (`max_connections`); settable live
    private val tickIntervalMs: Long = 1000,
    private val settings: SettingsPack = SettingsPack(),
    private val utp: UtpSocketManager? = null,
    private val limiter: RateLimiter? = null,
    private val torrentBandwidth: TorrentBandwidth? = null,
    private val connections: ConnectionBudget? = null,
    /**
     * Fast-resume state from a previous run ([saveResumeData] / `ResumeData.read`). When
     * present, [start] adopts the saved piece bitfield and partial-block progress instead
     * of running a full [recheck] — the libtorrent fast-resume path (`torrent::on_resume_data`).
     */
    private val resumeData: AddTorrentParams? = null,
    /** Optional IP blocklist consulted before every outbound dial (`apply_ip_filter`). */
    private val ipFilter: IpFilter? = null,
) {
    // the 20-byte wire info-hash: v1 when present, else the truncated v2 hash (BEP-52)
    private val infoHash: Sha1Hash = torrent.wireInfoHash()
    private val numPieces = torrent.numPieces
    private val blockSize = BLOCK_SIZE

    private val picker = PiecePicker(numPieces, blocksPerPiece = (torrent.pieceLength + blockSize - 1) / blockSize)
    private val pickRandom = Random.Default

    // settings_pack knobs (seconds-valued ones converted to ticks)
    private val maxOutRequestQueue = settings.getInt(IntSetting.MAX_OUT_REQUEST_QUEUE)
    private val requestQueueTimeSecs = settings.getInt(IntSetting.REQUEST_QUEUE_TIME)
    private val initialPickerThreshold = settings.getInt(IntSetting.INITIAL_PICKER_THRESHOLD)
    private val strictEndgame = settings.getBool(BoolSetting.STRICT_END_GAME_MODE)
    /** Upload (unchoke) slots for this torrent — `max_uploads`; settable live, default from settings. */
    var uploadSlots: Int = settings.getInt(IntSetting.UNCHOKE_SLOTS_LIMIT)
    private val requestTimeoutTicks = secondsToTicks(settings.getInt(IntSetting.REQUEST_TIMEOUT))
    private val pieceTimeoutTicks = secondsToTicks(settings.getInt(IntSetting.PIECE_TIMEOUT))
    private val chokeRoundEveryTicks = secondsToTicks(settings.getInt(IntSetting.UNCHOKE_INTERVAL))
    private val pexIntervalTicks = secondsToTicks(PEX_INTERVAL_SECONDS)
    private val outEncPolicy = settings.getInt(IntSetting.OUT_ENC_POLICY) // MSE: forced/enabled/disabled
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

    /** Endpoints we've already dialed (incl. from PEX/DHT/trackers) — dedups repeat gossip. */
    private val attemptedEndpoints = HashSet<PeerEndpoint>()

    /**
     * Per-tracker announce bookkeeping ([AnnounceEntry]), keyed by URL, built from
     * [TorrentInfo.announceTiers] preserving tier order. Drives BEP-12 tier failover, periodic
     * re-announce scheduling, and the once-only `started`/`completed` event guards.
     */
    private val announceEntries: List<AnnounceEntry> = buildList {
        for ((tierIdx, tier) in torrent.announceTiers.withIndex()) {
            for (url in tier) add(AnnounceEntry(url, tier = tierIdx))
        }
    }

    /** Monotonic seconds since session construction — the clock the announce scheduler uses. */
    private val monotonicStart = kotlin.time.TimeSource.Monotonic.markNow()
    private fun nowSecs(): Long = monotonicStart.elapsedNow().inWholeSeconds

    /** Whether we have already sent a `completed` event this run (across all trackers). */
    @Volatile
    private var completeSent = false

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

    /**
     * Super-seeding / initial-seeding (`torrent_flags::super_seeding`). When seeding, advertise
     * only one (rarest) piece to each peer at a time and hand out the next only once the peer
     * announces it got the previous — so the seed pushes the whole torrent into the swarm with
     * minimal redundant uploads while bootstrapping it.
     */
    @Volatile
    var superSeeding: Boolean = false

    /** Pieces currently handed out to some peer under super-seeding (not yet propagated). */
    private val superSeedAssigned = HashSet<Int>()

    /** Per-piece set of peers that supplied a block to it — for hash-failure blame. */
    private val pieceContributors = HashMap<Int, MutableSet<PeerContext>>()

    /** Share-ratio cap in per-mille (2000 = 2.0×); 0 = unlimited. Pauses seeding once met. */
    @Volatile
    var shareRatioLimit: Int = SeedingLimits.UNLIMITED

    /** Seed-time cap in seconds; 0 = unlimited. Pauses seeding once met. */
    @Volatile
    var seedTimeLimitSeconds: Int = SeedingLimits.UNLIMITED

    /** Maintenance ticks spent in the SEEDING state (drives the seed-time limit). */
    private var seedingTicks = 0

    /** Current lifecycle state. */
    @Volatile
    var state: TorrentState = TorrentState.CHECKING
        private set

    /** Invoked (off-lock) whenever [state] changes. */
    var onStateChanged: ((TorrentState) -> Unit)? = null

    /** Engine-internal state hook (queue manager) — separate so the app's [onStateChanged] is never clobbered. */
    internal var onStateChangedInternal: ((TorrentState) -> Unit)? = null

    /** Invoked (off-lock) whenever a piece is verified and completed. */
    var onPieceVerified: ((Int) -> Unit)? = null

    /**
     * Invoked (off-lock) when the fast-resume state has gone stale and should be persisted —
     * the torrent finished or was paused. The app responds by calling [saveResumeData] and
     * writing the bytes (the port of libtorrent's `save_resume_data` / `NEED_SAVE_RESUME`).
     */
    var onNeedSaveResume: (() -> Unit)? = null

    /**
     * Alert sink (off-lock). The owning [io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine]
     * (Tier E) sets this to funnel per-torrent events into the session alert queue. Defaults to
     * null (no-op). Called at the event sites this engine touches: tracker reply/error, peer
     * connect/disconnect/ban, piece finished, torrent finished, hash failures.
     */
    var onAlert: ((Alert) -> Unit)? = null

    /**
     * Invoked (off-lock) with a DHT-capable peer's advertised UDP port whenever a BEP-5
     * `port` message arrives. The engine feeds it into the DHT as a fresh node. Null = ignore.
     */
    var onDhtPort: ((host: String, port: Int) -> Unit)? = null

    /** True when this torrent carries v2 metadata — advertised on the wire (BEP-52 upgrade bit). */
    private val hasV2: Boolean = torrent.infoHashV2 != null

    /**
     * Per-file incremental v2 merkle trees, keyed by file index, for multi-piece real files.
     * Built lazily on first v2 verification. Each is constructed with the file's known
     * `pieces root` so [HashPicker] / [MerkleTree.setBlock] can validate against it.
     */
    private val merkleTrees: HashMap<Int, MerkleTree> = run {
        val m = HashMap<Int, MerkleTree>()
        if (torrent.infoHashV2 != null) {
            for (fidx in 0 until torrent.numFiles) {
                if (torrent.storage.isPadFile(fidx)) continue
                val numBlocks = torrent.storage.numBlocksInFile(fidx)
                if (numBlocks <= 0) continue
                if (torrent.storage.fileNumPieces(fidx) <= 1) continue // single-piece: root IS the hash
                val root = torrent.files[fidx].piecesRoot ?: continue
                m[fidx] = MerkleTree(numBlocks, torrent.storage.blocksPerPiece, root)
            }
        }
        m
    }

    /** v2 hash picker — decides when to request piece-layer hashes and validates responses. */
    private val hashPicker: HashPicker? = if (hasV2) HashPicker(torrent, merkleTrees) else null

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

    /** Peers we're currently uploading to (unchoked) — bounded by [uploadSlots] (`max_uploads`). */
    suspend fun numUnchoked(): Int = lock.withLock { peers.values.count { !it.amChoking } }

    /** True while any peer is in end-game mode (double-requesting the last blocks). */
    suspend fun inEndgame(): Boolean = lock.withLock { peers.values.any { it.endgameMode } }

    // --- lifecycle ---------------------------------------------------------------

    /**
     * Adopt fast-resume state, or [recheck] when there's none, then start the maintenance
     * tick, announce, and connect to peers. With [resumeData] present the saved `have`
     * bitfield is *trusted* (no rehash) — that's the whole point of fast resume; restarts
     * skip the full-disk re-read [recheck] would otherwise do.
     */
    suspend fun start() {
        if (resumeData != null) applyResumeData(resumeData) else recheck()
        ensureTick()
        if (paused) return
        setState(if (isSeedingLocked()) TorrentState.SEEDING else TorrentState.DOWNLOADING)
        connect(announceToAllTrackers(TrackerEvent.STARTED))
    }

    /**
     * Apply fast-resume [atp] without hashing: whole pieces in [AddTorrentParams.havePieces]
     * are flushed into the picker as had (`piece_flushed`, which is documented safe to call
     * straight from open for exactly this), and each block recorded in
     * [AddTorrentParams.unfinishedPieces] is marked finished so the picker won't re-request
     * the partial-piece progress already on disk. Saved stats, the sequential flag and
     * piece/file priorities are restored too. The port of `torrent` adopting resume data.
     */
    suspend fun applyResumeData(atp: AddTorrentParams) {
        setState(TorrentState.CHECKING)
        lock.withLock {
            val hp = atp.havePieces
            for (i in 0 until minOf(numPieces, hp.size())) {
                if (hp.getBit(i) && !picker.havePiece(i)) {
                    picker.pieceFlushed(i)
                    have.setBit(i)
                }
            }
            for ((piece, blocks) in atp.unfinishedPieces) {
                if (piece < 0 || piece >= numPieces || picker.havePiece(piece)) continue
                val n = minOf(picker.blocksInPiece(piece), blocks.size())
                for (b in 0 until n) {
                    if (blocks.getBit(b)) picker.markAsFinished(PieceBlock(piece, b))
                }
            }
            for (p in 0 until minOf(numPieces, atp.piecePriorities.size)) {
                picker.setPiecePriority(p, atp.piecePriorities[p])
            }
        }
        downloadedBytes = atp.totalDownloaded
        uploadedBytes = atp.totalUploaded
        if (atp.maxConnections > 0) maxPeers = atp.maxConnections // per-torrent max_connections
        if (atp.maxUploads > 0) uploadSlots = atp.maxUploads // per-torrent max_uploads
        if (atp.hasFlag(TorrentFlags.SEQUENTIAL_DOWNLOAD)) sequentialDownload = true
        if (atp.hasFlag(TorrentFlags.SUPER_SEEDING)) superSeeding = true
        if (atp.completeSent) completeSent = true // don't re-send event=completed after resume
        for ((idx, pr) in atp.filePriorities.withIndex()) {
            if (idx < filePriorities.size) setFilePriority(idx, pr)
        }
        setState(if (isSeedingLocked()) TorrentState.SEEDING else TorrentState.DOWNLOADING)
    }

    /**
     * Capture the current piece state as fast-resume [AddTorrentParams] — the port of
     * `save_resume_data` / `write_resume_data`. The result round-trips through
     * `ResumeData.write`/`read` (embedding the raw `info` dict so the torrent reloads
     * without its `.torrent` file) and feeds straight back into [applyResumeData] on the
     * next run. Verified `have` pieces become both `havePieces` and `verifiedPieces`
     * (every v1 piece is hash-checked before it's marked had); partially-downloaded
     * pieces export their on-disk (FINISHED) blocks as an `unfinished` block bitmask.
     *
     * @param savePath the storage path to record (the disk layer owns it; the session
     *   doesn't, so the caller supplies it).
     */
    suspend fun saveResumeData(savePath: String = ""): AddTorrentParams = lock.withLock {
        val haveBf = Bitfield.fromBytes(have.data(), numPieces)
        val unfinished = LinkedHashMap<Int, Bitfield>()
        for (piece in 0 until numPieces) {
            if (picker.havePiece(piece) || !picker.isDownloading(piece)) continue
            val nBlocks = picker.blocksInPiece(piece)
            var bf: Bitfield? = null
            for (b in 0 until nBlocks) {
                if (picker.isFinished(PieceBlock(piece, b))) {
                    val target = bf ?: Bitfield(nBlocks).also { bf = it }
                    target.setBit(b)
                }
            }
            bf?.let { unfinished[piece] = it }
        }
        var flags = TorrentFlags.DEFAULT_FLAGS
        if (sequentialDownload) flags = flags or TorrentFlags.SEQUENTIAL_DOWNLOAD
        if (paused) flags = flags or TorrentFlags.PAUSED
        if (superSeeding) flags = flags or TorrentFlags.SUPER_SEEDING
        // trackers + tiers preserved in announce order (libtorrent write_resume_data's "trackers"/"tier")
        val trackerUrls = announceEntries.map { it.url }
        val trackerTiers = announceEntries.map { it.tier }
        // currently-connected reachable peers, for warm reconnect on next run.
        val peerEndpoints = peers.values.mapNotNull { ctx ->
            val r = ctx.reachable ?: return@mapNotNull null
            PeerAddress.parseOrNull(r.host)?.let { Endpoint(it, r.port) }
        }
        AddTorrentParams(
            ti = torrent,
            infoSection = torrent.infoBytes,
            infoHashV1 = torrent.infoHashV1,
            infoHashV2 = torrent.infoHashV2,
            name = torrent.name,
            savePath = savePath,
            trackers = trackerUrls,
            trackerTiers = trackerTiers,
            peers = peerEndpoints,
            havePieces = haveBf,
            verifiedPieces = haveBf,
            unfinishedPieces = unfinished,
            filePriorities = filePriorities.toList(),
            piecePriorities = (0 until numPieces).map { picker.piecePriority(it) },
            maxConnections = maxPeers,
            maxUploads = uploadSlots,
            totalUploaded = uploadedBytes,
            totalDownloaded = downloadedBytes,
            completeSent = completeSent,
            flags = flags,
        )
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
            if (pieceHashMatches(i)) {
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

    /**
     * Hash-verify piece [i] against the torrent's hashes: v1 (SHA-1 of the piece) when a v1
     * hash exists (v1 / hybrid), otherwise v2 — recompute the piece's merkle root from its
     * 16 KiB-block SHA-256 leaves and compare to the published piece hash (BEP-52). For a
     * multi-piece file the leaves are padded to blocks-per-piece with the zero block hash.
     */
    private suspend fun pieceHashMatches(i: Int): Boolean {
        val hashes = disk.hashPiece(i)
        torrent.pieceHashV1(i)?.let { return hashes.v1 == it }
        return verifyPieceV2(i, hashes.v2Leaves)
    }

    /**
     * Verify piece [i] against its v2 merkle data. The owning file's [MerkleTree] is used
     * incrementally: each 16 KiB block leaf is folded into the tree via [MerkleTree.setBlock]
     * and checked against the already-known piece-layer root (loaded from the `.torrent`'s
     * `piece layers` or fetched via [HashPicker]). For single-piece files the piece root *is*
     * the file root, so we compare the recomputed root to [TorrentInfo.pieceHashV2] directly.
     */
    private fun verifyPieceV2(i: Int, leaves: List<Sha256Hash>): Boolean {
        val fidx = torrent.storage.fileForPiece(i)
        if (fidx < 0) return false
        if (torrent.storage.isPadFile(fidx)) return true // pad pieces carry no data to verify
        val tree = merkleTrees[fidx]
        if (tree == null) {
            // single-piece file (or no tree): the published piece hash is the whole answer.
            val v2 = torrent.pieceHashV2(i) ?: return false
            return computePieceRootV2(leaves) == v2
        }
        val pieceInFile = i - torrent.storage.firstPieceOf(fidx)
        if (pieceInFile < 0 || pieceInFile >= tree.numPieces()) return false
        // need the piece-layer root present to verify against; if absent we can't yet.
        if (!tree.hasNode(tree.pieceLayerStart() + pieceInFile)) {
            // fall back: maybe the published piece hash is directly available (piece layers loaded)
            val v2 = torrent.pieceHashV2(i) ?: return false
            return computePieceRootV2(leaves) == v2
        }
        // fold each block leaf in; verifies against the known piece root.
        val firstBlock = pieceInFile * tree.blocksPerPiece()
        var ok = true
        for ((b, h) in leaves.withIndex()) {
            val blockIndex = firstBlock + b
            if (blockIndex >= tree.numBlocks) break
            when (tree.setBlock(blockIndex, h)) {
                MerkleTree.SetBlockResult.OK, MerkleTree.SetBlockResult.UNKNOWN -> {}
                MerkleTree.SetBlockResult.HASH_FAILED,
                MerkleTree.SetBlockResult.BLOCK_HASH_FAILED -> ok = false
            }
        }
        return ok && tree.isPieceVerified(pieceInFile)
    }

    private fun computePieceRootV2(leaves: List<Sha256Hash>): Sha256Hash {
        val blocksPerPiece = torrent.pieceLength / blockSize
        val padded = if (numPieces > 1 && leaves.size < blocksPerPiece) {
            leaves.toMutableList().apply { while (size < blocksPerPiece) add(ZERO256) }
        } else {
            leaves
        }
        return Merkle.merkleRoot(padded, ZERO256)
    }

    /** Connect to up to [maxPeers] of the given endpoints, each on its own coroutine. */
    fun connect(peers: List<PeerEndpoint>) {
        if (paused) return
        ensureTick()
        for (peer in peers.distinct().take(maxPeers)) scope.launch { runPeer(peer) }
    }

    /**
     * Pause: stop requesting/serving and disconnect all peers. Idempotent. Sends `event=stopped`
     * to the trackers and flushes the disk so partial-piece progress is durable.
     */
    suspend fun pause() {
        val wasPaused = paused
        val closers = lock.withLock {
            paused = true
            peers.values.map { it.close }
        }
        setState(TorrentState.PAUSED)
        closers.forEach { runCatching { it() } }
        runCatching { disk.flush() }
        if (!wasPaused) runCatching { announceToAllTrackers(TrackerEvent.STOPPED) } // notify trackers
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

    /**
     * Graceful teardown: send `event=stopped` to the trackers, stop the tick, disconnect peers,
     * then flush and close the disk layer. The suspend complement of [stop] for a clean shutdown
     * (the port of `torrent::abort` + the `disk_io::release_files`/`close` path). Idempotent.
     */
    suspend fun shutdown() {
        val closers = lock.withLock {
            paused = true
            peers.values.map { it.close }
        }
        runCatching { announceToAllTrackers(TrackerEvent.STOPPED) }
        stop()
        closers.forEach { runCatching { it() } }
        runCatching { disk.flush() }
        runCatching { disk.close() }
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

    /**
     * Announce [event] across all tiers with BEP-12 failover. Within each tier the trackers are
     * tried in order; the first one that succeeds satisfies the tier (and is promoted to the
     * front for next time), and the remaining trackers in that tier are skipped. A failing
     * tracker falls through to the next in its tier and is rescheduled with exponential back-off.
     * Each [AnnounceEntry]'s next-announce instant is updated from the response interval (floored
     * by `min_interval`). Tracker reply/error alerts are emitted at each attempt.
     */
    private suspend fun announceToAllTrackers(event: TrackerEvent): List<PeerEndpoint> {
        val out = ArrayList<PeerEndpoint>()
        var udp: UdpTracker? = null
        val now = nowSecs()
        // group by tier, lowest tier first (BEP-12). `toSortedMap` is JVM-only, so sort the
        // entries explicitly to stay commonMain/Native-safe.
        val byTier = announceEntries.groupBy { it.tier }.entries.sortedBy { it.key }
        for ((_, tier) in byTier) {
            var tierSatisfied = false
            for (entry in tier) {
                // for periodic NONE re-announce, skip trackers not yet due
                if (event == TrackerEvent.NONE && entry.nextAnnounceEpochSecs > now) continue
                // BEP-12: once a tracker in this tier worked, don't hit the rest of the tier
                if (tierSatisfied && event == TrackerEvent.NONE) break
                val resp = announceOne(entry, event, udp)
                udp = resp.second
                val r = resp.first
                if (r != null) {
                    out.addAll(r.peers)
                    entry.markWorking(now, r.interval, r.minInterval, r.trackerId)
                    if (entry.lastEvent != TrackerEvent.STARTED) entry.startSent = true
                    entry.lastEvent = event
                    if (event == TrackerEvent.COMPLETED) entry.completeSent = true
                    alert(TrackerReplyAlert(torrent.name, entry.url, "", r.peers.size))
                    tierSatisfied = true
                    // promote the working tracker to the front of its tier (libtorrent deprioritizes
                    // failing ones); we keep order stable but mark working so it's tried first.
                } else {
                    entry.markFailed(now)
                    alert(
                        TrackerErrorAlert(
                            torrent.name, entry.url, "", entry.fails,
                            errorMessage = "announce failed",
                        ),
                    )
                }
            }
        }
        return out
    }

    /**
     * Announce a single [entry]. Returns the response (or null on failure) and the UDP tracker
     * to reuse (created lazily on first udp:// URL). HTTP and UDP are dispatched by scheme.
     */
    private suspend fun announceOne(
        entry: AnnounceEntry,
        event: TrackerEvent,
        existingUdp: UdpTracker?,
    ): Pair<AnnounceResponse?, UdpTracker?> {
        val req = AnnounceRequest(
            infoHash = infoHash,
            peerId = peerId,
            port = listenPort,
            uploaded = uploadedBytes,
            downloaded = downloadedBytes,
            left = torrent.totalSize - downloadedBytes,
            event = event,
            numWant = maxPeers,
            trackerId = entry.trackerId,
        )
        var udp = existingUdp
        return try {
            val resp = when {
                entry.url.startsWith("http") -> httpTracker?.announce(entry.url, req)
                entry.url.startsWith("udp") -> {
                    if (udp == null) udp = UdpTracker(network.bindUdp())
                    val hp = parseHostPort(entry.url) ?: return null to udp
                    udp.announce(hp.first, hp.second, req)
                }
                else -> null
            }
            resp to udp
        } catch (_: Exception) {
            null to udp
        }
    }

    /**
     * The maintenance-tick re-announce: announce `event=none` to any tracker whose scheduled
     * next-announce instant has arrived, and connect to whatever peers come back. Sends
     * `event=completed` exactly once across the session when the torrent first finishes.
     */
    private suspend fun maybeReannounce() {
        if (paused) return
        val seeding = isSeedingLocked()
        // send completed exactly once on finish (guarded by completeSent)
        if (seeding && !completeSent) {
            completeSent = true
            connect(announceToAllTrackers(TrackerEvent.COMPLETED))
            return
        }
        val now = nowSecs()
        val due = announceEntries.any { it.nextAnnounceEpochSecs <= now }
        if (due) connect(announceToAllTrackers(TrackerEvent.NONE))
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
        var runPex = false
        var stopSeeding = false
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
            runPex = tickCounter % pexIntervalTicks == 0

            // share-ratio / seed-time limits: pause once a seed hits its cap
            if (state == TorrentState.SEEDING) {
                seedingTicks++
                stopSeeding = SeedingLimits.shouldStopSeeding(
                    uploaded = uploadedBytes,
                    downloaded = downloadedBytes,
                    totalSize = torrent.totalSize,
                    seedingSeconds = seedingTicks.toLong() * tickIntervalMs / 1000,
                    ratioLimitPerMille = shareRatioLimit,
                    seedTimeLimitSeconds = seedTimeLimitSeconds,
                )
            } else {
                seedingTicks = 0
            }
        }
        for ((ctx, blocks) in sends) sendRequests(ctx, blocks)
        for ((pc, b, len) in cancels) runCatching { pc.sendCancel(b.pieceIndex, b.blockIndex * blockSize, len) }
        if (cancels.isNotEmpty()) snapshotPeers().forEach { maybeRequest(it) } // re-pick the freed blocks
        if (sendKeepAlives) snapshotPeers().forEach { runCatching { it.pc.sendKeepAlive() } }
        // never let a throw here kill the maintenance tick (the loop would exit permanently)
        if (runChoke) runCatching { runChokeRound() }
        if (runPex) runCatching { runPexRound() }
        // periodic tracker re-announce: any tracker whose interval has elapsed, plus the
        // once-only event=completed when this torrent first finishes.
        runCatching { maybeReannounce() }
        if (stopSeeding) runCatching { pause() } // reached the share-ratio / seed-time limit
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
        // IpFilter.access throws on a non-literal host; an unfilterable host is "allowed"
        val filter = ipFilter
        if (filter != null && runCatching { filter.access(peer.host) }.getOrDefault(0) == IpFilter.BLOCKED) {
            return // blocklisted peer
        }
        lock.withLock { attemptedEndpoints.add(peer) } // dedup repeat tracker/DHT/PEX offers
        if (connections != null && !connections.tryAcquire()) return // connections_limit
        var acquired = true
        try {
            // out_enc_policy: try MSE/PE first when allowed; PE_ENABLED falls back to
            // plaintext (one reconnect) if a reachable peer rejects encryption.
            if (outEncPolicy != EncPolicy.PE_DISABLED) {
                val transport = dial(peer) ?: return // peer unreachable
                val (raw, close) = transport
                val secured = try {
                    Mse.initiate(raw, infoHash)
                } catch (_: Exception) {
                    null
                }
                if (secured != null) {
                    val pc = PeerConnection(secured, infoHash, peerId, numPieces, hasV2 = hasV2)
                    acquired = false
                    runConnectedPeer(pc, close, reachable = peer, releaseBudget = true) { pc.performHandshake() }
                    return
                }
                runCatching { close() } // MSE rejected — drop this attempt
                if (outEncPolicy == EncPolicy.PE_FORCED) return // plaintext not allowed
            }

            // plaintext: µTP first when available, TCP as the fallback (outgoing_utp/outgoing_tcp)
            val transport = dial(peer)
            if (transport == null) {
                connections?.release(); acquired = false
                return
            }
            val (stream, close) = transport
            val pc = PeerConnection(stream, infoHash, peerId, numPieces, hasV2 = hasV2)
            acquired = false // runConnectedPeer's finally releases from here on
            runConnectedPeer(pc, close, reachable = peer, releaseBudget = true) { pc.performHandshake() }
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
        val pc = PeerConnection(stream, infoHash, peerId, numPieces, hasV2 = hasV2)
        runConnectedPeer(pc, close, releaseBudget = true) { pc.completeInboundHandshake(remote) }
    }

    private suspend fun runConnectedPeer(
        pc: PeerConnection,
        close: suspend () -> Unit,
        reachable: PeerEndpoint? = null,
        releaseBudget: Boolean = false,
        handshake: suspend () -> Unit,
    ) {
        if (paused) {
            runCatching { close() }
            if (releaseBudget) connections?.release()
            return
        }
        val ctx = PeerContext(pc, close, reachable)
        try {
            handshake()
            // self-connect / duplicate-peer-id rejection (errors::self_connection /
            // errors::duplicate_peer_id). PeerConnection only exposes the data; the policy is ours.
            val rejectId = lock.withLock {
                pc.isSelfConnect || peers.keys.any { it !== pc && it.remotePeerId != null && it.remotePeerId == pc.remotePeerId }
            }
            if (rejectId) {
                pc.disconnect()
                return // finally closes the transport + releases the budget
            }
            // per-torrent connection cap (max_connections): reject once we're full
            val added = lock.withLock { if (peers.size >= maxPeers) false else { peers[pc] = ctx; true } }
            if (!added) return // finally closes the transport + releases the budget
            alert(
                PeerConnectAlert(
                    torrent.name, endpointLabel(ctx),
                    direction = if (reachable != null) PeerConnectDirection.OUT else PeerConnectDirection.IN,
                ),
            )
            // BEP-5: advertise our DHT listen port to a DHT-capable peer.
            if (pc.supportsDht && listenPort in 1..0xffff) runCatching { pc.sendPort(listenPort) }
            // super-seeding: advertise "have none" then hand out a single rarest piece;
            // otherwise send our real bitfield (snapshot under the lock so it can't change
            // mid-encode).
            val superSeed = lock.withLock { superSeeding && picker.isSeeding() }
            if (superSeed) {
                pc.sendBitfield(Bitfield(numPieces)) // have-none
                val give = lock.withLock { pickSuperSeedLocked(ctx) }
                if (give >= 0) runCatching { pc.sendHave(give) }
            } else {
                val bf = lock.withLock { if (picker.numHave() > 0) Bitfield.fromBytes(have.data(), numPieces) else null }
                if (bf != null) pc.sendBitfield(bf)
            }
            pc.sendInterested()
            pc.sendExtended(
                ExtensionHandshake.HANDSHAKE_ID,
                ExtensionHandshake.build(metadataSize = torrent.infoBytes.size.toLong(), listenPort = listenPort),
            )
            pc.receiveLoop { msg -> handleMessage(ctx, msg) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // structured-concurrency cancellation must propagate (rethrown after cleanup
            // in finally); never swallow it.
            throw e
        } catch (_: Exception) {
            // disconnect / protocol error
        } finally {
            // per-peer cleanup runs even on cancellation/exception: drop the peer from every
            // index and release the picker refcounts it held.
            val wasMember = lock.withLock {
                val present = peers.remove(pc) != null
                if (currentOptimistic === ctx) currentOptimistic = null
                if (ctx.superSeedPiece >= 0) superSeedAssigned.remove(ctx.superSeedPiece)
                // drop this peer from any piece-blame sets
                val it = pieceContributors.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    e.value.remove(ctx)
                    if (e.value.isEmpty()) it.remove()
                }
                picker.clearPeer(pc)
                picker.decRefcount(pc.theirBitfield, pc)
                present
            }
            pc.disconnect()
            runCatching { close() }
            if (releaseBudget) connections?.release()
            if (wasMember) {
                alert(PeerDisconnectedAlert(torrent.name, endpointLabel(ctx), errorMessage = "connection closed"))
            }
        }
    }

    private suspend fun handleMessage(ctx: PeerContext, msg: PeerMessage) {
        when (msg) {
            is PeerMessage.Bitfield -> { lock.withLock { picker.incRefcount(msg.bitfield, ctx.pc) }; maybeRequest(ctx) }
            is PeerMessage.Have -> {
                lock.withLock { picker.incRefcount(msg.piece, ctx.pc) }
                // super-seeding: the peer got the piece we gave it → hand out the next one
                if (superSeeding && msg.piece == ctx.superSeedPiece) {
                    val give = lock.withLock { pickSuperSeedLocked(ctx) }
                    if (give >= 0) runCatching { ctx.pc.sendHave(give) }
                }
                maybeRequest(ctx)
            }
            PeerMessage.HaveAll -> { lock.withLock { picker.incRefcountAll(ctx.pc) }; maybeRequest(ctx) }
            PeerMessage.Unchoke -> maybeRequest(ctx)
            is PeerMessage.Piece -> onBlockReceived(ctx, msg)
            PeerMessage.Interested -> onInterested(ctx)
            is PeerMessage.Request -> serveBlock(ctx, msg)
            is PeerMessage.Extended -> handleExtended(ctx, msg)
            is PeerMessage.RejectRequest -> onRejectRequest(ctx, msg)
            is PeerMessage.AllowedFast -> {
                if (msg.piece in 0 until numPieces) {
                    lock.withLock { ctx.allowedFast.add(msg.piece) }
                    maybeRequest(ctx) // we may now request this piece even while choked
                }
            }
            is PeerMessage.Port -> {
                // BEP-5: feed a DHT-capable peer's advertised UDP node to the engine DHT.
                if (msg.port in 1..0xffff) ctx.reachable?.let { onDhtPort?.invoke(it.host, msg.port) }
            }
            is PeerMessage.Hashes -> onHashes(ctx, msg)
            is PeerMessage.HashReject -> hashPicker?.onHashReject(msg)
            is PeerMessage.HashRequest -> serveHashRequest(ctx, msg)
            else -> {} // choke/cancel/suggest handled/ignored
        }
    }

    /**
     * Inbound `hash_request` (BEP-52, id 21): a peer wants merkle hashes from our tree.
     * Mirrors `bt_peer_connection::on_hash_request` + `write_hashes` / `write_hash_reject`:
     * locate the file by its `pieces root`, extract the requested run plus uncle proof from
     * that file's [MerkleTree] via [MerkleTree.getHashes], and reply with `hashes`. When we
     * cannot answer — not a v2 torrent, unknown root, or an incomplete tree — fall back to a
     * protocol-valid `hash_reject`. We only ever serve hashes actually present in our tree
     * (so they verify against the known root); we never fabricate hashes.
     */
    private suspend fun serveHashRequest(ctx: PeerContext, msg: PeerMessage.HashRequest) {
        val hashes = if (hasV2) extractHashesFor(msg) else null
        runCatching {
            if (hashes != null) {
                ctx.pc.sendHashes(
                    PeerMessage.Hashes(
                        msg.piecesRoot, msg.baseLayer, msg.index, msg.length, msg.proofLayers,
                        hashes.map { it.toByteArray() },
                    ),
                )
            } else {
                ctx.pc.sendHashReject(
                    PeerMessage.HashReject(msg.piecesRoot, msg.baseLayer, msg.index, msg.length, msg.proofLayers),
                )
            }
        }
    }

    /**
     * Resolves a `hash_request` against our merkle trees, returning the run + proof hashes to
     * put in a `hashes` reply, or null if we cannot fully answer (matching libtorrent's empty
     * return → reject). Only the block layer (`base == 0`) and piece layer
     * (`base == blocksPerPieceLog`) are answerable, as in `hash_picker`/`on_hash_request`.
     */
    private fun extractHashesFor(msg: PeerMessage.HashRequest): List<Sha256Hash>? {
        if (msg.piecesRoot.size != Merkle.HASH_SIZE) return null
        val target = Digest32.sha256(msg.piecesRoot)
        var fidx = -1
        for (i in 0 until torrent.numFiles) {
            if (torrent.files[i].piecesRoot == target) { fidx = i; break }
        }
        if (fidx < 0) return null
        val tree = merkleTrees[fidx] ?: return null
        // only the block layer and the piece layer are valid request bases (libtorrent).
        if (msg.baseLayer != 0 && msg.baseLayer != tree.blocksPerPieceLog) return null
        return tree.getHashes(msg.baseLayer, msg.index, msg.length, msg.proofLayers)
    }

    /**
     * For a v2 peer, request the merkle hashes needed to verify the pieces we are about to
     * download from it (BEP-52). Tied to [toSend] so the peer provably has those pieces (the
     * picker chose them from the peer's bitfield); [HashPicker] dedups via its outstanding set.
     */
    private suspend fun maybeRequestHashes(ctx: PeerContext, toSend: List<PieceBlock>) {
        val picker = hashPicker ?: return
        if (!hasV2 || !ctx.pc.supportsV2 || toSend.isEmpty()) return
        val pieces = HashSet<Int>()
        for (b in toSend) pieces.add(b.pieceIndex)
        for (p in pieces) {
            if (!picker.needHashes(p) || picker.hasOutstanding(p)) continue
            val req = picker.buildHashRequest(p) ?: continue
            runCatching { ctx.pc.sendHashRequest(req) }
        }
    }

    /**
     * Inbound `reject_request` (BEP-6): the peer refused a block we asked for. Re-queue it
     * immediately so the picker can hand it to another peer (`peer_connection::incoming_reject_request`).
     */
    private suspend fun onRejectRequest(ctx: PeerContext, msg: PeerMessage.RejectRequest) {
        if (msg.begin % blockSize != 0) return
        val block = PieceBlock(msg.piece, msg.begin / blockSize)
        lock.withLock {
            if (ctx.inflight.remove(block) != null) picker.abortDownload(block, ctx.pc)
        }
        maybeRequest(ctx)
    }

    /** Feed a received `hashes` (BEP-52) response into the [HashPicker]; on success re-pick. */
    private suspend fun onHashes(ctx: PeerContext, msg: PeerMessage.Hashes) {
        val picker = hashPicker ?: return
        if (picker.onHashes(msg)) maybeRequest(ctx)
    }

    /**
     * Super-seeding piece selection (`torrent::superseed_picking`): release [ctx]'s previous
     * piece, then pick the rarest piece the peer lacks that isn't already handed to another
     * peer, reserve it, and return it (or -1 if none remain). Hold [lock].
     */
    private fun pickSuperSeedLocked(ctx: PeerContext): Int {
        if (ctx.superSeedPiece >= 0) {
            superSeedAssigned.remove(ctx.superSeedPiece)
            ctx.superSeedPiece = -1
        }
        val their = ctx.pc.theirBitfield
        var best = -1
        var bestAvail = Int.MAX_VALUE
        for (p in 0 until numPieces) {
            if (their.getBit(p) || p in superSeedAssigned) continue
            val avail = picker.availability(p)
            if (avail < bestAvail) { bestAvail = avail; best = p }
        }
        if (best >= 0) {
            superSeedAssigned.add(best)
            ctx.superSeedPiece = best
        }
        return best
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
        // --- inbound REQUEST validation (peer_connection::on_request) ---
        // out-of-range piece, negative/zero/oversized length, or a window that runs past the
        // piece are protocol violations — refuse and (for the bad ones) disconnect.
        if (request.piece < 0 || request.piece >= numPieces ||
            request.begin < 0 || request.length <= 0 ||
            request.length > blockSize || request.length > MAX_REQUEST_LENGTH
        ) {
            ctx.pc.disconnect()
            runCatching { ctx.close() }
            alert(PeerErrorAlert(torrent.name, endpointLabel(ctx), errorMessage = "invalid request"))
            return
        }
        val pieceSize = torrent.storage.pieceSize(request.piece)
        if (request.begin + request.length > pieceSize) {
            ctx.pc.disconnect()
            runCatching { ctx.close() }
            alert(PeerErrorAlert(torrent.name, endpointLabel(ctx), errorMessage = "request past end of piece"))
            return
        }

        // per-peer cap on outstanding inbound requests — drop a flooding peer (request_queue_size).
        val flood = lock.withLock {
            ctx.inboundRequests++
            ctx.inboundRequests > MAX_INBOUND_REQUESTS
        }
        if (flood) {
            ctx.pc.disconnect()
            runCatching { ctx.close() }
            alert(PeerErrorAlert(torrent.name, endpointLabel(ctx), errorMessage = "request flood"))
            return
        }

        // choked or we don't have it → refuse explicitly (BEP-6 reject) instead of silent drop.
        val haveIt = lock.withLock { picker.havePiece(request.piece) }
        if (ctx.amChoking || !haveIt) {
            lock.withLock { if (ctx.inboundRequests > 0) ctx.inboundRequests-- }
            runCatching { ctx.pc.sendRejectRequest(request.piece, request.begin, request.length) }
            return
        }
        val block = try {
            disk.read(request.piece, request.begin, request.length)
        } catch (e: Exception) {
            lock.withLock { if (ctx.inboundRequests > 0) ctx.inboundRequests-- }
            alert(TorrentErrorAlert(torrent.name, errorMessage = "disk read failed: ${e.message}"))
            runCatching { ctx.pc.sendRejectRequest(request.piece, request.begin, request.length) }
            return
        }
        limiter?.acquireUpload(block.size, torrentBandwidth) // upload rate limit
        ctx.pc.sendPiece(request.piece, request.begin, block)
        lock.withLock {
            if (ctx.inboundRequests > 0) ctx.inboundRequests--
            uploadedBytes += block.size
            ctx.roundUploaded += block.size
            ctx.stat.sentBytes(block.size, PIECE_MESSAGE_OVERHEAD)
        }
    }

    /** A short endpoint label for alerts; the dialed endpoint when known, else the wire address. */
    private fun endpointLabel(ctx: PeerContext): String =
        ctx.reachable?.let { "${it.host}:${it.port}" } ?: ctx.pc.remoteAddress

    private suspend fun onBlockReceived(ctx: PeerContext, msg: PeerMessage.Piece) {
        // --- received PIECE validation (peer_connection::incoming_piece) ---
        // piece in range, block-aligned begin, expected block length, and the (piece,begin)
        // must be an outstanding request to THIS peer. Otherwise ignore (no finish, no write).
        if (msg.piece < 0 || msg.piece >= numPieces || msg.begin < 0 || msg.begin % blockSize != 0) return
        val block = PieceBlock(msg.piece, msg.begin / blockSize)
        if (block.blockIndex < 0 || block.blockIndex >= picker.blocksInPiece(msg.piece)) return
        if (msg.block.size != blockLength(block)) return
        val wasOutstanding = lock.withLock { block in ctx.inflight }
        if (!wasOutstanding) return // unsolicited or already-cancelled block — drop it

        // download rate limit: delaying the (sequential) receive loop back-pressures the sender
        limiter?.acquireDownload(msg.block.size, torrentBandwidth)

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
                // claim the block as WRITING under the lock so a concurrent end-game
                // delivery of the same block sees it taken (isDownloaded) and is dropped —
                // first delivery wins, only one disk.write happens
                picker.markAsWriting(block, ctx.pc)
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

        // disk-write safety: only mark the block FINISHED *after* the write succeeds. On a
        // write failure re-queue it (abortDownload) so the picker hands it out again, and
        // surface a storage error — never advance the piece on un-persisted data.
        try {
            disk.write(msg.piece, msg.begin, msg.block)
        } catch (e: Exception) {
            lock.withLock { picker.abortDownload(block, ctx.pc) }
            alert(TorrentErrorAlert(torrent.name, errorMessage = "disk write failed: ${e.message}"))
            maybeRequest(ctx)
            return
        }
        val complete: Boolean
        lock.withLock {
            picker.markAsFinished(block, ctx.pc)
            // remember this peer contributed to the piece, for hash-failure blame.
            pieceContributors.getOrPut(msg.piece) { HashSet() }.add(ctx)
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
        if (paused) return
        // While choked we may still request pieces the peer granted via allowed_fast (BEP-6),
        // provided we still need them. Otherwise a choked peer gets nothing.
        val choked = pc.theirChoking
        val toSend: List<PieceBlock>
        lock.withLock {
            if (picker.isSeeding()) return
            if (choked && ctx.allowedFast.none { !picker.havePiece(it) }) return
            toSend = pickRequestsLocked(ctx, effectiveQueueSize(ctx), allowedFastOnly = choked)
        }
        maybeRequestHashes(ctx, toSend) // v2: fetch merkle hashes for pieces we're about to request
        sendRequests(ctx, toSend)
    }

    /**
     * The pick-and-mark core of [maybeRequest]; the caller holds [lock] and sends. When
     * [allowedFastOnly] is set (the peer is choking us but granted `allowed_fast` pieces) only
     * blocks belonging to a granted allowed-fast piece are picked (BEP-6).
     */
    private fun pickRequestsLocked(
        ctx: PeerContext,
        queueCap: Int,
        allowedFastOnly: Boolean = false,
    ): List<PieceBlock> {
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
            if (allowedFastOnly && b.pieceIndex !in ctx.allowedFast) continue
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
        if (pieceHashMatches(piece)) {
            val (notify, wasSeeding) = lock.withLock {
                picker.piecePassed(piece)
                have.setBit(piece)
                pieceContributors.remove(piece) // good piece: clear blame bookkeeping
                peers.keys.toList() to picker.isSeeding()
            }
            runCatching { disk.flush() } // persist the completed piece (piece_finished → flush)
            for (p in notify) runCatching { p.sendHave(piece) }
            onPieceVerified?.invoke(piece)
            alert(PieceFinishedAlert(torrent.name, piece))
            if (wasSeeding) {
                setState(TorrentState.SEEDING)
                alert(TorrentFinishedAlert(torrent.name))
            }
        } else {
            // bad data — blame contributing peers, ban repeat offenders, re-download from others.
            alert(HashFailedAlert(torrent.name, piece))
            val toBan = lock.withLock {
                picker.weDontHave(piece)
                val contribs = pieceContributors.remove(piece) ?: emptySet()
                val banList = ArrayList<PeerContext>()
                for (c in contribs) {
                    c.hashFailCount++
                    if (c.hashFailCount >= MAX_HASH_FAILS_BEFORE_BAN && !c.banned) {
                        c.banned = true
                        banList.add(c)
                    }
                }
                banList
            }
            for (c in toBan) {
                c.pc.disconnect()
                runCatching { c.close() }
                alert(PeerBanAlert(torrent.name, endpointLabel(c)))
            }
        }
    }

    // --- extension protocol (BEP-10) + metadata serving (BEP-9) -------------------

    private suspend fun handleExtended(ctx: PeerContext, msg: PeerMessage.Extended) {
        when (msg.extId) {
            ExtensionHandshake.HANDSHAKE_ID ->
                ExtensionHandshake.parse(msg.payload)?.let { p ->
                    p.utMetadataId?.let { ctx.metadataId = it }
                    p.utPexId?.let { ctx.pexId = it }
                }
            // ut_pex arrives under the id we advertised (UT_PEX_ID): gossip new peers in (BEP-11)
            ExtensionHandshake.UT_PEX_ID -> ingestPex(msg.payload)
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

    // --- peer exchange (BEP-11 ut_pex) --------------------------------------------

    /**
     * Ingest a received `ut_pex` message: dial the freshly-advertised peers (the
     * `add_peer` side of `ut_pex_peer_plugin::on_extended`). New endpoints are deduped
     * against everything we've already tried and capped by [maxPeers]; the dropped list
     * is informational only (we don't force-disconnect on a peer's say-so).
     */
    private suspend fun ingestPex(payload: ByteArray) {
        if (paused) return
        val m = UtPex.parse(payload) ?: return
        val advertised = m.added.map { PeerEndpoint(it.host, it.port) }
        val fresh = lock.withLock {
            if (peers.size >= maxPeers) return
            advertised.filter { attemptedEndpoints.add(it) }
        }
        if (fresh.isNotEmpty()) connect(fresh)
    }

    /**
     * One PEX round (`ut_pex_plugin::tick`, ~once a minute): tell each peer that
     * advertised `ut_pex` about the *connectable* peers it hasn't heard from us yet.
     * Only outbound peers (with a known [PeerContext.reachable] listen endpoint) are
     * gossiped; inbound peers' listen ports are unknown so we never relay them. Built
     * under [lock], sent outside it.
     */
    private suspend fun runPexRound() {
        val sends = ArrayList<Triple<PeerContext, Int, ByteArray>>()
        lock.withLock {
            val reachableAll = peers.values.mapNotNull { it.reachable }.toHashSet()
            for (ctx in peers.values) {
                val pid = ctx.pexId ?: continue
                val add = reachableAll.filter { it != ctx.reachable && it !in ctx.pexAdvertised }
                if (add.isEmpty()) continue
                val pexPeers = add.mapNotNull { UtPex.peerFromIpv4(it.host, it.port) }
                if (pexPeers.isEmpty()) continue
                ctx.pexAdvertised.addAll(add)
                sends.add(Triple(ctx, pid, UtPex.encode(pexPeers, includeEmpty = false)))
            }
        }
        for ((ctx, pid, bytes) in sends) runCatching { ctx.pc.sendExtended(pid, bytes) }
    }

    // --- helpers ------------------------------------------------------------------

    /** Emit an alert through [onAlert] if set; never throws (a bad sink can't break the engine). */
    private fun alert(a: Alert) {
        val sink = onAlert ?: return
        runCatching { sink(a) }
    }

    private suspend fun snapshotPeers(): List<PeerContext> = lock.withLock { peers.values.toList() }

    private suspend fun isSeedingLocked(): Boolean = lock.withLock { picker.isSeeding() }

    private fun setState(s: TorrentState) {
        if (state != s) {
            state = s
            onStateChanged?.invoke(s)
            onStateChangedInternal?.invoke(s)
            // finishing or pausing makes the on-disk resume data stale — ask to re-save
            if (s == TorrentState.SEEDING || s == TorrentState.PAUSED) onNeedSaveResume?.invoke()
        }
    }

    private fun blockLength(b: PieceBlock): Int {
        val pieceSize = torrent.storage.pieceSize(b.pieceIndex)
        return minOf(blockSize, pieceSize - b.blockIndex * blockSize)
    }

    /** All mutable per-peer state. Lives in [peers] under [lock]. */
    private class PeerContext(
        val pc: PeerConnection,
        val close: suspend () -> Unit,
        /** This peer's reachable (connectable) endpoint, when known — the address we dialed
         *  for outbound peers; null for inbound peers (their listen port is unknown). Only
         *  reachable endpoints are gossiped onward via PEX. */
        val reachable: PeerEndpoint? = null,
    ) {
        /** Blocks requested from this peer → age in ticks (for request timeouts). */
        val inflight = LinkedHashMap<PieceBlock, Int>()
        var metadataId: Int? = null

        /** The id this peer wants `ut_pex` messages sent under (their handshake `m["ut_pex"]`). */
        var pexId: Int? = null

        /** Reachable endpoints we've already advertised to this peer (PEX delta tracking). */
        val pexAdvertised = HashSet<PeerEndpoint>()
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

        /** Super-seeding: the single piece currently advertised to this peer (`m_superseed_piece`), or -1. */
        var superSeedPiece: Int = -1

        /** Pieces this peer has granted us via `allowed_fast` (BEP-6) — requestable while choked. */
        val allowedFast = HashSet<Int>()

        /** Outstanding inbound `request`s this peer has pending against us (flood guard). */
        var inboundRequests: Int = 0

        /** Failing-hash trust: bumped on every piece this peer contributed to that failed its hash. */
        var hashFailCount: Int = 0

        /** True once this peer has been banned for repeated corrupt data. */
        var banned: Boolean = false
    }

    companion object {
        const val BLOCK_SIZE = 16 * 1024

        /** The all-zero 32-byte block hash used to pad a v2 merkle leaf layer. */
        private val ZERO256: Sha256Hash = Digest32.zeros(Digest32.SHA256_SIZE)
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

        /** PEX gossip cadence — libtorrent's `ut_pex_plugin` ticks once a minute (60 s min). */
        const val PEX_INTERVAL_SECONDS = 60

        /** How long an outgoing µTP SYN may wait before falling back to TCP. */
        const val UTP_CONNECT_TIMEOUT_MS = 3000L

        /**
         * Hard ceiling on the byte length of an inbound `request`. libtorrent rejects requests
         * larger than a single block; we bound by 128 KiB defensively.
         */
        const val MAX_REQUEST_LENGTH = 128 * 1024

        /**
         * Max outstanding inbound `request`s a single peer may have pending against us before it
         * is treated as flooding and disconnected (libtorrent's in-request-queue cap).
         */
        const val MAX_INBOUND_REQUESTS = 2000

        /** Pieces a peer may contribute to that fail their hash before it is banned. */
        const val MAX_HASH_FAILS_BEFORE_BAN = 3

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
