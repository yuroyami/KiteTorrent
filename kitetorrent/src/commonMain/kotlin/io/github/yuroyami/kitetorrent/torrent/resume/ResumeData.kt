package io.github.yuroyami.kitetorrent.torrent.resume

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Version
import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.error.LibtorrentError
import io.github.yuroyami.kitetorrent.error.TorrentException
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder
import io.github.yuroyami.kitetorrent.peer.PeerAddress
import io.github.yuroyami.kitetorrent.picker.DownloadPriority
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo

/**
 * Fast-resume (de)serialisation — the pure-Kotlin port of libtorrent's
 * `write_resume_data()` (src/write_resume_data.cpp) and `read_resume_data()`
 * (src/read_resume_data.cpp).
 *
 * Resume data is a bencoded dictionary tagged `"file-format" = "libtorrent resume
 * file"` that captures everything needed to re-add a torrent without re-checking it:
 * the info-hash(es), the embedded `info` dictionary (when metadata is present), the
 * `pieces` bitmask, partially-downloaded pieces, trackers, web seeds, peers,
 * priorities, accumulated stats and the torrent flags. [write] produces those bytes
 * from an [AddTorrentParams]; [read] parses them back into one.
 *
 * The field names and value encodings are byte-for-byte those libtorrent uses, so a
 * file written here can be loaded by libtorrent and vice versa — e.g. the `pieces`
 * string is one byte per piece with bit 0 = "have" and bit 1 = "verified", and a peer
 * list is the concatenation of `address-bytes ++ big-endian-uint16-port`.
 *
 * Scope: the v2 merkle-tree `trees` key is not emitted or parsed here (see the note on
 * [AddTorrentParams]); everything else in the C++ functions for the non-deprecated
 * build (`TORRENT_ABI_VERSION != 1`, share-mode and super-seeding enabled, i2p
 * disabled) is reproduced.
 */
object ResumeData {

    /** The magic value of the `file-format` key. */
    const val FILE_FORMAT: String = "libtorrent resume file"

    /** The `file-version` this writer emits (libtorrent's current value). */
    const val FILE_VERSION: Long = 1

    // ---- write ---------------------------------------------------------------

    /**
     * Serialise [atp] to fast-resume bytes — the port of `write_resume_data_buf`
     * (`bencode(write_resume_data(atp))`).
     */
    fun write(atp: AddTorrentParams): ByteArray = Bencode.encode(writeEntry(atp))

    /**
     * Build the resume-data [Entry] dictionary for [atp] — the port of
     * `write_resume_data(add_torrent_params const&)`.
     */
    fun writeEntry(atp: AddTorrentParams): Entry {
        val ret = Entry.dict()
        val d = ret.dict()

        d["file-format"] = Entry.of(FILE_FORMAT)
        d["file-version"] = Entry.of(FILE_VERSION)
        d["libtorrent-version"] = Entry.of(Version.LIBTORRENT_VERSION)
        d["allocation"] = Entry.of(if (atp.storageModeAllocate) "allocate" else "sparse")

        d["total_uploaded"] = Entry.of(atp.totalUploaded)
        d["total_downloaded"] = Entry.of(atp.totalDownloaded)

        d["active_time"] = Entry.of(atp.activeTime.toLong())
        d["finished_time"] = Entry.of(atp.finishedTime.toLong())
        d["seeding_time"] = Entry.of(atp.seedingTime.toLong())
        d["last_seen_complete"] = Entry.of(atp.lastSeenComplete)
        d["last_download"] = Entry.of(atp.lastDownload)
        d["last_upload"] = Entry.of(atp.lastUpload)

        d["num_complete"] = Entry.of(atp.numComplete.toLong())
        d["num_incomplete"] = Entry.of(atp.numIncomplete.toLong())
        d["num_downloaded"] = Entry.of(atp.numDownloaded.toLong())

        // flags written as 0/1 integers (bencode has no bool; libtorrent's entry(bool)
        // stores an int).
        d["seed_mode"] = boolEntry(atp.hasFlag(TorrentFlags.SEED_MODE))
        d["upload_mode"] = boolEntry(atp.hasFlag(TorrentFlags.UPLOAD_MODE))
        d["share_mode"] = boolEntry(atp.hasFlag(TorrentFlags.SHARE_MODE))
        d["apply_ip_filter"] = boolEntry(atp.hasFlag(TorrentFlags.APPLY_IP_FILTER))
        d["paused"] = boolEntry(atp.hasFlag(TorrentFlags.PAUSED))
        d["auto_managed"] = boolEntry(atp.hasFlag(TorrentFlags.AUTO_MANAGED))
        d["super_seeding"] = boolEntry(atp.hasFlag(TorrentFlags.SUPER_SEEDING))
        d["sequential_download"] = boolEntry(atp.hasFlag(TorrentFlags.SEQUENTIAL_DOWNLOAD))
        d["stop_when_ready"] = boolEntry(atp.hasFlag(TorrentFlags.STOP_WHEN_READY))
        d["disable_dht"] = boolEntry(atp.hasFlag(TorrentFlags.DISABLE_DHT))
        d["disable_lsd"] = boolEntry(atp.hasFlag(TorrentFlags.DISABLE_LSD))
        d["disable_pex"] = boolEntry(atp.hasFlag(TorrentFlags.DISABLE_PEX))

        d["added_time"] = Entry.of(atp.addedTime)
        d["completed_time"] = Entry.of(atp.completedTime)

        d["save_path"] = Entry.of(atp.savePath)

        if (atp.name.isNotEmpty()) d["name"] = Entry.of(atp.name)

        // info-hash(es): the raw 20-/32-byte digests. libtorrent always writes the
        // value-typed v1/v2 hashes; we write whichever we actually know (from the
        // explicit fields or the parsed [ti]) and omit an unknown one rather than
        // emitting an all-zero digest that read() would mistake for a real hash.
        val v1 = atp.infoHashV1 ?: atp.ti?.infoHashV1
        val v2 = atp.infoHashV2 ?: atp.ti?.infoHashV2
        if (v1 != null) d["info-hash"] = Entry.of(v1.toByteArray())
        if (v2 != null) d["info-hash2"] = Entry.of(v2.toByteArray())

        // embed the info dictionary verbatim when we have its raw bytes.
        val info = atp.infoSection
        if (info != null) {
            d["info"] = Entry.Preformatted(info)
            atp.ti?.let { ti ->
                ti.comment?.takeIf { it.isNotEmpty() }?.let { d["comment"] = Entry.of(it) }
                ti.creationDate?.takeIf { it != 0L }?.let { d["creation date"] = Entry.of(it) }
                ti.createdBy?.takeIf { it.isNotEmpty() }?.let { d["created by"] = Entry.of(it) }
            }
        }

        // unfinished pieces: a list of {piece, bitmask} dicts, in ascending piece order
        // (a std::map is ordered; we sort to match).
        if (atp.unfinishedPieces.isNotEmpty()) {
            val up = Entry.list()
            for (pieceIdx in atp.unfinishedPieces.keys.sorted()) {
                val bf = atp.unfinishedPieces.getValue(pieceIdx)
                val pieceStruct = Entry.dict()
                pieceStruct.dict()["piece"] = Entry.of(pieceIdx.toLong())
                // bitmask is rounded up to whole bytes: (bits + 7) / 8.
                pieceStruct.dict()["bitmask"] = Entry.of(bf.data())
                up.items.add(pieceStruct)
            }
            d["unfinished"] = up
        }

        // trackers: tiered list-of-lists (BEP 12 layout), via the same builder as C++.
        d["trackers"] = buildTrackerList(atp.trackers, atp.trackerTiers)

        // web seeds. These keys are always present (possibly empty) so that an explicit
        // removal of web seeds is recorded in the resume data.
        val urlList = Entry.list()
        for (u in atp.urlSeeds) urlList.items.add(Entry.of(u))
        d["url-list"] = urlList

        val httpSeeds = Entry.list()
        for (u in atp.httpSeeds) httpSeeds.items.add(Entry.of(u))
        d["httpseeds"] = httpSeeds

        // pieces bitmask: one byte per piece, bit0 = have, bit1 = verified.
        val pieceCount = maxOf(atp.havePieces.size(), atp.verifiedPieces.size())
        if (pieceCount > 0) {
            val pieces = ByteArray(pieceCount)
            var i = 0
            while (i < atp.havePieces.size()) {
                if (atp.havePieces.getBit(i)) pieces[i] = (pieces[i].toInt() or 1).toByte()
                i++
            }
            i = 0
            while (i < atp.verifiedPieces.size()) {
                if (atp.verifiedPieces.getBit(i)) pieces[i] = (pieces[i].toInt() or 2).toByte()
                i++
            }
            d["pieces"] = Entry.of(pieces)
        } else {
            d["pieces"] = Entry.of(ByteArray(0))
        }

        // renamed (mapped) files: a list indexed by file index; gaps are empty strings.
        if (atp.renamedFiles.isNotEmpty()) {
            val maxIdx = atp.renamedFiles.keys.max()
            val fl = Entry.list()
            for (idx in 0..maxIdx) fl.items.add(Entry.of(atp.renamedFiles[idx] ?: ""))
            d["mapped_files"] = fl
        }

        // peers / banned peers, split by address family into the *6 variants.
        encodePeers(atp.peers)?.let { (v4, v6) ->
            if (v4 != null) d["peers"] = Entry.of(v4)
            if (v6 != null) d["peers6"] = Entry.of(v6)
        }
        encodePeers(atp.bannedPeers)?.let { (v4, v6) ->
            if (v4 != null) d["banned_peers"] = Entry.of(v4)
            if (v6 != null) d["banned_peers6"] = Entry.of(v6)
        }

        d["upload_rate_limit"] = Entry.of(atp.uploadLimit.toLong())
        d["download_rate_limit"] = Entry.of(atp.downloadLimit.toLong())
        d["max_connections"] = Entry.of(atp.maxConnections.toLong())
        d["max_uploads"] = Entry.of(atp.maxUploads.toLong())

        if (atp.filePriorities.isNotEmpty()) {
            val prio = Entry.list()
            for (p in atp.filePriorities) prio.items.add(Entry.of((p and 0xFF).toLong()))
            d["file_priority"] = prio
        }

        if (atp.piecePriorities.isNotEmpty()) {
            val prio = ByteArray(atp.piecePriorities.size) { (atp.piecePriorities[it] and 0xFF).toByte() }
            d["piece_priority"] = Entry.of(prio)
        }

        return ret
    }

    // ---- read ----------------------------------------------------------------

    /**
     * Parse fast-resume [buffer] into an [AddTorrentParams] — the port of the throwing
     * `read_resume_data(span<char const>, ...)` overload. Throws [TorrentException] on a
     * malformed or non-resume buffer (wrong/absent `file-format` tag, missing info-hash,
     * or a mismatching embedded info dict).
     */
    fun read(buffer: ByteArray): AddTorrentParams {
        val rd = Bdecode.decodeOrNull(buffer)
            ?: throw TorrentException(LibtorrentError.PARSE_FAILED)
        return readNode(rd)
    }

    /**
     * Parse an already-decoded resume-data node — the port of
     * `read_resume_data(bdecode_node const&, ...)`.
     */
    fun readNode(rd: BdecodeNode): AddTorrentParams {
        if (rd.type != BdecodeNode.Type.DICT) throw TorrentException(LibtorrentError.NOT_A_DICTIONARY)

        val allocNode = rd.dictFindString("allocation")
        val storageAllocate = allocNode.isValid &&
            (allocNode.stringValue() == "allocate" || allocNode.stringValue() == "full")

        if (rd.dictFindStringValue("file-format") != FILE_FORMAT) {
            throw TorrentException(LibtorrentError.INVALID_FILE_TAG)
        }

        val fileVersion = rd.dictFindIntValue("file-version", 1)
        if (fileVersion != 1L && fileVersion != 2L) {
            throw TorrentException(LibtorrentError.INVALID_FILE_TAG)
        }

        val infoHashBytes = rd.dictFindStringBytes("info-hash")
        val infoHash2Bytes = rd.dictFindStringBytes("info-hash2")
        val v1Len = infoHashBytes?.size ?: 0
        val v2Len = infoHash2Bytes?.size ?: 0
        if (v1Len != Digest32.SHA1_SIZE && v2Len != Digest32.SHA256_SIZE) {
            throw TorrentException(LibtorrentError.MISSING_INFO_HASH)
        }

        val name = rd.dictFindStringValue("name")

        var infoHashV1 = if (v1Len == Digest32.SHA1_SIZE) Digest32.sha1(infoHashBytes!!) else null
        var infoHashV2 = if (v2Len == Digest32.SHA256_SIZE) Digest32.sha256(infoHash2Bytes!!) else null

        // an embedded info dict: verify it matches the declared hash(es) before adopting
        // it, exactly like read_resume_data.
        var infoSection: ByteArray? = null
        var ti: TorrentInfo? = null
        val infoNode = rd.dictFindDict("info")
        if (infoNode.isValid) {
            val raw = infoNode.dataSection()
            val parsed = TorrentInfo.parse(Bencode.encode(synthRoot(raw, rd)))
            val matchesV1 = infoHashV1 == null || parsed.infoHashV1 == infoHashV1
            val matchesV2 = infoHashV2 == null || parsed.infoHashV2 == infoHashV2
            if (matchesV1 && matchesV2) {
                infoSection = raw
                ti = parsed
                // adopt the hashes the metadata actually carries when we didn't have them.
                if (infoHashV1 == null) infoHashV1 = parsed.infoHashV1
                if (infoHashV2 == null) infoHashV2 = parsed.infoHashV2
            } else {
                throw TorrentException(LibtorrentError.MISMATCHING_INFO_HASH)
            }
        }

        val totalUploaded = rd.dictFindIntValue("total_uploaded")
        val totalDownloaded = rd.dictFindIntValue("total_downloaded")

        val activeTime = rd.dictFindIntValue("active_time").toInt()
        val finishedTime = rd.dictFindIntValue("finished_time").toInt()
        val seedingTime = rd.dictFindIntValue("seeding_time").toInt()

        val lastSeenComplete = rd.dictFindIntValue("last_seen_complete")
        val lastDownload = rd.dictFindIntValue("last_download", 0)
        val lastUpload = rd.dictFindIntValue("last_upload", 0)

        val numComplete = rd.dictFindIntValue("num_complete", -1).toInt()
        val numIncomplete = rd.dictFindIntValue("num_incomplete", -1).toInt()
        val numDownloaded = rd.dictFindIntValue("num_downloaded", -1).toInt()

        val maxUploads = rd.dictFindIntValue("max_uploads", -1).toInt()
        val maxConnections = rd.dictFindIntValue("max_connections", -1).toInt()
        val uploadLimit = rd.dictFindIntValue("upload_rate_limit", -1).toInt()
        val downloadLimit = rd.dictFindIntValue("download_rate_limit", -1).toInt()

        // torrent flags: start from the defaults and apply each saved bool the way
        // apply_flag does (absent ⇒ keep the default for that bit).
        var flags = TorrentFlags.DEFAULT_FLAGS
        flags = applyFlag(flags, rd, "seed_mode", TorrentFlags.SEED_MODE)
        flags = applyFlag(flags, rd, "upload_mode", TorrentFlags.UPLOAD_MODE)
        flags = applyFlag(flags, rd, "share_mode", TorrentFlags.SHARE_MODE)
        flags = applyFlag(flags, rd, "apply_ip_filter", TorrentFlags.APPLY_IP_FILTER)
        flags = applyFlag(flags, rd, "paused", TorrentFlags.PAUSED)
        flags = applyFlag(flags, rd, "auto_managed", TorrentFlags.AUTO_MANAGED)
        flags = applyFlag(flags, rd, "super_seeding", TorrentFlags.SUPER_SEEDING)
        flags = applyFlag(flags, rd, "sequential_download", TorrentFlags.SEQUENTIAL_DOWNLOAD)
        flags = applyFlag(flags, rd, "stop_when_ready", TorrentFlags.STOP_WHEN_READY)
        flags = applyFlag(flags, rd, "disable_dht", TorrentFlags.DISABLE_DHT)
        flags = applyFlag(flags, rd, "disable_lsd", TorrentFlags.DISABLE_LSD)
        flags = applyFlag(flags, rd, "disable_pex", TorrentFlags.DISABLE_PEX)

        val savePath = rd.dictFindStringValue("save_path")

        // mapped (renamed) files
        val renamedFiles = LinkedHashMap<Int, String>()
        val mappedFiles = rd.dictFindList("mapped_files")
        if (mappedFiles.isValid) {
            for (i in 0 until mappedFiles.listSize()) {
                val newName = mappedFiles.listStringValueAt(i)
                if (newName.isEmpty()) continue
                renamedFiles[i] = newName
            }
        }

        val addedTime = rd.dictFindIntValue("added_time", 0)
        val completedTime = rd.dictFindIntValue("completed_time", 0)

        // file priorities; clear seed mode if any file is set to dont_download.
        val filePriorities = ArrayList<Int>()
        val filePriorityNode = rd.dictFindList("file_priority")
        if (filePriorityNode.isValid) {
            for (i in 0 until filePriorityNode.listSize()) {
                val raw = filePriorityNode.listIntValueAt(i, DownloadPriority.DEFAULT_PRIORITY.toLong())
                val p = clampPriority((raw and 0xFF).toInt())
                filePriorities.add(p)
                if (p == DownloadPriority.DONT_DOWNLOAD) flags = flags and TorrentFlags.SEED_MODE.inv()
            }
        }

        // trackers: a present list means it overrides the .torrent's trackers.
        val trackers = ArrayList<String>()
        val trackerTiers = ArrayList<Int>()
        val trackersNode = rd.dictFindList("trackers")
        if (trackersNode.isValid) {
            flags = flags or TorrentFlags.OVERRIDE_TRACKERS
            var tier = 0
            for (i in 0 until trackersNode.listSize()) {
                val tierList = trackersNode.listAt(i)
                if (tierList.type != BdecodeNode.Type.LIST) continue
                for (j in 0 until tierList.listSize()) {
                    trackers.add(tierList.listStringValueAt(j))
                    trackerTiers.add(tier)
                }
                tier++
            }
        }

        // web seeds: a present url-list/httpseeds means they override the .torrent's.
        val urlListNode = rd.dictFindList("url-list")
        val httpSeedsNode = rd.dictFindList("httpseeds")
        if (urlListNode.isValid || httpSeedsNode.isValid) {
            flags = flags or TorrentFlags.OVERRIDE_WEB_SEEDS
        }
        val urlSeeds = ArrayList<String>()
        if (urlListNode.isValid) {
            for (i in 0 until urlListNode.listSize()) {
                val u = urlListNode.listStringValueAt(i)
                if (u.isNotEmpty()) urlSeeds.add(u)
            }
        }
        val httpSeeds = ArrayList<String>()
        if (httpSeedsNode.isValid) {
            for (i in 0 until httpSeedsNode.listSize()) {
                val u = httpSeedsNode.listStringValueAt(i)
                if (u.isNotEmpty()) httpSeeds.add(u)
            }
        }

        // pieces bitmask
        val havePieces = Bitfield()
        val verifiedPieces = Bitfield()
        val piecesNode = rd.dictFindString("pieces")
        if (piecesNode.isValid) {
            val bytes = piecesNode.stringBytes()
            if (fileVersion == 1L) {
                val len = bytes.size
                havePieces.resize(len)
                verifiedPieces.resize(len)
                var anyVerified = false
                for (i in 0 until len) {
                    if ((bytes[i].toInt() and 1) != 0) havePieces.setBit(i) else havePieces.clearBit(i)
                    if ((bytes[i].toInt() and 2) != 0) {
                        verifiedPieces.setBit(i)
                        anyVerified = true
                    } else {
                        verifiedPieces.clearBit(i)
                    }
                }
                if (!anyVerified) verifiedPieces.clear()
            } else {
                // file-version 2: a packed have-bitfield, 8 pieces per byte.
                havePieces.assign(bytes, bytes.size * 8)
            }
        }

        // a standalone "verified" packed bitfield (file-version 2 style)
        val verifiedNode = rd.dictFindString("verified")
        if (verifiedNode.isValid) {
            val bytes = verifiedNode.stringBytes()
            verifiedPieces.assign(bytes, bytes.size * 8)
        }

        // piece priorities
        val piecePriorities = ArrayList<Int>()
        val piecePriorityNode = rd.dictFindString("piece_priority")
        if (piecePriorityNode.isValid) {
            val bytes = piecePriorityNode.stringBytes()
            for (b in bytes) piecePriorities.add(clampPriority(b.toInt() and 0xFF))
        }

        // peers / banned peers
        val peers = ArrayList<Endpoint>()
        decodePeersV4(rd.dictFindString("peers"), peers)
        decodePeersV6(rd.dictFindString("peers6"), peers)
        val bannedPeers = ArrayList<Endpoint>()
        decodePeersV4(rd.dictFindString("banned_peers"), bannedPeers)
        decodePeersV6(rd.dictFindString("banned_peers6"), bannedPeers)

        // unfinished pieces
        val unfinishedPieces = LinkedHashMap<Int, Bitfield>()
        val unfinishedNode = rd.dictFindList("unfinished")
        if (unfinishedNode.isValid) {
            for (i in 0 until unfinishedNode.listSize()) {
                val e = unfinishedNode.listAt(i)
                if (e.type != BdecodeNode.Type.DICT) continue
                val piece = e.dictFindIntValue("piece", -1).toInt()
                if (piece < 0) continue
                val bitmask = e.dictFindStringBytes("bitmask") ?: continue
                if (bitmask.isEmpty()) continue
                unfinishedPieces[piece] = Bitfield.fromBytes(bitmask, bitmask.size * 8)
            }
        }

        // loading from resume data implies no immediate re-save is needed.
        flags = flags and TorrentFlags.NEED_SAVE_RESUME.inv()

        return AddTorrentParams(
            ti = ti,
            infoSection = infoSection,
            infoHashV1 = infoHashV1,
            infoHashV2 = infoHashV2,
            name = name,
            savePath = savePath,
            trackers = trackers,
            trackerTiers = trackerTiers,
            urlSeeds = urlSeeds,
            httpSeeds = httpSeeds,
            peers = peers,
            bannedPeers = bannedPeers,
            filePriorities = filePriorities,
            piecePriorities = piecePriorities,
            havePieces = havePieces,
            verifiedPieces = verifiedPieces,
            unfinishedPieces = unfinishedPieces,
            renamedFiles = renamedFiles,
            flags = flags,
            storageModeAllocate = storageAllocate,
            maxUploads = maxUploads,
            maxConnections = maxConnections,
            uploadLimit = uploadLimit,
            downloadLimit = downloadLimit,
            totalUploaded = totalUploaded,
            totalDownloaded = totalDownloaded,
            activeTime = activeTime,
            finishedTime = finishedTime,
            seedingTime = seedingTime,
            addedTime = addedTime,
            completedTime = completedTime,
            lastSeenComplete = lastSeenComplete,
            lastDownload = lastDownload,
            lastUpload = lastUpload,
            numComplete = numComplete,
            numIncomplete = numIncomplete,
            numDownloaded = numDownloaded,
        )
    }

    // ---- helpers -------------------------------------------------------------

    private fun boolEntry(b: Boolean): Entry = Entry.of(if (b) 1L else 0L)

    /** Clamp a raw priority byte into libtorrent's `[dont_download, top_priority]` range. */
    private fun clampPriority(v: Int): Int = when {
        v < DownloadPriority.DONT_DOWNLOAD -> DownloadPriority.DONT_DOWNLOAD
        v > DownloadPriority.TOP_PRIORITY -> DownloadPriority.TOP_PRIORITY
        else -> v
    }

    /**
     * `apply_flag`: if the saved value is absent, keep the bit's default; if present and
     * 0, clear the bit; otherwise set it.
     */
    private fun applyFlag(current: Long, rd: BdecodeNode, name: String, flag: Long): Long {
        val default = if ((flag and TorrentFlags.DEFAULT_FLAGS) != 0L) 1L else 0L
        return if (rd.dictFindIntValue(name, default) == 0L) current and flag.inv() else current or flag
    }

    /**
     * `build_tracker_list`: turn the flat (url, tier) pair-arrays into the BEP-12
     * list-of-tiers, each tier a list of URLs. Tiers are clamped to `[0, 1024]` and the
     * outer list is grown to hold the highest tier seen.
     */
    private fun buildTrackerList(trackers: List<String>, tiers: List<Int>): Entry {
        val ret = Entry.list()
        if (trackers.isEmpty()) return ret
        // start with one (empty) tier, matching the C++ seed of tr_list.
        ret.items.add(Entry.list())
        var tier = 0
        for (i in trackers.indices) {
            if (i < tiers.size) {
                tier = tiers[i]
                if (tier < 0) tier = 0
                if (tier > 1024) tier = 1024
            }
            while (ret.items.size <= tier) ret.items.add(Entry.list())
            (ret.items[tier] as Entry.ListEntry).items.add(Entry.of(trackers[i]))
        }
        return ret
    }

    /**
     * Encode a peer list into the (v4, v6) wire blobs. Returns null when [list] is empty
     * (so the keys are omitted entirely, as in C++). Either component may itself be null
     * when no peer of that family is present.
     */
    private fun encodePeers(list: List<Endpoint>): Pair<ByteArray?, ByteArray?>? {
        if (list.isEmpty()) return null
        val v4 = ByteArrayBuilder(32)
        val v6 = ByteArrayBuilder(32)
        var hasV4 = false
        var hasV6 = false
        for (p in list) {
            if (p.isV6) {
                writeEndpoint(v6, p); hasV6 = true
            } else {
                writeEndpoint(v4, p); hasV4 = true
            }
        }
        return (if (hasV4) v4.toByteArray() else null) to (if (hasV6) v6.toByteArray() else null)
    }

    /** `write_endpoint`: address bytes in network order, then the port as big-endian uint16. */
    private fun writeEndpoint(out: ByteArrayBuilder, e: Endpoint) {
        out.append(e.address.bytes)
        out.append(((e.port ushr 8) and 0xFF).toByte())
        out.append((e.port and 0xFF).toByte())
    }

    private fun decodePeersV4(node: BdecodeNode, into: MutableList<Endpoint>) =
        decodePeers(node, 4, into)

    private fun decodePeersV6(node: BdecodeNode, into: MutableList<Endpoint>) =
        decodePeers(node, 16, into)

    /**
     * Read a packed peer blob of fixed-width entries (`addrLen` address bytes + 2 port
     * bytes), the port of the `read_v4_endpoint` / `read_v6_endpoint` loops. Trailing
     * bytes that don't form a whole entry are ignored, matching the C++ stride loop.
     */
    private fun decodePeers(node: BdecodeNode, addrLen: Int, into: MutableList<Endpoint>) {
        if (!node.isValid) return
        val bytes = node.stringBytes()
        val stride = addrLen + 2
        var off = 0
        while (off + stride <= bytes.size) {
            // The address is `addrLen` raw bytes; reformatting them as text and parsing
            // round-trips through [PeerAddress] and always succeeds for a 4- or 16-byte
            // input, but stay defensive and just skip an entry that somehow doesn't.
            val addr = PeerAddress.parseOrNull(addrToString(bytes, off, addrLen))
            if (addr != null) {
                val port = ((bytes[off + addrLen].toInt() and 0xFF) shl 8) or
                    (bytes[off + addrLen + 1].toInt() and 0xFF)
                into.add(Endpoint(addr, port))
            }
            off += stride
        }
    }

    /** Format `len` raw address bytes at [off] as the dotted-quad / colon-hex text PeerAddress parses. */
    private fun addrToString(bytes: ByteArray, off: Int, len: Int): String {
        if (len == 4) {
            return buildString {
                for (i in 0 until 4) {
                    if (i > 0) append('.')
                    append(bytes[off + i].toInt() and 0xFF)
                }
            }
        }
        // IPv6: eight colon-separated 16-bit hex groups (no ::-compression needed; the
        // parser accepts the fully-expanded form).
        return buildString {
            for (g in 0 until 8) {
                if (g > 0) append(':')
                val hi = bytes[off + g * 2].toInt() and 0xFF
                val lo = bytes[off + g * 2 + 1].toInt() and 0xFF
                append(((hi shl 8) or lo).toString(16))
            }
        }
    }

    /**
     * Wrap the raw `info`-dict bytes [infoRaw] in a synthetic root dictionary so it can
     * be handed to [TorrentInfo.parse] (which needs a top-level dict containing `info`).
     * The surrounding `announce` / `announce-list` / `url-list` / `nodes` keys are copied
     * across from the resume node [rd] when present, so the resulting [TorrentInfo] also
     * carries the torrent-file-level trackers, web seeds and DHT nodes (resume-level
     * trackers/web-seeds are applied separately to the atp fields).
     */
    private fun synthRoot(infoRaw: ByteArray, rd: BdecodeNode): Entry {
        val root = Entry.dict()
        root.dict()["info"] = Entry.Preformatted(infoRaw)
        for (key in listOf("announce", "announce-list", "url-list", "nodes", "comment", "created by", "creation date")) {
            val n = rd.dictFind(key)
            if (n.isValid) root.dict()[key] = Entry.from(n)
        }
        return root
    }
}
