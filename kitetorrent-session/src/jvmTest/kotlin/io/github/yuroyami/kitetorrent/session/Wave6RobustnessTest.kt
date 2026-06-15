package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.protocol.Handshake
import io.github.yuroyami.kitetorrent.protocol.PeerMessage
import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo
import io.github.yuroyami.kitetorrent.session.engine.ConnectionBudget
import io.github.yuroyami.kitetorrent.session.engine.RateLimiter
import io.github.yuroyami.kitetorrent.session.engine.TorrentSession
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.TcpConnection
import io.github.yuroyami.kitetorrent.session.net.bindTcp
import io.github.yuroyami.kitetorrent.session.peer.PeerConnection
import io.github.yuroyami.kitetorrent.session.peer.asByteStream
import io.github.yuroyami.kitetorrent.session.tracker.PeerEndpoint
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Wave 6: the `request_blocks.cpp` scheduler — true end-game (busy-block
 * duplication + `cancel` on first delivery), snubbing (queue collapse + the
 * blocking-request cancel), sequential download, and the session connection
 * budget. The adversary is a [ScriptedSeeder]: a real TCP peer built on the
 * production [PeerConnection] codec that serves every block *except* the ones it
 * is told to withhold, and records every `cancel` it receives.
 */
class Wave6RobustnessTest {

    private val pieceLength = 32 * 1024 // 2 blocks of 16 KiB per piece

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

    /**
     * A scripted remote peer: accepts inbound connections, handshakes, advertises
     * every piece, unchokes — then serves requests from [data] except the
     * blocks in [withhold] (piece to begin), which it silently never sends.
     * Every received `cancel` is recorded in [cancels].
     */
    private inner class ScriptedSeeder(
        private val runtime: NetworkRuntime,
        private val torrent: TorrentInfo,
        private val data: ByteArray,
        private val withhold: Set<Pair<Int, Int>> = emptySet(),
    ) {
        val cancels = CopyOnWriteArrayList<Pair<Int, Int>>() // (piece, begin)
        var port: Int = 0
            private set

        suspend fun start(scope: CoroutineScope) {
            val server = runtime.bindTcp(0)
            port = server.localPort
            scope.launch {
                while (isActive) {
                    val conn = try {
                        server.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    launch { serve(conn) }
                }
            }
        }

        private suspend fun serve(conn: TcpConnection) {
            try {
                val hs = Handshake.decode(conn.readExactly(Handshake.LENGTH)) ?: return
                val pc = PeerConnection(conn.asByteStream(), torrent.infoHashV1!!, peerId("scripted"), torrent.numPieces)
                pc.completeInboundHandshake(hs)
                val all = Bitfield(torrent.numPieces).apply { for (i in 0 until torrent.numPieces) setBit(i) }
                pc.sendBitfield(all)
                pc.sendUnchoke()
                pc.receiveLoopCatching { msg ->
                    when (msg) {
                        is PeerMessage.Request -> {
                            if ((msg.piece to msg.begin) !in withhold) {
                                val off = msg.piece * pieceLength + msg.begin
                                pc.sendPiece(msg.piece, msg.begin, data.copyOfRange(off, off + msg.length))
                            }
                        }
                        is PeerMessage.Cancel -> cancels.add(msg.piece to msg.begin)
                        else -> {}
                    }
                }
            } catch (_: Throwable) {
                // disconnect — fine
            } finally {
                conn.close()
            }
        }
    }

    // -------------------------------------------------------------------------
    // true end-game: busy duplication + cancel on first delivery
    // -------------------------------------------------------------------------

    @Test
    fun endgameDuplicatesTheStalledBlockAndCancelsTheLoser() = runBlocking {
        val data = ByteArray(32 * 1024) { ((it * 31 + 7) and 0xff).toByte() } // 1 piece, 2 blocks
        val torrent = buildTorrent("endgame.bin", data)
        val runtime = NetworkRuntime(coroutineContext)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        try {

            val staller = ScriptedSeeder(runtime, torrent, data, withhold = setOf(0 to 16_384))
            val good = ScriptedSeeder(runtime, torrent, data)
            staller.start(workers)
            good.start(workers)

            val session = TorrentSession(torrent, InMemoryDiskIo(torrent.storage), runtime, workers, listenPort = 0)
            session.recheck()

            // the staller connects FIRST and claims both blocks; it serves block 0 and
            // sits on block 1 forever
            session.connect(listOf(PeerEndpoint("127.0.0.1", staller.port)))
            withTimeout(10_000) { while (session.bytesDownloaded < 16_384L) delay(10) }

            // the good seeder arrives: nothing is free, so its only move is the
            // end-game busy pick of the withheld block
            session.connect(listOf(PeerEndpoint("127.0.0.1", good.port)))
            withTimeout(10_000) { while (!session.isSeeding()) delay(10) }

            assertTrue(session.inEndgame(), "the good seeder must have entered end-game mode")
            withTimeout(5_000) { while (staller.cancels.isEmpty()) delay(10) }
            assertTrue(
                (0 to 16_384) in staller.cancels,
                "first delivery must cancel the duplicate request on the losing peer (got ${staller.cancels})",
            )

        } finally {
            workers.cancel()
            runtime.close()
        }
    }

    // -------------------------------------------------------------------------
    // snubbing: queue collapse + cancel of the blocking request
    // -------------------------------------------------------------------------

    @Test
    fun stalledPeerGetsSnubbedAndItsBlockingRequestCancelled() = runBlocking {
        val data = ByteArray(32 * 1024) { ((it * 13 + 5) and 0xff).toByte() } // 1 piece, 2 blocks
        val torrent = buildTorrent("snub.bin", data)
        val runtime = NetworkRuntime(coroutineContext)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        try {

            val staller = ScriptedSeeder(runtime, torrent, data, withhold = setOf(0 to 16_384))
            staller.start(workers)

            // piece_timeout dropped to 1s so the snub fires fast (a settings override,
            // the way libtorrent tests tune the same knob)
            val settings = SettingsPack().apply { setInt(IntSetting.PIECE_TIMEOUT, 1) }
            val session = TorrentSession(
                torrent, InMemoryDiskIo(torrent.storage), runtime, workers,
                listenPort = 0, settings = settings,
            )
            session.recheck()
            session.connect(listOf(PeerEndpoint("127.0.0.1", staller.port)))

            // block 0 arrives, block 1 never does → after piece_timeout the peer is
            // snubbed and — because the piece has no free blocks left — the blocking
            // request is cancelled so another peer could pick it
            withTimeout(10_000) {
                while (session.numSnubbedPeers() == 0) delay(25)
            }
            withTimeout(10_000) {
                while (staller.cancels.isEmpty()) delay(25)
            }
            assertTrue((0 to 16_384) in staller.cancels, "the blocking request must be cancelled (got ${staller.cancels})")
            assertEquals(1, session.numSnubbedPeers())

        } finally {
            workers.cancel()
            runtime.close()
        }
    }

    // -------------------------------------------------------------------------
    // sequential download
    // -------------------------------------------------------------------------

    @Test
    fun sequentialDownloadVerifiesPiecesInOrder() = runBlocking {
        val data = ByteArray(128 * 1024) { ((it * 7 + 1) and 0xff).toByte() } // 4 pieces
        val torrent = buildTorrent("seq.bin", data)
        val runtime = NetworkRuntime(coroutineContext)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        try {

            val seeder = ScriptedSeeder(runtime, torrent, data)
            seeder.start(workers)

            val session = TorrentSession(torrent, InMemoryDiskIo(torrent.storage), runtime, workers, listenPort = 0)
            val verified = CopyOnWriteArrayList<Int>()
            session.onPieceVerified = { verified.add(it) }
            session.sequentialDownload = true
            session.recheck()
            session.connect(listOf(PeerEndpoint("127.0.0.1", seeder.port)))

            withTimeout(15_000) { while (!session.isSeeding()) delay(10) }
            assertEquals(listOf(0, 1, 2, 3), verified.toList(), "sequential mode must complete pieces in index order")

        } finally {
            workers.cancel()
            runtime.close()
        }
    }

    // -------------------------------------------------------------------------
    // rate limiting wired into the live download path
    // -------------------------------------------------------------------------

    @Test
    fun downloadRateLimitThrottlesTheTransfer() = runBlocking {
        val data = ByteArray(64 * 1024) { ((it * 23 + 9) and 0xff).toByte() } // 2 pieces
        val torrent = buildTorrent("limited.bin", data)
        val runtime = NetworkRuntime(coroutineContext)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val seeder = ScriptedSeeder(runtime, torrent, data)
            seeder.start(workers)

            val limiter = RateLimiter(workers).also {
                it.setDownloadLimit(32 * 1024) // 64 KiB at 32 KiB/s ≥ ~2 s
                it.start()
            }
            val session = TorrentSession(
                torrent, InMemoryDiskIo(torrent.storage), runtime, workers,
                listenPort = 0, limiter = limiter,
            )
            session.recheck()

            val started = TimeSource.Monotonic.markNow()
            session.connect(listOf(PeerEndpoint("127.0.0.1", seeder.port)))
            withTimeout(30_000) { while (!session.isSeeding()) delay(25) }
            val elapsed = started.elapsedNow()

            assertTrue(
                elapsed >= 1.seconds,
                "64 KiB at a 32 KiB/s cap cannot finish in $elapsed — the limiter isn't gating the receive path",
            )
        } finally {
            workers.cancel()
            runtime.close()
        }
    }

    // -------------------------------------------------------------------------
    // connections_limit
    // -------------------------------------------------------------------------

    @Test
    fun connectionBudgetCapsConcurrentPeers() = runBlocking {
        val data = ByteArray(96 * 1024) { ((it * 11 + 3) and 0xff).toByte() } // 3 pieces
        val torrent = buildTorrent("budget.bin", data)
        val runtime = NetworkRuntime(coroutineContext)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        try {

            val seederA = ScriptedSeeder(runtime, torrent, data)
            val seederB = ScriptedSeeder(runtime, torrent, data)
            seederA.start(workers)
            seederB.start(workers)

            val budget = ConnectionBudget(limit = 1)
            val session = TorrentSession(
                torrent, InMemoryDiskIo(torrent.storage), runtime, workers,
                listenPort = 0, connections = budget,
            )
            session.recheck()
            session.connect(
                listOf(
                    PeerEndpoint("127.0.0.1", seederA.port),
                    PeerEndpoint("127.0.0.1", seederB.port),
                ),
            )

            withTimeout(15_000) {
                while (!session.isSeeding()) {
                    assertTrue(session.numPeers() <= 1, "connections_limit=1 must never admit a second peer")
                    delay(10)
                }
            }
            assertTrue(budget.inUse() <= 1)

        } finally {
            workers.cancel()
            runtime.close()
        }
    }
}
