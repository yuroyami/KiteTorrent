package io.github.yuroyami.kitetorrent.session.engine

import io.github.yuroyami.kitetorrent.bandwidth.BandwidthChannel
import io.github.yuroyami.kitetorrent.bandwidth.BandwidthManager
import io.github.yuroyami.kitetorrent.bandwidth.BandwidthSocket
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Session-wide upload/download rate limiting — the live driver for the pure
 * [BandwidthManager]/[BandwidthChannel] port.
 *
 * libtorrent gives `session_impl` one `bandwidth_manager` per direction plus a
 * global `bandwidth_channel` pair, with per-torrent channels layered on top; a
 * `peer_connection` asks for quota before moving payload and is parked in the
 * manager's queue when the buckets are dry. This class is that wiring on
 * coroutines: [acquireUpload]/[acquireDownload] **suspend** until the requested
 * bytes are granted, and a periodic [update] (driven by [start]'s ticker, or by a
 * test directly) refills the buckets and releases parked waiters — the
 * `on_tick` → `update_quotas` cadence.
 *
 * A channel whose limit is `0` is unlimited and never participates (libtorrent
 * skips zero-throttle channels when assembling a request's channel list), so with
 * no limits configured every acquire returns immediately.
 *
 * Download limiting works by delaying the receive path *after* the bytes arrived
 * (back-pressure: a reader that stops draining stalls the sender's TCP window),
 * which is also how libtorrent's receive quota throttles a peer.
 */
class RateLimiter(
    private val scope: CoroutineScope,
    private val updateIntervalMs: Long = 250,
) {
    private val lock = Mutex()
    private val uploadManager = BandwidthManager(UPLOAD_CHANNEL)
    private val downloadManager = BandwidthManager(DOWNLOAD_CHANNEL)

    /** The session-global upload token bucket (`m_upload_channel`). */
    val uploadChannel = BandwidthChannel()

    /** The session-global download token bucket (`m_download_channel`). */
    val downloadChannel = BandwidthChannel()

    private var ticker: Job? = null

    @Volatile
    private var closed = false

    /** Cap session upload at [bytesPerSecond] (0 = unlimited). `upload_rate_limit`. */
    fun setUploadLimit(bytesPerSecond: Int) = uploadChannel.throttle(bytesPerSecond.coerceAtLeast(0))

    /** Cap session download at [bytesPerSecond] (0 = unlimited). `download_rate_limit`. */
    fun setDownloadLimit(bytesPerSecond: Int) = downloadChannel.throttle(bytesPerSecond.coerceAtLeast(0))

    fun uploadLimit(): Int = uploadChannel.throttle()
    fun downloadLimit(): Int = downloadChannel.throttle()

    /** A per-torrent channel pair to layer under the global limit (`peer_class` lite). */
    fun newTorrentChannels(): TorrentBandwidth = TorrentBandwidth()

    /** Start the periodic refill ticker. Idempotent. */
    fun start() {
        if (ticker != null) return
        ticker = scope.launch {
            while (!closed) {
                delay(updateIntervalMs)
                update(updateIntervalMs)
            }
        }
    }

    /**
     * One scheduling round: refill the buckets with [dtMilliseconds] worth of quota
     * and dispatch satisfied waiters. Exposed for deterministic tests.
     */
    suspend fun update(dtMilliseconds: Long) {
        lock.withLock {
            uploadManager.updateQuotas(dtMilliseconds)
            downloadManager.updateQuotas(dtMilliseconds)
        }
    }

    /** Suspend until [bytes] of upload quota are granted. */
    suspend fun acquireUpload(bytes: Int, torrent: TorrentBandwidth? = null) =
        acquire(uploadManager, uploadChannel, torrent?.upload, bytes)

    /** Suspend until [bytes] of download quota are granted. */
    suspend fun acquireDownload(bytes: Int, torrent: TorrentBandwidth? = null) =
        acquire(downloadManager, downloadChannel, torrent?.download, bytes)

    /**
     * Stop granting and release every parked waiter with what it has (the
     * `bandwidth_manager::close` semantics). Safe to call from non-suspending
     * teardown; pending dispatch runs on [scope].
     */
    fun close() {
        if (closed) return
        closed = true
        ticker?.cancel()
        ticker = null
        scope.launch {
            lock.withLock {
                uploadManager.close()
                downloadManager.close()
            }
        }
    }

    private suspend fun acquire(
        manager: BandwidthManager,
        global: BandwidthChannel,
        torrent: BandwidthChannel?,
        bytes: Int,
    ) {
        var remaining = bytes
        while (remaining > 0 && !closed) {
            val waiter = Waiter()
            val granted = lock.withLock {
                if (closed) return
                // zero-throttle channels are unlimited and excluded, like upstream
                val channels = ArrayList<BandwidthChannel>(2)
                if (global.throttle() > 0) channels.add(global)
                if (torrent != null && torrent.throttle() > 0) channels.add(torrent)
                if (channels.isEmpty()) return // nothing limits this direction
                manager.requestBandwidth(waiter, remaining, priority = 1, chans = channels)
            }
            if (granted > 0) return // banked quota covered it instantly
            val assigned = waiter.done.await()
            if (assigned <= 0) return // manager shut down
            remaining -= assigned
        }
    }

    /** Bridges one suspended acquire to the manager's [BandwidthSocket] callback. */
    private class Waiter : BandwidthSocket {
        val done = CompletableDeferred<Int>()
        override fun assignBandwidth(channel: Int, amount: Int) {
            done.complete(amount)
        }
        override fun isDisconnecting(): Boolean = false
    }

    companion object {
        /** `peer_connection::upload_channel`. */
        const val UPLOAD_CHANNEL = 0

        /** `peer_connection::download_channel`. */
        const val DOWNLOAD_CHANNEL = 1
    }
}

/**
 * A torrent's upload/download token-bucket pair, layered under the session-global
 * channels the way libtorrent stacks `bandwidth_channel`s (global → torrent) in a
 * peer's request. Limits of 0 = unlimited.
 */
class TorrentBandwidth {
    val upload = BandwidthChannel()
    val download = BandwidthChannel()

    fun setUploadLimit(bytesPerSecond: Int) = upload.throttle(bytesPerSecond.coerceAtLeast(0))
    fun setDownloadLimit(bytesPerSecond: Int) = download.throttle(bytesPerSecond.coerceAtLeast(0))
}
