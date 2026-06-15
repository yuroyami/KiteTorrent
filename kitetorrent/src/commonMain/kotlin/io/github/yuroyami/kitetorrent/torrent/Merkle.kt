package io.github.yuroyami.kitetorrent.torrent

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.crypto.Hasher256

/**
 * Pure-Kotlin port of libtorrent's merkle-tree free functions
 * (`include/libtorrent/aux_/merkle.hpp` + `src/merkle.cpp`).
 *
 * BitTorrent v2 (BEP 52) represents every file as a binary merkle tree of
 * SHA-256 hashes. The leaves are 16 KiB block hashes; interior nodes are
 * `SHA-256(left || right)`; the root is the file's piece-tree root that ends up
 * in the `.torrent`. When the number of leaves is not a power of two the tree is
 * padded on the right with a fixed *pad hash* (`SHA-256(0)`, then
 * `SHA-256(pad||pad)` one level up, etc.).
 *
 * ## Node indexing
 * libtorrent stores the tree as a flat array in breadth-first ("heap") order:
 * ```
 *             0                 <- layer 0 (root)
 *      1            2           <- layer 1
 *   3     4      5     6        <- layer 2
 *  7 8   9 10  11 12  13 14     <- layer 3 (leaves, for 8 leaves)
 * ```
 * So node `i`'s children are `2i+1` and `2i+2`, and its parent is `(i-1)/2`.
 * A tree with `L` leaves (L a power of two) has `2L-1` nodes and its first leaf
 * is at index `L-1`.
 *
 * This object carries only the *pure computation* half of libtorrent's merkle
 * code — index arithmetic, root computation, and node validation. Anything tied
 * to on-disk storage layout lives elsewhere; see [MerkleTree] for the in-memory
 * tree container.
 */
object Merkle {

    /** Size of a SHA-256 merkle node, in bytes. */
    const val HASH_SIZE: Int = Digest32.SHA256_SIZE

    /**
     * Index of the first node in `layer`, counting the root as layer 0.
     * `(1 << layer) - 1`. From `merkle_layer_start`.
     */
    fun layerStart(layer: Int): Int {
        require(layer >= 0) { "layer must be >= 0, was $layer" }
        require(layer < 31) { "layer $layer out of int range" }
        return (1 shl layer) - 1
    }

    /**
     * Flat node index given a `layer` and an `offset` from the start of that
     * layer. From `merkle_to_flat_index`.
     */
    fun toFlatIndex(layer: Int, offset: Int): Int {
        require(layer >= 0) { "layer must be >= 0, was $layer" }
        require(offset >= 0) { "offset must be >= 0, was $offset" }
        require(layer < 31) { "layer $layer out of int range" }
        return layerStart(layer) + offset
    }

    /**
     * Parent node index of `treeNode`. Node 0 (the root) has no parent.
     * `(treeNode - 1) / 2`. From `merkle_get_parent`.
     */
    fun getParent(treeNode: Int): Int {
        require(treeNode > 0) { "node 0 has no parent" }
        return (treeNode - 1) / 2
    }

    /**
     * Sibling node index of `treeNode`. Even nodes sit to the right of their
     * sibling (so the sibling is to the left, `-1`); odd nodes sit to the left
     * (sibling to the right, `+1`). From `merkle_get_sibling`.
     */
    fun getSibling(treeNode: Int): Int {
        require(treeNode > 0) { "node 0 has no sibling" }
        return treeNode + (if (treeNode and 1 != 0) 1 else -1)
    }

    /**
     * Index of `treeNode`'s first (left) child, `2*treeNode + 1`.
     * From `merkle_get_first_child`.
     */
    fun getFirstChild(treeNode: Int): Int = treeNode * 2 + 1

    /**
     * Index of the first descendant of `treeNode` exactly `depth` layers below
     * it, `((treeNode + 1) << depth) - 1`. From the two-argument
     * `merkle_get_first_child`.
     */
    fun getFirstChild(treeNode: Int, depth: Int): Int = ((treeNode + 1) shl depth) - 1

    /**
     * Number of nodes in a tree with `leafs` leaves (which must be a power of
     * two): `2*leafs - 1`. From `merkle_num_nodes`.
     */
    fun numNodes(leafs: Int): Int {
        require(leafs > 0) { "leafs must be > 0, was $leafs" }
        require(leafs and (leafs - 1) == 0) { "leafs must be a power of two, was $leafs" }
        // (leafs << 1) - 1, expressed so it never needs the extra top bit.
        return ((leafs - 1) shl 1) + 1
    }

    /**
     * Index of the first leaf in a tree with `numLeafs` leaves (a power of two):
     * `numLeafs - 1`. From `merkle_first_leaf`.
     */
    fun firstLeaf(numLeafs: Int): Int {
        require(numLeafs > 0) { "numLeafs must be > 0, was $numLeafs" }
        require((numLeafs - 1) and numLeafs == 0) { "numLeafs must be a power of two, was $numLeafs" }
        return numLeafs - 1
    }

    /**
     * Given a number of `blocks`, the number of leaves the tree needs — rounded
     * up to the next power of two. From `merkle_num_leafs`.
     */
    fun numLeafs(blocks: Int): Int {
        require(blocks > 0) { "blocks must be > 0, was $blocks" }
        var ret = 1
        while (blocks > ret) ret = ret shl 1
        return ret
    }

    /**
     * Height of a merkle tree with `leaves` leaves (a power of two), *not*
     * counting the root: a single root hash is 0 layers, 2 leaves is 1, etc.
     * From `merkle_num_layers`.
     */
    fun numLayers(leaves: Int): Int {
        require(leaves and (leaves - 1) == 0) { "leaves must be a power of two, was $leaves" }
        var layers = 0
        var l = leaves
        while (l > 1) {
            layers++
            l = l shr 1
        }
        return layers
    }

    /**
     * Layer that flat node `idx` falls into, with the root as layer 0.
     * From `merkle_get_layer`.
     */
    fun getLayer(idx: Int): Int {
        require(idx >= 0) { "idx must be >= 0, was $idx" }
        var layer = 1
        while (idx > (1 shl layer) - 2) layer++
        return layer - 1
    }

    /**
     * Offset of flat node `idx` within its own layer.
     * From `merkle_get_layer_offset`.
     */
    fun getLayerOffset(idx: Int): Int = idx - ((1 shl getLayer(idx)) - 1)

    /**
     * The pad hash for a tree level that holds `pieces` real nodes, given the
     * full leaf level holds `blocks` nodes. Starts from `SHA-256(0)` (the all-zero
     * leaf pad) and folds it up — `pad = SHA-256(pad || pad)` — once per level
     * until the level width reaches `blocks`. From `merkle_pad`.
     */
    fun pad(blocks: Int, pieces: Int): Sha256Hash {
        require(blocks >= pieces) { "blocks ($blocks) must be >= pieces ($pieces)" }
        var ret = Digest32.zeros(HASH_SIZE)
        var p = pieces
        while (p < blocks) {
            ret = Hasher256().update(ret.toByteArray()).update(ret.toByteArray()).final()
            p *= 2
        }
        return ret
    }

    /**
     * Computes `SHA-256(left || right)` — one interior merkle node.
     */
    fun hashPair(left: Sha256Hash, right: Sha256Hash): Sha256Hash =
        Hasher256().update(left.toByteArray()).update(right.toByteArray()).final()

    /**
     * True if `parent == SHA-256(left || right)`. From `merkle_validate_node`.
     */
    fun validateNode(left: Sha256Hash, right: Sha256Hash, parent: Sha256Hash): Boolean =
        hashPair(left, right) == parent

    /**
     * Computes the merkle root from a list of `leaves`, padding the right side
     * with `padHash` when the leaf count is not a power of two. This is the
     * top-level entry point that mirrors `merkle_root` (it rounds the leaf count
     * up to a power of two and delegates to [rootScratch]).
     *
     * @param leaves the real leaf hashes, in order. Must be non-empty.
     * @param padHash the hash for the right-hand padding (default `SHA-256(0)`,
     *   i.e. an all-zero 32-byte node). For the piece layer libtorrent uses
     *   [pad] to derive this; at the block layer it is the all-zero hash.
     */
    fun merkleRoot(leaves: List<Sha256Hash>, padHash: Sha256Hash = Digest32.zeros(HASH_SIZE)): Sha256Hash {
        require(leaves.isNotEmpty()) { "need at least one leaf to compute a root" }
        val numLeafs = numLeafs(leaves.size)
        return rootScratch(leaves, numLeafs, padHash)
    }

    /**
     * The core root computation, mirroring `merkle_root_scratch`.
     *
     * Folds the leaf list one layer at a time. At each step it hashes adjacent
     * pairs; if the current level has an odd count it forms one boundary node
     * from the last real hash and the running `pad`, after which everything to
     * the right is implied padding and never materialised. The running `pad` is
     * itself folded — `pad = SHA-256(pad || pad)` — so it always represents the
     * pad node for the level above.
     *
     * @param leaves the real leaf hashes for the bottom level (non-empty).
     * @param numLeafs the padded leaf count (a power of two, `>= leaves.size`).
     * @param pad the pad hash for the bottom level.
     */
    fun rootScratch(leaves: List<Sha256Hash>, numLeafs: Int, pad: Sha256Hash): Sha256Hash {
        require(numLeafs > 0) { "numLeafs must be > 0, was $numLeafs" }
        require((numLeafs - 1) and numLeafs == 0) { "numLeafs must be a power of two, was $numLeafs" }
        require(leaves.isNotEmpty()) { "need at least one leaf" }

        if (numLeafs == 1) return leaves[0]

        // Working level; starts as the real leaves and shrinks by half each pass.
        var level: List<Sha256Hash> = leaves
        var runningPad = pad
        var remainingLeafs = numLeafs

        while (remainingLeafs > 1) {
            val half = level.size / 2
            // scratch_space holds up to (level.size + 1) / 2 parents.
            val next = ArrayList<Sha256Hash>(half + 1)
            var i = 0
            while (i < half) {
                next.add(hashPair(level[i * 2], level[i * 2 + 1]))
                i++
            }
            if (level.size and 1 == 1) {
                // odd count: the final real hash pairs with the pad hash.
                next.add(hashPair(level[i * 2], runningPad))
            }
            // fold the pad up one level (the implied padding to the right).
            runningPad = hashPair(runningPad, runningPad)
            level = next
            remainingLeafs /= 2
        }

        return level[0]
    }
}
