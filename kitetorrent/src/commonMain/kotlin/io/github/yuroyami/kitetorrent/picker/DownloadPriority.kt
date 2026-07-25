package io.github.yuroyami.kitetorrent.picker

/**
 * Per-piece and per-file download priority constants. This is a pure-Kotlin port of
 * libtorrent's `download_priority.hpp`.
 *
 * libtorrent models priority as a `std::uint8_t` strong typedef in the range
 * `[0, 7]`. We keep the plain [Int] convention of v0 KiteTorrent. Priority 0
 * ([DONT_DOWNLOAD]) means the piece is *filtered* and is never picked.
 *
 * The piece picker squeezes the value into 3 bits, so only 0..7 are legal.
 */
object DownloadPriority {
    /** Don't download this piece/file (the piece is "filtered"). */
    const val DONT_DOWNLOAD: Int = 0

    /** The lowest non-zero priority. */
    const val LOW_PRIORITY: Int = 1

    /** The default priority a piece starts at. */
    const val DEFAULT_PRIORITY: Int = 4

    /** The highest priority. */
    const val TOP_PRIORITY: Int = 7
}
