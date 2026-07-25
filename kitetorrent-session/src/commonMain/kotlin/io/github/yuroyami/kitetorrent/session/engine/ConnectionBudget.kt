package io.github.yuroyami.kitetorrent.session.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session-wide connection cap, the enforcement half of libtorrent's
 * `connections_limit` setting (`session_impl::m_settings`, default 200).
 *
 * Every torrent shares one budget: an outbound dial or inbound accept first takes a
 * slot ([tryAcquire]); when none is free the dial is skipped / the accepted socket
 * is closed, which is libtorrent's `too_many_connections` disconnect. The slot is
 * returned on disconnect ([release]).
 */
class ConnectionBudget(limit: Int) {
    private val lock = Mutex()
    private var inUse = 0

    /**
     * The cap, mutable so a live `connections_limit` change (`apply_settings`) takes effect on
     * the running session rather than only future torrents. Updated under [lock] via [setLimit].
     */
    var limit: Int = limit
        private set

    /** Apply a new `connections_limit` live; excess in-use slots drain as peers disconnect. */
    suspend fun setLimit(newLimit: Int) {
        lock.withLock { limit = newLimit }
    }

    /** Take a connection slot; false (and no slot) when the session is full. */
    suspend fun tryAcquire(): Boolean = lock.withLock {
        if (inUse >= limit) return false
        inUse += 1
        true
    }

    /** Return a slot taken by [tryAcquire]. */
    suspend fun release() {
        lock.withLock { if (inUse > 0) inUse -= 1 }
    }

    /** Currently held slots (observability/tests). */
    suspend fun inUse(): Int = lock.withLock { inUse }
}
