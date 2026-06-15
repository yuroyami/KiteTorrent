package io.github.yuroyami.kitetorrent.extensions

import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry

/**
 * The BEP-10 extension-protocol handshake — the bencoded dict exchanged as `extended`
 * message id 0 right after the BitTorrent handshake (when the extension reserved bit is
 * set). Port of libtorrent's `bt_peer_connection::write_extensions` /
 * `parse_extensions`.
 *
 * The `m` dict maps extension names → the message id the *sender* wants the peer to use
 * when sending it that extension. So when we receive a peer's handshake, `m["ut_metadata"]`
 * is the id WE must put on `extended` messages we send them; the ids in [build] are the
 * ones we ask them to use toward us.
 */
object ExtensionHandshake {
    /** `extended` message id reserved for the handshake itself. */
    const val HANDSHAKE_ID = 0

    /** Ids we advertise for peers to use when sending these extensions to us. */
    const val UT_METADATA_ID = 1
    const val UT_PEX_ID = 2

    /** Build our handshake payload. [metadataSize] is included only when we know the info size. */
    fun build(
        metadataSize: Long? = null,
        clientVersion: String = "KiteTorrent",
        listenPort: Int? = null,
        supportUtPex: Boolean = true,
    ): ByteArray {
        val m = Entry.dict()
        m["ut_metadata"] = Entry.of(UT_METADATA_ID.toLong())
        if (supportUtPex) m["ut_pex"] = Entry.of(UT_PEX_ID.toLong())
        val d = Entry.dict()
        d["m"] = m
        if (metadataSize != null) d["metadata_size"] = Entry.of(metadataSize)
        d["v"] = Entry.of(clientVersion)
        if (listenPort != null) d["p"] = Entry.of(listenPort.toLong())
        return Bencode.encode(d)
    }

    /** A peer's parsed handshake: the ids to use toward them + the metadata size they reported. */
    data class Parsed(
        val utMetadataId: Int?,
        val utPexId: Int?,
        val metadataSize: Long?,
        val clientVersion: String?,
    ) {
        val supportsMetadata: Boolean get() = utMetadataId != null && utMetadataId != 0
    }

    fun parse(payload: ByteArray): Parsed? {
        val node = Bdecode.decodeOrNull(payload) ?: return null
        if (node.type != BdecodeNode.Type.DICT) return null
        val m = node.dictFindDict("m")
        val utMetadata = if (m.isValid) m.dictFindIntValue("ut_metadata", 0L).toInt().takeIf { it != 0 } else null
        val utPex = if (m.isValid) m.dictFindIntValue("ut_pex", 0L).toInt().takeIf { it != 0 } else null
        val size = node.dictFindInt("metadata_size").takeIf { it.isValid }?.intValue()
        val version = node.dictFindString("v").takeIf { it.isValid }?.stringValue()
        return Parsed(utMetadata, utPex, size, version)
    }
}
