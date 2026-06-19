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
import io.github.yuroyami.kitetorrent.session.net.TcpServer
import io.github.yuroyami.kitetorrent.session.net.bindTcp
import io.github.yuroyami.kitetorrent.session.peer.PeerConnection
import io.github.yuroyami.kitetorrent.session.peer.asByteStream
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Per-torrent connection cap (`max_connections`): with the cap set to 1, a session offered
 * two reachable seeders connects to exactly one. Proves the cap is a hard limit enforced at
 * peer-add time, not just a per-announce batch trim.
 */
class MaxConnectionsTest {

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

    private fun seeder(server: TcpServer, infoHash: Sha1Hash, numPieces: Int, scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                val conn = runCatching { server.accept() }.getOrNull() ?: break
                scope.launch {
                    val pc = PeerConnection(conn.asByteStream(), infoHash, peerId("seed"), numPieces)
                    runCatching {
                        pc.performHandshake()
                        pc.sendBitfield(Bitfield(numPieces).apply { setAll() })
                        pc.sendUnchoke()
                        pc.receiveLoop { }
                    }
                }
            }
        }
    }

    @Test
    fun connectionCapLimitsPeers() = runBlocking {
        val data = ByteArray(50_000) { (it and 0xff).toByte() }
        val torrent = buildTorrent(data)
        val infoHash = torrent.infoHashV1!!
        val numPieces = torrent.numPieces

        val runtime = NetworkRuntime(Dispatchers.IO)
        val s1 = runtime.bindTcp(0, "127.0.0.1")
        val s2 = runtime.bindTcp(0, "127.0.0.1")
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        seeder(s1, infoHash, numPieces, workers)
        seeder(s2, infoHash, numPieces, workers)

        val session = TorrentSession(
            torrent = torrent, disk = InMemoryDiskIo(torrent.storage), network = runtime,
            scope = workers, peerId = peerId("leecher"), listenPort = s1.localPort,
            settings = SettingsPack().apply { setInt(IntSetting.OUT_ENC_POLICY, EncPolicy.PE_DISABLED) },
        ).apply { maxPeers = 1 }

        session.connect(listOf(PeerEndpoint("127.0.0.1", s1.localPort), PeerEndpoint("127.0.0.1", s2.localPort)))

        delay(2_000) // let both dials resolve

        assertEquals(1, session.numPeers(), "max_connections=1 must cap the torrent at one peer")

        workers.cancel()
        s1.close(); s2.close()
        runtime.close()
    }
}
