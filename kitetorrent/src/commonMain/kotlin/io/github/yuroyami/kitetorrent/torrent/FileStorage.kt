package io.github.yuroyami.kitetorrent.torrent

import io.github.yuroyami.kitetorrent.Sha256Hash

/**
 * One file within a torrent: port of the per-file data libtorrent keeps in
 * `file_storage` (file_storage.hpp). [offset] is the file's start within the
 * torrent's single concatenated byte stream, which is what maps pieces to files.
 */
class FileEntry(
    /** Full path, '/'-separated, including the torrent's root directory for multi-file torrents. */
    val path: String,
    /** Size of the file in bytes. */
    val size: Long,
    /** Byte offset of this file within the concatenated torrent data. */
    val offset: Long,
    /** v2 per-file merkle root ("pieces root"), or null for v1 torrents. */
    val piecesRoot: Sha256Hash? = null,
    /** True if this is a BEP-47 padding file (`attr` contains 'p'). */
    val isPadFile: Boolean = false,
    /** Symlink target path if this entry is a symlink (`attr` contains 'l'), else null. */
    val symlinkTarget: String? = null,
    /** True if BEP-47 marks the file executable (`attr` contains 'x'). */
    val isExecutable: Boolean = false,
    /** True if BEP-47 marks the file hidden (`attr` contains 'h'). */
    val isHidden: Boolean = false,
) {
    /** Just the file name, without directories. */
    val name: String get() = path.substringAfterLast('/')

    override fun toString(): String = "FileEntry($path, $size bytes @ $offset)"
}

/**
 * The set of files in a torrent and the geometry that ties them to pieces: port of
 * libtorrent's `file_storage`. For a single-file torrent there is exactly one entry
 * whose path is the torrent name.
 */
class FileStorage internal constructor(
    /** The torrent name: the file name for single-file, the root directory for multi-file. */
    val name: String,
    /** Piece size in bytes (a power of two for well-formed torrents). */
    val pieceLength: Int,
    /** Files in their canonical order. */
    val files: List<FileEntry>,
) {
    /** Sum of all file sizes. */
    val totalSize: Long = files.sumOf { it.size }

    val numFiles: Int get() = files.size

    /** Number of pieces covering the concatenated data. */
    val numPieces: Int =
        if (pieceLength > 0 && totalSize > 0) ((totalSize + pieceLength - 1) / pieceLength).toInt() else 0

    /** True for a single-file torrent. */
    val isSingleFile: Boolean get() = files.size == 1 && files[0].path == name

    /** True if the geometry is usable (positive piece length). */
    fun isValid(): Boolean = pieceLength > 0

    // --- per-file piece mapping (BEP-52 / hybrid alignment view) -------------
    //
    // In a v2 / hybrid torrent every (non-pad) file begins on a piece boundary. Pad
    // files in the v1 list, and the v2 file tree's natural per-file trees, enforce
    // this. libtorrent exposes this through file_storage::file_index_at_piece,
    // map_file, file_piece_range, etc. The classic v1-flat geometry (numPieces over
    // the concatenated stream) is preserved above; this is the additive aligned view.
    //
    // For each file we record the piece index it starts on. Because v2 files are
    // piece-aligned, fileForPiece is a binary search over these starts. For v1-flat
    // torrents (where files are NOT aligned) the starts still describe which file the
    // *beginning* of a piece falls in, which matches libtorrent's
    // file_index_at_piece semantics.

    /** Piece index on which each file starts (file [i] begins at [fileStartPiece]`[i]`). */
    private val fileStartPiece: IntArray = IntArray(files.size) { i ->
        if (pieceLength > 0) (files[i].offset / pieceLength).toInt() else 0
    }

    /**
     * The file index that owns the byte range at the *start* of piece [piece]: port of
     * `file_storage::file_index_at_piece`. For v2/hybrid (piece-aligned) torrents this is
     * the file the whole piece belongs to. Returns -1 for an out-of-range piece or when
     * there are no files. Pad files are included (they occupy real pieces in the layout).
     */
    fun fileForPiece(piece: Int): Int {
        if (piece < 0 || piece >= numPieces || files.isEmpty()) return -1
        // binary search: largest fileStartPiece <= piece.
        var lo = 0
        var hi = files.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (fileStartPiece[mid] <= piece) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** Alias matching libtorrent's `file_index_at_piece`. */
    fun fileIndexAtPiece(piece: Int): Int = fileForPiece(piece)

    /** The piece index this file starts on (`file_offset / piece_length`). */
    fun firstPieceOf(fileIndex: Int): Int {
        require(fileIndex in files.indices) { "file index $fileIndex out of range [0,${files.size})" }
        return fileStartPiece[fileIndex]
    }

    /**
     * The piece range covering file [fileIndex], inclusive at both ends. The range runs
     * from the piece holding the file's first byte to the piece holding its last byte.
     * This is analogous to `file_storage::file_piece_range`. A zero-length file returns
     * [IntRange.EMPTY].
     */
    fun pieceRangeForFile(fileIndex: Int): IntRange {
        require(fileIndex in files.indices) { "file index $fileIndex out of range [0,${files.size})" }
        val f = files[fileIndex]
        if (f.size <= 0L || pieceLength <= 0) return IntRange.EMPTY
        val first = (f.offset / pieceLength).toInt()
        val last = ((f.offset + f.size - 1) / pieceLength).toInt()
        return first..last
    }

    /** Number of pieces (partial pieces counted) that file [fileIndex] spans. */
    fun numPiecesInFile(fileIndex: Int): Int {
        val r = pieceRangeForFile(fileIndex)
        return if (r.isEmpty()) 0 else r.last - r.first + 1
    }

    /**
     * Number of 16 KiB merkle leaf blocks in file [fileIndex]: port of
     * `file_storage::file_num_blocks`. Rounds the file size up to whole blocks.
     */
    fun numBlocksInFile(fileIndex: Int): Int {
        require(fileIndex in files.indices) { "file index $fileIndex out of range [0,${files.size})" }
        val size = files[fileIndex].size
        if (size <= 0L) return 0
        return ((size + DEFAULT_BLOCK_SIZE - 1) / DEFAULT_BLOCK_SIZE).toInt()
    }

    /**
     * Number of v2 pieces in file [fileIndex]: port of `file_storage::file_num_pieces`.
     * Equivalent to [numPiecesInFile] but computed from the file size alone (independent of
     * the concatenated-stream offset), matching how v2 per-file trees are laid out.
     */
    fun fileNumPieces(fileIndex: Int): Int {
        require(fileIndex in files.indices) { "file index $fileIndex out of range [0,${files.size})" }
        val size = files[fileIndex].size
        if (size <= 0L || pieceLength <= 0) return 0
        return ((size + pieceLength - 1) / pieceLength).toInt()
    }

    /** Blocks per piece for the v2 merkle layout (`pieceLength / 16KiB`, at least 1). */
    val blocksPerPiece: Int get() = if (pieceLength >= DEFAULT_BLOCK_SIZE) pieceLength / DEFAULT_BLOCK_SIZE else 1

    /** True if file [fileIndex] is a BEP-47 padding file (not downloadable content). */
    fun isPadFile(fileIndex: Int): Boolean {
        require(fileIndex in files.indices) { "file index $fileIndex out of range [0,${files.size})" }
        return files[fileIndex].isPadFile
    }

    /** The piece-layer offset of file [fileIndex]'s piece [pieceInFile] within that file's tree. */
    fun pieceInFileToFilePiece(fileIndex: Int, pieceInFile: Int): Int = firstPieceOf(fileIndex) + pieceInFile

    /** Size of piece [index]; the final piece is usually shorter than [pieceLength]. */
    fun pieceSize(index: Int): Int {
        require(index in 0 until numPieces) { "piece index $index out of range [0,$numPieces)" }
        return if (index == numPieces - 1) {
            val rem = (totalSize - (numPieces - 1).toLong() * pieceLength).toInt()
            if (rem == 0) pieceLength else rem
        } else pieceLength
    }

    companion object {
        /** v2 merkle leaf size (16 KiB), called `default_block_size` in libtorrent. */
        const val DEFAULT_BLOCK_SIZE: Int = 16 * 1024

        /**
         * Build a storage layout from an explicit file list. This is the public entry
         * point for creating a torrent with [CreateTorrent]. The constructor is internal
         * to keep parse-time construction in one place, so this factory is the supported
         * builder.
         */
        fun of(name: String, pieceLength: Int, files: List<FileEntry>): FileStorage =
            FileStorage(name, pieceLength, files)
    }
}
