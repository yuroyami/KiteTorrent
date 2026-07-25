package io.github.yuroyami.kitetorrent.session.disk

/**
 * Random-access file handle. This is the one thing a torrent client needs from the
 * filesystem that has no common-stdlib equivalent: reading and writing at an
 * arbitrary byte offset. kotlinx-io's `FileSystem` is stream-oriented, so this is a
 * small platform `expect`/`actual` (RandomAccessFile on JVM/Android, POSIX
 * `fseeko`/`fread`/`fwrite` on Apple).
 *
 * libtorrent fills the same seam with `pread` and `pwrite` (mmap_storage.cpp).
 */
expect class RandomAccessStorage(path: String) {
    /** Read up to [length] bytes at file [offset] into [into]; returns bytes read (may be < length at EOF). */
    fun readAt(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int

    /** Write [length] bytes from [from] at file [offset], extending the file if needed. */
    fun writeAt(offset: Long, from: ByteArray, fromOffset: Int, length: Int)

    /** Current file length in bytes. */
    fun length(): Long

    /** Flush buffered writes to durable storage. */
    fun flush()

    /** Close the handle. */
    fun close()
}

/** Create parent directories for [path] if needed (platform-specific). */
expect fun ensureParentDirectories(path: String)
