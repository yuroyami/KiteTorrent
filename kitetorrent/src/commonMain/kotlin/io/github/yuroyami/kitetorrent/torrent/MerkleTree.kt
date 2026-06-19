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

    /**
     * True if every block belonging to `pieceIndex` has been verified against the
     * root. A piece is considered verified once all of its (real) constituent
     * block hashes are verified. Mirrors `merkle_tree::blocks_verified` collapsed
     * to a single piece. For single-block pieces this is just [isBlockVerified].
     *
     * @param pieceIndex the piece, in `[0, numPieces())`.
     */
    fun isPieceVerified(pieceIndex: Int): Boolean {
        require(pieceIndex in 0 until numPieces()) {
            "piece index $pieceIndex out of range [0,${numPieces()})"
        }
        val bpp = blocksPerPiece()
        val start = pieceIndex shl blocksPerPieceLog
        val end = minOf(numBlocks, start + bpp)
        for (b in start until end) if (!blockVerified[b]) return false
        return start < end
    }

    /** True if every block hash has been verified. From `is_complete` (full-tree). */
    fun isComplete(): Boolean = blockVerified.allSet()

    /**
     * Loads a file's whole **piece layer** (the per-piece roots) and validates it
     * against the [expectedRoot] before committing. On success the piece layer and
     * all of its ancestors up to the root are filled in (the block layer below the
     * piece layer is left as padding/unknown — only piece-level and above become
     * meaningful, exactly like libtorrent's `mode_t::piece_layer`).
     *
     * Direct analogue of `merkle_tree::load_piece_layer`: it returns `false` (and
     * mutates nothing) when the supplied layer is the wrong size or fails to fold
     * up to the known root.
     *
     * @param pieces exactly [numPieces] piece hashes, in order.
     * @return true if the piece layer validated and was loaded.
     */
    fun loadPieceLayer(pieces: List<Sha256Hash>): Boolean {
        if (!verifyPieceLayer(pieces)) return false
        fillFromPieces(pieces)
        return true
    }

    /**
     * Loads a single **piece-layer row** for this file: the contiguous run of piece
     * roots `pieces`, the first of which is piece number `startPiece`. Each supplied
     * piece root is written into its piece-layer slot. The interior nodes are *not*
     * recomputed (an enclosing proof or a later full [loadPieceLayer] establishes
     * verification); this is the building-block libtorrent uses when filling the
     * tree row-by-row from `get_hashes` responses.
     *
     * @param startPiece index of the first supplied piece (>= 0).
     * @param pieces the run of piece roots, in order. `startPiece + pieces.size`
     *   must not exceed [numPieces].
     */
    fun loadPieceLayerRow(startPiece: Int, pieces: List<Sha256Hash>) {
        require(startPiece >= 0) { "startPiece must be >= 0, was $startPiece" }
        require(startPiece + pieces.size <= numPieces()) {
            "piece row [$startPiece,${startPiece + pieces.size}) exceeds numPieces ${numPieces()}"
        }
        val base = pieceLayerStart()
        for (i in pieces.indices) tree[base + startPiece + i] = pieces[i]
    }

    /**
     * Verifies a run of a single piece's block-leaf hashes against that piece's
     * already-known piece-layer root, without marking anything. Folds the supplied
     * leaves (padded as needed) and compares to the stored piece root.
     *
     * @param pieceIndex the piece whose leaves these are, in `[0, numPieces())`.
     * @param leaves the piece's block hashes, in order. Up to [blocksPerPiece]
     *   entries; a short trailing run is padded with the all-zero block hash.
     * @return true iff the leaves fold to the stored (non-zero) piece root.
     */
    fun verifyPieceLeaves(pieceIndex: Int, leaves: List<Sha256Hash>): Boolean {
        require(pieceIndex in 0 until numPieces()) {
            "piece index $pieceIndex out of range [0,${numPieces()})"
        }
        require(leaves.isNotEmpty()) { "need at least one leaf" }
        val bpp = blocksPerPiece()
        require(leaves.size <= bpp) { "too many leaves: ${leaves.size} > $bpp" }

        // flat index of this piece's root in the piece layer.
        val pieceRootIdx = pieceLayerStart() + pieceIndex
        val pieceRoot = tree[pieceRootIdx]
        if (pieceRoot.isAllZeros()) return false
        if (bpp == 1) return leaves.size == 1 && leaves[0] == pieceRoot
        // block-layer pad is the all-zero hash.
        return Merkle.merkleRoot(leaves, ZERO) == pieceRoot
    }

    /**
     * Verifies a BitTorrent v2 *hashes proof* against this tree and, on success,
     * inserts the proven hashes and their uncle nodes. Pure-computation analogue of
     * `merkle_validate_and_insert_proofs`: the run of `baseHashes` (starting at flat
     * base-layer offset `startIndex`) is folded into a subtree root, then the
     * `proofLayers` uncle hashes are walked upward until they reach a hash already
     * known in this tree (or the [expectedRoot]). If the proof checks out, the
     * subtree root, the uncle hashes, and the recomputed ancestors are written in
     * and the function returns true; otherwise the tree is left unchanged.
     *
     * @param baseHashes contiguous base-layer hashes (non-empty).
     * @param startIndex offset of the first base hash within the base layer.
     * @param proofLayers uncle hashes bottom-up; may be empty if the subtree root
     *   should equal the file root directly.
     * @param padHash pad hash for the base layer (default the all-zero block pad).
     * @return true iff the proof validated against this tree's known hashes/root.
     */
    fun addHashesWithProof(
        baseHashes: List<Sha256Hash>,
        startIndex: Int,
        proofLayers: List<Sha256Hash>,
        padHash: Sha256Hash = ZERO,
    ): Boolean {
        require(baseHashes.isNotEmpty()) { "need at least one base hash" }
        require(startIndex >= 0) { "startIndex must be >= 0, was $startIndex" }

        val pr = Merkle.computeProofRoot(baseHashes, startIndex, proofLayers, padHash)

        // the proof must terminate at a hash we already trust: either the apex
        // root, or (when proofLayers is empty) match an existing subtree node.
        val knownRoot = root()
        if (knownRoot.isAllZeros()) return false
        if (pr.root != knownRoot) return false

        // proof accepted; commit the subtree root and uncle hashes.
        // place the subtree root at its flat index.
        val leafCount = Merkle.numLeafs(baseHashes.size)
        val subtreeLayers = Merkle.numLayers(leafCount)
        // flat index of the subtree root: it lives `subtreeLayers` levels above the
        // base layer, at offset (startIndex >> subtreeLayers) within that layer.
        val baseLayerNo = Merkle.numLayers(numLeafs)
        val subtreeRootLayer = baseLayerNo - subtreeLayers
        val subtreeRootOffset = startIndex shr subtreeLayers
        var cursor = Merkle.toFlatIndex(subtreeRootLayer, subtreeRootOffset)
        if (cursor !in 0 until size) return false
        tree[cursor] = pr.subtreeRoot

        // walk up inserting uncle hashes and recomputed parents.
        for (proof in proofLayers) {
            if (cursor <= 0) break
            val sibling = Merkle.getSibling(cursor)
            if (sibling in 0 until size) tree[sibling] = proof
            val parent = Merkle.getParent(cursor)
            val left = minOf(cursor, sibling)
            if (left + 1 < size) tree[parent] = Merkle.hashPair(tree[left], tree[left + 1])
            cursor = parent
        }
        return true
    }

    /**
     * Extracts the hashes needed to answer a BitTorrent v2 `hash_request` (BEP-52) from this
     * tree — the inverse of [addHashesWithProof] / [Merkle.verifyProof]. Direct port of
     * `merkle_tree::get_hashes` (`src/merkle_tree.cpp`).
     *
     * Given a request for `count` hashes at layer `base` (counted *up from the block layer*:
     * `base == 0` is the block/leaf layer, `base == blocksPerPieceLog` the piece layer)
     * starting at `index` within that layer, plus `proofLayers` of uncle hashes to anchor the
     * run up toward the root, this returns the run of `count` hashes followed by the uncle
     * hashes — exactly the [PeerMessage.Hashes.hashes] layout the requester folds with
     * [Merkle.verifyProof].
     *
     * Returns `null` (libtorrent's empty-vector decline) when we cannot answer with hashes
     * that are all present in our tree: any requested run node is missing (and is not an
     * implicit zero pad), or any required proof node / sibling is missing, or the request
     * indices fall outside the tree. We never fabricate hashes — only nodes actually present
     * in [tree] are returned, so anything returned verifies against our known root.
     *
     * @param base the requested layer, counted up from the block layer (0 = block leaves).
     * @param index offset of the first hash within the `base` layer (>= 0).
     * @param count number of hashes to return from the `base` layer (> 0).
     * @param proofLayers number of uncle-hash layers to append above the run.
     * @return the run hashes followed by the proof hashes, or `null` if we cannot fully answer.
     */
    fun getHashes(base: Int, index: Int, count: Int, proofLayers: Int): List<Sha256Hash>? {
        if (base < 0 || index < 0 || count <= 0 || proofLayers < 0) return null

        // the layer `base` levels above the block layer, in flat-tree layer numbering
        // (root == layer 0). num_leafs() padded leaf count gives the block-layer depth.
        val baseLayerIdx = Merkle.numLayers(numLeafs) - base
        if (baseLayerIdx < 0) return null
        val layerStartIdx = Merkle.toFlatIndex(baseLayerIdx, index)
        // the whole requested run must lie within the tree.
        if (layerStartIdx + count > size) return null

        val ret = ArrayList<Sha256Hash>(count)
        if (base == 0) {
            // block-layer request: real leaves are stored; beyond m_num_blocks the leaves are
            // implicit zero padding (which we emit as the all-zero hash, matching libtorrent's
            // block_layer fast path / resize-with-zero).
            val blocksEnd = minOf(index + count, numBlocks)
            var i = index
            while (i < blocksEnd) {
                val flat = layerStartIdx + (i - index)
                if (tree[flat].isAllZeros()) return null
                ret.add(tree[flat])
                i++
            }
            // pad the remainder with the all-zero block hash.
            while (ret.size < count) ret.add(ZERO)
        } else {
            // interior/piece layer: every requested node must be present (non-zero). Pad nodes
            // above the block layer are non-zero (folded zero pads), so they are real entries.
            var i = layerStartIdx
            while (i < layerStartIdx + count) {
                if (!hasNode(i)) return null
                ret.add(tree[i])
                i++
            }
        }

        // how many layers up the tree the base-layer run itself spans (the run folds into a
        // subtree root that many levels above the base); the base layer doesn't count.
        val baseTreeLayers = Merkle.numLayers(Merkle.numLeafs(count)) - 1

        var proofIdx = layerStartIdx
        for (i in 0 until proofLayers) {
            if (proofIdx <= 0) return null // requester set proof_layers too high
            proofIdx = Merkle.getParent(proofIdx)
            if (i >= baseTreeLayers) {
                val sibling = Merkle.getSibling(proofIdx)
                if (sibling !in 0 until size) return null
                if (!hasNode(proofIdx) || !hasNode(sibling)) return null
                ret.add(tree[sibling])
            }
        }

        return ret
    }

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
