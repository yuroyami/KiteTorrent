package io.github.yuroyami.kitetorrent.session.disk

import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.crypto.Hasher256
import io.github.yuroyami.kitetorrent.torrent.FileStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * A [DiskIo] backed by real files under [basePath], via the platform
 * [RandomAccessStorage]. Ports the piece↔file mapping logic of libtorrent's
 * `default_storage`/`mmap_storage` (storage_utils.cpp): the torrent is a single
 * concatenated byte stream, and a piece read/write fans out across whichever files
 * its byte range overlaps.
 *
 * Blocking file syscalls run on [dispatcher] (defaults to [Dispatchers.Default]; callers on
 * JVM/Android should inject `Dispatchers.IO` — it is not available in `commonMain`).
 *
 * Concurrency: a shared [RandomAccessStorage] does an internal `seek` then `read`/
 * `write`, so two concurrent peers hitting the same file handle would interleave a
 * seek from one with the read from the other and corrupt data. Every handle is
 * therefore guarded by its own [Mutex] making each seek+transfer atomic, and the
 * handle map itself is guarded so lazy-open is race-free.
 *
 * Part-file: blocks that belong to excluded (priority-0) files or BEP-47 padding
 * files are not written to the real files. They are routed into a side `<name>.parts`
 * file (libtorrent's `part_file`, part_file.cpp), keyed by piece index, so the data
 * is still readable for hashing/verification and is reclaimed when the file's
 * priority later rises. This mirrors libtorrent's behavior of never touching the
 * destination file of a priority-0 file.
 *
 * Allocation: [storageMode] chooses sparse files (the default) or full preallocation
 * (`ALLOCATE`), matching libtorrent's `storage_mode_t`.
 *
 * Resume note: [checkExistingFiles] currently reports nothing as present (a fresh
 * download). Verified resume — hashing the on-disk pieces against the torrent hashes —
 * is a small follow-up that belongs in the engine's recheck path, since the expected
 * hashes live there, not here.
 */
class FileDiskIo(
    private val storage: FileStorage,
    basePath: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Per-file download priority; index 0 means the file is excluded (routed to the part-file). Null = all included. */
    private val filePriorities: IntArray? = null,
    /** Sparse (default) or full preallocation. */
    private val storageMode: StorageMode = StorageMode.SPARSE,
) : DiskIo {

    private val pieceLength = storage.pieceLength

    /** Current save path; mutated by [move]. */
    private var basePath: String = basePath

    /** Per-file path overrides set by [rename] (torrent-relative). */
    private val renamedPaths = HashMap<Int, String>()

    /** Guards [handles] and [partFile] lifecycle (open/close/clear). */
    private val mapLock = Mutex()
    private val handles = HashMap<Int, RandomAccessStorage>()

    /** Per-file lock making seek+read / seek+write atomic on the shared handle. */
    private val fileLocks = HashMap<Int, Mutex>()

    /** Files preallocated already (only relevant in [StorageMode.ALLOCATE]). */
    private val allocated = HashSet<Int>()

    // ---- part-file state (excluded / pad blocks) -------------------------------------

    private var partHandle: RandomAccessStorage? = null
    private val partLock = Mutex()
    /** Piece index → slot in the part-file. */
    private val partSlots = HashMap<Int, Int>()
    private var partNextSlot = 0

    private fun relPath(fileIndex: Int): String = renamedPaths[fileIndex] ?: storage.files[fileIndex].path

    private fun isExcluded(fileIndex: Int): Boolean {
        if (storage.files[fileIndex].isPadFile) return true
        val p = filePriorities ?: return false
        return fileIndex < p.size && p[fileIndex] == 0
    }

    private fun lockFor(fileIndex: Int): Mutex = fileLocks.getOrPut(fileIndex) { Mutex() }

    /** Open (or fetch) the handle for [fileIndex]; caller must hold [mapLock]. */
    private fun openHandleLocked(fileIndex: Int): RandomAccessStorage = handles.getOrPut(fileIndex) {
        val full = joinPath(basePath, relPath(fileIndex))
        ensureParentDirectories(full)
        val h = RandomAccessStorage(full)
        if (storageMode == StorageMode.ALLOCATE && allocated.add(fileIndex)) {
            val size = storage.files[fileIndex].size
            if (size > 0 && h.length() < size) {
                // extend the file to its full size by writing a single byte at the tail
                h.writeAt(size - 1, ONE_ZERO, 0, 1)
            }
        }
        h
    }

    private suspend fun handleFor(fileIndex: Int): RandomAccessStorage =
        mapLock.withLock { openHandleLocked(fileIndex) }

    private fun partFileLocked(): RandomAccessStorage = partHandle ?: run {
        val full = joinPath(basePath, "${storage.name}.parts")
        ensureParentDirectories(full)
        RandomAccessStorage(full).also { partHandle = it }
    }

    private fun slotOffset(slot: Int, offsetInPiece: Int): Long =
        slot.toLong() * pieceLength + offsetInPiece

    /** One file's slice of a piece-range transfer. */
    private data class Segment(val fileIndex: Int, val fileOffset: Long, val len: Int, val bufOffset: Int)

    /** The file segments that [length] bytes starting at absolute [absStart] cover. */
    private fun segments(absStart: Long, length: Int): List<Segment> {
        val out = ArrayList<Segment>(2)
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
                out.add(Segment(idx, inFileOffset, take, bufOff))
                pos += take
                bufOff += take
                remaining -= take
            }
        }
        return out
    }

    override suspend fun read(piece: Int, offset: Int, length: Int): ByteArray = withContext(dispatcher) {
        val out = ByteArray(length)
        val absStart = piece.toLong() * pieceLength + offset
        for (seg in segments(absStart, length)) {
            if (isExcluded(seg.fileIndex)) {
                // data for excluded/pad files lives in the part-file at (piece, pieceOffset)
                readPart(piece, offset + seg.bufOffset, out, seg.bufOffset, seg.len)
            } else {
                val h = handleFor(seg.fileIndex)
                lockFor(seg.fileIndex).withLock {
                    var done = 0
                    while (done < seg.len) {
                        val n = h.readAt(seg.fileOffset + done, out, seg.bufOffset + done, seg.len - done)
                        if (n <= 0) break // hole (unwritten) — leave as zero
                        done += n
                    }
                }
            }
        }
        out
    }

    override suspend fun write(piece: Int, offset: Int, data: ByteArray): Unit = withContext(dispatcher) {
        val absStart = piece.toLong() * pieceLength + offset
        for (seg in segments(absStart, data.size)) {
            if (isExcluded(seg.fileIndex)) {
                writePart(piece, offset + seg.bufOffset, data, seg.bufOffset, seg.len)
            } else {
                val h = handleFor(seg.fileIndex)
                lockFor(seg.fileIndex).withLock {
                    h.writeAt(seg.fileOffset, data, seg.bufOffset, seg.len)
                }
            }
        }
    }

    private suspend fun readPart(piece: Int, offsetInPiece: Int, into: ByteArray, intoOff: Int, len: Int) {
        partLock.withLock {
            val slot = partSlots[piece] ?: return@withLock // unwritten → leave zeros
            val h = partFileLocked()
            var done = 0
            while (done < len) {
                val n = h.readAt(slotOffset(slot, offsetInPiece) + done, into, intoOff + done, len - done)
                if (n <= 0) break
                done += n
            }
        }
    }

    private suspend fun writePart(piece: Int, offsetInPiece: Int, from: ByteArray, fromOff: Int, len: Int) {
        partLock.withLock {
            val slot = partSlots.getOrPut(piece) { partNextSlot++ }
            partFileLocked().writeAt(slotOffset(slot, offsetInPiece), from, fromOff, len)
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
        mapLock.withLock {
            for ((idx, h) in handles) lockFor(idx).withLock { h.flush() }
            partLock.withLock { partHandle?.flush() }
        }
    }

    override suspend fun close(): Unit = withContext(dispatcher) {
        mapLock.withLock {
            for ((idx, h) in handles) {
                lockFor(idx).withLock {
                    h.flush()
                    h.close()
                }
            }
            handles.clear()
            partLock.withLock {
                partHandle?.let { it.flush(); it.close() }
                partHandle = null
            }
        }
    }

    override suspend fun move(newSavePath: String): Unit = withContext(dispatcher) {
        if (newSavePath == basePath) return@withContext
        // flush + close every open handle first, libtorrent's move_storage operates on
        // closed files (it relocates the path, then reopens lazily).
        mapLock.withLock {
            for ((idx, h) in handles) lockFor(idx).withLock { h.flush(); h.close() }
            handles.clear()
            allocated.clear()
            partLock.withLock {
                partHandle?.let { it.flush(); it.close() }
                partHandle = null
            }

            SystemFileSystem.createDirectories(Path(newSavePath), mustCreate = false)
            // move each real file that exists
            for (idx in storage.files.indices) {
                if (isExcluded(idx)) continue
                relocate(joinPath(basePath, relPath(idx)), joinPath(newSavePath, relPath(idx)))
            }
            // move the part-file if present
            relocate(joinPath(basePath, "${storage.name}.parts"), joinPath(newSavePath, "${storage.name}.parts"))

            basePath = newSavePath
        }
    }

    /** Move [src] to [dst] if [src] exists, creating destination parent directories. */
    private fun relocate(src: String, dst: String) {
        val from = Path(src)
        if (!SystemFileSystem.exists(from)) return
        ensureParentDirectories(dst)
        val to = Path(dst)
        if (SystemFileSystem.exists(to)) SystemFileSystem.delete(to, mustExist = false)
        SystemFileSystem.atomicMove(from, to)
    }

    override suspend fun rename(fileIndex: Int, newName: String): Unit = withContext(dispatcher) {
        require(fileIndex in storage.files.indices) { "file index $fileIndex out of range" }
        mapLock.withLock {
            // close the open handle (if any) before moving the underlying file
            handles.remove(fileIndex)?.let { h -> lockFor(fileIndex).withLock { h.flush(); h.close() } }
            allocated.remove(fileIndex)

            val oldRel = relPath(fileIndex)
            if (oldRel != newName) relocate(joinPath(basePath, oldRel), joinPath(basePath, newName))
            renamedPaths[fileIndex] = newName
        }
    }

    private fun joinPath(base: String, rel: String): String =
        if (base.endsWith("/")) base + rel else "$base/$rel"

    companion object {
        const val BLOCK_SIZE = 16 * 1024
        private val ONE_ZERO = ByteArray(1)
    }
}
