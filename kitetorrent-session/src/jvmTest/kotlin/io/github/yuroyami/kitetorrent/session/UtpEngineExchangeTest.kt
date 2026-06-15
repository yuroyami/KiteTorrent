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
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * µTP as the in-engine transport (BEP-29): two real [KiteTorrentEngine]s with
 * [µTP enabled] exchange a torrent end-to-end. The leecher's outgoing dial tries
 * uTP first (the seeder's `UtpSocketManager` passively accepts on the shared
 * listen port and routes the handshake by info-hash), so the whole transfer —
 * handshake, bitfield, requests, blocks, `have`s — rides over UDP. The stream
 * counters on both managers prove TCP never carried the session.
 */
class UtpEngineExchangeTest {

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
    fun twoEnginesExchangeATorrentOverUtp() = runBlocking {
        // 2 pieces — sized for the simplified uTP window (fixed 64 KiB, no
        // retransmission timer yet), which is fine on loss-free loopback
        val data = ByteArray(64 * 1024) { ((it * 37 + 17) and 0xff).toByte() }
        val torrent = buildTorrent("utp-payload.bin", data)
        // multi-threaded dispatch like UtpLoopbackTest: the engines' selector and
        // datagram pumps must not share runBlocking's single thread
        val workers = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var seeder: KiteTorrentEngine? = null
        var leecher: KiteTorrentEngine? = null
        try {
            val seederDisk = InMemoryDiskIo(torrent.storage)
            var off = 0
            for (p in 0 until torrent.numPieces) {
                val len = torrent.storage.pieceSize(p)
                seederDisk.write(p, 0, data.copyOfRange(off, off + len))
                off += len
            }
            seeder = KiteTorrentEngine(workers, peerId = peerId("KT-utp-seed"), listenPort = 0, enableUtp = true)
            seeder.start()
            val seederSession = seeder.addTorrent(torrent, seederDisk)
            assertTrue(seederSession.isSeeding())

            val leecherDisk = InMemoryDiskIo(torrent.storage)
            leecher = KiteTorrentEngine(workers, peerId = peerId("KT-utp-leech"), listenPort = 0, enableUtp = true)
            leecher.start()
            val leecherSession = leecher.addTorrent(torrent, leecherDisk)
            leecherSession.connect(listOf(PeerEndpoint("127.0.0.1", seeder.boundListenPort)))

            withTimeout(30_000) {
                while (!leecherSession.isSeeding()) delay(25)
            }

            // the transfer rode uTP: the dialer holds an active stream, the seeder a passive one
            assertTrue(leecher.numUtpStreams() > 0, "leecher should have dialed over uTP")
            assertTrue(seeder.numUtpStreams() > 0, "seeder should have accepted over uTP")

            assertTrue(
                leecherDisk.snapshot().copyOf(data.size).contentEquals(data),
                "uTP-downloaded bytes must match the seeder's",
            )
        } finally {
            workers.cancel()
            seeder?.shutdown()
            leecher?.shutdown()
        }
    }
}
