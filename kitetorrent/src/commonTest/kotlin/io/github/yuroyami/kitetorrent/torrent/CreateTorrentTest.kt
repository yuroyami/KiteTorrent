package io.github.yuroyami.kitetorrent.torrent

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.crypto.Hasher256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [CreateTorrent], the pure-Kotlin port of libtorrent's `create_torrent`.
 *
 * The headline single-file test uses the *exact* four piece hashes from
 * libtorrent's own `test/mutable_test_torrents/test1_single.torrent` (a single
 * 51200-byte file named "a", 16 KiB pieces). Because [CreateTorrent] builds the same
 * canonical `info` dictionary, its generated info-hash must equal the one libtorrent
 * (and Python's hashlib) produce for that torrent -- proving byte-for-byte
 * interoperability, not just internal round-trip stability.
 */
class CreateTorrentTest {

    // The four 20-byte v1 piece hashes of test1_single.torrent, in order.
    private val piece0 = Digest32.fromHex("c8c4d47d84c77f5f4d4ca429d8af1e796d241c1a")
    private val piece1 = Digest32.fromHex("84b60ade9fcf6b7d4019fb88d18db962c68faeb8")
    private val piece2 = Digest32.fromHex("dff2fc9ddd20873de51de6e2767f77898daf5114")
    private val piece3 = Digest32.fromHex("1aa6285221af2b4f30155e2b1b0e77f7490795c7")

    private fun singleFileV1(): CreateTorrent =
        CreateTorrent.Builder(name = "a", pieceLength = 16384)
            .addFile("a", 51200)
            .build()
            .setHash(0, piece0)
            .setHash(1, piece1)
            .setHash(2, piece2)
            .setHash(3, piece3)

    @Test
    fun single_file_info_hash_matches_libtorrent() {
        val ct = singleFileV1()
        val t = TorrentInfo.parse(ct.generateBuffer())

        // This is the independently-computed SHA-1 of the canonical info dict for a
        // single 51200-byte file "a" with these four piece hashes.
        assertEquals("0496aa386ece056e0bf79d5d39c7df9f533fed92", t.infoHashV1!!.toHex())
    }

    @Test
    fun single_file_round_trips_through_torrent_info() {
        val ct = singleFileV1()
        val bytes = ct.generateBuffer()
        val t = TorrentInfo.parse(bytes)

        assertEquals("a", t.name)
        assertEquals(51200L, t.totalSize)
        assertEquals(16384, t.pieceLength)
        assertEquals(4, t.numPieces)
        assertEquals(1, t.numFiles)
        assertTrue(t.storage.isSingleFile)
        assertEquals("a", t.files[0].path)
        assertEquals(0L, t.files[0].offset)
        assertTrue(t.isV1)
        assertFalse(t.isV2)
        assertEquals(1, t.metaVersion)

        // piece hashes survive the trip in order
        assertEquals(piece0.toHex(), t.pieceHashV1(0)!!.toHex())
        assertEquals(piece3.toHex(), t.pieceHashV1(3)!!.toHex())

        // last piece is the remainder: 51200 - 3*16384 = 2048
        assertEquals(2048, t.storage.pieceSize(3))
        assertEquals(16384, t.storage.pieceSize(0))
    }

    @Test
    fun reparse_yields_stable_info_hash() {
        val bytes = singleFileV1().generateBuffer()
        // determinism: encoding once, parsing twice gives the same hash
        assertEquals(
            TorrentInfo.parse(bytes).infoHashV1,
            TorrentInfo.parse(bytes).infoHashV1,
        )
        // and re-generating from a fresh builder is byte-identical
        assertTrue(singleFileV1().generateBuffer().contentEquals(bytes))
    }

    @Test
    fun metadata_fields_are_written_and_parsed_back() {
        val ct = singleFileV1()
            .setComment("hello world")
            .setCreator("KiteTorrent test")
            .setCreationDate(1419490700L)
            .setPrivate(true)
            .addTracker("udp://tracker.one:80", tier = 0)
            .addTracker("http://tracker.two/announce", tier = 1)
            .addUrlSeed("http://seed.example/a")
            .addNode("dht.example.com", 6881)

        val t = TorrentInfo.parse(ct.generateBuffer())

        assertEquals("hello world", t.comment)
        assertEquals("KiteTorrent test", t.createdBy)
        assertEquals(1419490700L, t.creationDate)
        assertTrue(t.isPrivate)
        // two tiers, one tracker each, in tier order
        assertEquals(
            listOf(listOf("udp://tracker.one:80"), listOf("http://tracker.two/announce")),
            t.trackers,
        )
        assertEquals(listOf("http://seed.example/a"), t.webSeeds)
        assertEquals(listOf("dht.example.com" to 6881), t.dhtNodes)
    }

    @Test
    fun private_flag_changes_the_info_hash() {
        val publicHash = TorrentInfo.parse(singleFileV1().generateBuffer()).infoHashV1
        val privateHash = TorrentInfo.parse(
            singleFileV1().setPrivate(true).generateBuffer(),
        ).infoHashV1
        // the "private" key lives inside the info dict, so it must alter the hash
        assertTrue(publicHash != privateHash)
    }

    @Test
    fun multi_file_round_trips_with_cumulative_offsets() {
        // four files under directory "test1" (sizes mirror test1.torrent)
        val ct = CreateTorrent.Builder(name = "test1", pieceLength = 16384)
            .addFile("a", 51200)
            .addFile("b", 18)
            .addFile("c", 19)
            .addFile("d", 53248)
            .build()

        // total = 104485 -> ceil(/16384) = 7 pieces
        val numPieces = ct.numPieces()
        assertEquals(7, numPieces)
        for (i in 0 until numPieces) ct.setHash(i, fakeSha1(i))

        val t = TorrentInfo.parse(ct.generateBuffer())
        assertEquals("test1", t.name)
        assertEquals(4, t.numFiles)
        assertEquals(
            listOf("test1/a", "test1/b", "test1/c", "test1/d"),
            t.files.map { it.path },
        )
        assertEquals(listOf(51200L, 18L, 19L, 53248L), t.files.map { it.size })
        assertEquals(listOf(0L, 51200L, 51218L, 51237L), t.files.map { it.offset })
        assertEquals(51200L + 18 + 19 + 53248, t.totalSize)
        assertEquals(7, t.numPieces)
        assertTrue(t.isV1)
        assertFalse(t.isV2)
    }

    @Test
    fun v2_only_torrent_round_trips_file_tree_and_roots() {
        // two files of 32768 bytes each -> 2 blocks (== 2 pieces) per file, so each
        // file gets a real piece-layers entry and a multi-block merkle root.
        val pieceLen = 16384 // == block size, so blocks-per-piece == 1
        val leavesX = listOf(fakeSha256("x", 0), fakeSha256("x", 1))
        val leavesY = listOf(fakeSha256("y", 0), fakeSha256("y", 1))

        val ct = CreateTorrent.Builder(name = "root", pieceLength = pieceLen)
            .addFile("x.bin", 32768)
            .addFile("y.bin", 32768)
            .build(v2Only = true)
            .setFileV2Leaves(0, leavesX)
            .setFileV2Leaves(1, leavesY)

        val bytes = ct.generateBuffer()
        val t = TorrentInfo.parse(bytes)

        assertTrue(t.isV2)
        assertFalse(t.isV1)
        assertEquals(2, t.metaVersion)
        assertEquals(2, t.numFiles)
        assertEquals(listOf("root/x.bin", "root/y.bin"), t.files.map { it.path })
        assertEquals(listOf(32768L, 32768L), t.files.map { it.size })

        // the per-file root parsed from "pieces root" must equal the root computed
        // independently by the (separately-tested) MerkleTree from the same leaves.
        val expectedRootX = MerkleTree.fromLeaves(leavesX).root()
        val expectedRootY = MerkleTree.fromLeaves(leavesY).root()
        assertNotNull(t.files[0].piecesRoot)
        assertNotNull(t.files[1].piecesRoot)
        assertEquals(expectedRootX.toHex(), t.files[0].piecesRoot!!.toHex())
        assertEquals(expectedRootY.toHex(), t.files[1].piecesRoot!!.toHex())

        // the v2 info-hash must be stable across re-parses
        assertEquals(
            TorrentInfo.parse(bytes).infoHashV2,
            TorrentInfo.parse(bytes).infoHashV2,
        )
    }

    @Test
    fun hybrid_torrent_carries_both_v1_and_v2_metadata() {
        // multi-file hybrid: file "a" spans 2 blocks (2 pieces), file "b" is 1 block.
        // v1 geometry: total 49152 / 16384 = 3 pieces.
        val leavesA = listOf(fakeSha256("a", 0), fakeSha256("a", 1))
        val leavesB = listOf(fakeSha256("b", 0))
        val ct = CreateTorrent.Builder(name = "h", pieceLength = 16384)
            .addFile("a", 32768)
            .addFile("b", 16384)
            .build()
            .setFileV2Leaves(0, leavesA)
            .setFileV2Leaves(1, leavesB)
        for (i in 0 until ct.numPieces()) ct.setHash(i, fakeSha1(i))
        assertEquals(3, ct.numPieces())

        val bytes = ct.generateBuffer()
        val t = TorrentInfo.parse(bytes)

        assertTrue(t.isHybrid)
        assertEquals(2, t.metaVersion)
        assertEquals(49152L, t.totalSize)
        assertEquals(listOf("h/a", "h/b"), t.files.map { it.path })
        // v1 side: the pieces blob is present and indexable
        assertEquals(fakeSha1(0).toHex(), t.pieceHashV1(0)!!.toHex())
        // v2 side: per-file roots match the independent MerkleTree computation
        assertEquals(MerkleTree.fromLeaves(leavesA).root().toHex(), t.files[0].piecesRoot!!.toHex())
        assertEquals(MerkleTree.fromLeaves(leavesB).root().toHex(), t.files[1].piecesRoot!!.toHex())
    }

    @Test
    fun generate_fails_when_not_all_v1_hashes_set() {
        val ct = CreateTorrent.Builder(name = "a", pieceLength = 16384)
            .addFile("a", 51200)
            .build()
            .setHash(0, piece0) // only 1 of 4
        assertFailsWith<IllegalStateException> { ct.generate() }
    }

    @Test
    fun v2_only_rejects_v1_hash_and_vice_versa() {
        val v2 = CreateTorrent.Builder(name = "a", pieceLength = 16384)
            .addFile("a", 16384)
            .build(v2Only = true)
        assertFailsWith<IllegalStateException> { v2.setHash(0, piece0) }

        val v1 = CreateTorrent.Builder(name = "a", pieceLength = 16384)
            .addFile("a", 16384)
            .build(v1Only = true)
        assertFailsWith<IllegalStateException> { v1.setHash2(0, 0, fakeSha256("a", 0)) }
    }

    @Test
    fun empty_storage_is_rejected_at_construction() {
        assertFailsWith<IllegalArgumentException> {
            CreateTorrent.Builder(name = "a", pieceLength = 16384)
                .addFile("a", 0) // zero total size
                .build()
        }
    }

    // ---- helpers -------------------------------------------------------------

    /** A deterministic, non-zero 20-byte hash for tests that don't need real data. */
    private fun fakeSha1(seed: Int): Sha1Hash {
        val b = ByteArray(Digest32.SHA1_SIZE) { ((seed + it + 1) and 0xff).toByte() }
        return Digest32.sha1(b)
    }

    /** A deterministic, non-zero 32-byte block leaf for v2 tests. */
    private fun fakeSha256(file: String, block: Int): Sha256Hash =
        Hasher256.hash("$file-block$block".encodeToByteArray())
}
