package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.engine.TorrentSession
import io.github.yuroyami.kitetorrent.session.engine.TorrentState
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Auto-managed torrent queue (`active_limit`): with the active limit at 2, the third torrent
 * added is queued (paused); when an active torrent is paused, the queued one resumes — so the
 * engine never runs more than `active_limit` torrents at once, FIFO.
 */
class QueueManagementTest {

    private val pieceLength = 32 * 1024

    private fun buildTorrent(seed: Int): TorrentInfo {
        val data = ByteArray(40_000) { ((it * 7 + seed) and 0xff).toByte() }
        val numPieces = (data.size + pieceLength - 1) / pieceLength
        val pieces = ByteArrayBuilder()
        for (p in 0 until numPieces) {
            val s = p * pieceLength
            pieces.append(Hasher.hash(data, s, minOf(pieceLength, data.size - s)).toByteArray())
        }
        val info = Entry.dict().apply {
            this["name"] = Entry.of("f$seed.bin")
            this["piece length"] = Entry.of(pieceLength.toLong())
            this["length"] = Entry.of(data.size.toLong())
            this["pieces"] = Entry.of(pieces.toByteArray())
        }
        return TorrentInfo.parse(Bencode.encode(Entry.dict().apply { this["info"] = info }))
    }

    private suspend fun active(sessions: List<TorrentSession>) = sessions.count { it.state != TorrentState.PAUSED }

    @Test
    fun activeLimitQueuesAndResumesTorrents() = runBlocking {
        val runtime = CoroutineScope(coroutineContext + SupervisorJob())
        val engine = KiteTorrentEngine(runtime, peerId = Digest32.of(ByteArray(20) { 9 }), listenPort = 0)
        engine.activeLimit = 2
        engine.start()

        val sessions = (0..2).map { i -> engine.addTorrent(buildTorrent(i), InMemoryDiskIo(buildTorrent(i).storage)) }

        // three added, limit two → exactly one queued (paused)
        withTimeout(10_000) { while (active(sessions) != 2) delay(25) }
        assertEquals(2, active(sessions), "active_limit=2 caps running torrents at two")
        assertEquals(1, sessions.count { it.state == TorrentState.PAUSED }, "the third torrent is queued")
        val queued = sessions.first { it.state == TorrentState.PAUSED } // the newest (session 2)

        // pause an active torrent → its slot frees → the queued one resumes
        val activeOne = sessions.first { it.state != TorrentState.PAUSED }
        activeOne.pause()
        withTimeout(10_000) { while (queued.state == TorrentState.PAUSED) delay(25) }

        assertEquals(2, active(sessions), "pausing an active torrent lets the queued one resume")
        assertEquals(TorrentState.PAUSED, activeOne.state, "the manually-paused torrent stays paused")

        runtime.cancel()
        engine.shutdown()
    }
}
