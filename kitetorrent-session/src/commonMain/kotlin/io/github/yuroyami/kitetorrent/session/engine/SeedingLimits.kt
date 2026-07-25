package io.github.yuroyami.kitetorrent.session.engine

import kotlin.math.max

/**
 * The pure "stop seeding?" decision. This is the port of libtorrent's share-ratio and
 * seed-time limit checks (`session_impl::recalculate_auto_managed_torrents`,
 * `torrent::seed_ratio` / `is_seed_limit_reached`). Kept side-effect-free so the policy is
 * exhaustively unit-testable; [io.github.yuroyami.kitetorrent.session.engine.TorrentSession]
 * consults it on its maintenance tick and pauses when it returns true.
 */
object SeedingLimits {

    /** Sentinel for "no limit" in both the per-mille ratio and the seconds-valued time limit. */
    const val UNLIMITED = 0

    /**
     * @param uploaded total bytes uploaded for this torrent.
     * @param downloaded total bytes downloaded (0 for a torrent added as a finished seed).
     * @param totalSize the torrent's size. It is the floor for the ratio denominator, so a
     *   never-downloaded seed still has a meaningful ratio ("upload N× the torrent size").
     * @param seedingSeconds seconds spent in the seeding state.
     * @param ratioLimitPerMille share-ratio cap in per-mille (2000 = 2.0×); [UNLIMITED] = off.
     * @param seedTimeLimitSeconds seed-time cap in seconds; [UNLIMITED] = off.
     * @return true once either active limit is met.
     */
    fun shouldStopSeeding(
        uploaded: Long,
        downloaded: Long,
        totalSize: Long,
        seedingSeconds: Long,
        ratioLimitPerMille: Int,
        seedTimeLimitSeconds: Int,
    ): Boolean {
        if (ratioLimitPerMille > UNLIMITED) {
            val denom = max(max(downloaded, totalSize), 1L)
            // uploaded/denom >= ratio/1000  ⇔  uploaded*1000 >= denom*ratio (integer-safe)
            if (uploaded * 1000L >= denom * ratioLimitPerMille.toLong()) return true
        }
        if (seedTimeLimitSeconds > UNLIMITED && seedingSeconds >= seedTimeLimitSeconds) return true
        return false
    }
}
