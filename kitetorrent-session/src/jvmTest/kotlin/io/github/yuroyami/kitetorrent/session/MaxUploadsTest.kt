package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.connectTcp
import io.github.yuroyami.kitetorrent.session.peer.PeerConnection
import io.github.yuroyami.kitetorrent.session.peer.asByteStream
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Per-torrent upload-slot cap (`max_uploads`): a seeder with one upload slot, offered two
 * interested leechers, unchokes exactly one. Asserted before the first (10 s) choke round so
 * it isolates the `onInterested` slot enforcement.
 */
class MaxUploadsTest {

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

    private fun peerId(tag: String): Sha1Hash {
        val b = ByteArray(20); tag.encodeToByteArray().copyInto(b, 0, 0, minOf(tag.length, 20))
        return Digest32.of(b)
    }

    @Test
    fun uploadSlotLimitCapsUnchokedPeers() = runBlocking {
        val data = ByteArray(50_000) { (it and 0xff).toByte() }
        val torrent = buildTorrent(data)
        val infoHash = torrent.infoHashV1!!
        val numPieces = torrent.numPieces
        val runtime = NetworkRuntime(Dispatchers.IO)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // seeder engine with a full disk
        val seederDisk = InMemoryDiskIo(torrent.storage)
        var off = 0
        for (p in 0 until numPieces) {
            val len = torrent.storage.pieceSize(p)
            seederDisk.write(p, 0, data.copyOfRange(off, off + len)); off += len
        }
        val seeder = KiteTorrentEngine(workers, peerId = peerId("seeder"), listenPort = 0)
        seeder.start()
        val session = seeder.addTorrent(torrent, seederDisk)
        session.uploadSlots = 1 // one upload slot for this torrent

        // two leechers connect and declare interest
        repeat(2) { i ->
            workers.launch {
                val conn = runtime.connectTcp("127.0.0.1", seeder.boundListenPort)
                val pc = PeerConnection(conn.asByteStream(), infoHash, peerId("leech$i"), numPieces)
                runCatching {
                    pc.performHandshake()
                    pc.sendInterested()
                    pc.receiveLoop { }
                }
            }
        }

        withTimeout(15_000) { while (session.numPeers() < 2) delay(25) }
        delay(1_500) // well within the 10 s choke interval, so onInterested governs

        assertEquals(1, session.numUnchoked(), "max_uploads=1 must keep exactly one peer unchoked")

        workers.cancel()
        seeder.shutdown()
        runtime.close()
    }
}
