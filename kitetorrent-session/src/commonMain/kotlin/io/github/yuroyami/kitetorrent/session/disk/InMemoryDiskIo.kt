package io.github.yuroyami.kitetorrent.session.disk

import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.crypto.Hasher256
import io.github.yuroyami.kitetorrent.torrent.FileStorage

/**
 * A [DiskIo] that keeps the whole torrent in one in-memory buffer. The BitTorrent
 * piece model treats the files as a single concatenated byte stream, so piece `p`
 * lives at `p * pieceLength` within that stream — which is exactly this buffer.
 * Per-file boundaries only matter when writing to real files ([FileDiskIo]); here
 * one buffer is the simplest correct model.
 *
 * This is the engine's test workhorse: deterministic, no I/O, no platform deps.
 */
class InMemoryDiskIo(private val storage: FileStorage) : DiskIo {

    private val pieceLength = storage.pieceLength
    private val total = storage.totalSize
    private val data = ByteArray(total.toInt())
    private val written = BooleanArray(storage.numPieces)

    private fun absolute(piece: Int, offset: Int): Int = piece.toLong().times(pieceLength).plus(offset).toInt()

    override suspend fun read(piece: Int, offset: Int, length: Int): ByteArray {
        val start = absolute(piece, offset)
        return data.copyOfRange(start, start + length)
    }

    override suspend fun write(piece: Int, offset: Int, data: ByteArray) {
        val start = absolute(piece, offset)
        data.copyInto(this.data, start)
        // mark the piece written once its final byte has been filled (best-effort)
        if (offset + data.size >= storage.pieceSize(piece)) written[piece] = true
    }

    override suspend fun hashPiece(piece: Int): PieceHashes {
        val start = piece.toLong().times(pieceLength).toInt()
        val len = storage.pieceSize(piece)
        val v1 = Hasher.hash(data, start, len)

        // v2: SHA-256 of each 16 KiB block in the piece
        val leaves = ArrayList<Sha256Hash>()
        var o = 0
        while (o < len) {
            val blk = minOf(BLOCK_SIZE, len - o)
            leaves.add(Hasher256.hash(data, start + o, blk))
            o += blk
        }
        return PieceHashes(v1, leaves)
    }

    override suspend fun checkExistingFiles(): BooleanArray = written.copyOf()

    override suspend fun flush() {}

    override suspend fun close() {}

    /**
     * The torrent lives in one anonymous buffer addressed by absolute offset, so there
     * are no on-disk paths to relocate; [savePath] is tracked only so callers can read
     * back the current location, mirroring libtorrent's bookkeeping.
     */
    override suspend fun move(newSavePath: String) {
        savePath = newSavePath
    }

    /**
     * File names are not part of the in-memory model (the buffer is addressed by offset),
     * so a rename only updates the recorded name map for inspection by tests.
     */
    override suspend fun rename(fileIndex: Int, newName: String) {
        if (fileIndex in storage.files.indices) renamed[fileIndex] = newName
    }

    /** The current save path (defaults to empty until [move] is called). */
    var savePath: String = ""
        private set

    private val renamed = HashMap<Int, String>()

    /** The effective name of [fileIndex] after any [rename] (its original path otherwise). */
    fun fileName(fileIndex: Int): String = renamed[fileIndex] ?: storage.files[fileIndex].path

    /** Direct access to the backing bytes — for tests asserting written content. */
    fun snapshot(): ByteArray = data.copyOf()

    companion object {
        const val BLOCK_SIZE = 16 * 1024
    }
}
