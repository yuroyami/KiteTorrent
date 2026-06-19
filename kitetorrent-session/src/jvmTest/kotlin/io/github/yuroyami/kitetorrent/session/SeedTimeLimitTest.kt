package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo
import io.github.yuroyami.kitetorrent.session.engine.TorrentSession
import io.github.yuroyami.kitetorrent.session.engine.TorrentState
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.torrent.resume.AddTorrentParams
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
 * Seed-time limit wiring: a torrent that starts complete (SEEDING) and is given a 1-second
 * seed-time cap auto-pauses once the maintenance tick accrues that much seeding time —
 * proving [io.github.yuroyami.kitetorrent.session.engine.SeedingLimits] is consulted live.
 */
class SeedTimeLimitTest {

    private val pieceLength = 32 * 1024

    private fun buildTorrent(data: ByteArray): TorrentInfo {
        val numPieces = (data.size + pieceLength - 1) / pieceLength
        val pieces = ByteArrayBuilder()
        for (p in 0 until numPieces) {
            val s = p * pieceLength
            pieces.append(Hasher.hash(data, s, minOf(pieceLength, data.size - s)).toByteArray())
        }
        val info = Entry.dict().apply {
            this["name"] = Entry.of("f.bin")
            this["piece length"] = Entry.of(pieceLength.toLong())
            this["length"] = Entry.of(data.size.toLong())
            this["pieces"] = Entry.of(pieces.toByteArray())
        }
        return TorrentInfo.parse(Bencode.encode(Entry.dict().apply { this["info"] = info }))
    }

    @Test
    fun seederAutoPausesAtSeedTimeLimit() = runBlocking {
        val torrent = buildTorrent(ByteArray(70_000) { (it and 0xff).toByte() })
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val allHave = Bitfield(torrent.numPieces).apply { setAll() }

        val session = TorrentSession(
            torrent = torrent,
            disk = InMemoryDiskIo(torrent.storage),
            network = NetworkRuntime(Dispatchers.IO),
            scope = scope,
            peerId = Digest32.of(ByteArray(20) { 2 }),
            tickIntervalMs = 50, // fast ticks so a 1s seed-time cap is reached quickly
            resumeData = AddTorrentParams(havePieces = allHave, verifiedPieces = allHave),
        ).apply { seedTimeLimitSeconds = 1 }

        session.start()
        assertEquals(TorrentState.SEEDING, session.state, "starts complete → seeding")

        withTimeout(10_000) {
            while (session.state != TorrentState.PAUSED) delay(25)
        }
        assertEquals(TorrentState.PAUSED, session.state, "must auto-pause at the seed-time limit")

        scope.cancel()
    }
}
