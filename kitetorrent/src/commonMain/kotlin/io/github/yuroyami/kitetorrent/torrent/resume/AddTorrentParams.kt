package io.github.yuroyami.kitetorrent.torrent.resume

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.peer.PeerAddress
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo

/**
 * One IP endpoint — an address plus a TCP port — the pure-Kotlin stand-in for the
 * `tcp::endpoint` values libtorrent stores in `add_torrent_params::peers` and
 * `banned_peers`. On the wire (and in resume data) an endpoint is the address bytes
 * in network order followed by a 16-bit big-endian port: 6 bytes for IPv4, 18 for
 * IPv6 (see `write_endpoint` / `read_v*_endpoint` in socket_io.hpp).
 *
 * @property address the peer's IP, as a parsed [PeerAddress] (4- or 16-byte).
 * @property port the TCP port, 0..65535.
 */
data class Endpoint(val address: PeerAddress, val port: Int) {
    /** True if this is an IPv6 endpoint (18-byte wire form), per `is_v6(endpoint)`. */
    val isV6: Boolean get() = address.isV6
}

/**
 * The pure-Kotlin port of libtorrent's `add_torrent_params`
 * (include/libtorrent/add_torrent_params.hpp): all the information needed to add a
 * torrent to a session, and the in-memory shape that fast-resume data round-trips
 * through.
 *
 * This is the *full* config object — the counterpart of the C++ struct — and is the
 * type [ResumeData.write]/[ResumeData.read] serialise. (KiteTorrent already has a
 * smaller, load-focused `AddTorrentParams` in
 * [io.github.yuroyami.kitetorrent.torrent] produced by `LoadTorrent`; this resume
 * variant lives in its own package to keep both faithful to the names libtorrent
 * uses without clashing.)
 *
 * Fidelity notes / deliberate omissions:
 *  - [ti] is the parsed metadata when known; [infoSection] holds the *raw* bencoded
 *    `info` dictionary bytes. `write_resume_data` embeds those bytes verbatim under
 *    the `info` key (`entry["info"].preformatted()`), and `read_resume_data` recovers
 *    them from a present `info` dict — so [infoSection] is what actually crosses the
 *    serialisation boundary, while [ti] is the convenient decoded view.
 *  - Session-runtime-only and extension fields from the C++ struct
 *    (`userdata`, `extensions`, the deprecated `url`/`resume_data`) are not modelled.
 *  - The v2 merkle-tree fields (`merkle_trees`, `merkle_tree_mask`,
 *    `verified_leaf_hashes`) are out of scope for this resume port; the `MerkleTree`
 *    machinery owns that reconstruction. The `trees` resume key is therefore not
 *    emitted or parsed here.
 *
 * Every list/map defaults to empty and every scalar to libtorrent's documented
 * default (e.g. the rate/connection limits to `-1` = unlimited, the scrape counts to
 * `-1` = unknown, [flags] to [TorrentFlags.DEFAULT_FLAGS]).
 */
data class AddTorrentParams(
    // --- core identity ---------------------------------------------------------
    /** Parsed torrent metadata, when available (the immutable info-dict view). */
    val ti: TorrentInfo? = null,
    /**
     * Raw bencoded bytes of the `info` dictionary, when available. This is the
     * preimage of the info-hash and what gets embedded in / read from resume data.
     */
    val infoSection: ByteArray? = null,
    /** v1 info-hash (SHA-1), when known even without full metadata. */
    val infoHashV1: Sha1Hash? = null,
    /** v2 info-hash (SHA-256), when known. */
    val infoHashV2: Sha256Hash? = null,
    /** Display name used while the torrent has no metadata. */
    val name: String = "",
    /** Where the torrent's files are / will be stored. */
    val savePath: String = "",

    // --- trackers / sources ----------------------------------------------------
    /** Tracker announce URLs (flat list, aligned 1:1 with [trackerTiers]). */
    val trackers: List<String> = emptyList(),
    /** Tier index for each entry of [trackers]; empty ⇒ all tier 0. */
    val trackerTiers: List<Int> = emptyList(),
    /** DHT bootstrap nodes as host/port pairs. */
    val dhtNodes: List<Pair<String, Int>> = emptyList(),
    /** BEP 19 "get right" web seeds (`url-list`). */
    val urlSeeds: List<String> = emptyList(),
    /** BEP 17 HTTP seeds (`httpseeds`). */
    val httpSeeds: List<String> = emptyList(),
    /** Default tracker id used when announcing (empty ⇒ none). */
    val trackerId: String = "",

    // --- peers -----------------------------------------------------------------
    /** Peers to try connecting to. */
    val peers: List<Endpoint> = emptyList(),
    /** Peers banned from this torrent. */
    val bannedPeers: List<Endpoint> = emptyList(),

    // --- priorities ------------------------------------------------------------
    /** Per-file download priorities (0..7); shorter than the file count ⇒ rest default. */
    val filePriorities: List<Int> = emptyList(),
    /** Per-piece download priorities (0..7). File priorities take precedence. */
    val piecePriorities: List<Int> = emptyList(),

    // --- progress / verification ----------------------------------------------
    /** Pieces we already have. */
    val havePieces: Bitfield = Bitfield(),
    /** In seed mode, pieces verified valid. */
    val verifiedPieces: Bitfield = Bitfield(),
    /** Partially downloaded pieces: piece index → 16 KiB-block bitfield (set ⇒ have block). */
    val unfinishedPieces: Map<Int, Bitfield> = emptyMap(),
    /** File index → new filename to apply before adding (renamed/relocated files). */
    val renamedFiles: Map<Int, String> = emptyMap(),

    // --- flags & storage -------------------------------------------------------
    /** `torrent_flags_t` mask; see [TorrentFlags]. Defaults to [TorrentFlags.DEFAULT_FLAGS]. */
    val flags: Long = TorrentFlags.DEFAULT_FLAGS,
    /** Storage allocation mode; `true` ⇒ allocate (full), `false` ⇒ sparse (default). */
    val storageModeAllocate: Boolean = false,

    // --- limits ----------------------------------------------------------------
    /** Max simultaneous uploads; -1 ⇒ unlimited. */
    val maxUploads: Int = -1,
    /** Max peer connections; -1 ⇒ unlimited. */
    val maxConnections: Int = -1,
    /** Upload rate cap in bytes/sec; -1 ⇒ unlimited. */
    val uploadLimit: Int = -1,
    /** Download rate cap in bytes/sec; -1 ⇒ unlimited. */
    val downloadLimit: Int = -1,

    // --- accumulated stats -----------------------------------------------------
    /** Total bytes uploaded so far. */
    val totalUploaded: Long = 0,
    /** Total bytes downloaded so far. */
    val totalDownloaded: Long = 0,
    /** Seconds spent in the started state. */
    val activeTime: Int = 0,
    /** Seconds spent finished. */
    val finishedTime: Int = 0,
    /** Seconds spent seeding. */
    val seedingTime: Int = 0,

    // --- timestamps (POSIX seconds) -------------------------------------------
    /** When the torrent was first added; 0 ⇒ set on add. */
    val addedTime: Long = 0,
    /** When the download completed; 0 ⇒ unknown. */
    val completedTime: Long = 0,
    /** When a complete copy was last seen in the swarm; 0 ⇒ unknown. */
    val lastSeenComplete: Long = 0,
    /** When payload was last downloaded; 0 ⇒ never/unknown. */
    val lastDownload: Long = 0,
    /** When payload was last uploaded; 0 ⇒ never/unknown. */
    val lastUpload: Long = 0,

    // --- cached scrape data ----------------------------------------------------
    /** Seeds in the swarm; -1 ⇒ unknown. */
    val numComplete: Int = -1,
    /** Non-seed peers in the swarm; -1 ⇒ unknown. */
    val numIncomplete: Int = -1,
    /** Number of completed downloads reported; -1 ⇒ unknown. */
    val numDownloaded: Int = -1,
) {
    /** True if this flag (a single-bit mask from [TorrentFlags]) is set. */
    fun hasFlag(flag: Long): Boolean = (flags and flag) != 0L

    // The data-class-generated equals would compare [infoSection] (a ByteArray) by
    // reference. Override so every field — including the byte array (by content) and
    // the Bitfields (which already have value equality) — compares structurally.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AddTorrentParams) return false
        return contentEqualsNullable(infoSection, other.infoSection) && allFieldsEqual(other)
    }

    private fun allFieldsEqual(o: AddTorrentParams): Boolean =
        ti == o.ti && infoHashV1 == o.infoHashV1 && infoHashV2 == o.infoHashV2 &&
            name == o.name && savePath == o.savePath &&
            trackers == o.trackers && trackerTiers == o.trackerTiers &&
            dhtNodes == o.dhtNodes && urlSeeds == o.urlSeeds && httpSeeds == o.httpSeeds &&
            trackerId == o.trackerId && peers == o.peers && bannedPeers == o.bannedPeers &&
            filePriorities == o.filePriorities && piecePriorities == o.piecePriorities &&
            havePieces == o.havePieces && verifiedPieces == o.verifiedPieces &&
            unfinishedPieces == o.unfinishedPieces && renamedFiles == o.renamedFiles &&
            flags == o.flags && storageModeAllocate == o.storageModeAllocate &&
            maxUploads == o.maxUploads && maxConnections == o.maxConnections &&
            uploadLimit == o.uploadLimit && downloadLimit == o.downloadLimit &&
            totalUploaded == o.totalUploaded && totalDownloaded == o.totalDownloaded &&
            activeTime == o.activeTime && finishedTime == o.finishedTime &&
            seedingTime == o.seedingTime && addedTime == o.addedTime &&
            completedTime == o.completedTime && lastSeenComplete == o.lastSeenComplete &&
            lastDownload == o.lastDownload && lastUpload == o.lastUpload &&
            numComplete == o.numComplete && numIncomplete == o.numIncomplete &&
            numDownloaded == o.numDownloaded

    override fun hashCode(): Int {
        var h = ti?.hashCode() ?: 0
        h = 31 * h + (infoSection?.contentHashCode() ?: 0)
        h = 31 * h + name.hashCode()
        h = 31 * h + savePath.hashCode()
        h = 31 * h + trackers.hashCode()
        h = 31 * h + flags.hashCode()
        h = 31 * h + peers.hashCode()
        return h
    }

    companion object {
        private fun contentEqualsNullable(a: ByteArray?, b: ByteArray?): Boolean =
            if (a == null || b == null) a === b else a.contentEquals(b)
    }
}
