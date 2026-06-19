package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.session.net.HttpProxyConfig
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
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
 * HTTP CONNECT proxying: with an HTTP proxy configured, `connectTcp` issues a `CONNECT
 * host:port` request and, after a `200`, tunnels raw bytes to the target. A fake proxy checks
 * the CONNECT target and that data round-trips through the tunnel.
 */
class HttpProxyTest {

    @Test
    fun connectTcpTunnelsThroughHttpConnect() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val proxyServer = runtime.bindTcp(0, "127.0.0.1")
        val proxyPort = proxyServer.localPort
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        val target = CompletableDeferred<String>()

        workers.launch {
            val c = proxyServer.accept()
            runCatching {
                // read the CONNECT request + headers up to the blank line
                val sb = StringBuilder()
                while (true) {
                    val ch = c.readExactly(1)[0].toInt() and 0xFF
                    sb.append(ch.toChar())
                    if (ch == '\n'.code && sb.endsWith("\r\n\r\n")) break
                }
                val requestLine = sb.toString().substringBefore("\r\n") // CONNECT host:port HTTP/1.1
                target.complete(requestLine.split(' ')[1])
                c.write("HTTP/1.1 200 Connection established\r\n\r\n".encodeToByteArray())
                while (true) c.write(c.readExactly(4)) // echo the tunnelled bytes
            }
        }

        runtime.proxy = HttpProxyConfig("127.0.0.1", proxyPort)

        val (echoed, reached) = withTimeout(15_000) {
            val conn = runtime.connectTcp("1.2.3.4", 6881)
            conn.write("ping".encodeToByteArray())
            conn.readExactly(4).decodeToString() to target.await()
        }

        assertEquals("ping", echoed, "bytes must round-trip through the HTTP CONNECT tunnel")
        assertEquals("1.2.3.4:6881", reached, "the proxy must be asked to CONNECT to the real target")

        workers.cancel()
        proxyServer.close()
        runtime.close()
    }
}
