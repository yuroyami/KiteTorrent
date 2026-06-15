package io.github.yuroyami.kitetorrent.torrent

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.crypto.Hasher256

/**
 * A parsed `.torrent` file — the pure-Kotlin counterpart of libtorrent's
 * `torrent_info` (torrent_info.cpp). Built from the bencoded bytes of a torrent, it
 * exposes the metadata a client needs: the name, the file list, piece geometry, the
 * trackers, and — crucially — the info-hash(es).
 *
 * The **info-hash** is `SHA-1(info-dict)` for v1 (BEP 3) and `SHA-256(info-dict)`
 * for v2 (BEP 52); a hybrid torrent has both. Because [BdecodeNode.dataSection]
 * returns the exact bytes the `info` dictionary was parsed from, the hash is computed
 * over the original bytes and is byte-for-byte identical to every other client's.
 *
 * v1 (and the v1 side of hybrid torrents) is fully parsed. Pure-v2 file trees are
 * parsed for their file list and per-file merkle roots; full piece-layer / merkle
 * verification is a follow-up.
 */
class TorrentInfo private constructor(
    /** The files and piece geometry. */
    val storage: FileStorage,
    /** v1 info-hash (SHA-1 of the info dict), or null for a pure-v2 torrent. */
    val infoHashV1: Sha1Hash?,
    /** v2 info-hash (SHA-256 of the info dict), or null for a v1-only torrent. */
    val infoHashV2: Sha256Hash?,
    /** Concatenated 20-byte v1 piece hashes, or null if absent (pure v2). */
    private val piecesBlob: ByteArray?,
    /** Bencode `meta version` (2 for v2/hybrid, 1 for classic v1). */
    val metaVersion: Int,
    /** True if the torrent is marked private (no DHT/PEX). */
    val isPrivate: Boolean,
    /** Tracker tiers (BEP 12 announce-list); each inner list is a tier. */
    val trackers: List<List<String>>,
    /** BEP 19 web seeds (`url-list`). */
    val webSeeds: List<String>,
    /** DHT bootstrap nodes (`nodes`): host/port pairs. */
    val dhtNodes: List<Pair<String, Int>>,
    val comment: String?,
    val createdBy: String?,
    /** Creation time as a Unix timestamp (seconds), or null. */
    val creationDate: Long?,
    /** The exact bytes of the `info` dictionary — the info-hash preimage, and what
     *  `ut_metadata` serves to magnet peers. */
    val infoBytes: ByteArray,
) {
    val name: String get() = storage.name
    val pieceLength: Int get() = storage.pieceLength
    val numPieces: Int get() = storage.numPieces
    val totalSize: Long get() = storage.totalSize
    val files: List<FileEntry> get() = storage.files
    val numFiles: Int get() = storage.numFiles

    val isV1: Boolean get() = infoHashV1 != null
    val isV2: Boolean get() = infoHashV2 != null
    val isHybrid: Boolean get() = isV1 && isV2

    /** The v1 SHA-1 hash for piece [index], sliced from the `pieces` blob. */
    fun pieceHashV1(index: Int): Sha1Hash? {
        val blob = piecesBlob ?: return null
        val off = index * 20
        if (off < 0 || off + 20 > blob.size) return null
        return Digest32.sha1(blob.copyOfRange(off, off + 20))
    }

    /**
     * The canonical info-hash hex for display/lookups: the v2 hash when present
     * (it's the stronger identity), otherwise the v1 hash.
     */
    fun infoHashHex(): String = (infoHashV2 ?: infoHashV1)?.toHex() ?: ""

    /** A flat list of every tracker URL across all tiers. */
    fun allTrackers(): List<String> = trackers.flatten()

    override fun toString(): String =
        "TorrentInfo(name=$name, files=$numFiles, size=$totalSize, pieces=$numPieces, " +
            "v1=${infoHashV1?.toHex()}, v2=${infoHashV2?.toHex()})"

    companion object {
        /** Parse a `.torrent` file from its raw bencoded bytes. */
        fun parse(torrentFileBytes: ByteArray): TorrentInfo =
            fromRoot(Bdecode.decode(torrentFileBytes))

        /** Build from an already-decoded root torrent dictionary. */
        fun fromRoot(root: BdecodeNode): TorrentInfo {
            require(root.type == BdecodeNode.Type.DICT) { "torrent root is not a dictionary" }
            val info = root.dictFindDict("info")
            require(info.isValid) { "torrent has no 'info' dictionary" }

            val meta = info.dictFindIntValue("meta version", 1L).toInt()
            val pieceLength = info.dictFindIntValue("piece length", -1L).toInt()
            require(pieceLength > 0) { "missing or invalid 'piece length'" }

            val name = info.dictFindStringValue("name.utf-8").ifEmpty { info.dictFindStringValue("name") }
            require(name.isNotEmpty()) { "missing 'name'" }

            val piecesBlob = info.dictFindStringBytes("pieces")
            val hasV1 = piecesBlob != null || info.dictFind("files").isValid || info.dictFind("length").isValid
            val hasV2 = meta >= 2 && info.dictFindDict("file tree").isValid

            val infoBytes = info.dataSection()
            val infoHashV1 = if (hasV1) Hasher.hash(infoBytes) else null
            val infoHashV2 = if (hasV2) Hasher256.hash(infoBytes) else null

            val files = parseFiles(info, name)
            val storage = FileStorage(name, pieceLength, files)

            return TorrentInfo(
                storage = storage,
                infoHashV1 = infoHashV1,
                infoHashV2 = infoHashV2,
                piecesBlob = piecesBlob,
                metaVersion = meta,
                isPrivate = info.dictFindIntValue("private", 0L) != 0L,
                trackers = parseTrackers(root),
                webSeeds = parseStringOrList(root.dictFind("url-list")),
                dhtNodes = parseNodes(root.dictFindList("nodes")),
                comment = root.dictFindString("comment").takeIf { it.isValid }?.stringValue(),
                createdBy = root.dictFindString("created by").takeIf { it.isValid }?.stringValue(),
                creationDate = root.dictFindInt("creation date").takeIf { it.isValid }?.intValue(),
                infoBytes = infoBytes,
            )
        }

        private fun parseFiles(info: BdecodeNode, name: String): List<FileEntry> {
            val filesNode = info.dictFindList("files")
            val fileTree = info.dictFindDict("file tree")

            if (filesNode.isValid) {
                // multi-file v1 (or the v1 side of a hybrid torrent). The v1 `path` list
                // is relative to the torrent name. For a hybrid we additionally pull each
                // file's v2 merkle root out of the file tree, keyed by that relative path.
                val v2Roots: Map<String, Sha256Hash> =
                    if (fileTree.isValid)
                        collectFileTreeLeaves(fileTree, "")
                            .mapNotNull { lf -> lf.piecesRoot?.let { lf.relPath to it } }
                            .toMap()
                    else emptyMap()
                val out = ArrayList<FileEntry>(filesNode.listSize())
                var offset = 0L
                for (i in 0 until filesNode.listSize()) {
                    val f = filesNode.listAt(i)
                    if (f.type != BdecodeNode.Type.DICT) continue
                    val size = f.dictFindIntValue("length", 0L)
                    val pathNode = f.dictFindList("path.utf-8").takeIf { it.isValid }
                        ?: f.dictFindList("path")
                    val components = ArrayList<String>()
                    if (pathNode.isValid) {
                        for (j in 0 until pathNode.listSize()) components.add(pathNode.listStringValueAt(j))
                    }
                    val rel = components.joinToString("/")
                    val attr = f.dictFindStringValue("attr")
                    out.add(
                        FileEntry(
                            path = if (rel.isEmpty()) name else "$name/$rel",
                            size = size,
                            offset = offset,
                            piecesRoot = v2Roots[rel],
                            isPadFile = attr.contains('p'),
                            symlinkTarget = parseSymlink(f),
                        ),
                    )
                    offset += size
                }
                return out
            }

            if (fileTree.isValid) {
                // pure v2: reconstruct the files from the nested file-tree dict.
                val leaves = collectFileTreeLeaves(fileTree, "")
                // A single-file torrent is exactly one file sitting at the tree root (its
                // key is the name); anything else is multi-file and lives under `name/`.
                val multiFile = leaves.size > 1 || leaves.any { it.relPath.contains('/') }
                val out = ArrayList<FileEntry>(leaves.size)
                var offset = 0L
                for (lf in leaves) {
                    out.add(
                        FileEntry(
                            path = if (multiFile) "$name/${lf.relPath}" else lf.relPath,
                            size = lf.size,
                            offset = offset,
                            piecesRoot = lf.piecesRoot,
                            symlinkTarget = lf.symlink,
                        ),
                    )
                    offset += lf.size
                }
                return out
            }

            // single-file v1
            val size = info.dictFindIntValue("length", 0L)
            return listOf(FileEntry(path = name, size = size, offset = 0L))
        }

        private class V2Leaf(
            val relPath: String,
            val size: Long,
            val piecesRoot: Sha256Hash?,
            val symlink: String?,
        )

        // BEP 52 file tree: nested dicts keyed by path component; a file terminates in a
        // "" key whose value holds {length, pieces root}. Returns one entry per file with
        // the path RELATIVE to the torrent name (the name itself is not included here).
        private fun collectFileTreeLeaves(node: BdecodeNode, prefix: String): List<V2Leaf> {
            val out = ArrayList<V2Leaf>()
            for (i in 0 until node.dictSize()) {
                val (keyBytes, child) = node.dictAt(i)
                val key = keyBytes.decodeToString()
                if (key.isEmpty()) {
                    val size = child.dictFindIntValue("length", 0L)
                    val rootBytes = child.dictFindStringBytes("pieces root")
                    val piecesRoot =
                        if (rootBytes != null && rootBytes.size == 32) Digest32.sha256(rootBytes) else null
                    out.add(V2Leaf(prefix, size, piecesRoot, parseSymlink(child)))
                } else if (child.type == BdecodeNode.Type.DICT) {
                    val childPrefix = if (prefix.isEmpty()) key else "$prefix/$key"
                    out.addAll(collectFileTreeLeaves(child, childPrefix))
                }
            }
            return out
        }

        private fun parseSymlink(f: BdecodeNode): String? {
            val s = f.dictFindList("symlink path")
            if (!s.isValid) return null
            return (0 until s.listSize()).joinToString("/") { s.listStringValueAt(it) }
        }

        private fun parseTrackers(root: BdecodeNode): List<List<String>> {
            val tiers = ArrayList<List<String>>()
            val announceList = root.dictFindList("announce-list")
            if (announceList.isValid) {
                for (i in 0 until announceList.listSize()) {
                    val tierNode = announceList.listAt(i)
                    if (tierNode.type != BdecodeNode.Type.LIST) continue
                    val tier = ArrayList<String>()
                    for (j in 0 until tierNode.listSize()) {
                        tierNode.listStringValueAt(j).takeIf { it.isNotEmpty() }?.let { tier.add(it) }
                    }
                    if (tier.isNotEmpty()) tiers.add(tier)
                }
            }
            if (tiers.isEmpty()) {
                root.dictFindStringValue("announce").takeIf { it.isNotEmpty() }?.let { tiers.add(listOf(it)) }
            }
            return tiers
        }

        // url-list / web seeds may be a single string or a list of strings
        private fun parseStringOrList(node: BdecodeNode): List<String> = when (node.type) {
            BdecodeNode.Type.STRING -> listOf(node.stringValue())
            BdecodeNode.Type.LIST -> (0 until node.listSize()).map { node.listStringValueAt(it) }
                .filter { it.isNotEmpty() }
            else -> emptyList()
        }

        private fun parseNodes(node: BdecodeNode): List<Pair<String, Int>> {
            if (!node.isValid) return emptyList()
            val out = ArrayList<Pair<String, Int>>()
            for (i in 0 until node.listSize()) {
                val pair = node.listAt(i)
                if (pair.type == BdecodeNode.Type.LIST && pair.listSize() >= 2) {
                    val host = pair.listStringValueAt(0)
                    val port = pair.listIntValueAt(1).toInt()
                    if (host.isNotEmpty()) out.add(host to port)
                }
            }
            return out
        }
    }
}
