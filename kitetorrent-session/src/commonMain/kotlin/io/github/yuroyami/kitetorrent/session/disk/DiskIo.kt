package io.github.yuroyami.kitetorrent.session.disk

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash

/** A block within a piece: [piece] index, byte [offset] into the piece, and [length]. */
data class BlockRequest(val piece: Int, val offset: Int, val length: Int)

/** Result of hashing a piece: its v1 SHA-1 and (for v2 torrents) per-16KiB-block SHA-256 leaves. */
data class PieceHashes(val v1: Sha1Hash?, val v2Leaves: List<Sha256Hash>)

/**
 * The disk subsystem boundary — the Kotlin counterpart of libtorrent's
 * `disk_interface` (disk_interface.hpp). libtorrent dispatches each operation to a
 * thread pool and hands back the result through a completion callback; here every
 * operation simply suspends, so the caller writes straight-line coroutine code and
 * the implementation is free to run blocking file I/O on an I/O dispatcher.
 *
 * Implementations live per-platform (a kotlinx-io-backed one for JVM/Android/native);
 * this interface keeps the engine itself storage-agnostic, exactly as libtorrent's
 * does, which is what makes an in-memory test double trivial.
 */
interface DiskIo {

    /** Read [length] bytes at [offset] within [piece]. */
    suspend fun read(piece: Int, offset: Int, length: Int): ByteArray

    /** Write [data] at [offset] within [piece]. The bytes are owned by the callee after the call. */
    suspend fun write(piece: Int, offset: Int, data: ByteArray)

    /** Hash a fully-downloaded [piece] for verification against the torrent's hashes. */
    suspend fun hashPiece(piece: Int): PieceHashes

    /** Which pieces do we already have on disk? Drives the initial resume/recheck. */
    suspend fun checkExistingFiles(): BooleanArray

    /** Flush any buffered writes to durable storage. */
    suspend fun flush()

    /** Release file handles and stop. */
    suspend fun close()
}
