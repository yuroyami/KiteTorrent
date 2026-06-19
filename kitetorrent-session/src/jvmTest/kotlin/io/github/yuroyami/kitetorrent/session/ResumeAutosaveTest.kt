package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo
import io.github.yuroyami.kitetorrent.session.engine.TorrentSession
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.torrent.resume.AddTorrentParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Resume autosave trigger: the engine asks the app to persist fast-resume data exactly when
 * it goes stale — on pause, and on finishing (the transition to SEEDING). Completes the
 * fast-resume feature so an app never has to poll to know when to save.
 */
class ResumeAutosaveTest {

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

    private fun session(torrent: TorrentInfo, scope: CoroutineScope, resume: AddTorrentParams? = null) =
        TorrentSession(
            torrent = torrent,
            disk = InMemoryDiskIo(torrent.storage),
            network = NetworkRuntime(Dispatchers.IO),
            scope = scope,
            peerId = Digest32.of(ByteArray(20) { 1 }),
            resumeData = resume,
        )

    @Test
    fun autosaveFiresOnPause() = runBlocking {
        val torrent = buildTorrent(ByteArray(70_000) { (it and 0xff).toByte() })
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var saves = 0
        val s = session(torrent, scope).apply { onNeedSaveResume = { saves++ } }
        s.pause()
        assertTrue(saves >= 1, "pausing must request a resume-data save")
        scope.cancel()
    }

    @Test
    fun autosaveFiresOnFinish() = runBlocking {
        val torrent = buildTorrent(ByteArray(70_000) { (it and 0xff).toByte() })
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var saves = 0
        // resume data with every piece present → start() goes straight to SEEDING
        val allHave = Bitfield(torrent.numPieces).apply { setAll() }
        val resume = AddTorrentParams(havePieces = allHave, verifiedPieces = allHave)
        val s = session(torrent, scope, resume).apply { onNeedSaveResume = { saves++ } }
        s.start()
        assertTrue(saves >= 1, "finishing (reaching SEEDING) must request a resume-data save")
        scope.cancel()
    }
}
