package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.UtpStream
import io.github.yuroyami.kitetorrent.session.net.bindUdp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LEDBAT proof: the uplink-friendly behaviour. Driving the real congestion controller
 * with peer-reported one-way delay: when queuing delay sits below the 100 ms target the
 * window opens up, and when it climbs above the target the window backs off. That delay-
 * driven retreat is exactly what lets uTP yield to other traffic instead of clogging the
 * uplink. That is the reason to use uTP instead of TCP.
 */
class UtpLedbatTest {

    private val mss = UtpStream.DEFAULT_MAX_PAYLOAD

    @Test
    fun windowOpensBelowTargetAndBacksOffAboveIt() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val sock = runtime.bindUdp(0)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            // --- below target: ~0 queuing delay → cwnd should grow ---
            val low = UtpStream(sock, "127.0.0.1", 9, scope, selfTick = false)
            low.forceCongestionAvoidanceForTest()
            low.feedDelayAndAckForTest(10_000L, 0)        // base delay = 10 ms, no ack yet
            val lowBefore = low.congestionWindowBytes()
            repeat(50) { low.feedDelayAndAckForTest(10_000L, mss) }  // queuing 0 ms ≪ 100 ms target
            val lowAfter = low.congestionWindowBytes()
            assertTrue(lowAfter > lowBefore, "cwnd must open below target ($lowBefore → $lowAfter)")

            // --- above target: 250 ms queuing delay → cwnd should retreat ---
            val high = UtpStream(sock, "127.0.0.1", 9, scope, selfTick = false)
            high.forceCongestionAvoidanceForTest()
            high.feedDelayAndAckForTest(10_000L, 0)       // base delay = 10 ms
            repeat(50) { high.feedDelayAndAckForTest(10_000L, mss) }  // open it up first
            val highBefore = high.congestionWindowBytes()
            repeat(50) { high.feedDelayAndAckForTest(260_000L, mss) } // 250 ms queuing ≫ 100 ms target
            val highAfter = high.congestionWindowBytes()
            assertTrue(highAfter < highBefore, "cwnd must back off above target ($highBefore → $highAfter)")
        } finally {
            scope.cancel()
            sock.close()
            runtime.close()
        }
    }
}
