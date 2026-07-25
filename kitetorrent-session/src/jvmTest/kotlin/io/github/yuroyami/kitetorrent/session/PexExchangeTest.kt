package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.extensions.ExtensionHandshake
import io.github.yuroyami.kitetorrent.extensions.UtPex
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
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
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live PEX (BEP-11) proof: a downloader connected to ONE peer that has no data but
 * gossips a `ut_pex` message naming a seeder must discover that seeder and complete.
 * That proves PEX is wired into the engine, not just a codec. The introducer never
 * serves a byte; if the download finishes, the only way the downloader reached the
 * seeder was by ingesting the PEX message.
 */
class PexExchangeTest {

    private val pieceLength = 32 * 1024

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
        return TorrentInfo.parse(Bencode.encode(Entry.dict().apply { this["info"] = info }))
    }

    private fun peerId(tag: String): Sha1Hash {
        val b = ByteArray(20)
        tag.encodeToByteArray().copyInto(b, 0, 0, minOf(tag.length, 20))
        return Digest32.of(b)
    }

    @Test
    fun downloaderFindsSeederViaPexGossip() = runBlocking {
        val data = ByteArray(80_000) { ((it * 131 + 17) and 0xff).toByte() }
        val torrent = buildTorrent("payload.bin", data)
        val infoHash = torrent.infoHashV1!!
        val numPieces = torrent.numPieces

        val runtime = NetworkRuntime(Dispatchers.IO)
        val seederSrv = runtime.bindTcp(0, "127.0.0.1")
        val introSrv = runtime.bindTcp(0, "127.0.0.1")
        val seederPort = seederSrv.localPort
        val introPort = introSrv.localPort
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // --- seeder C: has everything, serves any requested block ---
        workers.launch {
            val conn = seederSrv.accept()
            val seeder = PeerConnection(conn.asByteStream(), infoHash, peerId("KT-seeder"), numPieces)
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
            }
        }

        // --- introducer B: no pieces, never serves; only gossips C's address via ut_pex ---
        workers.launch {
            val conn = introSrv.accept()
            val intro = PeerConnection(conn.asByteStream(), infoHash, peerId("KT-introd"), numPieces)
            try {
                intro.performHandshake()
                intro.sendBitfield(Bitfield(numPieces)) // have-none
                // tell the downloader about the seeder, under the id it advertised for ut_pex
                val pex = UtPex.encode(listOf(UtPex.peerFromIpv4("127.0.0.1", seederPort)!!))
                intro.sendExtended(ExtensionHandshake.UT_PEX_ID, pex)
                intro.receiveLoop { /* stay connected; ignore everything */ }
            } catch (_: Exception) {
            }
        }

        // --- downloader A: starts knowing ONLY the introducer ---
        val disk = InMemoryDiskIo(torrent.storage)
        val session = TorrentSession(
            torrent = torrent, disk = disk, network = runtime,
            scope = workers, peerId = peerId("KT-leecher"), listenPort = introPort,
            // fake introducer/seeder speak plaintext only: exercise the plaintext dial path
            settings = SettingsPack().apply { setInt(IntSetting.OUT_ENC_POLICY, EncPolicy.PE_DISABLED) },
        )
        session.connect(listOf(PeerEndpoint("127.0.0.1", introPort)))

        withTimeout(20_000) {
            while (!session.isSeeding()) delay(25)
        }

        workers.cancel()
        seederSrv.close()
        introSrv.close()
        runtime.close()

        assertTrue(
            disk.snapshot().copyOf(data.size).contentEquals(data),
            "downloader must have reached the seeder discovered via PEX",
        )
    }
}
