package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.crypto.Sha256
import io.github.yuroyami.kitetorrent.torrent.Merkle
import io.github.yuroyami.kitetorrent.torrent.MerkleTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the BitTorrent v2 merkle port ([Merkle] / [MerkleTree]).
 *
 * Two kinds of checks:
 *  1. The index arithmetic is pinned to the exact golden values from libtorrent's
 *     own `test/test_merkle.cpp`. These are integer ground truth, copied verbatim.
 *  2. The SHA-256 root computation is checked against *structural invariants*
 *     computed independently with the reused [Sha256]. Rather than hardcode opaque
 *     32-byte hash literals, we assert that the root of N leaves equals the explicit
 *     nested `SHA-256(left || right)` expression, which is exactly the algorithm's
 *     definition. A regression in the folding logic is therefore caught precisely.
 */
class MerkleTest {

    // --- helpers -------------------------------------------------------------

    /** A distinct, deterministic 32-byte "leaf hash" for index i (just sha256 of a label). */
    private fun leaf(i: Int): Sha256Hash =
        Digest32.sha256(Sha256.hash("leaf-$i".encodeToByteArray()))

    /** SHA-256(left || right), computed straight from the reused [Sha256]. */
    private fun pair(left: Sha256Hash, right: Sha256Hash): Sha256Hash {
        val buf = left.toByteArray() + right.toByteArray()
        return Digest32.sha256(Sha256.hash(buf))
    }

    private val zero: Sha256Hash = Digest32.zeros(32)

    // --- index math (golden vectors from libtorrent test/test_merkle.cpp) ----

    @Test
    fun numLeafs() {
        assertEquals(1, Merkle.numLeafs(1))
        assertEquals(2, Merkle.numLeafs(2))
        assertEquals(4, Merkle.numLeafs(3))
        assertEquals(4, Merkle.numLeafs(4))
        assertEquals(8, Merkle.numLeafs(5))
        assertEquals(8, Merkle.numLeafs(8))
        assertEquals(16, Merkle.numLeafs(9))
        assertEquals(16, Merkle.numLeafs(16))
        assertEquals(32, Merkle.numLeafs(17))
        assertEquals(32, Merkle.numLeafs(18))
    }

    @Test
    fun getParent() {
        assertEquals(0, Merkle.getParent(1))
        assertEquals(0, Merkle.getParent(2))
        assertEquals(1, Merkle.getParent(3))
        assertEquals(1, Merkle.getParent(4))
        assertEquals(2, Merkle.getParent(5))
        assertEquals(2, Merkle.getParent(6))
        assertEquals(3, Merkle.getParent(7))
        assertEquals(6, Merkle.getParent(13))
        assertEquals(6, Merkle.getParent(14))
    }

    @Test
    fun getSibling() {
        assertEquals(2, Merkle.getSibling(1))
        assertEquals(1, Merkle.getSibling(2))
        assertEquals(4, Merkle.getSibling(3))
        assertEquals(3, Merkle.getSibling(4))
        assertEquals(6, Merkle.getSibling(5))
        assertEquals(8, Merkle.getSibling(7))
        assertEquals(14, Merkle.getSibling(13))
        assertEquals(13, Merkle.getSibling(14))
    }

    @Test
    fun numNodes() {
        assertEquals(1, Merkle.numNodes(1))
        assertEquals(3, Merkle.numNodes(2))
        assertEquals(7, Merkle.numNodes(4))
        assertEquals(15, Merkle.numNodes(8))
        assertEquals(31, Merkle.numNodes(16))
    }

    @Test
    fun firstLeaf() {
        assertEquals(0, Merkle.firstLeaf(1))
        assertEquals(1, Merkle.firstLeaf(2))
        assertEquals(3, Merkle.firstLeaf(4))
        assertEquals(7, Merkle.firstLeaf(8))
        assertEquals(15, Merkle.firstLeaf(16))
    }

    @Test
    fun getLayer() {
        assertEquals(0, Merkle.getLayer(0))
        assertEquals(1, Merkle.getLayer(1))
        assertEquals(1, Merkle.getLayer(2))
        assertEquals(2, Merkle.getLayer(3))
        assertEquals(2, Merkle.getLayer(6))
        assertEquals(3, Merkle.getLayer(7))
        assertEquals(3, Merkle.getLayer(14))
        assertEquals(4, Merkle.getLayer(15))
    }

    @Test
    fun getLayerOffset() {
        assertEquals(0, Merkle.getLayerOffset(0))
        assertEquals(0, Merkle.getLayerOffset(1))
        assertEquals(1, Merkle.getLayerOffset(2))
        assertEquals(0, Merkle.getLayerOffset(3))
        assertEquals(3, Merkle.getLayerOffset(6))
        assertEquals(0, Merkle.getLayerOffset(7))
        assertEquals(7, Merkle.getLayerOffset(14))
        assertEquals(0, Merkle.getLayerOffset(15))
    }

    @Test
    fun numLayers() {
        assertEquals(0, Merkle.numLayers(1))
        assertEquals(1, Merkle.numLayers(2))
        assertEquals(2, Merkle.numLayers(4))
        assertEquals(3, Merkle.numLayers(8))
        assertEquals(4, Merkle.numLayers(16))
    }

    @Test
    fun getFirstChild() {
        assertEquals(1, Merkle.getFirstChild(0))
        assertEquals(3, Merkle.getFirstChild(1))
        assertEquals(5, Merkle.getFirstChild(2))
        assertEquals(7, Merkle.getFirstChild(3))
        assertEquals(31, Merkle.getFirstChild(15))
        // two-argument form: descend `depth` levels.
        assertEquals(7, Merkle.getFirstChild(0, 3))
        assertEquals(15, Merkle.getFirstChild(0, 4))
        assertEquals(7, Merkle.getFirstChild(1, 2))
    }

    @Test
    fun layerStartAndFlatIndex() {
        assertEquals(0, Merkle.layerStart(0))
        assertEquals(1, Merkle.layerStart(1))
        assertEquals(3, Merkle.layerStart(2))
        assertEquals(7, Merkle.layerStart(3))
        assertEquals(7, Merkle.toFlatIndex(3, 0))
        assertEquals(10, Merkle.toFlatIndex(3, 3))
        assertEquals(4, Merkle.toFlatIndex(2, 1))
    }

    // --- root computation (structural invariants) ----------------------------

    @Test
    fun rootOfSingleLeafIsTheLeaf() {
        // A one-leaf tree's root IS that leaf. No hashing happens.
        val l0 = leaf(0)
        assertEquals(l0, Merkle.merkleRoot(listOf(l0)))
    }

    @Test
    fun rootOfTwoLeaves() {
        // root = SHA-256(l0 || l1)
        val l0 = leaf(0)
        val l1 = leaf(1)
        val expected = pair(l0, l1)
        assertEquals(expected, Merkle.merkleRoot(listOf(l0, l1)))
    }

    @Test
    fun rootOfFourLeaves() {
        // root = SHA-256( SHA-256(l0||l1) || SHA-256(l2||l3) )
        val l = List(4) { leaf(it) }
        val left = pair(l[0], l[1])
        val right = pair(l[2], l[3])
        val expected = pair(left, right)
        assertEquals(expected, Merkle.merkleRoot(l))
    }

    @Test
    fun rootOfEightLeaves() {
        val l = List(8) { leaf(it) }
        val n01 = pair(l[0], l[1])
        val n23 = pair(l[2], l[3])
        val n45 = pair(l[4], l[5])
        val n67 = pair(l[6], l[7])
        val left = pair(n01, n23)
        val right = pair(n45, n67)
        val expected = pair(left, right)
        assertEquals(expected, Merkle.merkleRoot(l))
    }

    @Test
    fun rootOfThreeLeavesPadsToFour() {
        // 3 leaves -> padded to 4 with the all-zero pad hash on the right.
        // root = SHA-256( SHA-256(l0||l1) || SHA-256(l2||pad) )
        val l = List(3) { leaf(it) }
        val left = pair(l[0], l[1])
        val right = pair(l[2], zero)
        val expected = pair(left, right)
        assertEquals(expected, Merkle.merkleRoot(l, zero))
    }

    @Test
    fun rootOfFiveLeavesPadsToEight() {
        // 5 leaves -> padded to 8. Real: l0..l4. Padding uses the folding pad hash.
        // level0 pad = 0; level1 pad = SHA-256(0||0); etc.
        val l = List(5) { leaf(it) }
        val pad0 = zero
        val pad1 = pair(pad0, pad0) // pad hash one level up

        val n01 = pair(l[0], l[1])
        val n23 = pair(l[2], l[3])
        val n45 = pair(l[4], pad0) // boundary: last real leaf with pad
        // node for the all-padding pair (leaves 6,7) is the implied pad at level1
        val nPad = pad1

        val left = pair(n01, n23)
        val right = pair(n45, nPad)
        val expected = pair(left, right)
        assertEquals(expected, Merkle.merkleRoot(l, zero))
    }

    @Test
    fun padFolding() {
        // merkle_pad(blocks, pieces): fold SHA-256(0) up until the level width
        // reaches `blocks`.
        // pieces==blocks -> no folding -> all-zero.
        assertTrue(Merkle.pad(1, 1).isAllZeros())
        // one fold.
        assertEquals(pair(zero, zero), Merkle.pad(2, 1))
        // two folds.
        val once = pair(zero, zero)
        assertEquals(pair(once, once), Merkle.pad(4, 1))
    }

    // --- MerkleTree container ------------------------------------------------

    @Test
    fun treeGeometry() {
        val t = MerkleTree(numBlocks = 4, blocksPerPiece = 1)
        assertEquals(4, t.numLeafs)
        assertEquals(7, t.size)
        assertEquals(3, t.blockLayerStart)
        assertEquals(4, t.numPieces())
    }

    @Test
    fun treeRootMatchesStandaloneRoot() {
        val leaves = List(4) { leaf(it) }
        val t = MerkleTree.fromLeaves(leaves)
        // the tree's apex equals the standalone root computation.
        assertEquals(Merkle.merkleRoot(leaves), t.root())
        // and equals the hand-rolled nested expression.
        val expected = pair(pair(leaves[0], leaves[1]), pair(leaves[2], leaves[3]))
        assertEquals(expected, t.root())
    }

    @Test
    fun treeRootWithPaddedLeaves() {
        // 3 real blocks -> 4 leaves; the 4th leaf is zero padding.
        val leaves = List(3) { leaf(it) }
        val t = MerkleTree.fromLeaves(leaves)
        val expected = pair(pair(leaves[0], leaves[1]), pair(leaves[2], zero))
        assertEquals(expected, t.root())
        // standalone root with the same padding agrees.
        assertEquals(Merkle.merkleRoot(leaves, zero), t.root())
    }

    @Test
    fun fillTreeSetsInteriorNodes() {
        val leaves = List(4) { leaf(it) }
        val t = MerkleTree.fromLeaves(leaves)
        // node layout for 4 leaves: [0]=root [1,2]=interior [3,4,5,6]=leaves
        assertEquals(leaves[0], t[3])
        assertEquals(leaves[3], t[6])
        assertEquals(pair(leaves[0], leaves[1]), t[1])
        assertEquals(pair(leaves[2], leaves[3]), t[2])
        assertTrue(t.validateSingleLayer())
    }

    @Test
    fun verifyAgainstKnownRoot() {
        val leaves = List(4) { leaf(it) }
        val knownRoot = Merkle.merkleRoot(leaves)

        // a tree that knows the expected root; fill it with the leaves and verify.
        val good = MerkleTree(numBlocks = 4, blocksPerPiece = 1, expectedRoot = knownRoot)
        good.fillFromLeaves(leaves)
        assertTrue(good.verifyAgainstRoot())

        // corrupt one leaf -> verification must fail.
        val badLeaves = leaves.toMutableList()
        badLeaves[2] = leaf(99)
        val bad = MerkleTree(numBlocks = 4, blocksPerPiece = 1, expectedRoot = knownRoot)
        bad.fillFromLeaves(badLeaves)
        assertFalse(bad.verifyAgainstRoot())
    }

    @Test
    fun verifyPieceLayer() {
        // 4 blocks, 2 blocks per piece -> 2 pieces.
        val leaves = List(4) { leaf(it) }
        val knownRoot = Merkle.merkleRoot(leaves)
        // piece hashes are the parents of each block pair.
        val piece0 = pair(leaves[0], leaves[1])
        val piece1 = pair(leaves[2], leaves[3])

        val t = MerkleTree(numBlocks = 4, blocksPerPiece = 2, expectedRoot = knownRoot)
        assertEquals(2, t.numPieces())
        assertTrue(t.verifyPieceLayer(listOf(piece0, piece1)))
        // wrong piece layer rejected.
        assertFalse(t.verifyPieceLayer(listOf(piece0, leaf(123))))
    }

    @Test
    fun fillFromPiecesReconstructsRoot() {
        val leaves = List(4) { leaf(it) }
        val knownRoot = Merkle.merkleRoot(leaves)
        val piece0 = pair(leaves[0], leaves[1])
        val piece1 = pair(leaves[2], leaves[3])

        val t = MerkleTree(numBlocks = 4, blocksPerPiece = 2, expectedRoot = knownRoot)
        t.fillFromPieces(listOf(piece0, piece1))
        assertEquals(knownRoot, t.root())
    }

    @Test
    fun setBlockVerifiesAgainstKnownPieceHash() {
        // 2 blocks per piece, 2 blocks total -> a single piece == the root.
        val leaves = List(2) { leaf(it) }
        val knownRoot = Merkle.merkleRoot(leaves) // == SHA-256(l0||l1)

        val t = MerkleTree(numBlocks = 2, blocksPerPiece = 2, expectedRoot = knownRoot)
        // seed the piece root (== tree root for a single piece) so set_block can verify.
        t[0] = knownRoot

        // first block alone: sibling leaf unknown -> can't verify yet.
        assertEquals(MerkleTree.SetBlockResult.UNKNOWN, t.setBlock(0, leaves[0]))
        // second block completes the pair and matches -> OK, both verified.
        assertEquals(MerkleTree.SetBlockResult.OK, t.setBlock(1, leaves[1]))
        assertTrue(t.isBlockVerified(0))
        assertTrue(t.isBlockVerified(1))
        assertTrue(t.isComplete())
    }

    @Test
    fun setBlockDetectsHashFailure() {
        val leaves = List(2) { leaf(it) }
        val knownRoot = Merkle.merkleRoot(leaves)
        val t = MerkleTree(numBlocks = 2, blocksPerPiece = 2, expectedRoot = knownRoot)
        t[0] = knownRoot

        assertEquals(MerkleTree.SetBlockResult.UNKNOWN, t.setBlock(0, leaves[0]))
        // a wrong second block -> the pair won't hash to the known root.
        assertEquals(MerkleTree.SetBlockResult.HASH_FAILED, t.setBlock(1, leaf(42)))
        assertFalse(t.isBlockVerified(0))
        assertFalse(t.isBlockVerified(1))
        // the known root must be preserved after a failure.
        assertEquals(knownRoot, t[0])
    }
}
