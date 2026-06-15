package io.github.yuroyami.kitetorrent.picker

/**
 * Identifies a single block within a piece — pure-Kotlin port of libtorrent's
 * `piece_block` (include/libtorrent/piece_block.hpp).
 *
 * A *piece* is the unit the .torrent metadata hashes; a *block* is the unit a
 * peer actually requests over the wire (16 KiB by default). [pieceIndex] is a
 * plain piece index and [blockIndex] is the 0-based block offset inside that
 * piece. Both are plain [Int] (v0 KiteTorrent does not use strong index types).
 *
 * The C++ type defines a total order (piece first, then block) and value
 * equality; we mirror both so blocks can live in sorted/hashed containers.
 */
data class PieceBlock(val pieceIndex: Int, val blockIndex: Int) : Comparable<PieceBlock> {

    override fun compareTo(other: PieceBlock): Int {
        // piece_block::operator< : compare by piece, then by block.
        val byPiece = pieceIndex.compareTo(other.pieceIndex)
        return if (byPiece != 0) byPiece else blockIndex.compareTo(other.blockIndex)
    }

    companion object {
        /**
         * The sentinel "invalid" block. libtorrent uses
         * `{numeric_limits<piece_index_t>::max(), numeric_limits<int>::max()}`.
         */
        val INVALID: PieceBlock = PieceBlock(Int.MAX_VALUE, Int.MAX_VALUE)
    }
}
