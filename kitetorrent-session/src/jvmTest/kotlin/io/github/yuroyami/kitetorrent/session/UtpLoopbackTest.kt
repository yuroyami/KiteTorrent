package io.github.yuroyami.kitetorrent.session

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
 * Proves the uTP transport (BEP-29) actually moves bytes: an initiator [UtpStream]
 * actively opens to an acceptor (passive open), then a multi-packet payload written on
 * one end is reassembled in order on the other. Validates the SYN/STATE handshake,
 * sequence/ack accounting, packetization, and reassembly together over real loopback UDP.
 */
class UtpLoopbackTest {

    @Test
    fun utpHandshakeAndDataTransfer() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val sockA = runtime.bindUdp(0)
        val sockB = runtime.bindUdp(0)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        val initiator = UtpStream(sockA, "127.0.0.1", sockB.localPort, workers, connectionId = 0x4242)
        val acceptor = UtpStream(sockB, "127.0.0.1", sockA.localPort, workers)

        // pump inbound datagrams into each stream (the owner's responsibility)
        workers.launch { while (isActive) initiator.onDatagram(sockA.receive().data) }
        workers.launch { while (isActive) acceptor.onDatagram(sockB.receive().data) }

        // payload bigger than one packet (DEFAULT_MAX_PAYLOAD = 1400) → multiple ST_DATA
        val payload = ByteArray(5000) { ((it * 37 + 9) and 0xff).toByte() }

        withTimeout(15_000) {
            initiator.connect() // suspends until the acceptor acks our SYN
            initiator.write(payload)
            val received = acceptor.readExactly(payload.size)
            assertTrue(received.contentEquals(payload), "uTP-reassembled bytes must equal what was written")
        }

        workers.cancel()
        sockA.close()
        sockB.close()
        runtime.close()
    }
}
