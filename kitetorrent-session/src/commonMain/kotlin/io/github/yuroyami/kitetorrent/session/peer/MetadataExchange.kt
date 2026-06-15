package io.github.yuroyami.kitetorrent.session.peer

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.extensions.ExtensionHandshake
import io.github.yuroyami.kitetorrent.extensions.MetadataTransfer
import io.github.yuroyami.kitetorrent.extensions.UtMetadata
import io.github.yuroyami.kitetorrent.protocol.PeerMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetch a torrent's metadata (the `info` dict) from a peer over BEP-9 (`ut_metadata`),
 * negotiated via the BEP-10 extension handshake — i.e. how a download starts from a
 * magnet link, where we have only the info-hash. Port of libtorrent's `ut_metadata`
 * plugin driving `metadata_transfer`.
 *
 * The owning [pc] must already have completed the BitTorrent handshake with the
 * extension bit set, and must have been created with `numPieces = 0` (we don't know the
 * piece count until the metadata arrives — [PeerConnection] tolerates an unknown-size
 * bitfield in that mode).
 */
class MetadataExchange(
    private val pc: PeerConnection,
    private val infoHash: Sha1Hash,
) {
    /**
     * Run the exchange and return the raw, verified `info`-dict bytes (whose SHA-1 equals
     * [infoHash]), or null if the peer can't/won't provide them within [timeoutMillis].
     */
    suspend fun fetch(timeoutMillis: Long = 30_000): ByteArray? = coroutineScope {
        val result = CompletableDeferred<ByteArray?>()
        var theirMetadataId = -1
        var transfer: MetadataTransfer? = null

        // 1. send our extension handshake (we advertise ut_metadata = id 1).
        pc.sendExtended(ExtensionHandshake.HANDSHAKE_ID, ExtensionHandshake.build())

        // 2. drive the peer's messages; we only care about `extended` ones.
        val loop = launch {
            try {
                pc.receiveLoop { msg ->
                    if (msg !is PeerMessage.Extended) return@receiveLoop
                    when (msg.extId) {
                        ExtensionHandshake.HANDSHAKE_ID -> {
                            val hs = ExtensionHandshake.parse(msg.payload)
                            val size = hs?.metadataSize
                            if (hs?.supportsMetadata == true && size != null && size in 1..MAX_METADATA_SIZE) {
                                theirMetadataId = hs.utMetadataId!!
                                val t = MetadataTransfer(size.toInt())
                                transfer = t
                                for (piece in t.missingPieces()) {
                                    pc.sendExtended(theirMetadataId, UtMetadata.encodeRequest(piece))
                                }
                            } else {
                                result.complete(null) // peer has no metadata to give
                            }
                        }
                        ExtensionHandshake.UT_METADATA_ID -> {
                            // a ut_metadata message addressed to us (we advertised id 1)
                            val m = UtMetadata.parse(msg.payload) ?: return@receiveLoop
                            val t = transfer ?: return@receiveLoop
                            t.addMessage(m)
                            if (t.missingPieces().isEmpty()) {
                                result.complete(if (t.verify(infoHash)) t.assembled() else null)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // peer disconnected / protocol error
            }
            if (!result.isCompleted) result.complete(null)
        }

        val out = withTimeoutOrNull(timeoutMillis) { result.await() }
        loop.cancel()
        out
    }

    companion object {
        /** Sanity cap on advertised metadata size (libtorrent's default is similar). */
        const val MAX_METADATA_SIZE: Long = 8L * 1024 * 1024
    }
}
