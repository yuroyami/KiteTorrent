package io.github.yuroyami.kitetorrent.torrent

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder

/**
 * Builds a `.torrent` file from a set of files, piece geometry and pre-computed
 * piece hashes -- pure-Kotlin port of libtorrent's `create_torrent`
 * (`src/create_torrent.cpp`, `include/libtorrent/create_torrent.hpp`).
 *
 * In libtorrent a torrent is created in four steps:
 *  1. determine the files that will be part of the torrent,
 *  2. set torrent properties (trackers, web seeds, DHT nodes ...),
 *  3. read all file data, SHA-1/SHA-256 it and set the piece hashes,
 *  4. bencode the result into a buffer or file.
 *
 * Step 3 (reading and hashing bytes off disk) is intentionally **out of scope** of
 * the KiteTorrent core, which has no filesystem access in `commonMain`. Instead the
 * caller supplies the already-computed hashes:
 *  - v1: one [Sha1Hash] per piece via [setHash] (BEP 3), and
 *  - v2: one [Sha256Hash] per 16 KiB block via [setHash2] (BEP 52),
 *
 * exactly as libtorrent's `set_piece_hashes()` helper would feed them in. Once the
 * hashes are set, [generate] produces the bencode tree and [generateBuffer] the
 * encoded bytes.
 *
 * ### v1 / v2 / hybrid
 * Like libtorrent, the "flavour" produced depends on the flags and which hashes were
 * set:
 *  - construct with [v1Only] = true to force a v1-only torrent (setting v2 hashes is
 *    then an error),
 *  - construct with [v2Only] = true to force a v2-only torrent (setting v1 hashes is
 *    then an error),
 *  - otherwise, if both v1 and v2 hashes are fully set a **hybrid** torrent is
 *    produced; if only one side's hashes are set, that flavour is produced.
 *
 * A SHA-1/SHA-256 hash of all zeros is the "unset" sentinel: setting such a hash is
 * ignored, and a torrent whose hashes are not all set will not generate that
 * flavour (mirrors `validate_v1_hashes` / `validate_v2_hashes`).
 *
 * ### Files
 * Files are added through the constructor's [FileStorage] (build it directly) or,
 * more conveniently, through a [CreateTorrent.Builder]. For a single-file torrent
 * the one file's path is the torrent name. For a multi-file torrent every file's
 * path begins with the torrent's root-directory name; in the generated `files[]`
 * list the leading (root) component is deliberately dropped, exactly like
 * libtorrent.
 *
 * @param storage the files and piece geometry to encode. Must contain at least one
 *   file and a non-zero total size.
 * @param v1Only force a v1-only torrent (reject v2 hashes). Mutually exclusive with
 *   [v2Only].
 * @param v2Only force a v2-only torrent (reject v1 hashes). Mutually exclusive with
 *   [v1Only].
 */
class CreateTorrent(
    private val storage: FileStorage,
    private val v1Only: Boolean = false,
    private val v2Only: Boolean = false,
) {
    init {
        require(!(v1Only && v2Only)) { "v1Only and v2Only are mutually exclusive" }
        require(storage.numFiles > 0) { "the file storage has 0 files" }
        require(storage.totalSize > 0) { "the total size of the file storage is 0 bytes" }
        require(storage.pieceLength > 0) { "piece length must be > 0" }
    }

    // ---- torrent properties (step 2) ----------------------------------------

    private val trackers = ArrayList<Pair<String, Int>>()
    private val urlSeeds = ArrayList<String>()
    private val httpSeeds = ArrayList<String>()
    private val nodes = ArrayList<Pair<String, Int>>()
    private val collections = ArrayList<String>()
    private val similar = ArrayList<Sha1Hash>()

    private var comment: String? = null
    private var createdBy: String? = null
    private var rootCert: String? = null
    private var isPrivate: Boolean = false

    /**
     * The "creation date" field, a POSIX timestamp in seconds. `null` (the default)
     * omits the field; libtorrent instead defaults to the wall clock at construction
     * time, but core code has no clock, so omission is the honest default. Set it
     * explicitly via [creationDate] / [setCreationDate].
     */
    var creationDate: Long? = null

    // ---- piece hashes (step 3, supplied by the caller) ----------------------

    /** One SHA-1 per v1 piece; all-zero entries are "unset". Lazily sized. */
    private var pieceHash: Array<Sha1Hash>? = null

    /** Per-file SHA-256 block-leaf hashes; all-zero entries "unset". Lazily sized. */
    private var filePieceHash: Array<Array<Sha256Hash>?>? = null

    /** Resolved per-file v2 roots, filled by [generate] from [filePieceHash]. */
    private var fileRoots: Array<Sha256Hash>? = null

    /** The files and piece geometry used to build the torrent. */
    fun files(): FileStorage = storage

    /** Number of pieces in the associated [FileStorage]. */
    fun numPieces(): Int = storage.numPieces

    /** Piece size of all pieces but the last. Forwards to the [FileStorage]. */
    fun pieceLength(): Int = storage.pieceLength

    /** Size of piece [index]; the last piece is usually shorter. */
    fun pieceSize(index: Int): Int = storage.pieceSize(index)

    /** Total number of bytes across all files. */
    fun totalSize(): Long = storage.totalSize

    fun isV1Only(): Boolean = v1Only
    fun isV2Only(): Boolean = v2Only

    // ---- property setters (mirroring create_torrent member functions) -------

    /** Sets the (optional) UTF-8 comment. Passing `null` clears it. */
    fun setComment(str: String?): CreateTorrent { comment = str; return this }

    /** Sets the (optional) UTF-8 creator string, e.g. the software name. */
    fun setCreator(str: String?): CreateTorrent { createdBy = str; return this }

    /** Sets the "creation date" field (POSIX seconds); 0 or `null` omits it. */
    fun setCreationDate(timestamp: Long?): CreateTorrent {
        creationDate = if (timestamp == 0L) null else timestamp
        return this
    }

    /**
     * Sets the private flag (BEP 27). Private torrents ask clients to use only the
     * tracker -- no DHT, no PEX, no other peer sources.
     */
    fun setPrivate(p: Boolean): CreateTorrent { isPrivate = p; return this }

    /** Whether the private flag is set. */
    fun priv(): Boolean = isPrivate

    /**
     * Adds a tracker. Duplicate URLs are ignored. The [tier] is the fallback
     * priority (all tier-0 trackers are tried first, then tier-1, ...); the URL list
     * is kept sorted by tier, matching `create_torrent::add_tracker`.
     */
    fun addTracker(url: String, tier: Int = 0): CreateTorrent {
        if (url.isEmpty()) return this
        if (trackers.any { it.first == url }) return this
        trackers.add(url to tier)
        // stable sort by tier, preserving insertion order within a tier
        trackers.sortBy { it.second }
        return this
    }

    /** Adds a BEP 19 web seed (`url-list`). */
    fun addUrlSeed(url: String): CreateTorrent { urlSeeds.add(url); return this }

    /** Adds an HTTP seed (`httpseeds`). */
    fun addHttpSeed(url: String): CreateTorrent { httpSeeds.add(url); return this }

    /** Adds a DHT bootstrap node as a host/port pair (`nodes`). */
    fun addNode(host: String, port: Int): CreateTorrent { nodes.add(host to port); return this }

    /** Adds a BEP 38 "similar torrent" by info-hash. */
    fun addSimilarTorrent(ih: Sha1Hash): CreateTorrent { similar.add(ih); return this }

    /** Adds a BEP 38 collection name. */
    fun addCollection(c: String): CreateTorrent { collections.add(c); return this }

    /**
     * Sets the X.509 root certificate (PEM) for an SSL torrent. The string is the
     * certificate content, not a path.
     */
    fun setRootCert(cert: String): CreateTorrent { rootCert = cert; return this }

    // ---- piece-hash setters (step 3) ----------------------------------------

    /**
     * Sets the SHA-1 hash for piece [index] (v1, BEP 3). You must set every piece
     * before [generate]. An all-zero hash is treated as "unset".
     *
     * @throws IllegalStateException if this torrent was constructed [v2Only].
     */
    fun setHash(index: Int, h: Sha1Hash): CreateTorrent {
        check(!v2Only) { "set_hash() called on a v2-only torrent" }
        require(h.size == Digest32.SHA1_SIZE) { "v1 piece hash must be 20 bytes, was ${h.size}" }
        require(index in 0 until storage.numPieces) {
            "piece index $index out of range [0,${storage.numPieces})"
        }
        val arr = pieceHash ?: Array(storage.numPieces) { ZERO_SHA1 }.also { pieceHash = it }
        arr[index] = h
        return this
    }

    /**
     * Sets the BitTorrent v2 hash for the merkle leaf at [block] of file [file]
     * (BEP 52). [block] is the **block index within the file**, starting at 0; `h` is
     * the SHA-256 of that 16 KiB block. An all-zero hash is treated as "unset".
     *
     * Note: libtorrent's `set_hash2` takes the per-*piece* merkle root, because its
     * disk hasher folds each piece's block tree internally. KiteTorrent's core has no
     * disk hasher, so this method takes the raw block leaves and [generate] folds each
     * file's merkle tree itself (per-piece roots, file root, and the `piece layers`).
     *
     * @throws IllegalStateException if this torrent was constructed [v1Only].
     */
    fun setHash2(file: Int, block: Int, h: Sha256Hash): CreateTorrent {
        check(!v1Only) { "set_hash2() called on a v1-only torrent" }
        require(h.size == Digest32.SHA256_SIZE) { "v2 hash must be 32 bytes, was ${h.size}" }
        require(file in 0 until storage.numFiles) {
            "file index $file out of range [0,${storage.numFiles})"
        }
        require(block >= 0) { "block index must be >= 0, was $block" }
        val entry = storage.files[file]
        require(!entry.isPadFile) { "cannot set a v2 hash on a pad file" }

        val outer = filePieceHash ?: arrayOfNulls<Array<Sha256Hash>?>(storage.numFiles)
            .also { filePieceHash = it }
        val blocks = numBlocksInFile(entry.size)
        val inner = outer[file] ?: Array(blocks) { ZERO_SHA256 }.also { outer[file] = it }
        require(block < inner.size) {
            "block index $block out of range [0,${inner.size}) for file $file"
        }
        inner[block] = h
        return this
    }

    /**
     * Convenience for the common v2 case where the caller already holds a file's
     * 16 KiB block hashes in order. Equivalent to calling [setHash2] for each block.
     *
     * @throws IllegalStateException if this torrent was constructed [v1Only].
     */
    fun setFileV2Leaves(file: Int, leaves: List<Sha256Hash>): CreateTorrent {
        leaves.forEachIndexed { i, h -> setHash2(file, i, h) }
        return this
    }

    // ---- generation (step 4) -------------------------------------------------

    /** Bencodes [generate] into a fresh byte array (= `Bencode.encode(generate())`). */
    fun generateBuffer(): ByteArray = Bencode.encode(generate())

    /**
     * Builds the `.torrent` as a bencode [Entry] tree, ready for [Bencode.encode] or
     * for adding custom keys before encoding. Mirrors `create_torrent::generate`.
     *
     * @throws IllegalStateException if neither a full v1 nor a full v2 hash set is
     *   present, or a v2 file-tree path conflict is found.
     */
    fun generate(): Entry {
        check(storage.numFiles > 0 && storage.totalSize > 0) { "torrent is missing a file tree" }

        val makeV2 = !v1Only && validateV2Hashes()
        val makeV1 = !v2Only && validateV1Hashes()

        check(makeV1 || makeV2) {
            "no complete set of piece hashes was provided (need all v1 set_hash() " +
                "or all v2 set_hash2() values)"
        }

        val dict = Entry.dict()
        val root = dict.map

        // announce (first tracker) + announce-list (BEP 12)
        if (trackers.isNotEmpty()) {
            root["announce"] = Entry.of(trackers.first().first)
        }

        if (nodes.isNotEmpty()) {
            val nodesList = Entry.list()
            for ((host, port) in nodes) {
                val node = Entry.list()
                node.items.add(Entry.of(host))
                node.items.add(Entry.of(port.toLong()))
                nodesList.items.add(node)
            }
            root["nodes"] = nodesList
        }

        if (trackers.size > 1) {
            val tiers = Entry.list()
            var currentTier = trackers.first().second
            var tier = Entry.list()
            for ((url, t) in trackers) {
                if (t != currentTier) {
                    currentTier = t
                    tiers.items.add(tier)
                    tier = Entry.list()
                }
                tier.items.add(Entry.of(url))
            }
            tiers.items.add(tier)
            root["announce-list"] = tiers
        }

        comment?.takeIf { it.isNotEmpty() }?.let { root["comment"] = Entry.of(it) }
        creationDate?.takeIf { it != 0L }?.let { root["creation date"] = Entry.of(it) }
        createdBy?.takeIf { it.isNotEmpty() }?.let { root["created by"] = Entry.of(it) }

        if (urlSeeds.isNotEmpty()) root["url-list"] = stringOrList(urlSeeds)
        if (httpSeeds.isNotEmpty()) root["httpseeds"] = stringOrList(httpSeeds)

        // piece layers (top-level, BEP 52) -- computed before the info dict so the
        // per-file roots are available when writing the file tree.
        if (makeV2) {
            root["piece layers"] = buildPieceLayers()
        }

        // ---- the info dictionary ----
        val info = Entry.dict()
        val infoMap = info.map

        if (collections.isNotEmpty()) {
            val list = Entry.list()
            for (c in collections) list.items.add(Entry.of(c))
            infoMap["collections"] = list
        }
        if (similar.isNotEmpty()) {
            val list = Entry.list()
            for (ih in similar) list.items.add(Entry.of(ih.toByteArray()))
            infoMap["similar"] = list
        }

        infoMap["name"] = Entry.of(storage.name)
        rootCert?.takeIf { it.isNotEmpty() }?.let { infoMap["ssl-cert"] = Entry.of(it) }
        if (isPrivate) infoMap["private"] = Entry.of(1L)

        if (makeV1) writeV1Files(infoMap)
        if (makeV2) writeV2FileTree(infoMap)

        infoMap["piece length"] = Entry.of(storage.pieceLength.toLong())

        if (makeV1) {
            // concatenated 20-byte piece hashes
            val blob = ByteArray(storage.numPieces * Digest32.SHA1_SIZE)
            val hashes = pieceHash!!
            for (i in 0 until storage.numPieces) {
                hashes[i].toByteArray().copyInto(blob, i * Digest32.SHA1_SIZE)
            }
            infoMap["pieces"] = Entry.of(blob)
        }

        dict.map["info"] = info
        return dict
    }

    // ---- v1 info-dict assembly ----------------------------------------------

    private fun writeV1Files(infoMap: MutableMap<String, Entry>) {
        if (!isMultiFile()) {
            val f = storage.files[0]
            infoMap["length"] = Entry.of(f.size)
            addFileAttrs(infoMap, f)
            addSymlinkPath(infoMap, f)
        } else {
            val filesList = Entry.list()
            for (f in storage.files) {
                val fileE = Entry.dict()
                val fileMap = fileE.map
                fileMap["length"] = Entry.of(f.size)

                // path components, deliberately skipping the first (the torrent name)
                val pathE = Entry.list()
                for (part in pathComponentsWithoutRoot(f.path)) pathE.items.add(Entry.of(part))
                fileMap["path"] = pathE

                addFileAttrs(fileMap, f)
                addSymlinkPath(fileMap, f)
                filesList.items.add(fileE)
            }
            infoMap["files"] = filesList
        }
    }

    // ---- v2 info-dict assembly ----------------------------------------------

    private fun writeV2FileTree(infoMap: MutableMap<String, Entry>) {
        val roots = fileRoots ?: error("file roots not computed")
        val tree = Entry.dict()

        for ((i, f) in storage.files.withIndex()) {
            if (f.isPadFile) continue

            // Walk/create the nested dict down to this file's leaf node.
            var node = tree
            val comps = v2TreeComponents(f.path)
            check(comps.isNotEmpty()) { "v2 file tree path is empty for ${f.path}" }
            for (comp in comps) {
                val child = node.map[comp]
                node = when (child) {
                    null -> Entry.dict().also { node.map[comp] = it }
                    is Entry.Dict -> child
                    else -> throw IllegalStateException("inconsistent files: path conflict at '$comp'")
                }
                // a file already terminates here if the "" key exists
                if (node.map.containsKey("")) {
                    throw IllegalStateException("inconsistent files: a file already exists at '$comp'")
                }
            }
            if (node.map.isNotEmpty()) {
                throw IllegalStateException("inconsistent files: a directory already exists at ${f.path}")
            }

            val fileE = Entry.dict()
            addFileAttrs(fileE.map, f)
            if (f.symlinkTarget != null) {
                addSymlinkPath(fileE.map, f)
            } else {
                if (f.size > 0) fileE.map["pieces root"] = Entry.of(roots[i].toByteArray())
                fileE.map["length"] = Entry.of(f.size)
            }
            node.map[""] = fileE
        }

        infoMap["file tree"] = tree
        infoMap["meta version"] = Entry.of(2L)
    }

    /**
     * Computes each non-pad file's v2 root from its block leaves and assembles the
     * top-level `piece layers` dict (BEP 52): for every file with more than one
     * piece, a key of the raw 32-byte file root mapping to the concatenation of that
     * file's per-piece roots. Files with a single piece store only the root (in the
     * file tree) and get no piece-layers entry. Mirrors the `make_v2` block of
     * `create_torrent::generate`.
     *
     * The dict's **keys are raw 32-byte SHA-256 roots**, which are not valid UTF-8.
     * KiteTorrent's [Entry.Dict] keys are [String]s that [Bencode] re-encodes as
     * UTF-8 -- that would corrupt any byte >= 0x80. So this one dictionary is built
     * directly into bencoded bytes (its keys sorted by raw byte order) and returned as
     * an [Entry.Preformatted], which [Bencode] splices verbatim. The computed per-file
     * roots are stored in [fileRoots] for the file tree.
     */
    private fun buildPieceLayers(): Entry {
        val outer = filePieceHash ?: error("v2 hashes not set")
        val roots = Array(storage.numFiles) { ZERO_SHA256 }
        val blocksPerPiece = storage.pieceLength / DEFAULT_BLOCK_SIZE

        // (rootBytes, layerBlob) pairs for files with >= 2 pieces.
        val layerEntries = ArrayList<Pair<ByteArray, ByteArray>>()

        for ((i, f) in storage.files.withIndex()) {
            if (f.isPadFile || f.size == 0L) continue
            val leaves = outer[i]!!.toList()

            // per-piece roots (each piece is a complete sub-tree of `blocksPerPiece`
            // block leaves, padded with the all-zero block hash).
            val pieceRoots = perPieceRoots(leaves, blocksPerPiece)

            // file root = merkle root over the per-piece roots, right-padded with the
            // piece-layer pad hash (merkle_pad(blocksPerPiece, 1)).
            roots[i] = if (pieceRoots.size == 1) {
                pieceRoots[0]
            } else {
                val padHash = Merkle.pad(blocksPerPiece, 1)
                Merkle.merkleRoot(pieceRoots, padHash)
            }

            // files with a single piece need no piece-layers entry.
            if (pieceRoots.size < 2) continue

            val blob = ByteArray(pieceRoots.size * Digest32.SHA256_SIZE)
            for (p in pieceRoots.indices) {
                pieceRoots[p].toByteArray().copyInto(blob, p * Digest32.SHA256_SIZE)
            }
            layerEntries.add(roots[i].toByteArray() to blob)
        }

        fileRoots = roots
        return Entry.Preformatted(encodeRawKeyedDict(layerEntries))
    }

    /**
     * Splits a file's ordered block leaves into pieces of [blocksPerPiece] blocks and
     * folds each piece into its merkle root (padding the final, partial piece's leaves
     * up to [blocksPerPiece] with the all-zero block hash). For files no larger than
     * one piece the single "piece" is the whole (sub-power-of-two) block set, padded
     * up to the next power of two -- this is the per-file root.
     */
    private fun perPieceRoots(leaves: List<Sha256Hash>, blocksPerPiece: Int): List<Sha256Hash> {
        // sub-piece file: a single per-file root over all its blocks
        if (leaves.size <= blocksPerPiece) {
            return listOf(Merkle.merkleRoot(leaves, ZERO_SHA256))
        }
        val out = ArrayList<Sha256Hash>((leaves.size + blocksPerPiece - 1) / blocksPerPiece)
        var i = 0
        while (i < leaves.size) {
            val end = minOf(i + blocksPerPiece, leaves.size)
            val pieceLeaves = leaves.subList(i, end)
            // each full piece has exactly blocksPerPiece leaves (a power of two); the
            // trailing partial piece is padded to blocksPerPiece with the zero hash.
            out.add(Merkle.rootScratch(pieceLeaves, blocksPerPiece, ZERO_SHA256))
            i = end
        }
        return out
    }

    // ---- validation (mirrors validate_v1_hashes / validate_v2_hashes) -------

    private fun validateV1Hashes(): Boolean {
        val hashes = pieceHash ?: return false
        if (hashes.size != storage.numPieces) return false
        return hashes.none { it.isAllZeros() }
    }

    private fun validateV2Hashes(): Boolean {
        val outer = filePieceHash ?: return false
        if (outer.size != storage.numFiles) return false
        var anyReal = false
        for ((i, f) in storage.files.withIndex()) {
            val inner = outer[i]
            if (f.isPadFile) {
                if (inner != null && inner.isNotEmpty()) return false
                continue
            }
            if (f.size == 0L) {
                // empty files carry no hashes
                if (inner != null && inner.isNotEmpty()) return false
                continue
            }
            val expected = numBlocksInFile(f.size)
            if (inner == null || inner.size != expected) return false
            if (inner.any { it.isAllZeros() }) return false
            anyReal = true
        }
        // need at least one real (non-pad, non-empty) file with hashes
        return anyReal
    }

    // ---- shared entry helpers (mirror add_file_attrs / add_symlink_path) ----

    private fun addFileAttrs(map: MutableMap<String, Entry>, f: FileEntry) {
        if (!f.isPadFile && f.symlinkTarget == null) return
        val sb = StringBuilder()
        if (f.isPadFile) sb.append('p')
        if (f.symlinkTarget != null) sb.append('l')
        if (sb.isNotEmpty()) map["attr"] = Entry.of(sb.toString())
    }

    private fun addSymlinkPath(map: MutableMap<String, Entry>, f: FileEntry) {
        val target = f.symlinkTarget ?: return
        val list = Entry.list()
        for (part in target.split('/')) {
            if (part.isNotEmpty()) list.items.add(Entry.of(part))
        }
        map["symlink path"] = list
    }

    private fun stringOrList(values: List<String>): Entry =
        if (values.size == 1) {
            Entry.of(values[0])
        } else {
            Entry.list().also { l -> for (v in values) l.items.add(Entry.of(v)) }
        }

    // ---- geometry helpers ----------------------------------------------------

    /**
     * A torrent is multi-file when it has more than one file, or its single file has
     * a parent directory in its path (`test/test` style). Mirrors libtorrent's
     * `m_multifile` initialisation.
     */
    private fun isMultiFile(): Boolean {
        if (storage.numFiles > 1) return true
        val only = storage.files[0]
        // a leading "name/" prefix means the (single) file sits in a directory
        return only.path.contains('/')
    }

    /** Path split into components, dropping the leading root (== torrent name). */
    private fun pathComponentsWithoutRoot(path: String): List<String> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return if (parts.isNotEmpty()) parts.drop(1) else parts
    }

    /**
     * Path components for the v2 `file tree`. For a multi-file torrent the leading
     * root (== torrent name) is dropped, just like the v1 `files[].path`. For a
     * single-file torrent the full path (which is just the name) is kept, so the file
     * is keyed by its name directly under the tree root -- matching libtorrent's
     * `m_multifile ? lsplit_path(file_path) : pair(file_path, file_path)` split, both
     * of which then iterate over the *second* element.
     */
    private fun v2TreeComponents(path: String): List<String> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return if (isMultiFile()) {
            if (parts.isNotEmpty()) parts.drop(1) else parts
        } else {
            parts
        }
    }

    /** Number of 16 KiB blocks covering a file of [size] bytes (0 for an empty file). */
    private fun numBlocksInFile(size: Long): Int {
        if (size <= 0L) return 0
        return ((size + DEFAULT_BLOCK_SIZE - 1) / DEFAULT_BLOCK_SIZE).toInt()
    }

    /**
     * Fluent builder that collects files (path + size) and the torrent name, then
     * produces a [CreateTorrent]. This is the convenience layer over constructing a
     * [FileStorage] directly, analogous to libtorrent's `add_files` + `file_storage`
     * feeding `create_torrent`.
     *
     * Offsets are assigned automatically in insertion order. For a multi-file
     * torrent, file paths are stored as `name/<relative path>` so they round-trip
     * through [TorrentInfo].
     */
    class Builder(private val name: String, private val pieceLength: Int) {
        init {
            require(name.isNotEmpty()) { "torrent name must not be empty" }
            require(pieceLength > 0) { "piece length must be > 0, was $pieceLength" }
        }

        private class Spec(
            val relPath: String,
            val size: Long,
            val isPadFile: Boolean,
            val symlinkTarget: String?,
        )

        private val specs = ArrayList<Spec>()

        /**
         * Adds a file. [relPath] is relative to the torrent root (no leading name);
         * for a single-file torrent add exactly one file whose [relPath] equals the
         * torrent [name] (or empty, treated as the name).
         */
        fun addFile(
            relPath: String,
            size: Long,
            isPadFile: Boolean = false,
            symlinkTarget: String? = null,
        ): Builder {
            require(size >= 0L) { "file size must be >= 0, was $size" }
            specs.add(Spec(relPath, size, isPadFile, symlinkTarget))
            return this
        }

        /** Builds the immutable [FileStorage] from the collected specs. */
        fun buildStorage(): FileStorage {
            require(specs.isNotEmpty()) { "no files added" }
            val singleFile = specs.size == 1 &&
                !specs[0].isPadFile &&
                (specs[0].relPath.isEmpty() || specs[0].relPath == name) &&
                !specs[0].relPath.contains('/')

            val entries = ArrayList<FileEntry>(specs.size)
            var offset = 0L
            for (s in specs) {
                val fullPath = if (singleFile) {
                    name
                } else {
                    val rel = s.relPath.trim('/')
                    if (rel.isEmpty()) name else "$name/$rel"
                }
                entries.add(
                    FileEntry(
                        path = fullPath,
                        size = s.size,
                        offset = offset,
                        isPadFile = s.isPadFile,
                        symlinkTarget = s.symlinkTarget,
                    ),
                )
                offset += s.size
            }
            return FileStorage(name, pieceLength, entries)
        }

        /** Builds the [CreateTorrent] from the collected files. */
        fun build(v1Only: Boolean = false, v2Only: Boolean = false): CreateTorrent =
            CreateTorrent(buildStorage(), v1Only = v1Only, v2Only = v2Only)
    }

    companion object {
        /** v2 block size (BEP 52): 16 KiB. */
        const val DEFAULT_BLOCK_SIZE: Int = 16 * 1024

        private val ZERO_SHA1: Sha1Hash = Digest32.zeros(Digest32.SHA1_SIZE)
        private val ZERO_SHA256: Sha256Hash = Digest32.zeros(Digest32.SHA256_SIZE)

        /**
         * Bencodes a dictionary whose **keys are raw byte strings** (here, 32-byte
         * SHA-256 file roots) directly into bytes, sorting the keys by unsigned raw
         * byte order as BEP 3 requires. This exists because [Entry.Dict] keys are
         * UTF-8 [String]s and cannot represent arbitrary bytes losslessly; the result
         * is wrapped in an [Entry.Preformatted] and spliced verbatim by [Bencode].
         *
         * Each pair is `(keyBytes, valueBytes)` and is emitted as
         * `<len>:<keyBytes><len>:<valueBytes>`, i.e. both key and value are bencode
         * byte-strings -- matching libtorrent's `piece layers` layout.
         */
        private fun encodeRawKeyedDict(entries: List<Pair<ByteArray, ByteArray>>): ByteArray {
            val out = ByteArrayBuilder(64)
            out.append('d'.code.toByte())
            val sorted = entries.sortedWith { a, b -> compareBytesUnsigned(a.first, b.first) }
            for ((key, value) in sorted) {
                out.appendAscii(key.size.toString())
                out.append(':'.code.toByte())
                out.append(key)
                out.appendAscii(value.size.toString())
                out.append(':'.code.toByte())
                out.append(value)
            }
            out.append('e'.code.toByte())
            return out.toByteArray()
        }

        /** Unsigned lexicographic comparison of two byte arrays (BEP 3 key order). */
        private fun compareBytesUnsigned(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return a.size - b.size
        }
    }
}
