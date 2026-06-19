package io.github.yuroyami.kitetorrent.torrent

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.protocol.PeerMessage

/**
 * Decides which BitTorrent v2 merkle-hash ranges to request from peers, and validates the
 * `hashes` responses into per-file [MerkleTree]s — a practical-level port of libtorrent's
 * `hash_picker` (`src/hash_picker.cpp`).
 *
 * ## What this is for
 * A v2 download started from a magnet (or any time the `.torrent`'s `piece layers` are
 * absent) does not yet know each file's per-piece roots. Before a peer's piece data can be
 * verified, the client must obtain the **piece layer** of every multi-piece file. Peers
 * supply it through the `hash_request` / `hashes` / `hash_reject` exchange (BEP-52):
 *
 *  - We send a [PeerMessage.HashRequest] naming the file (by its `pieces root`), the layer
 *    we want (`base`), an `index`/`length` window into that layer, and how many `proofLayers`
 *    (uncle hashes) we need to anchor the response to the file root we already trust.
 *  - The peer replies with [PeerMessage.Hashes]: `length` hashes at the requested layer,
 *    followed by the uncle hashes. We fold them with [Merkle.verifyProof] against the file
 *    root; on success they go into that file's [MerkleTree].
 *  - Or the peer replies with [PeerMessage.HashReject], freeing the slot to retry elsewhere.
 *
 * ## Scope vs. libtorrent
 * libtorrent's production picker carries 512-piece request buckets, a future per-block
 * priority-request path, and `min_request_interval` rate limiting. This port keeps the
 * load-bearing core that matters for correctness:
 *
 *  - per-file piece-layer requests anchored to the known file root,
 *  - proof verification via the Tier-A [Merkle.verifyProof],
 *  - insertion of validated piece roots via [MerkleTree.loadPieceLayerRow],
 *  - outstanding-request tracking so a piece is not requested twice concurrently,
 *  - reject handling that re-opens the slot.
 *
 * It requests a file's piece layer in one window (libtorrent's common case for files of up
 * to 512 pieces); larger files are still handled correctly, just in a single larger request.
 *
 * The picker needs each file's own [MerkleTree] (constructed with the file's known root). It
 * does not own them — the session does — so they are passed in keyed by file index.
 *
 * @param info the parsed torrent (provides the file→piece mapping and known file roots).
 * @param trees per-file merkle trees, keyed by file index. A file's tree must have been
 *   constructed with that file's `pieces root` as its expected root for verification to work.
 *   Pad files and single-piece files may be omitted.
 */
class HashPicker(
    private val info: TorrentInfo,
    private val trees: Map<Int, MerkleTree>,
) {
    private val storage: FileStorage get() = info.storage

    /** Per-file outstanding piece-layer request flag (true while a request is in flight). */
    private val outstanding = HashSet<Int>()

    private val zero: Sha256Hash = Digest32.zeros(Merkle.HASH_SIZE)

    // --- queries -------------------------------------------------------------

    /**
     * True if we still need v2 hashes to be able to verify [piece] — i.e. its owning file is
     * a real (non-pad) multi-piece file whose piece-layer node for this piece is not yet
     * present in its [MerkleTree]. Single-piece files need no request (the file root *is* the
     * piece hash), and files whose `piece layers` were already loaded from the `.torrent`
     * return false. Mirrors the negation of `hash_picker::have_hash`.
     */
    fun needHashes(piece: Int): Boolean {
        val fidx = storage.fileForPiece(piece)
        if (fidx < 0) return false
        if (storage.isPadFile(fidx)) return false
        if (storage.fileNumPieces(fidx) <= 1) return false // file root is the piece hash
        val tree = trees[fidx] ?: return false
        val pieceInFile = piece - storage.firstPieceOf(fidx)
        if (pieceInFile < 0 || pieceInFile >= tree.numPieces()) return false
        val node = tree.pieceLayerStart() + pieceInFile
        return !tree.hasNode(node)
    }

    /** True once every (real) block of [piece] has been verified against its file's root. */
    fun pieceVerified(piece: Int): Boolean {
        val fidx = storage.fileForPiece(piece)
        if (fidx < 0) return false
        if (storage.isPadFile(fidx)) return true
        val tree = trees[fidx] ?: return false
        val pieceInFile = piece - storage.firstPieceOf(fidx)
        if (pieceInFile < 0 || pieceInFile >= tree.numPieces()) return false
        return tree.isPieceVerified(pieceInFile)
    }

    // --- request building ----------------------------------------------------

    /**
     * Builds the [PeerMessage.HashRequest] needed to obtain the piece-layer hashes for the
     * file that owns [piece], or null when no request is warranted ([needHashes] is false, or
     * a request for this file is already outstanding). On success the file is marked as having
     * an outstanding request until [onHashes] or [onHashReject] is called for it.
     *
     * The request covers the file's entire piece layer (`index = 0`, `length = numPieces of
     * the file rounded up to a power of two`), anchored to the file root with the right number
     * of `proofLayers`. This is libtorrent's standard piece-layer request shape.
     *
     * Wire fields (mirroring libtorrent's `hash_request`):
     *  - `piecesRoot`: the file's 32-byte v2 root (identifies the file on the wire),
     *  - `baseLayer`: number of tree layers between the block leaves and the piece layer
     *    (`log2(blocksPerPiece)`), i.e. the piece layer expressed as a `base`,
     *  - `index`: offset within the piece layer (0 — whole layer),
     *  - `length`: count of piece-layer hashes requested,
     *  - `proofLayers`: uncle hashes needed to fold up to the root.
     */
    fun buildHashRequest(piece: Int): PeerMessage.HashRequest? {
        if (!needHashes(piece)) return null
        val fidx = storage.fileForPiece(piece)
        if (fidx < 0 || fidx in outstanding) return null
        val tree = trees[fidx] ?: return null
        val root = info.files[fidx].piecesRoot ?: return null

        val numPieces = tree.numPieces()
        if (numPieces <= 1) return null

        val base = blocksPerPieceLog(tree)
        // length: pad the real piece count up to a power of two — the count of nodes the
        // peer should send at the piece layer (libtorrent uses merkle_num_leafs here).
        val length = Merkle.numLeafs(numPieces)
        // proof layers: number of tree levels from the piece layer up to (but excluding) the
        // root — these uncle hashes anchor the run to the file root. For a request spanning
        // the *entire* piece layer the subtree root already equals the file root, so no uncle
        // hashes are strictly required; we still advertise the full path so a peer that only
        // has a subset can answer. layers(pieceLayer) = numLayers(numLeafs(numPieces)).
        val proofLayers = Merkle.numLayers(Merkle.numLeafs(numPieces))

        outstanding.add(fidx)
        return PeerMessage.HashRequest(
            piecesRoot = root.toByteArray(),
            baseLayer = base,
            index = 0,
            length = length,
            proofLayers = proofLayers,
        )
    }

    // --- response handling ---------------------------------------------------

    /**
     * Validates and applies a received [PeerMessage.Hashes]. The message is matched to a file
     * by its `piecesRoot`; its leading `length` hashes are the requested piece-layer run and
     * any trailing hashes are uncle/proof hashes. The run + proof are folded with
     * [Merkle.verifyProof] against the file's known root; on success the validated piece roots
     * are written into that file's [MerkleTree] via [MerkleTree.loadPieceLayerRow] and the
     * outstanding-request slot is cleared.
     *
     * @return true if the hashes validated and were inserted; false (and no mutation) on any
     *   mismatch — wrong file, malformed hash sizes, or a failed proof.
     */
    fun onHashes(msg: PeerMessage.Hashes): Boolean {
        val fidx = fileForRoot(msg.piecesRoot) ?: return false
        val tree = trees[fidx] ?: return false
        val fileRoot = info.files[fidx].piecesRoot ?: return false

        // sanity: every hash must be a 32-byte node.
        if (msg.hashes.any { it.size != Merkle.HASH_SIZE }) return false

        val numPieces = tree.numPieces()
        // the run is the requested layer's hashes; the remainder are uncle hashes.
        val runLen = msg.length
        if (runLen <= 0 || runLen > msg.hashes.size) return false
        val baseHashes = msg.hashes.subList(0, runLen).map { Digest32.sha256(it) }
        val proofLayers = msg.hashes.subList(runLen, msg.hashes.size).map { Digest32.sha256(it) }

        // only the piece layer (base == log2(blocksPerPiece)) and the block layer (base == 0)
        // are supported, matching libtorrent. Here we apply piece-layer responses.
        val pieceBase = blocksPerPieceLog(tree)
        if (msg.baseLayer != pieceBase && msg.baseLayer != 0) return false

        // pad hash for the requested layer: at the piece layer the pad folds blocksPerPiece
        // all-zero blocks; at the block layer it is the all-zero hash.
        val padHash = if (msg.baseLayer == pieceBase && pieceBase > 0) {
            Merkle.pad(tree.blocksPerPiece(), 1)
        } else {
            zero
        }

        // verify the run + proof fold to the file root.
        if (!Merkle.verifyProof(fileRoot, baseHashes, msg.index, proofLayers, padHash)) {
            // failed proof: leave the outstanding slot set so a different peer can be tried by
            // a higher layer; but mirror libtorrent and surface failure to the caller.
            return false
        }

        if (msg.baseLayer == pieceBase) {
            // piece-layer response: insert the real piece roots (clamp to the file's actual
            // piece count; trailing entries are padding we don't store).
            val realCount = minOf(runLen, numPieces - msg.index)
            if (realCount <= 0 || msg.index < 0 || msg.index + realCount > numPieces) {
                outstanding.remove(fidx)
                return false
            }
            tree.loadPieceLayerRow(msg.index, baseHashes.subList(0, realCount))
        }
        outstanding.remove(fidx)
        return true
    }

    /**
     * Handles a [PeerMessage.HashReject]: the peer declined our request for that file. Clears
     * the outstanding slot so the file can be requested again (from this or another peer).
     * Mirrors `hash_picker::hashes_rejected` (the bucket's request is re-opened). No-op if the
     * file is unknown or had no outstanding request.
     */
    fun onHashReject(msg: PeerMessage.HashReject) {
        val fidx = fileForRoot(msg.piecesRoot) ?: return
        outstanding.remove(fidx)
    }

    /** Whether a piece-layer request for the file owning [piece] is currently in flight. */
    fun hasOutstanding(piece: Int): Boolean {
        val fidx = storage.fileForPiece(piece)
        return fidx >= 0 && fidx in outstanding
    }

    // --- internals -----------------------------------------------------------

    /** Number of tree levels between the block leaves and the piece layer for [tree]. */
    private fun blocksPerPieceLog(tree: MerkleTree): Int = tree.blocksPerPieceLog

    /** The file index whose v2 root matches [rootBytes], or null. */
    private fun fileForRoot(rootBytes: ByteArray): Int? {
        if (rootBytes.size != Merkle.HASH_SIZE) return null
        val target = Digest32.sha256(rootBytes)
        for (i in info.files.indices) {
            if (info.files[i].piecesRoot == target) return i
        }
        return null
    }
}
