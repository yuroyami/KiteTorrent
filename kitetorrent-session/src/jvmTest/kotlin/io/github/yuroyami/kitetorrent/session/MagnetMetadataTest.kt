package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.extensions.ExtensionHandshake
import io.github.yuroyami.kitetorrent.extensions.UtMetadata
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.protocol.PeerMessage
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.bindTcp
import io.github.yuroyami.kitetorrent.session.net.connectTcp
import io.github.yuroyami.kitetorrent.session.peer.MetadataExchange
import io.github.yuroyami.kitetorrent.session.peer.PeerConnection
import io.github.yuroyami.kitetorrent.session.peer.asByteStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves magnet-style start: a peer that has the `info` dict serves it over BEP-9
 * `ut_metadata` (negotiated by the BEP-10 extension handshake). A [MetadataExchange]
 * that begins with only the info-hash, as a magnet link does, then fetches, reassembles
 * and verifies it. The recovered bytes must be byte-identical to the original info dict
 * and hash back to the info-hash.
 */
class MagnetMetadataTest {

    private fun peerId(tag: String): Sha1Hash {
        val b = ByteArray(20)
        tag.encodeToByteArray().copyInto(b, 0, 0, minOf(tag.length, 20))
        return Digest32.of(b)
    }

    @Test
    fun fetchesMetadataFromPeerOverUtMetadata() = runBlocking {
        // a real info dict, > 16 KiB so the metadata is split into multiple ut_metadata pieces
        val pieces = ByteArrayBuilder().apply { repeat(40) { append(ByteArray(20) { i -> (it + i).toByte() }) } }
        val infoDict = Entry.dict().apply {
            this["name"] = Entry.of("magnet-payload.bin")
            this["piece length"] = Entry.of(16384L)
            this["length"] = Entry.of(40L * 16384)
            this["pieces"] = Entry.of(pieces.toByteArray()) // 40 * 20 = 800 bytes
            this["padding"] = Entry.of(ByteArray(20_000) { (it and 0x7f).toByte() }) // push info dict > 16KiB
        }
        val infoBytes = Bencode.encode(infoDict)
        val infoHash = Hasher.hash(infoBytes)
        assertTrue(infoBytes.size > 16384, "info dict should span >1 metadata piece")

        val runtime = NetworkRuntime(Dispatchers.IO)
        val server = runtime.bindTcp(0, "127.0.0.1")
        val workers = CoroutineScope(coroutineContext + SupervisorJob())

        // --- fake peer that owns the metadata and serves ut_metadata requests ---
        workers.launch {
            val conn = server.accept()
            val pc = PeerConnection(conn.asByteStream(), infoHash, peerId("KT-meta-srv"), numPieces = 4)
            pc.performHandshake()
            pc.sendExtended(ExtensionHandshake.HANDSHAKE_ID, ExtensionHandshake.build(metadataSize = infoBytes.size.toLong()))
            pc.receiveLoop { msg ->
                if (msg is PeerMessage.Extended && msg.extId == ExtensionHandshake.UT_METADATA_ID) {
                    val m = UtMetadata.parse(msg.payload)
                    if (m != null && m.type == UtMetadata.Type.REQUEST) {
                        val off = m.piece * 16384
                        val len = UtMetadata.pieceLength(m.piece, infoBytes.size)
                        val data = infoBytes.copyOfRange(off, off + len)
                        // reply with the id the fetcher advertised (ut_metadata = 1)
                        pc.sendExtended(ExtensionHandshake.UT_METADATA_ID, UtMetadata.encodeData(m.piece, infoBytes.size.toLong(), data))
                    }
                }
            }
        }

        // --- fetcher: starts with only the info-hash (a magnet) ---
        val conn = runtime.connectTcp("127.0.0.1", server.localPort)
        val pc = PeerConnection(conn.asByteStream(), infoHash, peerId("KT-magnet"), numPieces = 0)
        pc.performHandshake()

        val fetched = withTimeout(15_000) { MetadataExchange(pc, infoHash).fetch(timeoutMillis = 12_000) }

        workers.cancel()
        conn.close()
        server.close()
        runtime.close()

        assertNotNull(fetched, "should have fetched the metadata")
        assertTrue(fetched.contentEquals(infoBytes), "fetched info dict must be byte-identical")
        assertEquals(infoHash, Hasher.hash(fetched), "fetched metadata must hash to the info-hash")
    }
}
