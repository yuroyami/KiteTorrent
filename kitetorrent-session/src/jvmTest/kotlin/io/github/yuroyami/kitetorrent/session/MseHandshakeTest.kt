package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.protocol.PeerMessage
import io.github.yuroyami.kitetorrent.session.peer.ByteStream
import io.github.yuroyami.kitetorrent.session.peer.Mse
import io.github.yuroyami.kitetorrent.session.peer.MseByteStream
import io.github.yuroyami.kitetorrent.session.peer.PeerConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end MSE/PE proof: two peers complete the encrypted handshake over a duplex, then
 * run the *real* BitTorrent handshake and exchange a message over the resulting channel.
 * Because every byte after negotiation is RC4-streamed, a successful BitTorrent handshake
 * (whose pstr must decode to "BitTorrent protocol") is itself proof the two keystreams are
 * correctly derived and aligned — any drift would turn it into garbage and fail the decode.
 */
class MseHandshakeTest {

    /** An in-memory connected duplex: `a.write` feeds `b.read` and vice-versa. */
    private class PipeStream(
        private val outCh: Channel<ByteArray>,
        private val inCh: Channel<ByteArray>,
    ) : ByteStream {
        private var buf = ByteArray(0)
        private var pos = 0
        override suspend fun write(bytes: ByteArray) { outCh.send(bytes.copyOf()) }
        override suspend fun readExactly(n: Int): ByteArray {
            while (buf.size - pos < n) {
                val more = inCh.receive()
                buf = buf.copyOfRange(pos, buf.size) + more
                pos = 0
            }
            val out = buf.copyOfRange(pos, pos + n); pos += n; return out
        }
        override val remoteAddress: String = "pipe"
    }

    private fun pair(): Pair<ByteStream, ByteStream> {
        val ab = Channel<ByteArray>(Channel.UNLIMITED)
        val ba = Channel<ByteArray>(Channel.UNLIMITED)
        return PipeStream(ab, ba) to PipeStream(ba, ab)
    }

    private fun hash(seed: Int): Sha1Hash = Digest32.of(ByteArray(20) { ((it + seed) and 0xff).toByte() })

    @Test
    fun encryptedHandshakeThenBittorrentHandshakeAndMessage() = runBlocking {
        val (rawA, rawB) = pair()
        val infoHash = hash(7)
        val numPieces = 8

        // negotiate MSE concurrently (initiator dials, receiver accepts)
        val secureA = async { Mse.initiate(rawA, infoHash, Random(1)) }
        val secureB = async { Mse.accept(rawB, infoHash, Random(2)) }
        val sa = secureA.await()
        val sb = secureB.await()

        assertTrue(sa is MseByteStream, "initiator must end up on the encrypted transport")
        assertTrue(sb is MseByteStream, "receiver must end up on the encrypted transport")

        // the BitTorrent handshake now runs over the encrypted channel
        val pcA = PeerConnection(sa, infoHash, hash(100), numPieces)
        val pcB = PeerConnection(sb, infoHash, hash(200), numPieces)
        withTimeout(10_000) {
            val ha = async { pcA.performHandshake() }
            val hb = async { pcB.performHandshake() }
            ha.await(); hb.await()
        }

        // an ordinary message must also survive the RC4 stream end-to-end
        val received = CompletableDeferred<PeerMessage>()
        val loop = launch {
            runCatching { pcB.receiveLoop { msg -> if (!received.isCompleted) received.complete(msg) } }
        }
        pcA.sendInterested()
        val msg = withTimeout(10_000) { received.await() }
        assertTrue(msg is PeerMessage.Interested, "decrypted message must be the Interested we sent")

        loop.cancel()
    }
}
