package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.picker.DownloadPriority
import io.github.yuroyami.kitetorrent.picker.PieceBlock
import io.github.yuroyami.kitetorrent.picker.PiecePicker
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests `pick_pieces`'s option modes and end-game tail, ported in
 * [PiecePicker.pickPieces]: sequential / reverse / prioritize-partials ordering,
 * the partial-sprawl cap, and the single busy block appended for end-game mode.
 * No [kotlin.random.Random] is passed, so every ordering is deterministic.
 */
class PiecePickerOptionsTest {

    private val PIECES = 10
    private val BLOCKS = 4

    private fun allBits(): Bitfield {
        val bf = Bitfield(PIECES)
        for (i in 0 until PIECES) bf.setBit(i)
        return bf
    }

    private fun pickerWithASeed(): PiecePicker {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcountAll("seed") // every piece available, equal rarity
        return p
    }

    // -------------------------------------------------------------------------
    // sequential
    // -------------------------------------------------------------------------

    @Test
    fun sequentialPicksInPieceIndexOrder() {
        val p = pickerWithASeed()
        // make piece 7 rarer-looking via an extra peer on everything else; rarest
        // would prefer 7, sequential must ignore availability entirely
        for (i in 0 until PIECES) if (i != 7) p.incRefcount(i, "B")

        val picks = p.pickPieces(allBits(), numBlocksWanted = 8, options = PiecePicker.OPTION_SEQUENTIAL)
        assertContentEquals(
            (0 until 4).map { PieceBlock(0, it) } + (0 until 4).map { PieceBlock(1, it) },
            picks,
        )
    }

    @Test
    fun sequentialReversePicksFromTheEnd() {
        val p = pickerWithASeed()
        val picks = p.pickPieces(
            allBits(), numBlocksWanted = 4,
            options = PiecePicker.OPTION_SEQUENTIAL or PiecePicker.OPTION_REVERSE,
        )
        assertContentEquals((0 until 4).map { PieceBlock(PIECES - 1, it) }, picks)
    }

    @Test
    fun sequentialStillPicksTopPriorityFirst() {
        val p = pickerWithASeed()
        p.setPiecePriority(6, DownloadPriority.TOP_PRIORITY)
        val picks = p.pickPieces(allBits(), numBlocksWanted = 4, options = PiecePicker.OPTION_SEQUENTIAL)
        assertContentEquals((0 until 4).map { PieceBlock(6, it) }, picks)
    }

    // -------------------------------------------------------------------------
    // reverse (snubbed peers): most common pieces first
    // -------------------------------------------------------------------------

    @Test
    fun reverseRarestPicksTheMostCommonPieceFirst() {
        val p = PiecePicker(PIECES, BLOCKS)
        p.incRefcount(2, "A") // piece 2: availability 1 (rare)
        p.incRefcount(5, "A")
        p.incRefcount(5, "B")
        p.incRefcount(5, "C") // piece 5: availability 3 (common)

        val bf = Bitfield(PIECES).apply { setBit(2); setBit(5) }
        val normal = p.pickPieces(bf, numBlocksWanted = 1)
        val reversed = p.pickPieces(bf, numBlocksWanted = 1, options = PiecePicker.OPTION_RAREST_FIRST or PiecePicker.OPTION_REVERSE)
        assertEquals(listOf(PieceBlock(2, 0)), normal, "rarest-first wants the rare piece")
        assertEquals(listOf(PieceBlock(5, 0)), reversed, "reverse wants the common piece")
    }

    // -------------------------------------------------------------------------
    // prioritize_partials
    // -------------------------------------------------------------------------

    @Test
    fun prioritizePartialsDrainsTheStartedPieceFirst() {
        val p = pickerWithASeed()
        // start piece 8 (request one block) — it's now a partial
        assertTrue(p.markAsDownloading(PieceBlock(8, 0), "me"))
        // make piece 3 much rarer than 8 so plain rarest-first would pick 3...
        p.incRefcount(8, "B")
        p.incRefcount(8, "C")

        val picks = p.pickPieces(
            allBits(), numBlocksWanted = 3,
            options = PiecePicker.OPTION_RAREST_FIRST or PiecePicker.OPTION_PRIORITIZE_PARTIALS,
            numPeers = 10,
        )
        // ...but partials-first drains piece 8's remaining free blocks before anything else
        assertContentEquals((1 until 4).map { PieceBlock(8, it) }, picks)
    }

    @Test
    fun partialSprawlCapForcesPartialsEvenWithoutTheFlag() {
        val p = pickerWithASeed()
        // 3 partial pieces with 1 peer (3 > 1 * 3/2) → the cap kicks in
        for (piece in intArrayOf(4, 5, 6)) assertTrue(p.markAsDownloading(PieceBlock(piece, 0), "me"))

        val picks = p.pickPieces(allBits(), numBlocksWanted = 1, numPeers = 1)
        assertEquals(4, picks[0].pieceIndex, "the sprawl cap must steer the pick into a partial piece")
    }

    // -------------------------------------------------------------------------
    // random mode (below initial_picker_threshold): walks from the random start
    // -------------------------------------------------------------------------

    @Test
    fun randomModeWithoutRandomWalksFromPieceZero() {
        val p = pickerWithASeed()
        val picks = p.pickPieces(allBits(), numBlocksWanted = 4, options = 0)
        assertContentEquals((0 until 4).map { PieceBlock(0, it) }, picks)
    }

    // -------------------------------------------------------------------------
    // end-game busy tail
    // -------------------------------------------------------------------------

    @Test
    fun busyBlockIsAppendedWhenNothingFreeRemains() {
        val p = PiecePicker(1, BLOCKS)
        p.incRefcountAll("seed")
        // every block of the only piece is requested by peer A → nothing free
        for (b in 0 until BLOCKS) assertTrue(p.markAsDownloading(PieceBlock(0, b), "A"))

        val bf = Bitfield(1).apply { setBit(0) }
        val picks = p.pickPieces(bf, numBlocksWanted = 2, requesterPeer = "B")
        assertEquals(1, picks.size, "end-game appends exactly one busy block")
        assertTrue(p.numPeers(picks[0]) > 0, "the busy block is detectable via numPeers > 0")
    }

    @Test
    fun busyTailNeverReturnsOurOwnRequests() {
        val p = PiecePicker(1, BLOCKS)
        p.incRefcountAll("seed")
        for (b in 0 until BLOCKS) assertTrue(p.markAsDownloading(PieceBlock(0, b), "A"))

        val bf = Bitfield(1).apply { setBit(0) }
        assertTrue(
            p.pickPieces(bf, numBlocksWanted = 1, requesterPeer = "A").isEmpty(),
            "a peer must not double-request its own outstanding blocks",
        )
    }

    @Test
    fun paroleSuppressesTheBusyTail() {
        val p = PiecePicker(1, BLOCKS)
        p.incRefcountAll("seed")
        for (b in 0 until BLOCKS) assertTrue(p.markAsDownloading(PieceBlock(0, b), "A"))

        val bf = Bitfield(1).apply { setBit(0) }
        val picks = p.pickPieces(
            bf, numBlocksWanted = 1,
            options = PiecePicker.OPTION_RAREST_FIRST or PiecePicker.OPTION_ON_PAROLE,
            requesterPeer = "B",
        )
        assertTrue(picks.isEmpty())
    }

    // -------------------------------------------------------------------------
    // numDownloadingPieces (the strict end-game gate input)
    // -------------------------------------------------------------------------

    @Test
    fun numDownloadingPiecesTracksPartialLifecycle() {
        val p = pickerWithASeed()
        assertEquals(0, p.numDownloadingPieces())

        p.markAsDownloading(PieceBlock(1, 0), "me")
        p.markAsDownloading(PieceBlock(2, 0), "me")
        assertEquals(2, p.numDownloadingPieces())

        // filling a piece moves it out of the downloading bucket (to full)...
        for (b in 1 until BLOCKS) p.markAsDownloading(PieceBlock(1, b), "me")
        assertEquals(1, p.numDownloadingPieces())

        // ...and aborting the other returns it to open
        p.abortDownload(PieceBlock(2, 0), "me")
        assertEquals(0, p.numDownloadingPieces())
    }
}
