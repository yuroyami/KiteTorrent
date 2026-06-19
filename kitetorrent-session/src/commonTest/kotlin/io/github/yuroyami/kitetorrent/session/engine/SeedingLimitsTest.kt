package io.github.yuroyami.kitetorrent.session.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exhaustive policy test for the pure share-ratio / seed-time stop decision. */
class SeedingLimitsTest {

    private val size = 1_000_000L

    private fun stop(
        up: Long, down: Long, secs: Long, ratio: Int, timeLimit: Int,
    ) = SeedingLimits.shouldStopSeeding(up, down, size, secs, ratio, timeLimit)

    @Test
    fun unlimitedNeverStops() {
        assertFalse(stop(up = 10 * size, down = 0, secs = 1_000_000, ratio = 0, timeLimit = 0))
    }

    @Test
    fun ratioUsesSizeFloorForAFreshSeed() {
        // a never-downloaded seed (down = 0): ratio is uploaded relative to the torrent size
        assertFalse(stop(up = size, down = 0, secs = 0, ratio = 2000, timeLimit = 0), "1.0× < 2.0× limit")
        assertTrue(stop(up = 2 * size, down = 0, secs = 0, ratio = 2000, timeLimit = 0), "2.0× meets 2.0× limit")
        assertTrue(stop(up = 3 * size, down = 0, secs = 0, ratio = 2000, timeLimit = 0), "above the limit")
    }

    @Test
    fun ratioUsesActualDownloadWhenLargerThanSize() {
        // downloaded 2× the size → denominator is the download, not the size
        assertFalse(stop(up = 2 * size, down = 2 * size, secs = 0, ratio = 2000, timeLimit = 0), "1.0× of download")
        assertTrue(stop(up = 4 * size, down = 2 * size, secs = 0, ratio = 2000, timeLimit = 0), "2.0× of download")
    }

    @Test
    fun seedTimeLimitStops() {
        assertFalse(stop(up = 0, down = size, secs = 59, ratio = 0, timeLimit = 60))
        assertTrue(stop(up = 0, down = size, secs = 60, ratio = 0, timeLimit = 60))
        assertTrue(stop(up = 0, down = size, secs = 61, ratio = 0, timeLimit = 60))
    }

    @Test
    fun eitherLimitTrips() {
        // ratio not met, but time is
        assertTrue(stop(up = 0, down = size, secs = 100, ratio = 9999, timeLimit = 100))
        // time not met, but ratio is
        assertTrue(stop(up = 100 * size, down = size, secs = 0, ratio = 2000, timeLimit = 100_000))
    }
}
