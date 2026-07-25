package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.session.net.MonotonicMicros
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.UtpStream
import io.github.yuroyami.kitetorrent.session.net.bindUdp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Dynamic-window integration check: a multi-window transfer completes correctly while the
 * congestion window is live (no longer the old fixed 64 KiB), and the window stays within
 * sane bounds throughout: at least one MSS, and never past the cap. The directional
 * proofs live in the focused controller tests. [UtpLedbatTest] covers growth and
 * back-off under delay. [UtpRetransmitTest] and [UtpFastResendTest] cover
 * multiplicative decrease on loss.
 */
class UtpCongestionTest {

    @Test
    fun largeTransferCompletesUnderDynamicWindow() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val sockA = runtime.bindUdp(0)
        val sockB = runtime.bindUdp(0)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        val initiator = UtpStream(
            sockA, "127.0.0.1", sockB.localPort, workers,
            nowMicros = MonotonicMicros(), connectionId = 0x5151, tickIntervalMs = 20L,
        )
        val acceptor = UtpStream(
            sockB, "127.0.0.1", sockA.localPort, workers, nowMicros = MonotonicMicros(),
        )

        workers.launch { while (isActive) initiator.onDatagram(sockA.receive().data) }
        workers.launch { while (isActive) acceptor.onDatagram(sockB.receive().data) }

        val payload = ByteArray(100_000) { ((it * 29 + 5) and 0xff).toByte() }

        withTimeout(20_000) {
            initiator.connect()
            workers.launch { initiator.write(payload) }
            val received = acceptor.readExactly(payload.size)
            assertTrue(received.contentEquals(payload), "uTP must deliver the whole payload")
        }

        val cwnd = initiator.congestionWindowBytes()
        assertTrue(
            cwnd in UtpStream.DEFAULT_MAX_PAYLOAD..UtpStream.MAX_CWND,
            "cwnd must stay within [MSS, MAX] under the dynamic window (was $cwnd)",
        )

        workers.cancel()
        sockA.close()
        sockB.close()
        runtime.close()
    }
}
