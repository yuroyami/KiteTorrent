package io.github.yuroyami.kitetorrent.torrent

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode

/**
 * The add-a-torrent payload produced by [LoadTorrent]. This is the pure-Kotlin analogue of the
 * fields libtorrent's `load_torrent_*` functions populate in an `add_torrent_params`
 * (src/load_torrent.cpp, `update_atp`).
 *
 * In libtorrent, `load_torrent_buffer` / `load_torrent_file` build a `torrent_info` and
 * then *drain* the non-info-dictionary metadata (trackers, web seeds, DHT nodes) out of
 * it into the surrounding `add_torrent_params`, so the session sees a single object with
 * both the torrent and its session-level hints. KiteTorrent has no session type yet, so
 * the same drained view is bundled here alongside the [ti] it came from.
 *
 * The fields are the ones `update_atp` moves across:
 *  - [trackers] / [trackerTiers] ← the announce URLs and their tiers,
 *  - [urlSeeds] ← `web_seed_entry::url_seed` entries (BEP 19),
 *  - [dhtNodes] ← the `nodes` bootstrap list,
 *  - [infoHashes] convenience pair ← `ti->info_hashes()`.
 *
 * Per-file merkle tree reconstruction and piece-layer verification (the second half of
 * `update_atp`) are **not** reproduced here. See the note in [LoadTorrent].
 */
class AddTorrentParams internal constructor(
    /** The parsed torrent metadata. */
    val ti: TorrentInfo,
    /** Flat list of announce URLs (all tiers), in tier order. */
    val trackers: List<String>,
    /** Tier index for each entry of [trackers], aligned 1:1. */
    val trackerTiers: List<Int>,
    /** BEP 19 web seeds (`url-list`). */
    val urlSeeds: List<String>,
    /** DHT bootstrap nodes (`nodes`): host/port pairs. */
    val dhtNodes: List<Pair<String, Int>>,
) {
    /** v1 info-hash (SHA-1), or null for a pure-v2 torrent. */
    val infoHashV1: Sha1Hash? get() = ti.infoHashV1
    /** v2 info-hash (SHA-256), or null for a v1-only torrent. */
    val infoHashV2: Sha256Hash? get() = ti.infoHashV2
    /** Convenience pair of (v1, v2) info-hashes, mirroring `info_hashes_t`. */
    val infoHashes: Pair<Sha1Hash?, Sha256Hash?> get() = ti.infoHashV1 to ti.infoHashV2

    /** The torrent's display name (`name` from the info dict). */
    val name: String get() = ti.name
}

/**
 * Thin loaders that turn raw `.torrent` bytes (or an already-decoded node) into a parsed
 * torrent. This is the pure-Kotlin port of libtorrent's src/load_torrent.cpp
 * (`load_torrent_buffer` / `load_torrent_parsed`, and the `update_atp` drain step).
 *
 * libtorrent's loaders are deliberately thin wrappers around the `torrent_info`
 * constructor; the heavy lifting (bdecoding, hashing the info dict, building the file
 * list) lives in `torrent_info`, which KiteTorrent already implements as [TorrentInfo].
 * So [loadTorrent] is simply `TorrentInfo.parse`, and [loadTorrentParsed] /
 * [loadTorrentBuffer] add the `update_atp` step: lifting the announce list, web seeds and
 * DHT nodes out of the parsed torrent into an [AddTorrentParams].
 *
 * **Scope note (faithful omission):** the second half of `update_atp` rebuilds the v2
 * per-file merkle trees from the torrent's piece layers and verifies each computed root
 * against the file's `pieces root`, throwing `torrent_invalid_piece_layer` on mismatch.
 * That depends on `torrent_info` internals (`piece_layer(f)`, `_internal_drain()`,
 * `free_piece_layers()`) that [TorrentInfo] does not expose, and on the `MerkleTree`
 * reconstruction machinery. It is intentionally left out of this thin core helper rather
 * than reaching into private state; the [MerkleTree] / [Merkle] types already provide the
 * root computation when that verification step is wired up later.
 */
object LoadTorrent {

    /**
     * Parse a `.torrent` file from its raw bencoded [buffer] into a [TorrentInfo].
     * Equivalent to libtorrent constructing a `torrent_info` from a buffer, which is the
     * core of `load_torrent_buffer` minus the session-level drain. Identical to
     * [TorrentInfo.parse]; provided under the load_torrent name for parity with the C++
     * API surface.
     */
    fun loadTorrent(buffer: ByteArray): TorrentInfo = TorrentInfo.parse(buffer)

    /**
     * Full `load_torrent_buffer`: parse [buffer] and drain its session-level metadata
     * into an [AddTorrentParams] (see [AddTorrentParams] and the scope note on
     * [LoadTorrent] for what is and isn't carried across).
     */
    fun loadTorrentBuffer(buffer: ByteArray): AddTorrentParams =
        updateAtp(TorrentInfo.parse(buffer))

    /**
     * `load_torrent_parsed`: build an [AddTorrentParams] from an already-decoded root
     * torrent dictionary [node], avoiding a re-decode when the caller already has one.
     */
    fun loadTorrentParsed(node: BdecodeNode): AddTorrentParams =
        updateAtp(TorrentInfo.fromRoot(node))

    /**
     * The drain step of libtorrent's `update_atp`: copy the announce list (with tiers),
     * the url-seed web seeds and the DHT nodes out of [ti] into a fresh
     * [AddTorrentParams].
     *
     * [TorrentInfo] already exposes trackers as a list of tiers (each inner list a tier);
     * here they are flattened to the parallel `(url, tier)` representation libtorrent's
     * `add_torrent_params` uses. The tier index is the position of the inner list, exactly
     * as `torrent_info`'s announce parsing assigns it.
     */
    private fun updateAtp(ti: TorrentInfo): AddTorrentParams {
        val trackers = ArrayList<String>()
        val tiers = ArrayList<Int>()
        ti.trackers.forEachIndexed { tier, group ->
            for (url in group) {
                trackers.add(url)
                tiers.add(tier)
            }
        }
        return AddTorrentParams(
            ti = ti,
            trackers = trackers,
            trackerTiers = tiers,
            urlSeeds = ti.webSeeds,
            dhtNodes = ti.dhtNodes,
        )
    }
}
