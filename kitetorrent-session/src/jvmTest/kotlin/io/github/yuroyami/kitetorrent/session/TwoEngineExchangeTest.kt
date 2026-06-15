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
 * The strongest end-to-end test: **two real [KiteTorrentEngine] instances** exchange a
 * torrent over loopback. The seeder serves blocks from its pre-filled disk (the upload
 * path: inbound accept → handshake → unchoke → serve requests), and the leecher
 * downloads and verifies them (the download path). No fakes on either side — this proves
 * two KiteTorrent peers interoperate.
 */
class TwoEngineExchangeTest {

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

    private fun peerId(tag: String): Sha1Hash {
        val b = ByteArray(20)
        tag.encodeToByteArray().copyInto(b, 0, 0, minOf(tag.length, 20))
        return Digest32.of(b)
    }

    @Test
    fun twoEnginesExchangeATorrent() = runBlocking {
        val data = ByteArray(120_000) { ((it * 73 + 11) and 0xff).toByte() }
        val torrent = buildTorrent("payload.bin", data)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // --- seeder engine: a disk pre-filled with the whole torrent ---
        val seederDisk = InMemoryDiskIo(torrent.storage)
        var off = 0
        for (p in 0 until torrent.numPieces) {
            val len = torrent.storage.pieceSize(p)
            seederDisk.write(p, 0, data.copyOfRange(off, off + len))
            off += len
        }
        val seeder = KiteTorrentEngine(workers, peerId = peerId("KT-seeder"), listenPort = 0)
        seeder.start() // binds the listen socket
        val seederSession = seeder.addTorrent(torrent, seederDisk)
        assertTrue(seederSession.isSeeding(), "seeder should have every piece after recheck")

        // --- leecher engine: empty disk, connects to the seeder ---
        val leecherDisk = InMemoryDiskIo(torrent.storage)
        val leecher = KiteTorrentEngine(workers, peerId = peerId("KT-leecher"), listenPort = 0)
        leecher.start()
        val leecherSession = leecher.addTorrent(torrent, leecherDisk)
        leecherSession.connect(listOf(PeerEndpoint("127.0.0.1", seeder.boundListenPort)))

        withTimeout(25_000) {
            while (!leecherSession.isSeeding()) delay(25)
        }

        workers.cancel()
        seeder.shutdown()
        leecher.shutdown()

        assertTrue(
            leecherDisk.snapshot().copyOf(data.size).contentEquals(data),
            "leecher's downloaded bytes must match the seeder's",
        )
    }
}
