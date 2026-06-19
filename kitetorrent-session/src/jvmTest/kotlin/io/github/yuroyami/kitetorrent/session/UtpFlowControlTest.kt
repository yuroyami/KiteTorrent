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
import kotlin.test.assertEquals

/**
 * uTP flow control: the sender never keeps more bytes in flight than the peer's advertised
 * receive window. The effective send window is min(cwnd, peer-window) — so a small advertised
 * window binds below the congestion window, and a large one lets the congestion window rule.
 * Previously the peer's `wnd_size` was ignored, which could overrun a slow receiver.
 */
class UtpFlowControlTest {

    @Test
    fun effectiveWindowIsMinOfCwndAndPeerWindow() = runBlocking {
        val runtime = NetworkRuntime(Dispatchers.IO)
        val sock = runtime.bindUdp(0)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val s = UtpStream(sock, "127.0.0.1", 9, scope, selfTick = false)

            // a tiny advertised window binds below cwnd (INIT_CWND = 2×MSS)
            s.feedPeerWindowForTest(1000)
            assertEquals(1000, s.effectiveSendWindowForTest(), "small peer window must cap the send window")

            // a large advertised window lets the congestion window rule
            s.feedPeerWindowForTest(1_000_000)
            assertEquals(UtpStream.INIT_CWND, s.effectiveSendWindowForTest(), "large peer window → cwnd binds")

            // a zero window (receiver full) clamps the limit to zero (a probe still goes out
            // via the bytesInFlight==0 escape in awaitWindow)
            s.feedPeerWindowForTest(0)
            assertEquals(0, s.effectiveSendWindowForTest(), "a full receiver advertises a zero window")
        } finally {
            scope.cancel()
            sock.close()
            runtime.close()
        }
    }
}
