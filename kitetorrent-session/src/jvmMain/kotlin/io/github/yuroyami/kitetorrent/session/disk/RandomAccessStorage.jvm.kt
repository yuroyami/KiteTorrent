package io.github.yuroyami.kitetorrent.session.disk

import java.io.File
import java.io.RandomAccessFile

/** JVM/desktop random-access file via [RandomAccessFile] (seek + positional read/write). */
actual class RandomAccessStorage actual constructor(path: String) {
    private val raf = RandomAccessFile(path, "rw")

    actual fun readAt(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
        raf.seek(offset)
        return raf.read(into, intoOffset, length)
    }

    actual fun writeAt(offset: Long, from: ByteArray, fromOffset: Int, length: Int) {
        raf.seek(offset)
        raf.write(from, fromOffset, length)
    }

    actual fun length(): Long = raf.length()

    actual fun flush() = raf.fd.sync()

    actual fun close() = raf.close()
}

actual fun ensureParentDirectories(path: String) {
    File(path).parentFile?.mkdirs()
}
