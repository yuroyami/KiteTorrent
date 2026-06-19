package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.tracker.PeerEndpoint
import io.github.yuroyami.kitetorrent.settings.EncPolicy
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Super-seeding: a seed in super-seed mode advertises only one piece at a time, releasing
 * the next only after the peer announces it got the previous. A single leecher must still
 * download the whole multi-piece torrent — proving the hand-out / propagate / reassign loop
 * walks through every piece instead of stalling.
 */
class SuperSeedTest {

    private val pieceLength = 32 * 1024 // 120 KB → 4 pieces

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

    private fun peerId(tag: String): Sha1Hash {
        val b = ByteArray(20)
        tag.encodeToByteArray().copyInto(b, 0, 0, minOf(tag.length, 20))
        return Digest32.of(b)
    }

    private fun plaintext() = SettingsPack().apply {
        setInt(IntSetting.OUT_ENC_POLICY, EncPolicy.PE_DISABLED)
        setInt(IntSetting.IN_ENC_POLICY, EncPolicy.PE_DISABLED)
    }

    @Test
    fun superSeederPushesWholeTorrent() = runBlocking {
        val data = ByteArray(120_000) { ((it * 73 + 11) and 0xff).toByte() }
        val torrent = buildTorrent(data)
        assertTrue(torrent.numPieces >= 4)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        val seederDisk = InMemoryDiskIo(torrent.storage)
        var off = 0
        for (p in 0 until torrent.numPieces) {
            val len = torrent.storage.pieceSize(p)
            seederDisk.write(p, 0, data.copyOfRange(off, off + len)); off += len
        }
        val seeder = KiteTorrentEngine(workers, peerId = peerId("KT-superseed"), listenPort = 0, settings = plaintext())
        seeder.start()
        val seederSession = seeder.addTorrent(torrent, seederDisk)
        assertTrue(seederSession.isSeeding())
        seederSession.superSeeding = true // enable BEFORE the leecher connects

        val leecherDisk = InMemoryDiskIo(torrent.storage)
        val leecher = KiteTorrentEngine(workers, peerId = peerId("KT-leecher"), listenPort = 0, settings = plaintext())
        leecher.start()
        val leecherSession = leecher.addTorrent(torrent, leecherDisk)
        leecherSession.connect(listOf(PeerEndpoint("127.0.0.1", seeder.boundListenPort)))

        withTimeout(30_000) {
            while (!leecherSession.isSeeding()) delay(25)
        }

        workers.cancel()
        seeder.shutdown()
        leecher.shutdown()

        assertTrue(
            leecherDisk.snapshot().copyOf(data.size).contentEquals(data),
            "leecher must complete the torrent from a super-seeder",
        )
    }
}
