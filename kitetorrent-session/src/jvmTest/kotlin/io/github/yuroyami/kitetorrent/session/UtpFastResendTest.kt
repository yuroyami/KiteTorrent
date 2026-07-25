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
 * uTP fast-retransmit proof. The sender's RTO floor is set to 5 s, yet it recovers a
 * dropped data packet and finishes the whole transfer in well under 3 s. That gap can
 * only close via SACK / duplicate-ACK fast retransmit. The RTO timer demonstrably had
 * not fired yet. Distinguishes fast-resend from the slower RTO path ([UtpRetransmitTest]).
 */
class UtpFastResendTest {

    @Test
    fun fastResendsBeforeTheRtoFires() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val sockA = runtime.bindUdp(0)
        val sockB = runtime.bindUdp(0)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // RTO floor is a full 5 s: if recovery happens within the 3 s timeout below, it
        // was fast-resend (SACK / dup-ACK), not the retransmission timer.
        val initiator = UtpStream(
            sockA, "127.0.0.1", sockB.localPort, workers,
            nowMicros = MonotonicMicros(), connectionId = 0x4747,
            minTimeoutMicros = 5_000_000L, tickIntervalMs = 20L,
        )
        val acceptor = UtpStream(
            sockB, "127.0.0.1", sockA.localPort, workers,
            nowMicros = MonotonicMicros(), tickIntervalMs = 20L,
        )

        workers.launch { while (isActive) initiator.onDatagram(sockA.receive().data) }
        var droppedOne = false
        workers.launch {
            while (isActive) {
                val data = sockB.receive().data
                val pkt = parseUtpPacket(data)
                if (!droppedOne && pkt != null &&
                    pkt.header.type == UtpType.ST_DATA && pkt.payload.isNotEmpty()
                ) {
                    droppedOne = true // drop the first data packet; the rest create the SACK hole
                    continue
                }
                acceptor.onDatagram(data)
            }
        }

        // several packets after the hole → enough SACK/dup-ACK evidence to trigger fast-resend
        val payload = ByteArray(8000) { ((it * 53 + 11) and 0xff).toByte() }

        withTimeout(3_000) {
            initiator.connect()
            initiator.write(payload)
            val received = acceptor.readExactly(payload.size)
            assertTrue(received.contentEquals(payload), "bytes must arrive via fast-resend before the 5s RTO")
        }
        assertTrue(droppedOne, "the test must have actually dropped a data packet")

        workers.cancel()
        sockA.close()
        sockB.close()
        runtime.close()
    }
}
