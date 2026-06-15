package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Proves [FileDiskIo] writes/reads real files and maps a piece across a file boundary. */
class FileDiskIoTest {

    @Test
    fun writesAndReadsRealFilesAcrossFileBoundary() = runBlocking {
        // multi-file torrent: two files, 30000 + 30000 bytes, piece length 16384.
        // Piece 1 (bytes 16384..32767) straddles the boundary at 30000.
        val pieceLength = 16384
        val fileA = ByteArray(30_000) { (it and 0x7f).toByte() }
        val fileB = ByteArray(30_000) { ((it * 3 + 1) and 0x7f).toByte() }
        val whole = fileA + fileB
        val numPieces = (whole.size + pieceLength - 1) / pieceLength

        val pieces = ByteArrayBuilder()
        for (p in 0 until numPieces) {
            val s = p * pieceLength
            pieces.append(Hasher.hash(whole, s, minOf(pieceLength, whole.size - s)).toByteArray())
        }
        val info = Entry.dict().apply {
            this["name"] = Entry.of("data")
            this["piece length"] = Entry.of(pieceLength.toLong())
            this["pieces"] = Entry.of(pieces.toByteArray())
            this["files"] = Entry.list().apply {
                items.add(Entry.dict().apply {
                    this["length"] = Entry.of(30_000L)
                    this["path"] = Entry.list().apply { items.add(Entry.of("a.bin")) }
                })
                items.add(Entry.dict().apply {
                    this["length"] = Entry.of(30_000L)
                    this["path"] = Entry.list().apply { items.add(Entry.of("b.bin")) }
                })
            }
        }
        val torrent = TorrentInfo.parse(Bencode.encode(Entry.dict().apply { this["info"] = info }))

        val dir = File(System.getProperty("java.io.tmpdir"), "kitetorrent-test-${hashCode()}")
        val disk = FileDiskIo(torrent.storage, dir.absolutePath, Dispatchers.IO)
        try {
            // write every piece
            for (p in 0 until numPieces) {
                val s = p * pieceLength
                val len = minOf(pieceLength, whole.size - s)
                disk.write(p, 0, whole.copyOfRange(s, s + len))
            }
            disk.flush()

            // the two real files exist on disk with the right bytes
            assertTrue(File(dir, "data/a.bin").exists())
            assertTrue(File(dir, "data/b.bin").exists())
            assertTrue(File(dir, "data/a.bin").readBytes().contentEquals(fileA))
            assertTrue(File(dir, "data/b.bin").readBytes().contentEquals(fileB))

            // read the boundary-straddling piece 1 back and check it matches + hashes
            val piece1 = disk.read(1, 0, pieceLength)
            assertTrue(piece1.contentEquals(whole.copyOfRange(pieceLength, 2 * pieceLength)))
            assertEquals(torrent.pieceHashV1(1), disk.hashPiece(1).v1)
        } finally {
            disk.close()
            File(dir, "data/a.bin").delete()
            File(dir, "data/b.bin").delete()
            File(dir, "data").delete()
            dir.delete()
        }
    }
}
