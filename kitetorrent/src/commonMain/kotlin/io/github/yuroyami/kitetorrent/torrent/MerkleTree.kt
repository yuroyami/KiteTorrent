package io.github.yuroyami.kitetorrent.torrent

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha256Hash

/**
 * In-memory BitTorrent v2 merkle tree for one file — a focused pure-computation
 * port of libtorrent's `aux::merkle_tree` (`include/libtorrent/aux_/merkle_tree.hpp`
 * + `src/merkle_tree.cpp`).
 *
 * Each file in a v2 torrent has its own merkle tree. The leaves are the file's
 * 16 KiB **block** hashes; somewhere above them sits the **piece** layer (whole
 * pieces); the apex is the file's **root** hash, which is published in the
 * `.torrent`. The number of blocks is rounded up to a power of two by padding the
 * leaf layer with the all-zero block hash.
 *
 * libtorrent's production class carries several space-optimised storage modes
 * (`empty_tree`, `piece_layer`, `block_layer`, `full_tree`) plus resume-data and
 * on-disk plumbing. This port keeps the conceptual core that is pure computation:
 *
 *  - a flat `2*numLeafs-1` node array (the "full tree" representation),
 *  - filling interior nodes from a complete leaf set ([fillFromLeaves]),
 *  - filling interior nodes from the piece layer ([fillFromPieces]),
 *  - reading the [root] and arbitrary nodes,
 *  - verifying a candidate root / piece layer against a known root,
 *  - tracking which block hashes have been *verified* against the root.
 *
 * The invariant libtorrent maintains — interior nodes are either correct or
 * all-zero, never wrong — is preserved here for the verification helpers.
 *
 * Index arithmetic and the standalone root computation live in [Merkle].
 *
 * @param numBlocks number of real (non-padding) block hashes in the file. Must be
 *   `>= 1`.
 * @param blocksPerPiece blocks contained in one piece — a power of two. This
 *   fixes where the piece layer sits relative to the leaves. For the common case
 *   where a piece *is* a block, pass `1`.
 * @param root the file's known root hash (the apex). Verification compares against
 *   this. May be null when the root is not yet known (e.g. building a tree to then
 *   read its [root]).
 */
class MerkleTree(
    val numBlocks: Int,
    blocksPerPiece: Int = 1,
    private val expectedRoot: Sha256Hash? = null,
) {
    init {
        require(numBlocks >= 1) { "numBlocks must be >= 1, was $numBlocks" }
        require(blocksPerPiece >= 1) { "blocksPerPiece must be >= 1, was $blocksPerPiece" }
        require(blocksPerPiece and (blocksPerPiece - 1) == 0) {
            "blocksPerPiece must be a power of two, was $blocksPerPiece"
        }
    }

    /**
     * `blocks_per_piece_log` in libtorrent — `log2(blocksPerPiece)`. Also the
     * number of tree levels between the block layer and the piece layer (0 when a
     * piece is a single block). Stored as a small int.
     */
    val blocksPerPieceLog: Int = run {
        var v = blocksPerPiece
        var log = 0
        while (v > 1) { v = v shr 1; log++ }
        log
    }

    /** Blocks per piece, reconstructed from [blocksPerPieceLog]. */
    fun blocksPerPiece(): Int = 1 shl blocksPerPieceLog

    /** Padded leaf count — `numBlocks` rounded up to a power of two. */
    val numLeafs: Int = Merkle.numLeafs(numBlocks)

    /** Total number of nodes in the (padded) tree. */
    val size: Int = Merkle.numNodes(numLeafs)

    /** Flat index of the first leaf (block) node. */
    val blockLayerStart: Int = Merkle.firstLeaf(numLeafs)

    /** Number of real pieces (rounding up the partial trailing piece). */
    fun numPieces(): Int = (numBlocks + blocksPerPiece() - 1) shr blocksPerPieceLog

    /** Flat index of the first node in the piece layer. */
    fun pieceLayerStart(): Int = Merkle.firstLeaf(Merkle.numLeafs(numPieces()))

    /**
     * The flat node array. Index 0 is the root, [blockLayerStart] is the first
     * block hash. All-zero entries are "unknown" — exactly libtorrent's
     * convention. Mutable so the tree can be filled incrementally.
     */
    private val tree: Array<Sha256Hash> = Array(size) { ZERO }

    /**
     * One bit per block, set when that block hash has been *verified* against the
     * root (not merely present). Mirrors `m_block_verified`.
     */
    private val blockVerified = Bitfield(numBlocks)

    // --- node access ---------------------------------------------------------

    /** The hash at flat node `idx` (all-zero if unknown). */
    operator fun get(idx: Int): Sha256Hash {
        require(idx in 0 until size) { "node index $idx out of range [0,$size)" }
        return tree[idx]
    }

    /** Sets the hash at flat node `idx` directly (no validation). */
    operator fun set(idx: Int, h: Sha256Hash) {
        require(idx in 0 until size) { "node index $idx out of range [0,$size)" }
        require(h.size == Merkle.HASH_SIZE) { "merkle node must be 32 bytes, was ${h.size}" }
        tree[idx] = h
    }

    /** True if node `idx` holds a (non-zero) hash. From `has_node` (full-tree mode). */
    fun hasNode(idx: Int): Boolean {
        require(idx in 0 until size) { "node index $idx out of range [0,$size)" }
        return !tree[idx].isAllZeros()
    }

    /**
     * The root hash. If the tree has been filled this is `tree[0]`; otherwise the
     * [expectedRoot] passed to the constructor (or all-zero if neither is set).
     * Mirrors `merkle_tree::root()`.
     */
    fun root(): Sha256Hash {
        val computed = tree[0]
        if (!computed.isAllZeros()) return computed
        return expectedRoot ?: ZERO
    }

    /** The known/expected root supplied at construction, if any. */
    fun expectedRoot(): Sha256Hash? = expectedRoot

    // --- filling -------------------------------------------------------------

    /**
     * Places `leaves` at the block layer (padding the rest with the all-zero
     * block hash) and computes every interior node up to the root. The resulting
     * `tree[0]` is the file root. Mirrors loading the block layer and calling
     * `merkle_fill_tree`.
     *
     * @param leaves exactly [numBlocks] block hashes, in order.
     */
    fun fillFromLeaves(leaves: List<Sha256Hash>) {
        require(leaves.size == numBlocks) {
            "expected $numBlocks block hashes, got ${leaves.size}"
        }
        // lay the real leaves into the leaf layer; padding stays all-zero.
        for (i in leaves.indices) tree[blockLayerStart + i] = leaves[i]
        for (i in numBlocks until numLeafs) tree[blockLayerStart + i] = ZERO
        fillTree(numLeafs, blockLayerStart)
    }

    /**
     * Places the `pieces` hashes at the piece layer, pads the piece layer with the
     * proper pad hash ([Merkle.pad]), computes the nodes from the piece layer up to
     * the root, and computes each piece's block-layer descendants as padding so the
     * tree is internally consistent for the levels at and above the piece layer.
     *
     * This mirrors how libtorrent reconstructs a tree from just the piece layer
     * (`load_piece_layer` / `build_vector` for `piece_layer` mode). It does **not**
     * invent real block hashes — only the piece layer and above are meaningful.
     *
     * @param pieces exactly [numPieces] piece hashes, in order.
     */
    fun fillFromPieces(pieces: List<Sha256Hash>) {
        val np = numPieces()
        require(pieces.size == np) { "expected $np piece hashes, got ${pieces.size}" }

        val pieceLayerSize = Merkle.numLeafs(np)
        val start = Merkle.firstLeaf(pieceLayerSize)
        val padHash = if (blocksPerPieceLog > 0) Merkle.pad(blocksPerPiece(), 1) else ZERO

        for (i in pieces.indices) tree[start + i] = pieces[i]
        for (i in np until pieceLayerSize) tree[start + i] = padHash
        // compute the piece layer's ancestors up to the root.
        fillTree(pieceLayerSize, start)
    }

    /**
     * Fills interior nodes from a complete level. `levelStart` is the flat index of
     * the first node in that level and `levelSize` its width (a power of two). Each
     * adjacent pair is hashed into its parent, repeating up to the root. Direct port
     * of `merkle_fill_tree`.
     */
    fun fillTree(levelSize: Int, levelStart: Int) {
        require(levelSize >= 1) { "levelSize must be >= 1, was $levelSize" }
        require(levelStart >= 0) { "levelStart must be >= 0, was $levelStart" }
        var start = levelStart
        var sz = levelSize
        while (sz > 1) {
            var parent = Merkle.getParent(start)
            var i = start
            while (i < start + sz) {
                tree[parent] = Merkle.hashPair(tree[i], tree[i + 1])
                i += 2
                parent++
            }
            start = Merkle.getParent(start)
            sz /= 2
        }
    }

    // --- verification --------------------------------------------------------

    /**
     * True if every adjacent leaf pair in this tree hashes to its stored parent —
     * i.e. the layer immediately above the leaves is internally consistent. Direct
     * port of `merkle_validate_single_layer`. (A one-node tree is trivially valid.)
     */
    fun validateSingleLayer(): Boolean {
        if (size == 1) return true
        var idx = blockLayerStart
        while (idx < size) {
            if (!Merkle.validateNode(tree[idx], tree[idx + 1], tree[Merkle.getParent(idx)])) {
                return false
            }
            idx += 2
        }
        return true
    }

    /**
     * Recomputes the root from the currently-set block layer and reports whether it
     * matches the [expectedRoot]. Requires all [numBlocks] real leaves to be present.
     *
     * This is the headline "does my data match the torrent?" check.
     */
    fun verifyAgainstRoot(): Boolean {
        val target = expectedRoot ?: return false
        val leaves = ArrayList<Sha256Hash>(numLeafs)
        for (i in 0 until numLeafs) leaves.add(tree[blockLayerStart + i])
        // block-layer pad is the all-zero hash.
        return Merkle.rootScratch(leaves, numLeafs, ZERO) == target
    }

    /**
     * Validates a candidate piece layer against the [expectedRoot] without mutating
     * this tree. Mirrors the check inside `load_piece_layer`: pad the piece layer to
     * a power of two with [Merkle.pad], fold to a root, and compare.
     *
     * @return true if `pieces` produce the expected root.
     */
    fun verifyPieceLayer(pieces: List<Sha256Hash>): Boolean {
        val target = expectedRoot ?: return false
        if (pieces.size != numPieces()) return false
        if (numBlocks == 1) return pieces.size == 1 && pieces[0] == target
        val padHash = Merkle.pad(blocksPerPiece(), 1)
        return Merkle.merkleRoot(pieces, padHash) == target
    }

    /**
     * Sets a single block hash and, if the surrounding subtree is now fully known,
     * verifies it against the already-known ancestor hashes — marking the affected
     * blocks as verified on success. A focused analogue of `merkle_tree::set_block`
     * for the full-tree representation.
     *
     * Specifically: it writes the block hash, finds the smallest enclosing piece (or
     * the whole tree, for single-block pieces), and if every leaf and the piece root
     * are known it folds the leaves and compares against the stored piece root.
     *
     * @return one of [SetBlockResult].
     */
    fun setBlock(blockIndex: Int, h: Sha256Hash): SetBlockResult {
        require(blockIndex in 0 until numBlocks) {
            "block index $blockIndex out of range [0,$numBlocks)"
        }
        val blockTreeIndex = blockLayerStart + blockIndex

        // already verified? then this must match.
        if (blockVerified[blockIndex]) {
            return if (tree[blockTreeIndex] == h) SetBlockResult.OK else SetBlockResult.BLOCK_HASH_FAILED
        }

        tree[blockTreeIndex] = h

        // the enclosing subtree whose root we can check against: the piece this
        // block belongs to. piece root index, and that piece's leaf range.
        val piece = blockIndex shr blocksPerPieceLog
        val leafsStart = piece shl blocksPerPieceLog
        val leafsSize = blocksPerPiece()
        // flat index of the piece root: walk up `blocksPerPieceLog` levels.
        var rootIndex = blockTreeIndex
        repeat(blocksPerPieceLog) { rootIndex = Merkle.getParent(rootIndex) }

        // need the piece root to verify against.
        if (tree[rootIndex].isAllZeros()) return SetBlockResult.UNKNOWN

        // need every leaf in the piece (or zero-padding) to be present.
        for (i in 0 until leafsSize) {
            val li = blockLayerStart + leafsStart + i
            if (li >= size) break
            // beyond the real blocks the leaf is implied-zero padding, that's fine.
            if (leafsStart + i < numBlocks && tree[li].isAllZeros()) return SetBlockResult.UNKNOWN
        }

        val savedRoot = tree[rootIndex]
        fillTree(leafsSize, blockLayerStart + leafsStart)
        if (savedRoot != tree[rootIndex]) {
            // hash failure: restore the known root, leave the (bad) leaves as-is.
            tree[rootIndex] = savedRoot
            return SetBlockResult.HASH_FAILED
        }

        val leafsEnd = minOf(numBlocks, leafsStart + leafsSize)
        for (i in leafsStart until leafsEnd) blockVerified.setBit(i)
        return SetBlockResult.OK
    }

    /** True if block `blockIndex`'s hash has been verified against the root. */
    fun isBlockVerified(blockIndex: Int): Boolean {
        require(blockIndex in 0 until numBlocks)
        return blockVerified[blockIndex]
    }

    /** True if every block hash has been verified. From `is_complete` (full-tree). */
    fun isComplete(): Boolean = blockVerified.allSet()

    /** Result of [setBlock], mirroring `merkle_tree::set_block_result`. */
    enum class SetBlockResult {
        /** The block hash was accepted (and, where possible, verified). */
        OK,

        /** The enclosing subtree root is not yet known — can't verify yet. */
        UNKNOWN,

        /** The subtree failed to hash to its known root (some block is wrong). */
        HASH_FAILED,

        /** This specific block hash disagreed with an already-verified value. */
        BLOCK_HASH_FAILED,
    }

    companion object {
        private val ZERO: Sha256Hash = Digest32.zeros(Merkle.HASH_SIZE)

        /**
         * Builds a fully-computed tree from a complete leaf set and returns it. The
         * resulting tree's [root] is the file root; pass `blocksPerPiece` to position
         * the piece layer.
         */
        fun fromLeaves(leaves: List<Sha256Hash>, blocksPerPiece: Int = 1): MerkleTree {
            val t = MerkleTree(leaves.size, blocksPerPiece, null)
            t.fillFromLeaves(leaves)
            return t
        }
    }
}
