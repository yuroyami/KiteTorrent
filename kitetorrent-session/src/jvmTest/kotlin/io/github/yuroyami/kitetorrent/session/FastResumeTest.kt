package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.session.disk.DiskIo
import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo
import io.github.yuroyami.kitetorrent.session.disk.PieceHashes
import io.github.yuroyami.kitetorrent.session.engine.TorrentSession
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.torrent.resume.AddTorrentParams
import io.github.yuroyami.kitetorrent.torrent.resume.ResumeData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fast-resume proof: a [TorrentSession] started with saved [AddTorrentParams] adopts the
 * previous run's piece state *without hashing the disk* — the libtorrent fast-resume path,
 * versus the full-rehash [TorrentSession.recheck]. Also proves the state survives a full
 * round-trip through `ResumeData.write`/`read` (the bytes an app persists across a hard kill),
 * including partially-downloaded pieces.
 */
class FastResumeTest {

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

    /** An in-memory disk pre-loaded with [data] (so `checkExistingFiles` + `hashPiece` see it). */
    private suspend fun preloadedDisk(torrent: TorrentInfo, data: ByteArray): InMemoryDiskIo {
        val disk = InMemoryDiskIo(torrent.storage)
        for (p in 0 until torrent.numPieces) {
            val start = p * pieceLength
            val len = torrent.storage.pieceSize(p)
            disk.write(p, 0, data.copyOfRange(start, start + len))
        }
        return disk
    }

    /** Counts how many times the engine hashed a piece — the cost fast-resume avoids. */
    private class CountingDisk(private val inner: DiskIo) : DiskIo {
        var hashCount = 0
            private set
        override suspend fun read(piece: Int, offset: Int, length: Int) = inner.read(piece, offset, length)
        override suspend fun write(piece: Int, offset: Int, data: ByteArray) = inner.write(piece, offset, data)
        override suspend fun hashPiece(piece: Int): PieceHashes { hashCount++; return inner.hashPiece(piece) }
        override suspend fun checkExistingFiles() = inner.checkExistingFiles()
        override suspend fun flush() = inner.flush()
        override suspend fun close() = inner.close()
    }

    private fun peerId(tag: String): Sha1Hash {
        val b = ByteArray(20)
        tag.encodeToByteArray().copyInto(b, 0, 0, minOf(tag.length, 20))
        return Digest32.of(b)
    }

    @Test
    fun fastResumeAdoptsPiecesWithoutHashing() = runBlocking {
        val data = ByteArray(80_000) { ((it * 131 + 17) and 0xff).toByte() }
        val torrent = buildTorrent("payload.bin", data)
        val numPieces = torrent.numPieces
        assertEquals(3, numPieces)

        val runtime = NetworkRuntime(Dispatchers.IO)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            // --- control: no resume data → a full recheck hashes every piece on disk ---
            val controlDisk = CountingDisk(preloadedDisk(torrent, data))
            val control = TorrentSession(
                torrent = torrent, disk = controlDisk, network = runtime,
                scope = scope, peerId = peerId("KT-control"),
            )
            control.start()
            assertTrue(control.isSeeding(), "recheck should claim all pieces from disk")
            assertEquals(numPieces, controlDisk.hashCount, "recheck hashes every present piece")

            // persist that state the way an app would, across a simulated hard kill
            val resumeBytes = ResumeData.write(control.saveResumeData(savePath = "/downloads"))
            val resume = ResumeData.read(resumeBytes)

            // --- fast resume: same state, but adopted with ZERO hashing ---
            val resumeDisk = CountingDisk(preloadedDisk(torrent, data))
            val resumed = TorrentSession(
                torrent = torrent, disk = resumeDisk, network = runtime,
                scope = scope, peerId = peerId("KT-resumed"), resumeData = resume,
            )
            resumed.start()
            assertTrue(resumed.isSeeding(), "fast resume should report a complete torrent")
            assertEquals(1.0f, resumed.progress(), "all pieces present after resume")
            assertEquals(0, resumeDisk.hashCount, "fast resume must NOT re-hash the disk")
        } finally {
            scope.cancel()
            runtime.close()
        }
    }

    @Test
    fun resumeRoundTripsWholeAndPartialPieces() = runBlocking {
        val data = ByteArray(80_000) { ((it * 91 + 7) and 0xff).toByte() }
        val torrent = buildTorrent("payload.bin", data)
        val numPieces = torrent.numPieces
        assertEquals(3, numPieces) // 2 blocks per piece

        // craft a mid-download state: piece 0 complete, piece 1 has block 0 only, piece 2 untouched
        val have = Bitfield(numPieces).apply { setBit(0) }
        val partial = linkedMapOf(1 to Bitfield(2).apply { setBit(0) })
        val crafted = AddTorrentParams(
            ti = torrent,
            infoSection = torrent.infoBytes,
            infoHashV1 = torrent.infoHashV1,
            havePieces = have,
            verifiedPieces = have,
            unfinishedPieces = partial,
            savePath = "/downloads",
        )

        // through the persisted bytes and back — what actually crosses a restart
        val reread = ResumeData.read(ResumeData.write(crafted))

        val runtime = NetworkRuntime(Dispatchers.IO)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val session = TorrentSession(
                torrent = torrent, disk = InMemoryDiskIo(torrent.storage), network = runtime,
                scope = scope, peerId = peerId("KT-partial"), resumeData = reread,
            )
            session.start()

            assertEquals(1, session.numHave(), "only piece 0 is a whole, had piece")
            assertFalse(session.isSeeding())

            // re-export and confirm both the whole piece and the partial block survived the cycle
            val out = session.saveResumeData(savePath = "/downloads")
            assertTrue(out.havePieces.getBit(0), "whole piece 0 persists")
            assertFalse(out.havePieces.getBit(1), "piece 1 is only partial, not had")
            assertEquals(true, out.unfinishedPieces[1]?.getBit(0), "piece 1 block 0 persists as on-disk")
            assertEquals(false, out.unfinishedPieces[1]?.getBit(1) ?: false, "piece 1 block 1 was never downloaded")
        } finally {
            scope.cancel()
            runtime.close()
        }
    }
}
