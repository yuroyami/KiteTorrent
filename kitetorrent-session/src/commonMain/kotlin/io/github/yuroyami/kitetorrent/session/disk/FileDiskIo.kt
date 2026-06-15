package io.github.yuroyami.kitetorrent.session.disk

import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.crypto.Hasher256
import io.github.yuroyami.kitetorrent.torrent.FileStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [DiskIo] backed by real files under [basePath], via the platform
 * [RandomAccessStorage]. Ports the piece↔file mapping logic of libtorrent's
 * `default_storage`/`mmap_storage` (storage_utils.cpp): the torrent is a single
 * concatenated byte stream, and a piece read/write fans out across whichever files
 * its byte range overlaps.
 *
 * Blocking file syscalls run on [dispatcher] (pass `Dispatchers.IO` on JVM/Android).
 *
 * Resume note: [checkExistingFiles] currently reports nothing as present (a fresh
 * download). Verified resume — hashing the on-disk pieces against the torrent hashes —
 * is a small follow-up that belongs in the engine's recheck path, since the expected
 * hashes live there, not here.
 */
class FileDiskIo(
    private val storage: FileStorage,
    private val basePath: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DiskIo {

    private val pieceLength = storage.pieceLength
    private val handles = HashMap<Int, RandomAccessStorage>()

    private fun handleFor(fileIndex: Int): RandomAccessStorage = handles.getOrPut(fileIndex) {
        val full = joinPath(basePath, storage.files[fileIndex].path)
        ensureParentDirectories(full)
        RandomAccessStorage(full)
    }

    /** Walk the file segments that [length] bytes starting at absolute [absStart] cover. */
    private inline fun forEachSegment(
        absStart: Long,
        length: Int,
        action: (fileIndex: Int, fileOffset: Long, len: Int, bufOffset: Int) -> Unit,
    ) {
        var pos = absStart
        var remaining = length
        var bufOff = 0
        val files = storage.files
        for (idx in files.indices) {
            if (remaining <= 0) break
            val f = files[idx]
            val fEnd = f.offset + f.size
            if (pos >= fEnd) continue
            // files are contiguous in the concatenated stream, so once pos < fEnd we overlap here
            val inFileOffset = pos - f.offset
            val take = minOf(remaining.toLong(), fEnd - pos).toInt()
            if (take > 0) {
                action(idx, inFileOffset, take, bufOff)
                pos += take
                bufOff += take
                remaining -= take
            }
        }
    }

    override suspend fun read(piece: Int, offset: Int, length: Int): ByteArray = withContext(dispatcher) {
        val out = ByteArray(length)
        val absStart = piece.toLong() * pieceLength + offset
        forEachSegment(absStart, length) { idx, fOff, len, bOff ->
            var done = 0
            while (done < len) {
                val n = handleFor(idx).readAt(fOff + done, out, bOff + done, len - done)
                if (n <= 0) break // hole (unwritten) — leave as zero
                done += n
            }
        }
        out
    }

    override suspend fun write(piece: Int, offset: Int, data: ByteArray): Unit = withContext(dispatcher) {
        val absStart = piece.toLong() * pieceLength + offset
        forEachSegment(absStart, data.size) { idx, fOff, len, bOff ->
            handleFor(idx).writeAt(fOff, data, bOff, len)
        }
    }

    override suspend fun hashPiece(piece: Int): PieceHashes = withContext(dispatcher) {
        val len = storage.pieceSize(piece)
        val bytes = read(piece, 0, len)
        val leaves = ArrayList<Sha256Hash>()
        var o = 0
        while (o < len) {
            val blk = minOf(BLOCK_SIZE, len - o)
            leaves.add(Hasher256.hash(bytes, o, blk))
            o += blk
        }
        PieceHashes(Hasher.hash(bytes, 0, len), leaves)
    }

    override suspend fun checkExistingFiles(): BooleanArray = BooleanArray(storage.numPieces)

    override suspend fun flush(): Unit = withContext(dispatcher) {
        for (h in handles.values) h.flush()
    }

    override suspend fun close(): Unit = withContext(dispatcher) {
        for (h in handles.values) h.close()
        handles.clear()
    }

    private fun joinPath(base: String, rel: String): String =
        if (base.endsWith("/")) base + rel else "$base/$rel"

    companion object {
        const val BLOCK_SIZE = 16 * 1024
    }
}
