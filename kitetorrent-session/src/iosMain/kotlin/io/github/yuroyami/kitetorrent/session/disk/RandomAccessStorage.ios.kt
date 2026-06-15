@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kitetorrent.session.disk

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.posix.fwrite
import platform.posix.mkdir

/** Apple (iOS/macOS) random-access file via POSIX stdio with positional seeks. */
actual class RandomAccessStorage actual constructor(path: String) {
    // "r+b" opens existing for read+write; if it doesn't exist, "w+b" creates it.
    private val fp = fopen(path, "r+b") ?: fopen(path, "w+b")
        ?: throw RuntimeException("cannot open file: $path")

    actual fun readAt(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
        if (length == 0) return 0
        fseeko(fp, offset.convert(), SEEK_SET)
        return into.usePinned { pinned ->
            fread(pinned.addressOf(intoOffset), 1.convert(), length.convert(), fp).toInt()
        }
    }

    actual fun writeAt(offset: Long, from: ByteArray, fromOffset: Int, length: Int) {
        if (length == 0) return
        fseeko(fp, offset.convert(), SEEK_SET)
        from.usePinned { pinned ->
            fwrite(pinned.addressOf(fromOffset), 1.convert(), length.convert(), fp)
        }
    }

    actual fun length(): Long {
        val cur = ftello(fp)
        fseeko(fp, 0.convert(), SEEK_END)
        val end = ftello(fp)
        fseeko(fp, cur, SEEK_SET)
        return end.toLong()
    }

    actual fun flush() {
        fflush(fp)
    }

    actual fun close() {
        fclose(fp)
    }
}

actual fun ensureParentDirectories(path: String) {
    val slash = path.lastIndexOf('/')
    if (slash <= 0) return
    val absolute = path.startsWith("/")
    var cur = if (absolute) "" else "."
    for (part in path.substring(0, slash).split('/')) {
        if (part.isEmpty()) continue
        cur += "/$part"
        mkdir(cur, 0x1FFu.convert()) // 0777; ignore EEXIST
    }
}
