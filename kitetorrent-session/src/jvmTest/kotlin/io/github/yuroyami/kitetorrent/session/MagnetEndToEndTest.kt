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
import io.github.yuroyami.kitetorrent.torrent.MagnetUri
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The complete magnet workflow through the public API: a seeder engine has the torrent;
 * a leecher engine, given ONLY a `magnet:?xt=urn:btih:…` link, fetches the metadata via
 * `ut_metadata`, builds the [TorrentInfo], and then downloads and verifies the data. It
 * does all of that by talking to the seeder. This exercises
 * [KiteTorrentEngine.addMagnet] end to end.
 */
class MagnetEndToEndTest {

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
    fun magnetDownloadBetweenTwoEngines() = runBlocking {
        val data = ByteArray(90_000) { ((it * 53 + 7) and 0xff).toByte() }
        val torrent = buildTorrent("payload.bin", data)
        val magnet = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:${torrent.infoHashV1!!.toHex()}&dn=payload")
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // seeder: has everything, serves both metadata (ut_metadata) and data
        val seederDisk = InMemoryDiskIo(torrent.storage)
        var off = 0
        for (p in 0 until torrent.numPieces) {
            val len = torrent.storage.pieceSize(p)
            seederDisk.write(p, 0, data.copyOfRange(off, off + len)); off += len
        }
        val seeder = KiteTorrentEngine(workers, peerId = peerId("KT-seed"), listenPort = 0)
        seeder.start()
        seeder.addTorrent(torrent, seederDisk)

        // leecher: starts from the magnet link, with no .torrent
        val leecher = KiteTorrentEngine(workers, peerId = peerId("KT-magnet"), listenPort = 0)
        leecher.start()
        var leecherDisk: InMemoryDiskIo? = null
        val session = leecher.addMagnet(
            magnet = magnet,
            peers = listOf(PeerEndpoint("127.0.0.1", seeder.boundListenPort)),
            diskFactory = { t -> InMemoryDiskIo(t.storage).also { leecherDisk = it } },
        )

        assertNotNull(session, "addMagnet should have fetched metadata and started a session")
        withTimeout(30_000) { while (!session.isSeeding()) delay(25) }

        workers.cancel()
        seeder.shutdown()
        leecher.shutdown()

        assertTrue(
            leecherDisk!!.snapshot().copyOf(data.size).contentEquals(data),
            "data downloaded via magnet must match the seeder",
        )
    }
}
