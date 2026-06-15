package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.picker.BlockState
import io.github.yuroyami.kitetorrent.picker.DownloadPriority
import io.github.yuroyami.kitetorrent.picker.PieceBlock
import io.github.yuroyami.kitetorrent.picker.PiecePicker
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the rarest-first piece picker ([PiecePicker]) — a faithful core port of
 * libtorrent's `piece_picker`.
 *
 * The assertions pin behaviour we can derive from first principles (no opaque
 * golden hashes here): the rarest-first *ordering* implied by the ported
 * `piece_pos::priority()` formula, the block state machine, the have/want
 * counters, and that filtered (priority-0) pieces are never picked. Tie-breaking
 * is made deterministic by passing no [kotlin.random.Random] to `pickPieces`, so
 * equal-priority pieces come out in ascending piece-index order.
 */
class PiecePickerTest {

    private val PIECES = 10
    private val BLOCKS = 4

    /** A bitfield over [PIECES] pieces with the given indices set. */
    private fun bits(vararg have: Int): Bitfield {
        val bf = Bitfield(PIECES)
        for (i in have) bf.setBit(i)
        return bf
    }

    /** A bitfield with every piece set. */
    private fun allBits(): Bitfield {
        val bf = Bitfield(PIECES)
        for (i in 0 until PIECES) bf.setBit(i)
        return bf
    }

    // -------------------------------------------------------------------------
    // availability + rarest-first ordering
    // -------------------------------------------------------------------------

    @Test
    fun rarestFirstPicksTheRarePieceBeforeCommonOnes() {
        val p = PiecePicker(PIECES, BLOCKS)

        // Peer A has pieces {0,1,2,3}; peer B has {1,2,3,4}.
        // => availability: piece0 = 1 (only A), piece4 = 1 (only B),
        //    pieces 1,2,3 = 2 (both), pieces 5..9 = 0 (nobody).
        val peerA = bits(0, 1, 2, 3)
        val peerB = bits(1, 2, 3, 4)
        p.incRefcount(peerA, "A")
        p.incRefcount(peerB, "B")

        assertEquals(1, p.availability(0))
        assertEquals(2, p.availability(1))
        assertEquals(1, p.availability(4))
        assertEquals(0, p.availability(9))

        // Now pick from a peer that has BOTH the rare piece (0) and a common one (1).
        // Rarest-first must surface piece 0's blocks before piece 1's.
        val picks = p.pickPieces(bits(0, 1), numBlocksWanted = 8)
        assertEquals(8, picks.size)
        // first 4 blocks are piece 0, next 4 are piece 1
        assertContentEquals(
            (0 until 4).map { PieceBlock(0, it) } + (0 until 4).map { PieceBlock(1, it) },
            picks,
        )
    }

    @Test
    fun pieceOnlyOnePeerHasOutranksPieceEveryoneHas() {
        val p = PiecePicker(PIECES, BLOCKS)
        // piece 5 is rare (1 peer), piece 1 is common (3 peers)
        p.incRefcount(5, "A")          // avail(5) = 1
        p.incRefcount(1, "A")
        p.incRefcount(1, "B")
        p.incRefcount(1, "C")          // avail(1) = 3

        // effectivePriority is lower (picked earlier) for the rarer piece
        assertTrue(p.effectivePriority(5) < p.effectivePriority(1))

        // Ask for just one block from a peer holding both — must be from piece 5.
        val picks = p.pickPieces(bits(1, 5), numBlocksWanted = 1)
        assertEquals(listOf(PieceBlock(5, 0)), picks)
    }

    @Test
    fun seedsRaiseAvailabilityOfEveryPiece() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcount(3, "peer")        // one normal peer has piece 3
        assertEquals(1, p.availability(3))
        assertEquals(0, p.availability(7))

        p.incRefcountAll("seed")        // a seed joins
        assertEquals(1, p.numSeeds())
        // every piece's availability goes up by 1
        for (i in 0 until PIECES) assertTrue(p.availability(i) >= 1)
        assertEquals(2, p.availability(3))
        assertEquals(1, p.availability(7))

        p.decRefcountAll("seed")        // seed leaves
        assertEquals(0, p.numSeeds())
        assertEquals(1, p.availability(3))
        assertEquals(0, p.availability(7))
    }

    @Test
    fun decRefcountSplitsASeedWhenNeeded() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcountAll("seed")        // seeds = 1, no per-piece counts
        assertEquals(1, p.availability(0))

        // The seed sends "dont-have piece 0": its count was 0, so a seed is split
        // into per-piece counts, then piece 0 is decremented.
        p.decRefcount(0, "seed")
        assertEquals(0, p.numSeeds())
        assertEquals(0, p.availability(0))   // 0 + (1 - 1)
        assertEquals(1, p.availability(1))   // 0 + 1  (split count)
    }

    // -------------------------------------------------------------------------
    // priority 0 (filtered) pieces are never picked
    // -------------------------------------------------------------------------

    @Test
    fun filteredPiecesAreNeverPicked() {
        val p = PiecePicker(PIECES, BLOCKS)
        // everyone has everything
        p.incRefcount(allBits(), "A")

        // filter pieces 0 and 1 (priority 0 = dont-download)
        assertTrue(p.setPiecePriority(0, DownloadPriority.DONT_DOWNLOAD))
        assertTrue(p.setPiecePriority(1, DownloadPriority.DONT_DOWNLOAD))
        assertEquals(-1, p.effectivePriority(0))
        assertEquals(-1, p.effectivePriority(1))

        // ask for everything; pieces 0 and 1 must not appear
        val picks = p.pickPieces(allBits(), numBlocksWanted = PIECES * BLOCKS)
        assertTrue(picks.none { it.pieceIndex == 0 })
        assertTrue(picks.none { it.pieceIndex == 1 })
        // and all the other 8 pieces × 4 blocks are pickable
        assertEquals(8 * BLOCKS, picks.size)
    }

    @Test
    fun higherManualPriorityIsPickedFirst() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcount(allBits(), "A")     // uniform availability

        // raise piece 9 to top priority; with equal availability it must lead
        p.setPiecePriority(9, DownloadPriority.TOP_PRIORITY)
        assertTrue(p.effectivePriority(9) < p.effectivePriority(0))

        val picks = p.pickPieces(allBits(), numBlocksWanted = 1)
        assertEquals(listOf(PieceBlock(9, 0)), picks)
    }

    @Test
    fun settingPriorityReportsFilterFlips() {
        val p = PiecePicker(PIECES, BLOCKS)
        // default(4) -> top(7): not a filter flip
        assertFalse(p.setPiecePriority(2, DownloadPriority.TOP_PRIORITY))
        // top(7) -> dont(0): filter flip
        assertTrue(p.setPiecePriority(2, DownloadPriority.DONT_DOWNLOAD))
        // dont(0) -> dont(0): no change
        assertFalse(p.setPiecePriority(2, DownloadPriority.DONT_DOWNLOAD))
        // dont(0) -> low(1): unfilter flip
        assertTrue(p.setPiecePriority(2, DownloadPriority.LOW_PRIORITY))
    }

    // -------------------------------------------------------------------------
    // block state machine + we_have
    // -------------------------------------------------------------------------

    @Test
    fun markingAllBlocksFinishedFlipsWeHave() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcount(allBits(), "A")

        assertFalse(p.havePiece(3))
        assertEquals(0, p.numHave())

        // walk one block through the full state machine
        val b0 = PieceBlock(3, 0)
        assertTrue(p.markAsDownloading(b0, "A"))
        assertTrue(p.isRequested(b0))
        assertEquals(1, p.numPeers(b0))
        assertEquals("A", p.getDownloader(b0))

        assertTrue(p.markAsWriting(b0, "A"))
        assertFalse(p.isRequested(b0))
        assertTrue(p.isDownloaded(b0))
        assertFalse(p.isFinished(b0))

        p.markAsFinished(b0, "A")
        assertTrue(p.isFinished(b0))

        // the piece isn't finished until ALL blocks are
        assertFalse(p.isPieceFinished(3))
        assertFalse(p.havePiece(3))

        // finish the remaining blocks directly
        for (j in 1 until BLOCKS) p.markAsFinished(PieceBlock(3, j), "A")
        assertTrue(p.isPieceFinished(3))

        // hash check passes -> we now HAVE the piece, num_have flips
        p.piecePassed(3)
        assertTrue(p.havePiece(3))
        assertTrue(p.isPieceFlushed(3))
        assertEquals(1, p.numHave())

        // a had piece is no longer pickable
        assertEquals(-1, p.effectivePriority(3))
        val picks = p.pickPieces(bits(3), numBlocksWanted = BLOCKS)
        assertTrue(picks.isEmpty())
    }

    @Test
    fun pieceFlushedWithoutHashCheckStillCountsAsHave() {
        val p = PiecePicker(PIECES, BLOCKS)
        assertFalse(p.havePiece(2))
        // e.g. loading resume data: straight to flushed
        p.pieceFlushed(2)
        assertTrue(p.havePiece(2))
        assertEquals(1, p.numHave())
        // cursor advanced past piece... not piece 2 (it's not piece 0), so cursor stays 0
        assertEquals(0, p.cursor())
    }

    @Test
    fun cursorAdvancesAsLeadingPiecesAreHad() {
        val p = PiecePicker(PIECES, BLOCKS)
        assertEquals(0, p.cursor())
        p.pieceFlushed(0)
        assertEquals(1, p.cursor())   // piece 0 had -> cursor moves to 1
        p.pieceFlushed(1)
        assertEquals(2, p.cursor())
        // a gap: having piece 3 (not 2) doesn't move the cursor
        p.pieceFlushed(3)
        assertEquals(2, p.cursor())
        // filling the gap jumps the cursor across the contiguous had run 2,3
        p.pieceFlushed(2)
        assertEquals(4, p.cursor())
    }

    @Test
    fun abortDownloadFreesTheBlock() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcount(allBits(), "A")
        val b = PieceBlock(5, 2)

        assertTrue(p.markAsDownloading(b, "A"))
        assertTrue(p.isDownloading(5))
        assertTrue(p.isRequested(b))

        p.abortDownload(b, "A")
        assertFalse(p.isRequested(b))
        // the piece had only that one block; it should be back to open/not-downloading
        assertFalse(p.isDownloading(5))

        // ...and the freed block is pickable again
        val picks = p.pickPieces(bits(5), numBlocksWanted = BLOCKS)
        assertEquals(BLOCKS, picks.size)
        assertTrue(picks.contains(b))
    }

    @Test
    fun endGameSecondRequesterBumpsPeerCount() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcount(allBits(), "A")
        val b = PieceBlock(6, 0)

        assertTrue(p.markAsDownloading(b, "A"))
        assertEquals(1, p.numPeers(b))
        // a second peer requests the same block (end-game): still requested, 2 peers
        assertTrue(p.markAsDownloading(b, "B"))
        assertEquals(2, p.numPeers(b))
        assertTrue(p.isRequested(b))

        // one aborts -> block stays requested (other peer still wants it)
        p.abortDownload(b, "A")
        assertTrue(p.isRequested(b))
        assertEquals(1, p.numPeers(b))

        // the other aborts -> now free
        p.abortDownload(b, "B")
        assertFalse(p.isRequested(b))
    }

    @Test
    fun partialPieceIsPreferredOverUntouchedAtSameAvailability() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcount(allBits(), "A")    // uniform availability

        // start downloading piece 7 (one block requested) -> it's now 'downloading'
        assertTrue(p.markAsDownloading(PieceBlock(7, 0), "A"))

        // at equal availability, the partial piece sorts ahead of untouched ones
        // (priority adjustment -3 vs -2)
        assertTrue(p.effectivePriority(7) < p.effectivePriority(0))

        // picking a single block surfaces piece 7's next free block first
        val picks = p.pickPieces(allBits(), numBlocksWanted = 1)
        assertEquals(1, picks.size)
        assertEquals(7, picks[0].pieceIndex)
        // and it skips the already-requested block 0
        assertTrue(picks[0].blockIndex != 0)
    }

    @Test
    fun weDontHaveReDownloadsAPiece() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcount(allBits(), "A")
        p.pieceFlushed(4)
        assertTrue(p.havePiece(4))
        assertEquals(1, p.numHave())

        p.weDontHave(4)
        assertFalse(p.havePiece(4))
        assertEquals(0, p.numHave())
        // pickable again
        assertTrue(p.effectivePriority(4) >= 0)
        val picks = p.pickPieces(bits(4), numBlocksWanted = BLOCKS)
        assertEquals(BLOCKS, picks.size)
    }

    @Test
    fun writeFailedLocksPieceUntilRestored() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcount(allBits(), "A")
        val b = PieceBlock(8, 1)
        p.markAsDownloading(b, "A")
        assertTrue(p.markAsWriting(b, "A"))

        // write fails: the block frees, the piece locks (no picks)
        p.writeFailed(b)
        // the only active block is gone, so the locked piece's entry is erased
        // and the piece is open again, fully pickable
        val picksAfterFail = p.pickPieces(bits(8), numBlocksWanted = BLOCKS)
        assertEquals(BLOCKS, picksAfterFail.size)
    }

    @Test
    fun restorePieceClearsPartialState() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcount(allBits(), "A")
        // get a couple of blocks of piece 2 into flight
        p.markAsDownloading(PieceBlock(2, 0), "A")
        p.markAsDownloading(PieceBlock(2, 1), "A")
        assertTrue(p.isDownloading(2))

        // restore the whole piece -> back to open, all blocks free
        p.restorePiece(2)
        assertFalse(p.isDownloading(2))
        val picks = p.pickPieces(bits(2), numBlocksWanted = BLOCKS)
        assertEquals(BLOCKS, picks.size)
        assertContentEquals((0 until BLOCKS).map { PieceBlock(2, it) }, picks)
    }

    // -------------------------------------------------------------------------
    // want / have counters
    // -------------------------------------------------------------------------

    @Test
    fun wantAndHaveCountersTrackFilteringAndProgress() {
        val p = PiecePicker(PIECES, BLOCKS)
        assertEquals(PIECES, p.numWant())     // nothing filtered yet
        assertEquals(0, p.numHave())
        assertEquals(PIECES, p.numWantLeft())
        assertFalse(p.isSeeding())

        // filter 2 pieces -> want drops by 2
        p.setPiecePriority(0, DownloadPriority.DONT_DOWNLOAD)
        p.setPiecePriority(1, DownloadPriority.DONT_DOWNLOAD)
        assertEquals(PIECES - 2, p.numWant())
        assertEquals(PIECES - 2, p.numWantLeft())

        // get one (non-filtered) piece
        p.pieceFlushed(5)
        assertEquals(1, p.numHave())
        assertEquals(PIECES - 2 - 1, p.numWantLeft())

        // finishing every wanted piece => is_finished
        for (i in 2 until PIECES) p.pieceFlushed(i)
        assertTrue(p.isFinished())
    }

    @Test
    fun weHaveAllMakesUsASeed() {
        val p = PiecePicker(PIECES, BLOCKS)
        assertFalse(p.isSeeding())
        p.weHaveAll()
        assertTrue(p.isSeeding())
        assertEquals(PIECES, p.numHave())
        // nothing is pickable anymore
        assertTrue(p.pickPieces(allBits(), numBlocksWanted = PIECES * BLOCKS).isEmpty())
        assertEquals(PIECES, p.cursor())
        assertEquals(0, p.reverseCursor())
    }

    // -------------------------------------------------------------------------
    // short last piece
    // -------------------------------------------------------------------------

    @Test
    fun shortLastPieceHasFewerBlocks() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.setBlocksInLastPiece(2)        // last piece only has 2 blocks
        p.incRefcount(allBits(), "A")

        assertEquals(BLOCKS, p.blocksInPiece(0))
        assertEquals(2, p.blocksInPiece(PIECES - 1))

        // picking from just the last piece yields only its 2 blocks
        val picks = p.pickPieces(bits(PIECES - 1), numBlocksWanted = BLOCKS)
        assertEquals(2, picks.size)
        assertContentEquals(listOf(PieceBlock(PIECES - 1, 0), PieceBlock(PIECES - 1, 1)), picks)

        // finishing those 2 blocks finishes the piece
        p.markAsFinished(PieceBlock(PIECES - 1, 0), "A")
        p.markAsFinished(PieceBlock(PIECES - 1, 1), "A")
        assertTrue(p.isPieceFinished(PIECES - 1))
    }

    // -------------------------------------------------------------------------
    // block state enum sanity (cheap guard against state-flow regressions)
    // -------------------------------------------------------------------------

    @Test
    fun downloaderClearedOnPeerDisconnect() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcount(allBits(), "A")
        val b = PieceBlock(0, 0)
        p.markAsDownloading(b, "A")
        assertEquals("A", p.getDownloader(b))

        p.clearPeer("A")
        assertEquals(null, p.getDownloader(b))
        // block is still requested, just with no recorded downloader
        assertEquals(BlockState.REQUESTED, blockStateOf(p, b))
    }

    /** Reads a block's state indirectly through the public predicates. */
    private fun blockStateOf(p: PiecePicker, b: PieceBlock): BlockState = when {
        p.isFinished(b) -> BlockState.FINISHED
        p.isDownloaded(b) -> BlockState.WRITING
        p.isRequested(b) -> BlockState.REQUESTED
        else -> BlockState.NONE
    }
}
