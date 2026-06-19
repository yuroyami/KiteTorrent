package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.crypto.Hasher256
import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.tracker.PeerEndpoint
import io.github.yuroyami.kitetorrent.settings.EncPolicy
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.torrent.CreateTorrent
import io.github.yuroyami.kitetorrent.torrent.FileEntry
import io.github.yuroyami.kitetorrent.torrent.FileStorage
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
 * BitTorrent v2 (BEP-52) end-to-end: two engines exchange a **v2-only** torrent. Every
 * piece is verified by recomputing its SHA-256 merkle root from the 16 KiB block leaves
 * and matching the published `piece layers` — there is no v1 SHA-1 anywhere. Also exercises
 * the truncated-v2 wire info-hash on the handshake and inbound routing. Plaintext, to keep
 * v2 isolated from MSE (covered separately).
 */
class V2ExchangeTest {

    private val blockSize = 16 * 1024
    private val pieceLength = 64 * 1024 // 4 blocks per piece

    private fun buildV2Torrent(name: String, data: ByteArray): TorrentInfo {
        val leaves = ArrayList<Sha256Hash>()
        var off = 0
        while (off < data.size) {
            leaves.add(Hasher256.hash(data, off, minOf(blockSize, data.size - off)))
            off += blockSize
        }
        val storage = FileStorage.of(name, pieceLength, listOf(FileEntry(path = name, size = data.size.toLong(), offset = 0L)))
        val bytes = CreateTorrent(storage, v2Only = true).setFileV2Leaves(0, leaves).generateBuffer()
        return TorrentInfo.parse(bytes)
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
    fun twoEnginesExchangeAV2OnlyTorrent() = runBlocking {
        val data = ByteArray(200_000) { ((it * 53 + 7) and 0xff).toByte() }
        val torrent = buildV2Torrent("payload.bin", data)
        assertTrue(torrent.isV2 && !torrent.isV1, "must be a pure v2 torrent")
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // seeder: disk pre-filled with the whole torrent — recheck must claim every piece via v2
        val seederDisk = InMemoryDiskIo(torrent.storage)
        var off = 0
        for (p in 0 until torrent.numPieces) {
            val len = torrent.storage.pieceSize(p)
            seederDisk.write(p, 0, data.copyOfRange(off, off + len))
            off += len
        }
        val seeder = KiteTorrentEngine(workers, peerId = peerId("KT-seeder"), listenPort = 0, settings = plaintext())
        seeder.start()
        val seederSession = seeder.addTorrent(torrent, seederDisk)
        assertTrue(seederSession.isSeeding(), "seeder must recognise a complete v2 torrent via merkle verification")

        // leecher: empty disk, downloads + verifies every piece against the piece layers
        val leecherDisk = InMemoryDiskIo(torrent.storage)
        val leecher = KiteTorrentEngine(workers, peerId = peerId("KT-leecher"), listenPort = 0, settings = plaintext())
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
            "leecher must download and v2-verify the whole torrent",
        )
    }
}
