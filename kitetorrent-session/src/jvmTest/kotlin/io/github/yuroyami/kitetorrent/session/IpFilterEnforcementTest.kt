package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.peer.IpFilter
import io.github.yuroyami.kitetorrent.protocol.PeerMessage
import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo
import io.github.yuroyami.kitetorrent.session.engine.TorrentSession
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IP-filter enforcement: a blocklisted peer is never dialed. A real seeder is listening on
 * loopback (it would happily complete a handshake), but 127.0.0.1 is blocked, so the
 * session must not connect to it. This proves [IpFilter] is consulted on the dial path,
 * and not parsed and then ignored.
 */
class IpFilterEnforcementTest {

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
    fun blocklistedPeerIsNeverDialed() = runBlocking {
        val data = ByteArray(60_000) { ((it * 17 + 3) and 0xff).toByte() }
        val torrent = buildTorrent("payload.bin", data)
        val infoHash = torrent.infoHashV1!!
        val numPieces = torrent.numPieces

        val runtime = NetworkRuntime(Dispatchers.IO)
        val server = runtime.bindTcp(0, "127.0.0.1")
        val port = server.localPort
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // a real seeder on loopback: it accepts and handshakes if anyone connects
        var accepted = false
        workers.launch {
            val conn = server.accept()
            accepted = true
            val seeder = PeerConnection(conn.asByteStream(), infoHash, peerId("KT-seeder"), numPieces)
            runCatching {
                seeder.performHandshake()
                seeder.sendBitfield(Bitfield(numPieces).apply { setAll() })
                seeder.sendUnchoke()
                seeder.receiveLoop { msg ->
                    if (msg is PeerMessage.Request) {
                        val from = msg.piece * pieceLength + msg.begin
                        seeder.sendPiece(msg.piece, msg.begin, data.copyOfRange(from, from + msg.length))
                    }
                }
            }
        }

        // block all of 127.0.0.1
        val filter = IpFilter().apply { addRule("127.0.0.1", "127.0.0.1", IpFilter.BLOCKED) }

        val session = TorrentSession(
            torrent = torrent, disk = InMemoryDiskIo(torrent.storage), network = runtime,
            scope = workers, peerId = peerId("KT-leecher"), listenPort = port,
            settings = SettingsPack().apply { setInt(IntSetting.OUT_ENC_POLICY, EncPolicy.PE_DISABLED) },
            ipFilter = filter,
        )
        session.connect(listOf(PeerEndpoint("127.0.0.1", port)))

        delay(1_500) // ample time for a dial to have happened, if it were allowed

        assertEquals(0, session.numPeers(), "a blocklisted peer must never be connected")
        assertEquals(false, accepted, "the seeder must never have accepted a connection from us")

        workers.cancel()
        server.close()
        runtime.close()
    }
}
