package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.session.net.MonotonicMicros
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.UtpStream
import io.github.yuroyami.kitetorrent.session.net.UtpType
import io.github.yuroyami.kitetorrent.session.net.bindUdp
import io.github.yuroyami.kitetorrent.session.net.parseUtpPacket
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
 * uTP reliability proof for the RTO path: the test drops the LAST data packet. Nothing
 * arrives after it, so there's no SACK and no duplicate-ACK to trigger fast-resend. The
 * only way the transfer completes is the retransmission timer firing.
 * ([UtpFastResendTest] covers fast-resend separately.)
 */
class UtpRetransmitTest {

    @Test
    fun retransmitsADroppedDataPacket() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val sockA = runtime.bindUdp(0)
        val sockB = runtime.bindUdp(0)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // initiator: real monotonic clock + a short RTO so the resend happens fast
        val initiator = UtpStream(
            sockA, "127.0.0.1", sockB.localPort, workers,
            nowMicros = MonotonicMicros(), connectionId = 0x4242,
            minTimeoutMicros = 150_000L, tickIntervalMs = 20L,
        )
        val acceptor = UtpStream(
            sockB, "127.0.0.1", sockA.localPort, workers,
            nowMicros = MonotonicMicros(), tickIntervalMs = 20L,
        )

        // initiator receives everything; the acceptor's pump DROPS exactly one DATA packet
        workers.launch { while (isActive) initiator.onDatagram(sockA.receive().data) }
        var droppedOne = false
        workers.launch {
            while (isActive) {
                val data = sockB.receive().data
                val pkt = parseUtpPacket(data)
                // drop the final short packet (payload < full MSS): nothing follows it, so
                // there's no SACK/dup-ACK. Only the RTO timer can recover it.
                if (!droppedOne && pkt != null &&
                    pkt.header.type == UtpType.ST_DATA &&
                    pkt.payload.isNotEmpty() && pkt.payload.size < 1400
                ) {
                    droppedOne = true
                    continue
                }
                acceptor.onDatagram(data)
            }
        }

        val payload = ByteArray(5000) { ((it * 37 + 9) and 0xff).toByte() }

        withTimeout(15_000) {
            initiator.connect()
            initiator.write(payload)
            val received = acceptor.readExactly(payload.size)
            assertTrue(received.contentEquals(payload), "all bytes must arrive despite the dropped packet")
        }
        assertTrue(droppedOne, "the test must have actually dropped a data packet")

        workers.cancel()
        sockA.close()
        sockB.close()
        runtime.close()
    }
}
