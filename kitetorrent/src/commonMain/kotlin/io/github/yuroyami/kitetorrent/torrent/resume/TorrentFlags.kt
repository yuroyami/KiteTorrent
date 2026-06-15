package io.github.yuroyami.kitetorrent.torrent.resume

/**
 * The `torrent_flags_t` bit constants — pure-Kotlin port of libtorrent's
 * `torrent_flags` namespace (include/libtorrent/torrent_flags.hpp).
 *
 * In libtorrent these are `bitfield_flag<std::uint64_t>` values (`0_bit`, `1_bit`,
 * …). KiteTorrent carries the same 64-bit mask as a plain [Long]; each constant is
 * `1L shl bit` for the same bit index libtorrent assigns, so a flag word is
 * byte-for-byte the same value as in C++.
 *
 * Only the flags that survive into / out of fast-resume are exercised by
 * [io.github.yuroyami.kitetorrent.torrent.resume.ResumeData], but the full set is
 * reproduced for fidelity and so callers can configure an
 * [AddTorrentParams] exactly as they would the C++ struct. The deprecated
 * `TORRENT_ABI_VERSION == 1` flags (`pinned`, `override_resume_data`,
 * `merge_resume_trackers`, `use_resume_save_path`, `merge_resume_http_seeds`) are
 * intentionally omitted: this port targets the non-deprecated build, matching
 * [io.github.yuroyami.kitetorrent.error.LibtorrentError]'s `TORRENT_ABI_VERSION != 1`
 * choice, and [defaultFlags] correspondingly excludes them.
 */
object TorrentFlags {
    /** Assume all files are present and valid; verify lazily on first request (`0_bit`). */
    const val SEED_MODE: Long = 1L shl 0

    /** Start in upload-only mode: make no piece requests (`1_bit`). */
    const val UPLOAD_MODE: Long = 1L shl 1

    /** Share-mode: only upload, never download more than uploaded (`2_bit`). */
    const val SHARE_MODE: Long = 1L shl 2

    /** Subject this torrent to the session IP filter (`3_bit`). On by default. */
    const val APPLY_IP_FILTER: Long = 1L shl 3

    /** The torrent is paused: it won't contact the tracker or any peers (`4_bit`). */
    const val PAUSED: Long = 1L shl 4

    /** Let libtorrent's queuing logic start/stop/seed this torrent automatically (`5_bit`). */
    const val AUTO_MANAGED: Long = 1L shl 5

    /**
     * It's an error to add a torrent already in the session (`6_bit`). Add-time only;
     * not saved by `write_resume_data`.
     */
    const val DUPLICATE_IS_ERROR: Long = 1L shl 6

    /** Include this torrent in `post_torrent_updates()` (`7_bit`). On by default; not saved. */
    const val UPDATE_SUBSCRIBE: Long = 1L shl 7

    /** Super-seeding / initial-seeding mode (`8_bit`). */
    const val SUPER_SEEDING: Long = 1L shl 8

    /** Pick low-index pieces before high-index ones (`9_bit`). */
    const val SEQUENTIAL_DOWNLOAD: Long = 1L shl 9

    /** Force-stop on the edge from non-transferring to transferring state (`10_bit`). */
    const val STOP_WHEN_READY: Long = 1L shl 10

    /**
     * Trackers in [AddTorrentParams] override those in the .torrent (`11_bit`).
     * Set by `read_resume_data` when the resume data carries a trackers list; not saved.
     */
    const val OVERRIDE_TRACKERS: Long = 1L shl 11

    /**
     * Web seeds in [AddTorrentParams] override those in the .torrent (`12_bit`).
     * Set by `read_resume_data` when the resume data carries web seeds; not saved.
     */
    const val OVERRIDE_WEB_SEEDS: Long = 1L shl 12

    /**
     * The torrent needs to (re-)save its resume data (`13_bit`). On by default; this is
     * an ephemeral running-torrent state and is cleared by `read_resume_data`; not saved.
     */
    const val NEED_SAVE_RESUME: Long = 1L shl 13

    /** Disable DHT for this torrent only (`19_bit`). */
    const val DISABLE_DHT: Long = 1L shl 19

    /** Disable local service discovery for this torrent only (`20_bit`). */
    const val DISABLE_LSD: Long = 1L shl 20

    /** Disable peer exchange for this torrent only (`21_bit`). */
    const val DISABLE_PEX: Long = 1L shl 21

    /** Trust the resume data without re-checking files on disk (`22_bit`). */
    const val NO_VERIFY_FILES: Long = 1L shl 22

    /** Default any file not covered by `file_priorities` to `dont_download` (`23_bit`). */
    const val DEFAULT_DONT_DOWNLOAD: Long = 1L shl 23

    /** Treat as an i2p torrent for the `allow_i2p_mixed` setting (`24_bit`). */
    const val I2P_TORRENT: Long = 1L shl 24

    /**
     * The flags an `add_torrent_params` is constructed with — the port of
     * `torrent_flags::default_flags` for `TORRENT_ABI_VERSION != 1`:
     * `update_subscribe | auto_managed | paused | apply_ip_filter | need_save_resume`.
     */
    const val DEFAULT_FLAGS: Long =
        UPDATE_SUBSCRIBE or AUTO_MANAGED or PAUSED or APPLY_IP_FILTER or NEED_SAVE_RESUME

    /** True if [flag] (a single-bit mask) is set in [flags]. */
    fun has(flags: Long, flag: Long): Boolean = (flags and flag) != 0L
}
