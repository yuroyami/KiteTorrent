package io.github.yuroyami.kitetorrent.torrent

import io.github.yuroyami.kitetorrent.Sha256Hash

/**
 * One file within a torrent — port of the per-file data libtorrent keeps in
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
) {
    /** Just the file name, without directories. */
    val name: String get() = path.substringAfterLast('/')

    override fun toString(): String = "FileEntry($path, $size bytes @ $offset)"
}

/**
 * The set of files in a torrent and the geometry that ties them to pieces — port of
 * libtorrent's `file_storage`. For a single-file torrent there is exactly one entry
 * whose path is the torrent name.
 */
class FileStorage internal constructor(
    /** The torrent name — the file name for single-file, the root directory for multi-file. */
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

    /** Size of piece [index]; the final piece is usually shorter than [pieceLength]. */
    fun pieceSize(index: Int): Int {
        require(index in 0 until numPieces) { "piece index $index out of range [0,$numPieces)" }
        return if (index == numPieces - 1) {
            val rem = (totalSize - (numPieces - 1).toLong() * pieceLength).toInt()
            if (rem == 0) pieceLength else rem
        } else pieceLength
    }
}
