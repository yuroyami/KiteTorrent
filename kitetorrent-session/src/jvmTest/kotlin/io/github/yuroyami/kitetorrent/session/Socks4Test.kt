package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.Socks4Config
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
 * SOCKS4 proxying: with a SOCKS4 proxy configured, `connectTcp` sends a SOCKS4 CONNECT and, on
 * a request-granted (0x5A) reply, tunnels raw bytes to the target. A fake proxy verifies the
 * requested target and that data round-trips through the tunnel.
 */
class Socks4Test {

    @Test
    fun connectTcpTunnelsThroughSocks4() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val proxyServer = runtime.bindTcp(0, "127.0.0.1")
        val proxyPort = proxyServer.localPort
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        val target = CompletableDeferred<String>()

        workers.launch {
            val c = proxyServer.accept()
            runCatching {
                val head = c.readExactly(8) // VN, CD, DSTPORT(2), DSTIP(4)
                val port = ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
                val ip = "${head[4].toInt() and 0xFF}.${head[5].toInt() and 0xFF}." +
                    "${head[6].toInt() and 0xFF}.${head[7].toInt() and 0xFF}"
                while (c.readExactly(1)[0].toInt() != 0) Unit // consume the null-terminated USERID
                target.complete("$ip:$port")
                c.write(byteArrayOf(0x00, 0x5A, 0, 0, 0, 0, 0, 0)) // request granted
                while (true) c.write(c.readExactly(4)) // echo the tunnelled bytes
            }
        }

        runtime.proxy = Socks4Config("127.0.0.1", proxyPort)

        val (echoed, reached) = withTimeout(15_000) {
            val conn = runtime.connectTcp("1.2.3.4", 6881)
            conn.write("ping".encodeToByteArray())
            conn.readExactly(4).decodeToString() to target.await()
        }

        assertEquals("ping", echoed, "bytes must round-trip through the SOCKS4 tunnel")
        assertEquals("1.2.3.4:6881", reached, "the proxy must be asked to reach the real target")

        workers.cancel()
        proxyServer.close()
        runtime.close()
    }
}
