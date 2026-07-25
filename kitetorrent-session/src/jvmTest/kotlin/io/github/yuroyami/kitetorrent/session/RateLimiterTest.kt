package io.github.yuroyami.kitetorrent.session

import io.github.yuroyami.kitetorrent.session.engine.RateLimiter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * The [RateLimiter] adapter over the ported `bandwidth_manager`: unlimited
 * channels grant instantly, limited ones park the acquirer until [RateLimiter.update]
 * rounds (the `update_quotas` cadence) have refilled enough quota, and [RateLimiter.close]
 * releases everything. The test drives updates manually to stay deterministic, with
 * no wall-clock timing involved.
 */
class RateLimiterTest {

    @Test
    fun unlimitedDirectionGrantsImmediately() = runBlocking {
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        val limiter = RateLimiter(workers) // no limits set
        withTimeout(1_000) { limiter.acquireUpload(1 shl 20) } // must not suspend on quota
        workers.cancel()
    }

    @Test
    fun limitedAcquireParksUntilQuotaAccrues() = runBlocking {
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        val limiter = RateLimiter(workers)
        limiter.setUploadLimit(10_000) // bytes/second

        var granted = false
        val job = workers.launch {
            limiter.acquireUpload(25_000)
            granted = true
        }
        // let the acquire reach the manager queue
        repeat(10) { yield() }
        assertFalse(granted, "25k against an empty 10k/s bucket must queue")

        limiter.update(1_000) // +10k: not enough
        repeat(10) { yield() }
        assertFalse(granted)

        limiter.update(1_000) // +10k = 20k, still short
        limiter.update(1_000) // +10k = 25k assigned, so it is dispatched
        withTimeout(2_000) { job.join() }
        assertTrue(granted, "three seconds of quota must satisfy a 25k request at 10k/s")

        workers.cancel()
    }

    @Test
    fun perTorrentChannelIsTheTighterConstraint() = runBlocking {
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        val limiter = RateLimiter(workers)
        limiter.setUploadLimit(1_000_000) // generous global
        val torrent = limiter.newTorrentChannels()
        torrent.setUploadLimit(10_000) // tight per-torrent

        var granted = false
        val job = workers.launch {
            limiter.acquireUpload(20_000, torrent)
            granted = true
        }
        repeat(10) { yield() }
        assertFalse(granted, "the per-torrent channel must constrain the request")

        limiter.update(1_000)
        limiter.update(1_000) // 2 s × 10k/s covers the 20k
        withTimeout(2_000) { job.join() }
        assertTrue(granted)

        workers.cancel()
    }

    @Test
    fun closeReleasesParkedWaiters() = runBlocking {
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        val limiter = RateLimiter(workers)
        limiter.setDownloadLimit(1_000)

        val job = workers.launch { limiter.acquireDownload(1 shl 20) }
        repeat(10) { yield() }
        assertFalse(job.isCompleted)

        limiter.close()
        withTimeout(2_000) { job.join() } // released with whatever it had, so no hang
        workers.cancel()
    }
}
