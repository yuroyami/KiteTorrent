package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo
import io.github.yuroyami.kitetorrent.session.engine.TorrentSession
import io.github.yuroyami.kitetorrent.session.engine.TorrentState
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wave 5: verified resume (recheck only claims hash-valid pieces) + the state machine. */
class Wave5RobustnessTest {

    private val pieceLength = 32 * 1024

    private fun buildTorrent(name: String, data: ByteArray): TorrentInfo {
        val numPieces = (data.size + pieceLength - 1) / pieceLength
        val pieces = ByteArrayBuilder()
        for (p in 0 until numPieces) {
            val s = p * pieceLength
            pieces.append(Hasher.hash(data, s, minOf(pieceLength, data.size - s)).toByteArray())
        }
        val info = Entry.dict().apply {
            this["name"] = Entry.of(name)
            this["piece length"] = Entry.of(pieceLength.toLong())
            this["length"] = Entry.of(data.size.toLong())
            this["pieces"] = Entry.of(pieces.toByteArray())
        }
        return TorrentInfo.parse(Bencode.encode(Entry.dict().apply { this["info"] = info }))
    }

    private suspend fun writePiece(disk: InMemoryDiskIo, t: TorrentInfo, data: ByteArray, p: Int, corrupt: Boolean) {
        val s = p * pieceLength
        val bytes = data.copyOfRange(s, s + t.storage.pieceSize(p))
        if (corrupt) bytes[0] = (bytes[0] + 1).toByte()
        disk.write(p, 0, bytes)
    }

    @Test
    fun recheckClaimsOnlyHashValidPieces() = runBlocking {
        val data = ByteArray(80_000) { ((it * 17 + 3) and 0xff).toByte() } // 3 pieces
        val torrent = buildTorrent("x.bin", data)
        val runtime = NetworkRuntime(coroutineContext)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        val disk = InMemoryDiskIo(torrent.storage)

        val last = torrent.numPieces - 1
        for (p in 0 until torrent.numPieces) writePiece(disk, torrent, data, p, corrupt = (p == last))

        val session = TorrentSession(torrent, disk, runtime, workers, listenPort = 0)
        session.recheck() // VERIFIES each piece against its hash

        assertEquals(torrent.numPieces - 1, session.numHave()) // the corrupted last piece is rejected
        assertTrue(session.have[0]); assertTrue(session.have[1]); assertFalse(session.have[last])
        assertEquals(TorrentState.DOWNLOADING, session.state)

        workers.cancel(); runtime.close()
    }

    @Test
    fun fullyValidDiskRechecksToSeedingThenPauses() = runBlocking {
        val data = ByteArray(80_000) { ((it * 29 + 5) and 0xff).toByte() }
        val torrent = buildTorrent("y.bin", data)
        val runtime = NetworkRuntime(coroutineContext)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        val disk = InMemoryDiskIo(torrent.storage)
        for (p in 0 until torrent.numPieces) writePiece(disk, torrent, data, p, corrupt = false)

        val session = TorrentSession(torrent, disk, runtime, workers, listenPort = 0)
        session.recheck()
        assertTrue(session.isSeeding())
        assertEquals(TorrentState.SEEDING, session.state)

        session.pause()
        assertEquals(TorrentState.PAUSED, session.state)

        workers.cancel(); runtime.close()
    }

    @Test
    fun corruptDiskRechecksToNothing() = runBlocking {
        val data = ByteArray(40_000) { ((it * 11 + 1) and 0xff).toByte() } // 2 pieces
        val torrent = buildTorrent("z.bin", data)
        val runtime = NetworkRuntime(coroutineContext)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        val disk = InMemoryDiskIo(torrent.storage)
        for (p in 0 until torrent.numPieces) writePiece(disk, torrent, data, p, corrupt = true)

        val session = TorrentSession(torrent, disk, runtime, workers, listenPort = 0)
        session.recheck()
        assertEquals(0, session.numHave())
        assertEquals(TorrentState.DOWNLOADING, session.state)

        workers.cancel(); runtime.close()
    }
}
