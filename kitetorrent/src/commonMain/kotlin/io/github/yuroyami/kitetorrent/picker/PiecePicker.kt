package io.github.yuroyami.kitetorrent.picker

import io.github.yuroyami.kitetorrent.Bitfield
import kotlin.random.Random

/**
 * Rarest-first piece selection: a pure-Kotlin port of the **core** of libtorrent's
 * `piece_picker` (src/piece_picker.cpp, include/libtorrent/piece_picker.hpp).
 *
 * The picker decides *which block to request from which peer*. It tracks, per
 * piece:
 *  1. **availability**: how many connected peers have the piece ([incRefcount]/
 *     [decRefcount] driven by HAVE / BITFIELD / disconnect events), plus a seed
 *     counter so seeds don't each bump every piece individually;
 *  2. **priority**: a manually-set 0..7 level ([setPiecePriority]); 0 = filtered
 *     (never downloaded);
 *  3. **block state**: for partially downloaded pieces, the
 *     [BlockState.NONE]/[BlockState.REQUESTED]/[BlockState.WRITING]/[BlockState.FINISHED]
 *     state of every block, and which peer each was requested from;
 *  4. **have**: whether the piece has passed its hash check.
 *
 * [pickPieces] returns blocks in *rarest-first* order: the controlling key is
 * `piece_pos::priority()` (ported verbatim in [effectivePriority]), where a
 * **lower** value is picked first. Availability dominates that value, so a piece
 * only one peer has outranks a piece every peer has; the manual priority and a
 * partial-vs-open adjustment break ties exactly as upstream does.
 *
 * Peers are kept fully opaque: callers identify a peer by any token (`peerId:
 * Any?`), mirroring libtorrent's `torrent_peer*` without dragging in a
 * `peer_connection`.
 *
 * ### Scope (faithful core)
 * Ported: availability + seed accounting, the exact priority formula and its
 * rarest-first / sequential / random ordering, the download-queue bucketing
 * (open / downloading / full / finished / zero-prio) via [updatePieceState], the
 * full block state machine ([markAsDownloading]/[markAsWriting]/[markAsFinished]/
 * [markAsCanceled]/[abortDownload]/[writeFailed]/[restorePiece]), cursors, and the
 * want/have/num_have counters.
 *
 * Deliberately omitted (and noted in the module summary): the `reverse`,
 * `on_parole`, `piece_extent_affinity`, `prefer_contiguous_blocks`/`expand_piece`
 * whole-piece heuristics, suggested-pieces, end-game busy-block stealing, pad
 * files, hashing-job details, and the mutable `m_pieces`/`m_priority_boundaries`
 * swap-list with random in-bucket shuffling. Instead of that swap-list,
 * [pickPieces] computes the pick order by stably sorting the pickable pieces on
 * the same `priority()` key the swap-list encodes. The *ordering semantics* are
 * the same, with far less machinery. Equal-priority ties are broken by piece
 * index (the swap list randomizes them); pass a [Random] to [pickPieces] to
 * restore randomized tie-breaking.
 */
class PiecePicker(
    numPieces: Int,
    blocksPerPiece: Int,
) {
    init {
        require(numPieces >= 0) { "numPieces must be >= 0, was $numPieces" }
        require(blocksPerPiece in 1..MAX_BLOCKS_PER_PIECE) {
            "blocksPerPiece must be in 1..$MAX_BLOCKS_PER_PIECE, was $blocksPerPiece"
        }
    }

    /** Number of pieces tracked. */
    val numPieces: Int = numPieces

    /** Blocks in every piece except possibly the last (see [blocksInLastPiece]). */
    private val blocksPerPiece: Int = blocksPerPiece

    /**
     * Blocks in the final piece. Defaults to a full [blocksPerPiece]; the caller
     * may shrink it via [setBlocksInLastPiece] when the torrent's last piece is
     * short. Kept as a simple knob since this core does not carry byte sizes.
     */
    private var blocksInLastPiece: Int = blocksPerPiece

    // ---- per-piece state (the "m_piece_map") --------------------------------

    /**
     * Availability counter per piece: the number of *non-seed* connected peers
     * that have advertised this piece. Seeds are tracked once in [seeds] instead
     * of being added here. Mirrors `piece_pos::peer_count`.
     */
    private val peerCount: IntArray = IntArray(numPieces)

    /** Manual priority 0..7 per piece; 0 = filtered. Mirrors `piece_pos::piece_priority`. */
    private val piecePriority: IntArray = IntArray(numPieces) { DownloadPriority.DEFAULT_PRIORITY }

    /** Download-queue category per piece (one of the `STATE_*` constants). */
    private val downloadState: IntArray = IntArray(numPieces) { STATE_OPEN }

    /** True once piece `i` has passed its hash check (we "have" it). */
    private val havePiece: BooleanArray = BooleanArray(numPieces)

    /**
     * For every piece in a download-queue bucket (i.e. `downloadState != STATE_OPEN`)
     * this holds its [DownloadingPiece]; `null` otherwise. This is the union of
     * libtorrent's four `m_downloads` vectors, keyed directly by piece index.
     */
    private val downloads: Array<DownloadingPiece?> = arrayOfNulls(numPieces)

    /** The number of connected seeds (peers that have every piece). Mirrors `m_seeds`. */
    private var seeds: Int = 0

    /** Pieces currently in [STATE_DOWNLOADING] (the `m_downloads[piece_downloading]` size). */
    private var numDownloading: Int = 0

    /** Pieces in *any* download bucket (non-null [downloads] entries). */
    private var numDownloadEntries: Int = 0

    // ---- aggregate counters -------------------------------------------------

    /** Number of pieces that have passed the hash check (incl. filtered ones). `m_num_have`. */
    private var numHavePieces: Int = 0

    /** Number of pieces we don't have that are filtered (priority 0). `m_num_filtered`. */
    private var numFiltered: Int = 0

    /** Number of pieces we have that are also filtered. `m_num_have_filtered`. */
    private var numHaveFiltered: Int = 0

    /** Lowest piece index we don't have. `m_cursor`. */
    private var cursor: Int = 0

    /** One past the last piece we don't have. `m_reverse_cursor`. */
    private var reverseCursor: Int = numPieces

    init {
        // Match resize(): advance the cursors past any leading/trailing pieces we
        // already "have or filter". On a fresh picker nothing is had and the
        // default priority is non-zero, so the cursors stay at [0, numPieces).
        recomputeCursorsFromScratch()
    }

    // =========================================================================
    // basic geometry
    // =========================================================================

    /** Blocks in piece [index]: [blocksInLastPiece] for the last piece, else [blocksPerPiece]. */
    fun blocksInPiece(index: Int): Int {
        requirePiece(index)
        return if (index == numPieces - 1) blocksInLastPiece else blocksPerPiece
    }

    /** Blocks in the final piece. Mirrors `piece_picker::blocks_in_last_piece()`. */
    fun blocksInLastPiece(): Int = blocksInLastPiece

    /** Blocks per (non-last) piece. */
    fun blocksPerPiece(): Int = blocksPerPiece

    /**
     * Override the block count of the final piece (for a short last piece). Must
     * be called before any blocks are picked. This core does not derive it from a
     * total byte size; the higher-level torrent layer supplies it.
     */
    fun setBlocksInLastPiece(blocks: Int) {
        require(blocks in 1..blocksPerPiece) { "blocksInLastPiece out of range: $blocks" }
        blocksInLastPiece = blocks
    }

    private fun requirePiece(index: Int) {
        require(index in 0 until numPieces) { "piece index $index out of range [0,$numPieces)" }
    }

    // =========================================================================
    // availability (refcounting)
    // =========================================================================

    /**
     * Increment the availability of [index] for [peerId] (a HAVE message).
     * Mirrors `piece_picker::inc_refcount(piece_index_t, torrent_peer const*)`.
     * The peer token is unused here (libtorrent only uses it for debug refcount
     * sets) but kept in the signature for symmetry with the bitfield overload.
     */
    fun incRefcount(index: Int, peerId: Any? = null) {
        requirePiece(index)
        peerCount[index] += 1
    }

    /**
     * Decrement the availability of [index] (a peer that had it disconnected, or
     * sent dont-have). Mirrors `piece_picker::dec_refcount(piece_index_t, ...)`,
     * including the "break one seed" fix-up: if the per-piece count is already 0
     * but seeds exist, one seed is converted into explicit per-piece counts first
     * so the counter never goes negative.
     */
    fun decRefcount(index: Int, peerId: Any? = null) {
        requirePiece(index)
        if (peerCount[index] == 0) {
            // A seed said "I don't have this piece anymore"; split a seed out.
            check(seeds > 0) { "dec_refcount underflow on piece $index" }
            breakOneSeed()
        }
        check(peerCount[index] > 0) { "dec_refcount underflow on piece $index" }
        peerCount[index] -= 1
    }

    /**
     * Increment availability for every piece in [bitmask] for [peerId] (a BITFIELD
     * message). If the bitmask covers every piece it is treated as a seed and
     * routed through [incRefcountAll]. Mirrors the bitfield `inc_refcount`.
     */
    fun incRefcount(bitmask: Bitfield, peerId: Any? = null) {
        if (bitmask.noneSet()) return
        if (bitmask.size() == numPieces && bitmask.allSet()) {
            incRefcountAll(peerId)
            return
        }
        val n = minOf(bitmask.size(), numPieces)
        for (i in 0 until n) if (bitmask.getBit(i)) peerCount[i] += 1
    }

    /**
     * Decrement availability for every piece in [bitmask] (a peer with this
     * bitfield disconnected). Mirrors the bitfield `dec_refcount`, with the same
     * break-one-seed fix-up per piece.
     */
    fun decRefcount(bitmask: Bitfield, peerId: Any? = null) {
        if (bitmask.noneSet()) return
        if (bitmask.size() == numPieces && bitmask.allSet()) {
            decRefcountAll(peerId)
            return
        }
        val n = minOf(bitmask.size(), numPieces)
        for (i in 0 until n) {
            if (!bitmask.getBit(i)) continue
            if (peerCount[i] == 0) {
                check(seeds > 0) { "dec_refcount underflow on piece $i" }
                breakOneSeed()
            }
            check(peerCount[i] > 0) { "dec_refcount underflow on piece $i" }
            peerCount[i] -= 1
        }
    }

    /**
     * Register a connected seed: bumps the [seeds] counter for every piece at once.
     * Mirrors `inc_refcount_all`. Used when a seed joins the swarm.
     */
    fun incRefcountAll(peerId: Any? = null) {
        seeds += 1
    }

    /**
     * Unregister a seed. If [seeds] is positive it is simply decremented; if it is
     * already 0 (because a former seed was split into per-piece counts) every
     * piece's [peerCount] is decremented instead. Mirrors `dec_refcount_all`.
     */
    fun decRefcountAll(peerId: Any? = null) {
        if (seeds > 0) {
            seeds -= 1
            return
        }
        for (i in 0 until numPieces) {
            check(peerCount[i] > 0) { "dec_refcount_all underflow on piece $i" }
            peerCount[i] -= 1
        }
    }

    /**
     * Convert one seed into explicit per-piece availability. Called when a seed
     * sends a dont-have for a piece whose count is 0. Mirrors `break_one_seed`.
     */
    private fun breakOneSeed() {
        check(seeds > 0) { "break_one_seed with no seeds" }
        seeds -= 1
        for (i in 0 until numPieces) peerCount[i] += 1
    }

    /** Availability of [piece]: connected peers (incl. seeds) that have it. `get_availability`. */
    fun availability(piece: Int): Int {
        requirePiece(piece)
        return peerCount[piece] + seeds
    }

    /** Availability of every piece, into a fresh [IntArray]. `get_availability(vector&)`. */
    fun availability(): IntArray = IntArray(numPieces) { peerCount[it] + seeds }

    /** The current seed count. */
    fun numSeeds(): Int = seeds

    // =========================================================================
    // priority
    // =========================================================================

    /**
     * The effective priority / sort value of [piece]: a verbatim port of
     * `piece_pos::priority(piece_picker const*)`.
     *
     * Returns **-1** when the piece must not appear in the pick list: it is
     * filtered (priority 0), we already have it, no peer has it, or it is in the
     * `full`/`finished` download bucket (all blocks already requested/received).
     *
     * Otherwise the value is
     * `availability * (PRIORITY_LEVELS - piecePriority) * PRIO_FACTOR + adjustment`
     * where `availability = peerCount + 1` and the adjustment keeps actively
     * downloading (partial) pieces a notch ahead of untouched ones:
     *  * `-3` for a piece already in the `downloading` bucket,
     *  * `-2` for an open (not-yet-downloading) piece.
     *
     * A **lower** value is picked first, so low availability and high manual
     * priority both pull the value down.
     */
    fun effectivePriority(piece: Int): Int {
        requirePiece(piece)
        val prio = piecePriority[piece]
        val state = downloadState[piece]
        if (prio == DownloadPriority.DONT_DOWNLOAD ||
            havePiece[piece] ||
            peerCount[piece] + seeds == 0 ||
            state == STATE_FULL ||
            state == STATE_FINISHED
        ) {
            return -1
        }

        // keep downloading (partial) pieces ahead of untouched open pieces
        val adjustment = if (state != STATE_OPEN) -3 else -2

        val avail = peerCount[piece] + 1
        return avail * (PRIORITY_LEVELS - prio) * PRIO_FACTOR + adjustment
    }

    /**
     * Set the manual priority of [piece] to [newPriority] (0..7). Returns true iff
     * this flipped the piece between filtered (0) and not-filtered, updating the
     * filtered/have-filtered counters and cursors accordingly. Mirrors
     * `set_piece_priority`.
     */
    fun setPiecePriority(piece: Int, newPriority: Int): Boolean {
        requirePiece(piece)
        require(newPriority in DownloadPriority.DONT_DOWNLOAD..DownloadPriority.TOP_PRIORITY) {
            "priority $newPriority out of range 0..7"
        }
        val prev = piecePriority[piece]
        if (newPriority == prev) return false

        // "had" follows have_piece(): we have it, or it's a downloading piece that
        // already passed its hash check.
        val had = havePieceInternal(piece)
        var flipped = false

        val wasFiltered = prev == DownloadPriority.DONT_DOWNLOAD
        val nowFiltered = newPriority == DownloadPriority.DONT_DOWNLOAD

        if (nowFiltered && !wasFiltered) {
            // piece just got filtered
            if (had) numHaveFiltered += 1 else numFiltered += 1
            if (!havePiece[piece]) advanceCursorsForFilteredOrHave(piece)
            flipped = true
        } else if (!nowFiltered && wasFiltered) {
            // piece just got unfiltered
            if (had) numHaveFiltered -= 1 else numFiltered -= 1
            if (!havePiece[piece]) {
                if (piece < cursor) cursor = piece
                if (piece >= reverseCursor) reverseCursor = piece + 1
                if (reverseCursor == cursor) { reverseCursor = 0; cursor = numPieces }
            }
            flipped = true
        }

        piecePriority[piece] = newPriority
        return flipped
    }

    /** The manual priority (0..7) currently set on [piece]. `piece_priority`. */
    fun piecePriority(piece: Int): Int {
        requirePiece(piece)
        return piecePriority[piece]
    }

    /** The manual priority of every piece, into a fresh [IntArray]. `piece_priorities`. */
    fun piecePriorities(): IntArray = piecePriority.copyOf()

    /** A snapshot of [piece]'s state. `piece_stats`. */
    fun pieceStats(piece: Int): PieceStats {
        requirePiece(piece)
        return PieceStats(
            peerCount = peerCount[piece] + seeds,
            priority = effectivePriority(piece),
            have = havePiece[piece],
            downloading = downloadState[piece] != STATE_OPEN,
        )
    }

    // =========================================================================
    // have / finished queries
    // =========================================================================

    /**
     * True if we have [piece] (it passed the hash check). Like `have_piece`, this
     * is also true for a still-downloading piece whose hash already verified.
     */
    fun havePiece(piece: Int): Boolean {
        requirePiece(piece)
        return havePieceInternal(piece)
    }

    private fun havePieceInternal(piece: Int): Boolean {
        if (havePiece[piece]) return true
        val dp = downloads[piece] ?: return false
        return dp.passedHashCheck
    }

    /**
     * True if [piece] is fully written to disk (`is_piece_flushed`). In this core
     * a piece is "flushed" exactly when [havePiece] flips it on via
     * [markAsFinished] of the last block or an explicit [pieceFlushed].
     */
    fun isPieceFlushed(piece: Int): Boolean {
        requirePiece(piece)
        return havePiece[piece]
    }

    /** True if [piece] is partially or fully in a download bucket. `is_downloading`. */
    fun isDownloading(piece: Int): Boolean {
        requirePiece(piece)
        return downloadState[piece] != STATE_OPEN
    }

    /**
     * True if every block of [piece] is finished or writing (or we already have
     * it). Mirrors `is_piece_finished`. Note this is about *blocks received*, not
     * the hash check. A piece can be "finished" before it has "passed".
     */
    fun isPieceFinished(piece: Int): Boolean {
        requirePiece(piece)
        if (havePiece[piece]) return true
        val dp = downloads[piece] ?: return false
        val max = blocksInPiece(piece)
        return dp.finished + dp.writing >= max
    }

    /** Number of pieces whose hash has passed. `num_have`. */
    fun numHave(): Int = numHavePieces

    /** True once we have every piece. `is_seeding`. */
    fun isSeeding(): Boolean = numHavePieces == numPieces

    /**
     * True once every piece we *want* has passed its hash check. Mirrors
     * `is_finished()`: `numPieces - numFiltered <= numHave`.
     */
    fun isFinished(): Boolean = numPieces - numFiltered <= numHavePieces

    /** Pieces we want and don't yet have. `num_want_left`. */
    fun numWantLeft(): Int = numPieces - numHavePieces - numFiltered + numHaveFiltered

    /** Number of pieces we *want* (not filtered). The `num_pieces` of `want()`. */
    fun numWant(): Int = numPieces - numFiltered - numHaveFiltered

    /** Lowest piece index we don't have. `cursor`. */
    fun cursor(): Int = cursor

    /** One past the last piece we don't have. `reverse_cursor`. */
    fun reverseCursor(): Int = reverseCursor

    // =========================================================================
    // marking that we have / don't have whole pieces
    // =========================================================================

    /**
     * Mark that the hash of [piece] passed. The piece may still be flushing to
     * disk. If all blocks are already finished, this also flushes it (we "have"
     * it). Mirrors `piece_passed`.
     */
    fun piecePassed(piece: Int) {
        requirePiece(piece)
        val dp = downloads[piece] ?: return
        if (dp.locked) return
        if (dp.passedHashCheck) return
        dp.passedHashCheck = true
        accountHave(piece)
        if (dp.finished < blocksInPiece(piece)) return
        pieceFlushed(piece)
    }

    /**
     * Mark [piece] as completely downloaded *and* flushed to disk. The picker now
     * counts it as had and stops tracking its blocks. Mirrors `piece_flushed`.
     * Safe to call straight from open (e.g. loading resume data): the have count
     * is accounted as if it had been downloaded and hash-checked first.
     */
    fun pieceFlushed(piece: Int) {
        requirePiece(piece)
        if (havePiece[piece]) return

        var passed = false
        val dp = downloads[piece]
        if (dp != null) {
            if (dp.locked) return
            passed = dp.passedHashCheck
            eraseDownloadPiece(piece)
        }
        if (!passed) accountHave(piece)

        havePiece[piece] = true
        advanceCursorsForFilteredOrHave(piece)
    }

    /**
     * Mark every piece as had. This sets up a seed's picker. Mirrors `we_have_all`.
     */
    fun weHaveAll() {
        numHaveFiltered += numFiltered
        numFiltered = 0
        cursor = numPieces
        reverseCursor = 0
        numHavePieces = numPieces
        numDownloadEntries = 0
        for (i in 0 until numPieces) {
            downloads[i] = null
            setDownloadState(i, STATE_OPEN)
            havePiece[i] = true
        }
    }

    /**
     * Mark that we no longer have [piece] (e.g. it failed a later check and must
     * be redownloaded). Mirrors `we_dont_have`. Restores cursors and the have
     * count, and clears any partial download state.
     */
    fun weDontHave(piece: Int) {
        requirePiece(piece)
        var had = havePiece[piece]
        if (!had) {
            val dp = downloads[piece] ?: return
            had = dp.passedHashCheck
            eraseDownloadPiece(piece)
        }
        if (had) accountLost(piece)

        if (piecePriority[piece] != DownloadPriority.DONT_DOWNLOAD) {
            if (piece < cursor) cursor = piece
            if (piece >= reverseCursor) reverseCursor = piece + 1
            if (reverseCursor == cursor) { reverseCursor = 0; cursor = numPieces }
        }
        havePiece[piece] = false
    }

    // =========================================================================
    // block-level state queries
    // =========================================================================

    /** True if [block] is currently in the [BlockState.REQUESTED] state. `is_requested`. */
    fun isRequested(block: PieceBlock): Boolean {
        val info = blockInfoOrNull(block) ?: return false
        return info.state == BlockState.REQUESTED
    }

    /** True if [block] is finished or writing (i.e. we've received it). `is_downloaded`. */
    fun isDownloaded(block: PieceBlock): Boolean {
        if (havePiece[block.pieceIndex]) return true
        val info = blockInfoOrNull(block) ?: return false
        return info.state == BlockState.FINISHED || info.state == BlockState.WRITING
    }

    /** True if [block] has been written to disk. `is_finished`. */
    fun isFinished(block: PieceBlock): Boolean {
        if (havePiece[block.pieceIndex]) return true
        val info = blockInfoOrNull(block) ?: return false
        return info.state == BlockState.FINISHED
    }

    /** How many peers currently have [block] in a request queue. `num_peers`. */
    fun numPeers(block: PieceBlock): Int {
        val info = blockInfoOrNull(block) ?: return 0
        return info.numPeers
    }

    /** The peer token [block] was last requested/downloaded from, or `null`. `get_downloader`. */
    fun getDownloader(block: PieceBlock): Any? {
        val info = blockInfoOrNull(block) ?: return null
        if (info.state == BlockState.NONE) return null
        return info.peer
    }

    /**
     * The peer token for every block of [piece] (`null` where unknown). Length is
     * [blocksInPiece]. Mirrors `get_downloaders`.
     */
    fun getDownloaders(piece: Int): Array<Any?> {
        requirePiece(piece)
        val n = blocksInPiece(piece)
        val dp = downloads[piece] ?: return arrayOfNulls(n)
        return Array(n) { dp.blocks[it].peer }
    }

    private fun blockInfoOrNull(block: PieceBlock): BlockInfo? {
        requirePiece(block.pieceIndex)
        val dp = downloads[block.pieceIndex] ?: return null
        require(block.blockIndex in dp.blocks.indices) {
            "block index ${block.blockIndex} out of range for piece ${block.pieceIndex}"
        }
        return dp.blocks[block.blockIndex]
    }

    // =========================================================================
    // block-level state transitions
    // =========================================================================

    /**
     * Mark [block] as requested from [peerId]. Mirrors `mark_as_downloading`.
     * Returns false (without changing state) if the block is already writing or
     * finished. If the block was already requested from another peer, this just
     * bumps its peer count (end-game style). Lazily creates the piece's
     * [DownloadingPiece] the first time a block of an open piece is touched.
     */
    fun markAsDownloading(block: PieceBlock, peerId: Any? = null): Boolean {
        requirePiece(block.pieceIndex)
        require(!havePiece[block.pieceIndex]) { "marking a block of a had piece ${block.pieceIndex}" }
        val dp = ensureDownloadPiece(block.pieceIndex)
        val info = dp.blocks[requireBlock(dp, block)]
        when (info.state) {
            BlockState.WRITING, BlockState.FINISHED -> return false
            BlockState.REQUESTED -> {
                // already requested from someone else: just add this peer
                info.peer = peerId
                info.numPeers += 1
            }
            BlockState.NONE -> {
                info.state = BlockState.REQUESTED
                info.peer = peerId
                info.numPeers = 1
                dp.requested += 1
            }
        }
        updatePieceState(block.pieceIndex)
        return true
    }

    /**
     * Mark [block] as writing (received, flushing to disk), downloaded from
     * [peerId]. Mirrors `mark_as_writing`. Returns false if the block was already
     * writing or finished. Clears the block's peer count (all competing requests
     * are considered cancelled now).
     */
    fun markAsWriting(block: PieceBlock, peerId: Any? = null): Boolean {
        requirePiece(block.pieceIndex)
        if (havePiece[block.pieceIndex]) return false
        val dp = ensureDownloadPiece(block.pieceIndex)
        val info = dp.blocks[requireBlock(dp, block)]
        if (info.state == BlockState.WRITING || info.state == BlockState.FINISHED) {
            info.peer = peerId
            return false
        }
        if (info.state == BlockState.REQUESTED) dp.requested -= 1
        info.peer = peerId
        info.state = BlockState.WRITING
        info.numPeers = 0
        dp.writing += 1
        updatePieceState(block.pieceIndex)
        return true
    }

    /**
     * Mark [block] as finished (on disk), downloaded from [peerId]. Mirrors
     * `mark_as_finished`. Idempotent on an already-finished block. When this
     * completes the piece and its hash already passed, the piece is flushed.
     */
    fun markAsFinished(block: PieceBlock, peerId: Any? = null) {
        requirePiece(block.pieceIndex)
        if (downloadState[block.pieceIndex] == STATE_OPEN && havePiece[block.pieceIndex]) return
        val dp = ensureDownloadPiece(block.pieceIndex)
        val info = dp.blocks[requireBlock(dp, block)]
        if (info.state == BlockState.FINISHED) return

        // preserve a known downloader if this finish carries no peer (peer may
        // have disconnected between writing and finishing)
        if (info.state != BlockState.WRITING || peerId != null) info.peer = peerId

        dp.finished += 1
        if (info.state == BlockState.WRITING) {
            dp.writing -= 1
        } else if (info.state == BlockState.REQUESTED) {
            dp.requested -= 1
        }
        info.state = BlockState.FINISHED
        info.numPeers = 0
        updatePieceState(block.pieceIndex)

        val dpNow = downloads[block.pieceIndex] ?: return
        if (dpNow.finished < blocksInPiece(block.pieceIndex)) return
        if (dpNow.passedHashCheck && !dpNow.hashing) pieceFlushed(block.pieceIndex)
    }

    /**
     * Undo a *writing* block back to free (a write was cancelled). Mirrors
     * `mark_as_canceled`. Only acts on a writing block; a no-op otherwise. If the
     * piece becomes empty it is removed from the download list.
     */
    fun markAsCanceled(block: PieceBlock, peerId: Any? = null) {
        requirePiece(block.pieceIndex)
        val dp = downloads[block.pieceIndex] ?: return
        val info = dp.blocks[requireBlock(dp, block)]
        if (info.state == BlockState.FINISHED) return
        info.peer = peerId
        if (info.state == BlockState.WRITING) {
            dp.writing -= 1
            info.state = BlockState.NONE
            updatePieceState(block.pieceIndex)
            maybeEraseEmpty(block.pieceIndex)
        }
    }

    /**
     * Mark a *writing* block as failed-to-write: it returns to free and the piece
     * is locked (no more picks) until [restorePiece] is called. If the piece had
     * already passed its hash check, that is undone and the have count restored.
     * Mirrors `write_failed`.
     */
    fun writeFailed(block: PieceBlock) {
        requirePiece(block.pieceIndex)
        val dp = downloads[block.pieceIndex] ?: return
        val info = dp.blocks[requireBlock(dp, block)]
        if (info.state == BlockState.FINISHED) return
        if (info.state == BlockState.WRITING) dp.writing -= 1

        info.peer = null
        info.state = BlockState.NONE
        if (dp.passedHashCheck) {
            dp.passedHashCheck = false
            accountLost(block.pieceIndex)
        }
        dp.locked = true
        updatePieceState(block.pieceIndex)
        if (dp.finished + dp.writing + dp.requested == 0) eraseDownloadPiece(block.pieceIndex)
    }

    /**
     * Cancel a *requested* block for [peerId]. Mirrors `abort_download`. Decrements
     * the block's peer count; if other peers still have it requested the block
     * stays requested. When it drops to zero the block returns to free, and if the
     * whole piece becomes empty it is removed from the download list.
     */
    fun abortDownload(block: PieceBlock, peerId: Any? = null) {
        requirePiece(block.pieceIndex)
        val dp = downloads[block.pieceIndex] ?: return
        val info = dp.blocks[requireBlock(dp, block)]
        if (info.state != BlockState.REQUESTED) return

        if (info.numPeers > 0) info.numPeers -= 1
        if (info.peer == peerId) info.peer = null

        // other peers still want it: leave it requested
        if (info.numPeers > 0) return

        info.peer = null
        info.state = BlockState.NONE
        dp.requested -= 1

        if (dp.requested + dp.finished + dp.writing == 0) {
            eraseDownloadPiece(block.pieceIndex)
            return
        }
        updatePieceState(block.pieceIndex)
    }

    /**
     * Restore [piece] after a hash/write failure so it can be re-downloaded.
     * Mirrors `restore_piece`. With no [blocks] given the whole piece is reset
     * (its download entry is dropped). With specific [blocks], only those
     * writing/finished blocks are freed; the piece is dropped only once it is
     * empty.
     */
    fun restorePiece(piece: Int, blocks: IntArray = IntArray(0)) {
        requirePiece(piece)
        val dp = downloads[piece] ?: return
        dp.locked = false

        if (blocks.isNotEmpty()) {
            for (b in blocks) {
                val info = dp.blocks[b]
                when (info.state) {
                    BlockState.WRITING -> dp.writing -= 1
                    BlockState.FINISHED -> dp.finished -= 1
                    BlockState.REQUESTED -> continue // leave intact
                    BlockState.NONE -> {}
                }
                info.peer = null
                info.state = BlockState.NONE
            }
            updatePieceState(piece)
        }

        if (blocks.isEmpty() || dp.requested + dp.finished + dp.writing == 0) {
            eraseDownloadPiece(piece)
        }
    }

    /** Lock [piece] so no blocks are picked from it until [restorePiece]. `lock_piece`. */
    fun lockPiece(piece: Int) {
        requirePiece(piece)
        val dp = downloads[piece] ?: return
        if (dp.passedHashCheck) {
            dp.passedHashCheck = false
            accountLost(piece)
        }
        dp.locked = true
    }

    /** Clear [peerId] from every block that referenced it (a peer disconnected). `clear_peer`. */
    fun clearPeer(peerId: Any?) {
        for (dp in downloads) {
            if (dp == null) continue
            for (info in dp.blocks) if (info.peer == peerId) info.peer = null
        }
    }

    // =========================================================================
    // piece picking
    // =========================================================================

    /**
     * Pick up to [numBlocksWanted] interesting blocks for a peer whose available
     * pieces are described by [peerBitfield]. This is a port of `pick_pieces`,
     * including its option modes and the end-game busy-block tail.
     *
     * "Interesting" means: the peer has the piece, we don't have it, and it isn't
     * filtered (priority 0). Free blocks ([BlockState.NONE]) are returned first;
     * only when those cannot satisfy the request does the end-game tail append **one
     * busy block** (a block already requested from another peer). Callers detect it
     * via `numPeers(block) > 0` and apply their own end-game gates, exactly like
     * `request_a_block` does upstream.
     *
     * Option modes (combinable, same bit values as `piece_picker`):
     *  - [OPTION_RAREST_FIRST]: ascending [effectivePriority] (rarer + higher
     *    manual priority first; partial pieces a notch ahead). The default.
     *  - [OPTION_REVERSE]: walk the rarest-first order *backwards* (most common
     *    last-bucket pieces first). Used for snubbed peers, so they converge on the
     *    same common pieces instead of keeping rare pieces unavailable.
     *  - [OPTION_SEQUENTIAL]: top-priority (7) pieces first, then ascending piece
     *    index ([OPTION_REVERSE]: descending).
     *  - [OPTION_PRIORITIZE_PARTIALS]: already-downloading pieces are picked before
     *    anything else (sorted rarest-first when that flag is also set). Forced
     *    automatically when partial pieces sprawl: more than `numPeers * 3/2` of
     *    them, or more than [MAX_PARTIAL_BLOCKS] blocks' worth (`pick_pieces`'s
     *    anti-sprawl cap).
     *  - [OPTION_ON_PAROLE]: suppress the end-game tail (a parole peer must not
     *    double-request).
     *  - Neither rarest nor sequential: random-start walk over all pickable pieces
     *    (libtorrent's mode below `initial_picker_threshold`, usually combined with
     *    [OPTION_PRIORITIZE_PARTIALS]).
     *
     * The returned blocks are **not** marked. The caller decides what to request
     * and then calls [markAsDownloading]. Unlike upstream (which dedupes in
     * `request_a_block`), the result never repeats a block.
     *
     * @param random drives the in-bucket tie shuffle, the random-walk mode and the
     *   end-game piece/block choice; null = deterministic (ties by index, walk from
     *   piece 0, first busy candidate).
     * @param numPeers number of connected peers, for the partial-sprawl cap.
     * @param requesterPeer the picking peer's token: the end-game tail never returns
     *   a block whose last requester is this peer.
     */
    fun pickPieces(
        peerBitfield: Bitfield,
        numBlocksWanted: Int,
        options: Int = OPTION_RAREST_FIRST,
        random: Random? = null,
        numPeers: Int = 0,
        requesterPeer: Any? = null,
    ): List<PieceBlock> {
        if (numBlocksWanted <= 0 || numPieces == 0) return emptyList()

        var opts = options
        // anti-sprawl: too many partial pieces → finish them before opening new ones
        if (numDownloading > numPeers * 3 / 2 || numDownloading * blocksPerPiece > MAX_PARTIAL_BLOCKS) {
            opts = opts or OPTION_PRIORITIZE_PARTIALS
        }

        val result = ArrayList<PieceBlock>(numBlocksWanted)
        val visited = HashSet<Int>()
        var remaining = numBlocksWanted

        // take every free block of [piece] (in index order) until satisfied.
        // This is the free-block half of add_blocks / add_blocks_downloading
        fun takeFreeBlocks(piece: Int) {
            if (remaining <= 0 || !visited.add(piece)) return
            val dp = downloads[piece]
            if (dp != null && dp.locked) return
            val blocks = blocksInPiece(piece)
            var j = 0
            while (j < blocks && remaining > 0) {
                if (dp == null || dp.blocks[j].state == BlockState.NONE) {
                    result.add(PieceBlock(piece, j))
                    remaining -= 1
                }
                j += 1
            }
        }

        // ---- 1. prioritize_partials: drain downloading pieces first --------------
        if (opts and OPTION_PRIORITIZE_PARTIALS != 0) {
            val partials = ArrayList<Int>()
            for (p in 0 until numPieces) {
                if (downloadState[p] != STATE_DOWNLOADING) continue
                if (!isPieceFree(p, peerBitfield)) continue
                partials.add(p)
            }
            // partial_compare_rarest_first: order partials by availability
            if (opts and OPTION_RAREST_FIRST != 0) partials.sortBy { peerCount[it] + seeds }
            for (p in partials) {
                takeFreeBlocks(p)
                if (remaining <= 0) return result
            }
        }

        // ---- 2. the main mode -----------------------------------------------------
        when {
            opts and OPTION_SEQUENTIAL != 0 -> {
                // top-priority pieces are always picked first, in priority order
                val top = ArrayList<Int>()
                for (p in 0 until numPieces) {
                    if (piecePriority[p] != DownloadPriority.TOP_PRIORITY) continue
                    if (!isPieceFree(p, peerBitfield) || effectivePriority(p) < 0) continue
                    top.add(p)
                }
                top.sortBy { effectivePriority(it) }
                for (p in top) {
                    takeFreeBlocks(p)
                    if (remaining <= 0) return result
                }
                val range = if (opts and OPTION_REVERSE != 0) {
                    (reverseCursor - 1) downTo cursor
                } else {
                    cursor until reverseCursor
                }
                for (p in range) {
                    if (p !in 0 until numPieces) continue
                    if (piecePriority[p] == DownloadPriority.TOP_PRIORITY) continue
                    if (!isPieceFree(p, peerBitfield) || effectivePriority(p) < 0) continue
                    takeFreeBlocks(p)
                    if (remaining <= 0) return result
                }
            }

            opts and OPTION_RAREST_FIRST != 0 -> {
                val candidates = ArrayList<Int>()
                val n = minOf(peerBitfield.size(), numPieces)
                for (piece in 0 until n) {
                    if (!isPieceFree(piece, peerBitfield)) continue
                    // skip full/finished buckets (no free blocks); effectivePriority == -1
                    if (effectivePriority(piece) < 0) continue
                    candidates.add(piece)
                }
                if (random != null) candidates.shuffle(random)
                // stable sort by effective priority (ascending == rarest/most-important first)
                candidates.sortBy { effectivePriority(it) }
                if (opts and OPTION_REVERSE != 0) candidates.reverse()
                for (piece in candidates) {
                    takeFreeBlocks(piece)
                    if (remaining <= 0) return result
                }
            }

            else -> {
                // random-start walk over every pickable piece, wrapping once
                val start = random?.nextInt(numPieces) ?: 0
                var p = start
                do {
                    if (isPieceFree(p, peerBitfield) && effectivePriority(p) >= 0) takeFreeBlocks(p)
                    p += 1
                    if (p == numPieces) p = 0
                } while (p != start && remaining > 0)
            }
        }
        if (remaining <= 0) return result

        // ---- 3. END-GAME: append one block already requested from someone else ----
        // (the tail of pick_pieces). Parole peers must only download exclusively.
        if (opts and OPTION_ON_PAROLE != 0) return result

        // collect the partial (downloading + full) pieces this peer could duplicate
        // a request into, capped like upstream's stack allocation
        val busyPieces = ArrayList<DownloadingPiece>()
        for (p in 0 until numPieces) {
            val st = downloadState[p]
            if (st != STATE_DOWNLOADING && st != STATE_FULL) continue
            val dp = downloads[p] ?: continue
            if (dp.locked || dp.requested == 0) continue
            if (p >= peerBitfield.size() || !peerBitfield.getBit(p)) continue
            if (havePieceInternal(p)) continue
            if (piecePriority[p] == DownloadPriority.DONT_DOWNLOAD) continue
            busyPieces.add(dp)
            if (busyPieces.size >= MAX_ENDGAME_PARTIALS) break
        }

        // pick one random busy piece; within it one random requested-by-someone-else
        // block. A piece whose outstanding requests are all our own is discarded.
        var size = busyPieces.size
        val temp = ArrayList<PieceBlock>()
        while (size > 0) {
            val idx = if (random != null) random.nextInt(size) else 0
            val dp = busyPieces[idx]
            temp.clear()
            for (j in dp.blocks.indices) {
                val info = dp.blocks[j]
                if (info.state != BlockState.REQUESTED) continue
                if (requesterPeer != null && info.peer == requesterPeer) continue
                temp.add(PieceBlock(dp.index, j))
            }
            if (temp.isNotEmpty()) {
                result.add(temp[if (random != null) random.nextInt(temp.size) else 0])
                break
            }
            busyPieces[idx] = busyPieces[size - 1]
            size -= 1
        }
        return result
    }

    /**
     * Number of pieces in the *downloading* bucket only (partially requested, free
     * blocks remain): `m_downloads[piece_downloading].size()`, the count behind the
     * partial-sprawl cap.
     */
    fun numDownloadingPieces(): Int = numDownloading

    /**
     * Pieces in *any* download bucket (downloading/full/finished/zero-prio):
     * `get_download_queue_size`. The strict end-game gate compares this to
     * [numWantLeft]: only when every wanted piece is already in the download queue
     * may a busy block be double-requested.
     */
    fun downloadQueueSize(): Int = numDownloadEntries

    /**
     * Blocks of [piece] still free to request (not requested/writing/finished),
     * derived from the `piece_info` counters. `snub_peer` uses this to decide
     * whether a stalled request actually blocks the piece's completion.
     */
    fun numFreeBlocksIn(piece: Int): Int {
        requirePiece(piece)
        val dp = downloads[piece] ?: return if (havePiece[piece]) 0 else blocksInPiece(piece)
        return blocksInPiece(piece) - dp.requested - dp.writing - dp.finished
    }

    /**
     * True if [piece] is a candidate to pick from [bitmask]: the peer has it, we
     * don't, and it isn't filtered. Mirrors `is_piece_free`.
     */
    fun isPieceFree(piece: Int, bitmask: Bitfield): Boolean {
        requirePiece(piece)
        if (piece >= bitmask.size() || !bitmask.getBit(piece)) return false
        return !havePiece[piece] && piecePriority[piece] != DownloadPriority.DONT_DOWNLOAD
    }

    // =========================================================================
    // internal: download-piece lifecycle & bucket classification
    // =========================================================================

    /** Lazily create (or fetch) the [DownloadingPiece] for [piece]. `add_download_piece`. */
    private fun ensureDownloadPiece(piece: Int): DownloadingPiece {
        downloads[piece]?.let { return it }
        val n = blocksInPiece(piece)
        val dp = DownloadingPiece(piece, Array(n) { BlockInfo() })
        downloads[piece] = dp
        numDownloadEntries += 1
        // it's now in a download bucket; classify it (starts as 'downloading')
        setDownloadState(piece, STATE_DOWNLOADING)
        updatePieceState(piece)
        return dp
    }

    /** Drop [piece]'s download entry and return it to the open state. `erase_download_piece`. */
    private fun eraseDownloadPiece(piece: Int) {
        if (downloads[piece] != null) numDownloadEntries -= 1
        downloads[piece] = null
        setDownloadState(piece, STATE_OPEN)
    }

    /** Single write point for [downloadState], keeping [numDownloading] in sync. */
    private fun setDownloadState(piece: Int, newState: Int) {
        val old = downloadState[piece]
        if (old == newState) return
        if (old == STATE_DOWNLOADING) numDownloading -= 1
        if (newState == STATE_DOWNLOADING) numDownloading += 1
        downloadState[piece] = newState
    }

    /** If [piece]'s download entry has no active blocks, drop it. */
    private fun maybeEraseEmpty(piece: Int) {
        val dp = downloads[piece] ?: return
        if (dp.requested + dp.finished + dp.writing == 0) eraseDownloadPiece(piece)
    }

    /**
     * Re-classify [piece]'s download bucket from its block counters. This is a port
     * of `update_piece_state` (minus the reverse sub-states). Buckets:
     *  * filtered → `zero_prio`
     *  * no active blocks → back to `open` (entry dropped by callers when empty)
     *  * some blocks still free → `downloading`
     *  * all requested/received but some still requested → `full`
     *  * all received (finished+writing) → `finished`
     *
     * The `full`/`finished` buckets report [effectivePriority] -1 so they drop out
     * of the pick list, which is the whole point of the bucketing.
     */
    private fun updatePieceState(piece: Int) {
        val dp = downloads[piece] ?: return
        val numBlocks = blocksInPiece(piece)
        val newState = when {
            piecePriority[piece] == DownloadPriority.DONT_DOWNLOAD -> STATE_ZERO_PRIO
            dp.requested + dp.finished + dp.writing == 0 -> STATE_OPEN
            dp.requested + dp.finished + dp.writing < numBlocks -> STATE_DOWNLOADING
            dp.requested > 0 -> STATE_FULL
            else -> STATE_FINISHED
        }
        // update_piece_state never recreates an open download entry; if a piece
        // emptied out, leave the (about-to-be-erased) entry classified as it was.
        if (newState == STATE_OPEN) return
        setDownloadState(piece, newState)
    }

    private fun requireBlock(dp: DownloadingPiece, block: PieceBlock): Int {
        require(block.blockIndex in dp.blocks.indices) {
            "block index ${block.blockIndex} out of range for piece ${block.pieceIndex}"
        }
        return block.blockIndex
    }

    // =========================================================================
    // internal: have/lost accounting & cursors
    // =========================================================================

    /** Bump the have counters for [piece] (it passed the hash check). `account_have`. */
    private fun accountHave(piece: Int) {
        numHavePieces += 1
        if (piecePriority[piece] == DownloadPriority.DONT_DOWNLOAD) {
            numFiltered -= 1
            numHaveFiltered += 1
        }
    }

    /** Reverse [accountHave] (we lost a piece we had). `account_lost`. */
    private fun accountLost(piece: Int) {
        numHavePieces -= 1
        if (piecePriority[piece] == DownloadPriority.DONT_DOWNLOAD) {
            numFiltered += 1
            numHaveFiltered -= 1
        }
    }

    /**
     * After [piece] became had-or-filtered, walk the cursors forward over the new
     * run of had/filtered pieces. Mirrors the cursor maintenance shared by
     * `piece_flushed` / `set_piece_priority`.
     */
    private fun advanceCursorsForFilteredOrHave(piece: Int) {
        if (cursor == reverseCursor - 1 && cursor == piece) {
            cursor = numPieces
            reverseCursor = 0
            return
        }
        if (cursor == piece) {
            cursor += 1
            while (cursor < numPieces && (havePiece[cursor] || isFilteredAt(cursor))) cursor += 1
        } else if (reverseCursor - 1 == piece) {
            reverseCursor -= 1
            while (reverseCursor > 0 && (havePiece[reverseCursor - 1] || isFilteredAt(reverseCursor - 1))) {
                reverseCursor -= 1
            }
        }
    }

    private fun isFilteredAt(piece: Int): Boolean =
        piecePriority[piece] == DownloadPriority.DONT_DOWNLOAD

    /** Recompute both cursors from the [havePiece]/priority arrays (used at init). */
    private fun recomputeCursorsFromScratch() {
        var c = 0
        while (c < numPieces && (havePiece[c] || isFilteredAt(c))) c += 1
        cursor = c
        var r = numPieces
        while (r > 0 && (havePiece[r - 1] || isFilteredAt(r - 1))) r -= 1
        reverseCursor = if (c >= numPieces) 0 else r
    }

    companion object {
        /** Number of priority levels (0..7). `piece_picker::priority_levels`. */
        const val PRIORITY_LEVELS: Int = 8

        /** Multiplier creating 3 sub-levels per availability. `piece_picker::prio_factor`. */
        const val PRIO_FACTOR: Int = 3

        /** Hard cap on blocks per piece (15-bit counter in upstream). `max_blocks_per_piece`. */
        const val MAX_BLOCKS_PER_PIECE: Int = (1 shl 15) - 1

        // ---- pick_pieces option bits (same values as piece_picker's) -----------

        /** Pick rarest pieces first (the normal steady-state mode). `rarest_first`. */
        const val OPTION_RAREST_FIRST: Int = 1

        /** Walk the pick order backwards, most common pieces first. `reverse`. */
        const val OPTION_REVERSE: Int = 2

        /** The peer is on parole: only exclusive picks, no end-game tail. `on_parole`. */
        const val OPTION_ON_PAROLE: Int = 4

        /** Drain partially-downloaded pieces before opening new ones. `prioritize_partials`. */
        const val OPTION_PRIORITIZE_PARTIALS: Int = 8

        /** Pick pieces in index order (streaming). `sequential`. */
        const val OPTION_SEQUENTIAL: Int = 16

        /**
         * Anti-sprawl cap: force [OPTION_PRIORITIZE_PARTIALS] once partial pieces
         * hold more than this many blocks (2048 blocks = 32 MiB at 16 KiB).
         * Matches the constant in `pick_pieces`.
         */
        const val MAX_PARTIAL_BLOCKS: Int = 2048

        /** Cap on partial pieces scanned by the end-game tail (upstream's `TORRENT_ALLOCA` cap). */
        const val MAX_ENDGAME_PARTIALS: Int = 200

        // download-queue categories (subset of piece_pos::download_queue_t).
        // values match libtorrent's numbering; reverse sub-states are omitted.
        private const val STATE_DOWNLOADING = 0
        private const val STATE_FULL = 1
        private const val STATE_FINISHED = 2
        private const val STATE_ZERO_PRIO = 3
        private const val STATE_OPEN = 4
    }
}
