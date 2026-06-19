package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.Socks5Config
import io.github.yuroyami.kitetorrent.session.net.bindTcp
import io.github.yuroyami.kitetorrent.session.tracker.AnnounceRequest
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.github.yuroyami.kitetorrent.session.tracker.TrackerEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
import kotlin.test.assertTrue

/**
 * HTTP tracker through a SOCKS5 proxy (gap C): with [NetworkRuntime.proxy] set, [HttpTracker]
 * tunnels a plain `http://` announce through `connectTcp` (which performs the SOCKS5 negotiation)
 * instead of dialling the tracker directly, then speaks HTTP/1.1 over the tunnel. A fake SOCKS5
 * proxy doubles as the HTTP origin: it negotiates, captures the CONNECT target, reads the GET, and
 * replies with a bencoded announce body. The injected ktor client is never used on this path.
 */
class HttpTrackerProxyTest {

    @Test
    fun announceTunnelsHttpThroughSocks5Proxy() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val proxyServer = runtime.bindTcp(0, "127.0.0.1")
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        val target = CompletableDeferred<String>()
        val requestLine = CompletableDeferred<String>()

        workers.launch {
            val c = proxyServer.accept()
            runCatching {
                // SOCKS5 negotiation
                val greeting = c.readExactly(2)
                c.readExactly(greeting[1].toInt())
                c.write(byteArrayOf(0x05, 0x00))
                val req = c.readExactly(4)                 // VER, CMD, RSV, ATYP
                val atyp = req[3].toInt() and 0xFF
                val host = when (atyp) {
                    0x01 -> {
                        val a = c.readExactly(4)
                        "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}.${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}"
                    }
                    0x03 -> {
                        val len = c.readExactly(1)[0].toInt() and 0xFF
                        c.readExactly(len).decodeToString()
                    }
                    else -> { c.readExactly(16); "v6" }
                }
                val portB = c.readExactly(2)
                val port = ((portB[0].toInt() and 0xFF) shl 8) or (portB[1].toInt() and 0xFF)
                target.complete("$host:$port")
                c.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // granted

                // now act as the HTTP origin behind the tunnel: read the request, reply bencoded.
                val sb = StringBuilder()
                while (true) {
                    val ch = c.readExactly(1)[0].toInt() and 0xFF
                    sb.append(ch.toChar())
                    if (ch == '\n'.code && sb.endsWith("\r\n\r\n")) break
                }
                requestLine.complete(sb.toString().substringBefore("\r\n"))
                val body = "d8:intervali1800e5:peers0:e".encodeToByteArray()
                val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".encodeToByteArray() + body
                c.write(resp)
            }
        }

        runtime.proxy = Socks5Config("127.0.0.1", proxyServer.localPort)
        val client = HttpClient(CIO)
        val tracker = HttpTracker(client, network = runtime)

        val req = AnnounceRequest(
            infoHash = Digest32.sha1(ByteArray(20)),
            peerId = Digest32.sha1(ByteArray(20) { 1 }),
            port = 6881,
            uploaded = 0L,
            downloaded = 0L,
            left = 100L,
            event = TrackerEvent.STARTED,
        )

        val (resp, reached, line) = withTimeout(15_000) {
            val r = tracker.announce("http://tracker.example:8080/announce", req)
            Triple(r, target.await(), requestLine.await())
        }

        assertEquals(1800, resp.interval, "the bencoded body fetched over the tunnel must parse")
        assertEquals("tracker.example:8080", reached, "the proxy must be asked to reach the real tracker host")
        assertTrue(line.startsWith("GET /announce?"), "the tracker GET must traverse the tunnel: $line")

        client.close()
        workers.cancel()
        proxyServer.close()
        runtime.close()
    }
}
