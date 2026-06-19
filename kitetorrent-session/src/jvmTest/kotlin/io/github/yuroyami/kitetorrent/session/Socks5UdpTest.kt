package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.Socks5Config
import io.github.yuroyami.kitetorrent.session.net.Socks5UdpAssociation
import io.github.yuroyami.kitetorrent.session.net.bindTcp
import io.github.yuroyami.kitetorrent.session.net.bindUdp
import io.github.yuroyami.kitetorrent.session.net.connectTcp
import io.github.yuroyami.kitetorrent.session.net.socks5UdpAssociate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SOCKS5 UDP ASSOCIATE demux (RFC 1928 §6–§7): a proxied [io.github.yuroyami.kitetorrent.session.net.UdpSocket]
 * SOCKS5-wraps outbound datagrams to the relay (hiding the real destination) and unwraps inbound
 * ones back to the true origin. A fake relay verifies the wrapped framing and that a round-trip
 * presents the un-proxied (host, port, payload) view to the caller.
 */
class Socks5UdpTest {

    @Test
    fun proxiedUdpSocketWrapsAndUnwrapsThroughRelay() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // fake SOCKS5 proxy control endpoint: negotiate then UDP-ASSOCIATE, advertising the relay.
        val controlServer = runtime.bindTcp(0, "127.0.0.1")
        // the relay UDP socket the proxy listens on for wrapped datagrams.
        val relayUdp = runtime.bindUdp(0, "127.0.0.1")
        val relayPort = relayUdp.localPort

        val sawTarget = CompletableDeferred<String>()

        workers.launch {
            val c = controlServer.accept()
            runCatching {
                val greeting = c.readExactly(2)        // VER, NMETHODS
                c.readExactly(greeting[1].toInt())     // offered methods
                c.write(byteArrayOf(0x05, 0x00))       // no-auth
                c.readExactly(3)                       // VER, CMD=3 (UDP ASSOCIATE), RSV
                c.readExactly(1 + 4 + 2)               // ATYP + 0.0.0.0 + port 0
                // reply: success, BND.ADDR = 127.0.0.1, BND.PORT = relayPort
                val hi = (relayPort ushr 8).toByte(); val lo = relayPort.toByte()
                c.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, hi, lo))
                // keep the control connection alive for the lifetime of the test
                c.readExactly(1)
            }
        }

        // relay loop: receive a wrapped datagram, parse the SOCKS5 UDP header, echo the inner
        // payload back wrapped as if from the same (host, port) the client targeted.
        workers.launch {
            runCatching {
                while (true) {
                    val pkt = relayUdp.receive()
                    val d = pkt.data
                    // RSV(2) FRAG(1) ATYP(1)=IPv4 ADDR(4) PORT(2) DATA
                    val host = "${d[4].toInt() and 0xFF}.${d[5].toInt() and 0xFF}." +
                        "${d[6].toInt() and 0xFF}.${d[7].toInt() and 0xFF}"
                    val port = ((d[8].toInt() and 0xFF) shl 8) or (d[9].toInt() and 0xFF)
                    sawTarget.complete("$host:$port")
                    val payload = d.copyOfRange(10, d.size)
                    // echo back wrapped with the same origin header, to the client's relay sender
                    val reply = byteArrayOf(0x00, 0x00, 0x00, 0x01, d[4], d[5], d[6], d[7], d[8], d[9]) + payload
                    relayUdp.send(reply, pkt.host, pkt.port)
                }
            }
        }

        runtime.proxy = Socks5Config("127.0.0.1", controlServer.localPort)

        val (origin, payload, target) = withTimeout(15_000) {
            // open the association over the listen socket and route it through the proxy
            val clientRelay = runtime.bindUdp(0, "127.0.0.1")
            val control = runtime.connectTcp("127.0.0.1", controlServer.localPort, bypassProxy = true)
            val assoc: Socks5UdpAssociation = socks5UdpAssociate(control, clientRelay, Socks5Config("127.0.0.1", controlServer.localPort))
            val proxied = clientRelay.proxiedThrough(assoc)

            proxied.send("hello".encodeToByteArray(), "9.8.7.6", 1234)
            val back = proxied.receive()
            Triple(back.host to back.port, back.data.decodeToString(), sawTarget.await())
        }

        assertEquals("9.8.7.6:1234", target, "the relay must see the real target inside the wrapper")
        assertEquals("9.8.7.6" to 1234, origin, "unwrap must present the true origin to the caller")
        assertEquals("hello", payload, "the inner payload must round-trip unchanged")

        workers.cancel()
        controlServer.close()
        relayUdp.close()
        runtime.close()
    }
}
