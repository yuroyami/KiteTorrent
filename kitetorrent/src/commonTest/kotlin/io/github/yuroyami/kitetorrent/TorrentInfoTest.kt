package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parses two *real* torrents taken verbatim from libtorrent's own test suite
 * (test/mutable_test_torrents). The expected info-hashes were computed
 * independently with Python's hashlib over the raw `info` dictionary bytes, so a
 * match proves KiteTorrent's parse + hash pipeline is interoperable with the rest
 * of the BitTorrent world.
 */
class TorrentInfoTest {

    // test/mutable_test_torrents/test1_single.torrent — a single 51200-byte file "a"
    private val singleFileTorrentHex =
        "6431303a6372656174656420627931303a6c6962746f7272656e7431333a6372656174696f" +
        "6e2064617465693134313934393037303065343a696e666f64363a6c656e67746869353132" +
        "303065343a6e616d65313a6131323a7069656365206c656e67746869313633383465363a70" +
        "696563657338303ac8c4d47d84c77f5f4d4ca429d8af1e796d241c1a84b60ade9fcf6b7d40" +
        "19fb88d18db962c68faeb8dff2fc9ddd20873de51de6e2767f77898daf51141aa6285221af" +
        "2b4f30155e2b1b0e77f7490795c76565"

    // test/mutable_test_torrents/test1.torrent — a 4-file torrent under directory "test1"
    private val multiFileTorrentHex =
        "6431303a6372656174656420627931303a6c6962746f7272656e7431333a637265617469" +
        "6f6e2064617465693134313934353239393265343a696e666f64353a66696c65736c6436" +
        "3a6c656e67746869353132303065343a706174686c313a61656564363a6c656e67746869" +
        "313865343a706174686c313a62656564363a6c656e67746869313965343a706174686c31" +
        "3a63656564363a6c656e67746869353332343865343a706174686c313a64656565343a6e" +
        "616d65353a746573743131323a7069656365206c656e67746869313633383465363a7069" +
        "656365733134303ac8c4d47d84c77f5f4d4ca429d8af1e796d241c1a84b60ade9fcf6b7d" +
        "4019fb88d18db962c68faeb8dff2fc9ddd20873de51de6e2767f77898daf51145caf5c74" +
        "ff72a79f7378098f5ef17af23537b846ac12eb57609e6ef87279dcb1741ba756c8dbc0d4" +
        "42f46fad48518fdd6b1ba6a595c4d0821adcf797bd0355e7f4c036a4510af16baa956bd8" +
        "e00974a26565"

    @Test
    fun single_file_torrent() {
        val t = TorrentInfo.parse(Hex.decode(singleFileTorrentHex))
        // info-hash computed independently via hashlib over the raw info dict
        assertEquals("0496aa386ece056e0bf79d5d39c7df9f533fed92", t.infoHashV1!!.toHex())
        assertEquals("a", t.name)
        assertEquals(51200L, t.totalSize)
        assertEquals(16384, t.pieceLength)
        assertEquals(4, t.numPieces)
        assertEquals(1, t.numFiles)
        assertTrue(t.storage.isSingleFile)
        assertEquals("a", t.files[0].path)
        assertEquals(51200L, t.files[0].size)
        assertEquals(0L, t.files[0].offset)
        // first piece hash = first 20 bytes of the pieces blob
        assertEquals("c8c4d47d84c77f5f4d4ca429d8af1e796d241c1a", t.pieceHashV1(0)!!.toHex())
        // metadata
        assertEquals("libtorrent", t.createdBy)
        assertEquals(1419490700L, t.creationDate)
        assertTrue(t.isV1)
        assertFalse(t.isV2)
        assertEquals(1, t.metaVersion)
        // last piece is the remainder: 51200 - 3*16384 = 2048
        assertEquals(2048, t.storage.pieceSize(3))
        assertEquals(16384, t.storage.pieceSize(0))
    }

    @Test
    fun multi_file_torrent() {
        val t = TorrentInfo.parse(Hex.decode(multiFileTorrentHex))
        assertEquals("d43e185e297421ea3f253ae04300e373aef9404e", t.infoHashV1!!.toHex())
        assertEquals("test1", t.name)
        assertEquals(4, t.numFiles)
        assertEquals(16384, t.pieceLength)

        // files are listed under the root directory, in order, with cumulative offsets
        assertEquals(listOf("test1/a", "test1/b", "test1/c", "test1/d"), t.files.map { it.path })
        assertEquals(listOf(51200L, 18L, 19L, 53248L), t.files.map { it.size })
        assertEquals(listOf(0L, 51200L, 51218L, 51237L), t.files.map { it.offset })

        assertEquals(51200L + 18 + 19 + 53248, t.totalSize)
        assertEquals(7, t.numPieces) // ceil(104485 / 16384)
        assertEquals("libtorrent", t.createdBy)
        assertEquals(1419452992L, t.creationDate)
    }

    @Test
    fun reparse_is_stable() {
        // parsing the same bytes twice yields the same info-hash (determinism)
        val bytes = Hex.decode(singleFileTorrentHex)
        assertEquals(TorrentInfo.parse(bytes).infoHashV1, TorrentInfo.parse(bytes).infoHashV1)
    }
}
