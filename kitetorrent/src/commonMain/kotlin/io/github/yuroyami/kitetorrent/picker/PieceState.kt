package io.github.yuroyami.kitetorrent.picker

/**
 * The four states a single block can be in — pure-Kotlin port of the anonymous
 * enum inside libtorrent's `piece_picker::block_info`
 * (include/libtorrent/piece_picker.hpp).
 *
 * State flows strictly forward during a healthy download:
 * [NONE] → [REQUESTED] → [WRITING] → [FINISHED]. A block may also jump straight
 * from [NONE] to [WRITING] or [FINISHED] (e.g. resume data, or a peer that hands
 * us a whole block at once), and may fall back to [NONE] on abort / write-failure.
 */
enum class BlockState {
    /** Not requested from anyone yet — free to be picked. */
    NONE,

    /** A request for this block is outstanding to at least one peer. */
    REQUESTED,

    /** The block arrived and is being flushed to disk. */
    WRITING,

    /** The block is on disk (or counted as had, e.g. a pad block). */
    FINISHED,
}

/**
 * Bookkeeping for one block of a *downloading* piece — port of
 * `piece_picker::block_info`. Only pieces that are partially downloaded keep an
 * array of these (see [DownloadingPiece]); pieces we fully have or have never
 * touched carry none.
 *
 * In C++ this is a packed bit-field struct (14-bit [numPeers] + 2-bit state) to
 * fit 8 bytes; we don't need the packing in Kotlin, so the fields are plain.
 */
class BlockInfo {
    /**
     * The opaque peer token this block was last requested from / downloaded from,
     * or `null`. libtorrent stores a `torrent_peer*`; KiteTorrent keeps peers
     * opaque, so the picker stores whatever token the caller passed in.
     */
    var peer: Any? = null

    /**
     * How many peers currently have this block in their request queue. A block in
     * [BlockState.REQUESTED] has `numPeers > 0`; in any other state it is 0.
     * (End-game mode is the reason this can exceed 1.)
     */
    var numPeers: Int = 0

    /** The state of this block. */
    var state: BlockState = BlockState.NONE

    /** Reset to the pristine "never touched" state, reused when a piece is recycled. */
    fun reset() {
        peer = null
        numPeers = 0
        state = BlockState.NONE
    }
}

/**
 * Tracks block allocations for a single piece that is currently being downloaded
 * — port of `piece_picker::downloading_piece`.
 *
 * libtorrent stores the per-block [BlockInfo] objects in one big flat pool
 * (`m_block_info`) indexed via `info_idx * blocks_per_piece`; that's a memory
 * micro-optimization. We keep the (semantically identical) [blocks] array inline,
 * which is simpler and just as correct.
 *
 * The [finished] / [writing] / [requested] counters are redundant caches of the
 * states in [blocks] (kept in sync on every mutation) so the picker can classify
 * the piece's download-queue bucket in O(1).
 */
class DownloadingPiece(
    /** The piece index this entry describes. */
    val index: Int,
    /** Per-block info; `blocks.size == blocksInPiece(index)`. */
    val blocks: Array<BlockInfo>,
) {
    /** Number of blocks in [BlockState.FINISHED]. */
    var finished: Int = 0

    /** Number of blocks in [BlockState.WRITING]. */
    var writing: Int = 0

    /** Number of blocks in [BlockState.REQUESTED]. */
    var requested: Int = 0

    /**
     * Set once the piece's hash has been verified. The piece may not be "had" yet
     * because the last block might still be flushing to disk.
     */
    var passedHashCheck: Boolean = false

    /**
     * When set, no blocks from this piece may be picked. Used while synchronizing
     * with the disk thread after a hash-failure or write-failure, until
     * [PiecePicker.restorePiece] clears it.
     */
    var locked: Boolean = false

    /** Set while there is an outstanding hash job for this piece (v2 torrents). */
    var hashing: Boolean = false
}

/**
 * A snapshot of one piece's high-level state — port of
 * `piece_picker::piece_stats_t`. Returned by [PiecePicker.pieceStats].
 */
data class PieceStats(
    /** Availability: number of connected peers (incl. seeds) that have the piece. */
    val peerCount: Int,
    /** The effective priority/sort value (`piece_pos::priority`); -1 = not pickable. */
    val priority: Int,
    /** True if we have the piece (passed hash check). */
    val have: Boolean,
    /** True if the piece is currently being downloaded. */
    val downloading: Boolean,
)
