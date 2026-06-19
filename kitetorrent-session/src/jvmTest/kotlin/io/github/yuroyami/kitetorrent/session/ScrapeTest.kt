package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.bindUdp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Live UDP tracker scrape (BEP-15): the engine performs the connect → scrape exchange against
 * a loopback fake tracker and surfaces the swarm statistics. Proves the scrape clients are
 * wired through an engine entry point, not just present as codecs.
 */
class ScrapeTest {

    private fun i32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun putI32(bb: ByteArrayBuilder, v: Int) {
        bb.append((v ushr 24).toByte()); bb.append((v ushr 16).toByte())
        bb.append((v ushr 8).toByte()); bb.append(v.toByte())
    }

    private fun putI64(bb: ByteArrayBuilder, v: Long) {
        for (s in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) bb.append((v ushr s).toByte())
    }

    @Test
    fun engineScrapesSwarmStatsOverUdp() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val trackerSock = runtime.bindUdp(0)
        val trackerPort = trackerSock.localPort
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // fake BEP-15 tracker: answer connect (action 0), then scrape (action 2) with fixed stats
        workers.launch {
            // connect request: protocol_id(8), action=0(4), transaction_id(4 @ off 12)
            val connReq = trackerSock.receive()
            val txn1 = i32(connReq.data, 12)
            val connResp = ByteArrayBuilder().apply {
                putI32(this, 0)            // action = connect
                putI32(this, txn1)         // echo transaction id
                putI64(this, 0x41727101980L) // some connection id
            }.toByteArray()
            trackerSock.send(connResp, connReq.host, connReq.port)

            // scrape request: connection_id(8), action=2(4), transaction_id(4 @ off 12), infohash(20)
            val scrapeReq = trackerSock.receive()
            val txn2 = i32(scrapeReq.data, 12)
            val scrapeResp = ByteArrayBuilder().apply {
                putI32(this, 2)            // action = scrape
                putI32(this, txn2)         // echo transaction id
                putI32(this, 42)           // seeders
                putI32(this, 7)            // completed
                putI32(this, 3)            // leechers
            }.toByteArray()
            trackerSock.send(scrapeResp, scrapeReq.host, scrapeReq.port)
        }

        val engine = KiteTorrentEngine(workers, listenPort = 0)
        engine.start()
        val infoHash: Sha1Hash = Digest32.of(ByteArray(20) { (it + 1).toByte() })

        val resp = withTimeout(15_000) {
            engine.scrape(infoHash, listOf("udp://127.0.0.1:$trackerPort/announce"))
        }

        assertNotNull(resp, "scrape must return swarm stats")
        assertEquals(42, resp.seeders)
        assertEquals(7, resp.completed)
        assertEquals(3, resp.leechers)

        workers.cancel()
        trackerSock.close()
        engine.shutdown()
        runtime.close()
    }
}
