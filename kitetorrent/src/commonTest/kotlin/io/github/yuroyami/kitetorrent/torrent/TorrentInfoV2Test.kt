package io.github.yuroyami.kitetorrent.torrent

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.crypto.Hasher256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validates the v2 (BEP-52) download anchor: parsing the top-level `piece layers` and the
 * [TorrentInfo.pieceHashV2] accessor, against a real v2 torrent built by [CreateTorrent].
 * For each piece we recompute the merkle root from the file's block hashes exactly as the
 * live download path will (pad to blocks-per-piece with the zero block hash) and assert it
 * equals the published piece hash, so the verification recipe matches the torrent's own
 * independently-computed piece layers.
 */
class TorrentInfoV2Test {

    private val blockSize = 16 * 1024

    @Test
    fun pieceHashV2MatchesRecomputedMerkleRoots() {
        val pieceLength = 64 * 1024 // 4 blocks per piece
        val data = ByteArray(200_000) { ((it * 53 + 7) and 0xff).toByte() }
        val name = "payload.bin"

        // block-level SHA-256 leaves of the file
        val leaves = ArrayList<Sha256Hash>()
        var off = 0
        while (off < data.size) {
            val len = minOf(blockSize, data.size - off)
            leaves.add(Hasher256.hash(data, off, len))
            off += blockSize
        }

        val storage = FileStorage(name, pieceLength, listOf(FileEntry(path = name, size = data.size.toLong(), offset = 0L)))
        val bytes = CreateTorrent(storage, v2Only = true).setFileV2Leaves(0, leaves).generateBuffer()

        val ti = TorrentInfo.parse(bytes)
        assertTrue(ti.isV2 && !ti.isV1, "must be a v2-only torrent")
        assertEquals((data.size + pieceLength - 1) / pieceLength, ti.numPieces)

        val blocksPerPiece = pieceLength / blockSize
        val zero = Digest32.zeros(Digest32.SHA256_SIZE)
        for (p in 0 until ti.numPieces) {
            val firstBlock = p * blocksPerPiece
            val lastBlock = minOf(firstBlock + blocksPerPiece, leaves.size)
            val pieceLeaves = leaves.subList(firstBlock, lastBlock).toMutableList()
            // multi-piece: each piece's hash is the root over exactly blocks-per-piece leaves,
            // padding a partial last piece with the all-zero block hash
            if (ti.numPieces > 1) while (pieceLeaves.size < blocksPerPiece) pieceLeaves.add(zero)
            val recomputed = Merkle.merkleRoot(pieceLeaves, zero)

            val published = ti.pieceHashV2(p)
            assertNotNull(published, "piece $p must have a v2 hash from the piece layers")
            assertEquals(published, recomputed, "piece $p merkle root must match the published piece hash")
        }
    }

    @Test
    fun wireInfoHashTruncatesV2ToTwentyBytes() {
        val pieceLength = 64 * 1024
        val data = ByteArray(50_000) { (it and 0xff).toByte() }
        val name = "f.bin"
        val leaves = ArrayList<Sha256Hash>()
        var off = 0
        while (off < data.size) {
            leaves.add(Hasher256.hash(data, off, minOf(blockSize, data.size - off))); off += blockSize
        }
        val storage = FileStorage(name, pieceLength, listOf(FileEntry(path = name, size = data.size.toLong(), offset = 0L)))
        val ti = TorrentInfo.parse(CreateTorrent(storage, v2Only = true).setFileV2Leaves(0, leaves).generateBuffer())

        val wire = ti.wireInfoHash()
        assertEquals(Digest32.SHA1_SIZE, wire.toByteArray().size)
        assertTrue(wire.toByteArray().contentEquals(ti.infoHashV2!!.toByteArray().copyOf(Digest32.SHA1_SIZE)))
    }
}
