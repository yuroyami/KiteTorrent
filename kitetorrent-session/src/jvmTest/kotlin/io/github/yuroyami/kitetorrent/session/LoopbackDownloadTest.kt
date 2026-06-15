package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.protocol.PeerMessage
import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo
import io.github.yuroyami.kitetorrent.session.engine.TorrentSession
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.bindTcp
import io.github.yuroyami.kitetorrent.session.peer.PeerConnection
import io.github.yuroyami.kitetorrent.session.peer.asByteStream
import io.github.yuroyami.kitetorrent.session.tracker.PeerEndpoint
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
import kotlin.test.assertTrue

/**
 * The end-to-end proof: a [TorrentSession] downloads a real torrent from a fake
 * seeder over a loopback TCP socket. This exercises the whole Tier 2 stack together —
 * ktor sockets, the BitTorrent handshake, bitfield/unchoke, the rarest-first request
 * pipeline, piece receipt, hash verification, and completion — against the pure-core
 * codecs and piece picker. If the bytes on the downloader's disk match the seeder's,
 * the engine genuinely works.
 */
class LoopbackDownloadTest {

    private val pieceLength = 32 * 1024 // 2 blocks per piece

    /** Build a single-file v1 torrent over [data] with correct piece hashes. */
    private fun buildTorrent(name: String, data: ByteArray): TorrentInfo {
        val numPieces = (data.size + pieceLength - 1) / pieceLength
        val pieces = ByteArrayBuilder()
        for (p in 0 until numPieces) {
            val start = p * pieceLength
            val len = minOf(pieceLength, data.size - start)
            pieces.append(Hasher.hash(data, start, len).toByteArray())
        }
        val info = Entry.dict().apply {
            this["name"] = Entry.of(name)
            this["piece length"] = Entry.of(pieceLength.toLong())
            this["length"] = Entry.of(data.size.toLong())
            this["pieces"] = Entry.of(pieces.toByteArray())
        }
        val root = Entry.dict().apply { this["info"] = info }
        return TorrentInfo.parse(Bencode.encode(root))
    }

    @Test
    fun downloadsRealTorrentFromLoopbackSeeder() = runBlocking {
        val data = ByteArray(80_000) { ((it * 131 + 17) and 0xff).toByte() }
        val torrent = buildTorrent("payload.bin", data)
        val infoHash = torrent.infoHashV1!!
        val numPieces = torrent.numPieces
        assertTrue(numPieces == 3) // 32768 + 32768 + 14464

        val runtime = NetworkRuntime(Dispatchers.IO)
        val server = runtime.bindTcp(0, "127.0.0.1")
        val port = server.localPort
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // --- fake seeder: has every piece, serves any block it's asked for ---
        workers.launch {
            val conn = server.accept()
            val seeder = PeerConnection(conn.asByteStream(), infoHash, peerIdBytes("KT-seeder"), numPieces)
            try {
                seeder.performHandshake()
                seeder.sendBitfield(Bitfield(numPieces).apply { setAll() })
                seeder.sendUnchoke()
                seeder.receiveLoop { msg ->
                    if (msg is PeerMessage.Request) {
                        val from = msg.piece * pieceLength + msg.begin
                        seeder.sendPiece(msg.piece, msg.begin, data.copyOfRange(from, from + msg.length))
                    }
                }
            } catch (_: Exception) {
                // connection closed once the download finishes — expected
            }
        }

        // --- downloader: a real TorrentSession against an in-memory disk ---
        val disk = InMemoryDiskIo(torrent.storage)
        val session = TorrentSession(
            torrent = torrent,
            disk = disk,
            network = runtime,
            scope = workers,
            peerId = peerIdBytes("KT-leecher"),
            listenPort = port,
        )
        session.connect(listOf(PeerEndpoint("127.0.0.1", port)))

        withTimeout(20_000) {
            while (!session.isSeeding()) delay(25)
        }

        workers.cancel()
        server.close()
        runtime.close()

        assertTrue(disk.snapshot().copyOf(data.size).contentEquals(data), "downloaded bytes must match the seeder")
    }

    private fun peerIdBytes(tag: String): io.github.yuroyami.kitetorrent.Sha1Hash {
        val b = ByteArray(20)
        val t = tag.encodeToByteArray()
        t.copyInto(b, 0, 0, minOf(t.size, 20))
        return io.github.yuroyami.kitetorrent.Digest32.of(b)
    }
}
