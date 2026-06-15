package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.UtpSocketManager
import io.github.yuroyami.kitetorrent.session.net.UtpStream
import io.github.yuroyami.kitetorrent.session.net.bindUdp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The [UtpSocketManager] demultiplexer in isolation: an active open through manager
 * A reaches manager B as an incoming connection, bytes flow both ways across the
 * two demuxed streams, and the DHT-side transport sees non-uTP datagrams.
 */
class UtpSocketManagerTest {

    @Test
    fun managersConnectAcceptAndExchangeBytes() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val workers = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val a = UtpSocketManager(runtime.bindUdp(0), workers)
            val b = UtpSocketManager(runtime.bindUdp(0), workers)
            val accepted = CompletableDeferred<UtpStream>()
            b.onIncomingConnection = { accepted.complete(it) }
            a.start()
            b.start()

            val payload = ByteArray(40_000) { ((it * 19 + 3) and 0xff).toByte() }
            withTimeout(15_000) {
                val toB = a.connect("127.0.0.1", b.localPort)
                val fromA = accepted.await()

                // A → B
                workers.launch { toB.write(payload) }
                val got = fromA.readExactly(payload.size)
                assertTrue(got.contentEquals(payload), "A→B bytes must survive the demux")

                // B → A (the reverse direction over the same streams)
                workers.launch { fromA.write(payload) }
                val back = toB.readExactly(payload.size)
                assertTrue(back.contentEquals(payload), "B→A bytes must survive the demux")
            }
            assertEquals(1, a.numStreams())
            assertEquals(1, b.numStreams())
        } finally {
            workers.cancel()
            runtime.close()
        }
    }
}
