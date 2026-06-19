package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.Socks5Config
import io.github.yuroyami.kitetorrent.session.net.bindTcp
import io.github.yuroyami.kitetorrent.session.net.connectTcp
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
 * SOCKS5 proxying (RFC 1928): with a proxy configured, `connectTcp` performs the SOCKS5 CONNECT
 * negotiation to the proxy and then tunnels raw bytes to the target. A fake proxy verifies the
 * handshake, the exact target address it was asked to reach, and that data round-trips through
 * the tunnel.
 */
class Socks5Test {

    @Test
    fun connectTcpTunnelsThroughSocks5() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val proxyServer = runtime.bindTcp(0, "127.0.0.1")
        val proxyPort = proxyServer.localPort
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        val target = CompletableDeferred<String>()

        // fake SOCKS5 proxy: negotiate, capture the requested target, then echo bytes
        workers.launch {
            val c = proxyServer.accept()
            runCatching {
                val greeting = c.readExactly(2)            // VER, NMETHODS
                c.readExactly(greeting[1].toInt())         // the offered methods
                c.write(byteArrayOf(0x05, 0x00))           // select no-auth

                val req = c.readExactly(4)                 // VER, CMD, RSV, ATYP
                val addr = c.readExactly(4)                // IPv4 (ATYP 0x01)
                val portB = c.readExactly(2)
                val host = "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}." +
                    "${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}"
                val port = ((portB[0].toInt() and 0xFF) shl 8) or (portB[1].toInt() and 0xFF)
                target.complete("$host:$port")

                c.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // success, BND 0.0.0.0:0
                while (true) c.write(c.readExactly(4))     // echo the tunnelled bytes
            }
        }

        runtime.proxy = Socks5Config("127.0.0.1", proxyPort)

        val (echoed, reached) = withTimeout(15_000) {
            val conn = runtime.connectTcp("1.2.3.4", 6881) // dials the proxy, tunnels to the target
            conn.write("ping".encodeToByteArray())
            val back = conn.readExactly(4)
            back.decodeToString() to target.await()
        }

        assertEquals("ping", echoed, "bytes must round-trip through the SOCKS5 tunnel")
        assertEquals("1.2.3.4:6881", reached, "the proxy must be asked to reach the real target")

        workers.cancel()
        proxyServer.close()
        runtime.close()
    }
}
