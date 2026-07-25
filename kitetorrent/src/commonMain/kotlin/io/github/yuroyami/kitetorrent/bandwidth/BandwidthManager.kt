package io.github.yuroyami.kitetorrent.bandwidth

/**
 * Schedules bandwidth fairly across competing consumers. This is a pure-Kotlin port of
 * libtorrent's `bandwidth_manager` (aux_/bandwidth_manager.hpp,
 * bandwidth_manager.cpp).
 *
 * There is one manager per direction ([channel] is upload or download). Consumers
 * call [requestBandwidth]; if their channels have quota banked the request is
 * granted immediately, otherwise it joins [queueSize] and is serviced over
 * subsequent [updateQuotas] rounds. Each round refills every involved
 * [BandwidthChannel]'s token bucket, splits the available quota in proportion to
 * request priorities, and dispatches finished requests through
 * [BandwidthSocket.assignBandwidth].
 *
 * This is the pure scheduling algorithm: the only outside interaction is the
 * [BandwidthSocket] callback, so it ports without any I/O or timers. The driver
 * decides when to call [updateQuotas] and with what elapsed time.
 *
 * @param channel which direction this manager assigns (upload or download); passed
 *   straight back to [BandwidthSocket.assignBandwidth].
 */
class BandwidthManager(private val channel: Int) {

    /** Consumers waiting for bandwidth, in arrival order. (`m_queue`) */
    private val queue: ArrayDeque<BwRequest> = ArrayDeque()

    /** Sum of the still-unassigned bytes of every queued request. (`m_queued_bytes`) */
    private var queuedBytes: Long = 0L

    /** Once aborted, the manager grants nothing and dispatches whatever was pending. */
    private var abort: Boolean = false

    /** Number of requests currently queued. (`queue_size`) */
    fun queueSize(): Int = queue.size

    /** Total still-unassigned bytes across all queued requests. (`queued_bytes`) */
    fun queuedBytes(): Long = queuedBytes

    /** True if [request] is currently in the queue. (`is_queued`) */
    fun isQueued(peer: BandwidthSocket): Boolean = queue.any { it.peer === peer }

    /**
     * Shuts the manager down: clears the queue and immediately dispatches every
     * pending request with whatever it had been [BwRequest.assigned] so far.
     * Faithful to `bandwidth_manager::close`.
     */
    fun close() {
        abort = true
        val pending = ArrayList(queue)
        queue.clear()
        queuedBytes = 0L
        // libtorrent drains back-to-front.
        for (i in pending.indices.reversed()) {
            val bwr = pending[i]
            bwr.peer.assignBandwidth(channel, bwr.assigned)
        }
    }

    /**
     * Requests [blk] bytes for [peer] across the given [chans], at [priority]
     * (1 = normal). Faithful to `bandwidth_manager::request_bandwidth`:
     *
     * - returns [blk] immediately if the manager is *not* aborted and either there
     *   are no channels (unmetered) or none of the channels [BandwidthChannel.needQueueing]
     *   (enough quota was already held, and has been withdrawn for it);
     * - otherwise enqueues the request and returns 0, signalling that the peer's
     *   [BandwidthSocket.assignBandwidth] will be called from a later [updateQuotas].
     *
     * A return of 0 while aborting means the manager is shutting down and nothing
     * will be assigned.
     */
    fun requestBandwidth(
        peer: BandwidthSocket,
        blk: Int,
        priority: Int,
        chans: List<BandwidthChannel>,
    ): Int {
        if (abort) return 0

        require(blk > 0) { "request block must be positive, was $blk" }
        require(priority > 0) { "request priority must be positive, was $priority" }
        require(chans.size <= BwRequest.MAX_BANDWIDTH_CHANNELS) {
            "a peer may belong to at most ${BwRequest.MAX_BANDWIDTH_CHANNELS} channels"
        }

        if (chans.isEmpty()) {
            // Not rate limited by anything, so satisfy the request immediately.
            return blk
        }

        val bwr = BwRequest(peer, blk, priority)
        for (ch in chans) {
            if (ch.needQueueing(blk)) bwr.channels.add(ch)
        }

        if (bwr.channels.isEmpty()) return blk

        queuedBytes += blk
        queue.addLast(bwr)
        return 0
    }

    /**
     * Runs one scheduling round over an elapsed [dtMilliseconds]. Faithful to
     * `bandwidth_manager::update_quotas` (which first converts its `time_duration`
     * via `total_milliseconds`):
     *
     * 1. nothing to do while aborted or with an empty queue; the elapsed time is
     *    clamped to 3000 ms so a long stall cannot dump a huge burst;
     * 2. disconnecting peers are pulled out, their assigned quota refunded to their
     *    channels, and they are dispatched with 0;
     * 3. each live channel's [BandwidthChannel.tmp] accumulates the summed priority
     *    of the requests competing for it, then its token bucket is refilled once;
     * 4. each request takes its proportional share; requests that are now full, or
     *    that have run out of [BwRequest.ttl] with something assigned, are
     *    dispatched and removed;
     * 5. [queuedBytes] is decremented by everything handed out or dropped.
     */
    fun updateQuotas(dtMilliseconds: Long) {
        if (abort) return
        if (queue.isEmpty()) return

        var dt = dtMilliseconds
        if (dt > 3000L) dt = 3000L

        // Requests to dispatch at the end of this round.
        val dispatch = ArrayList<BwRequest>()

        // --- 1. evict disconnecting peers, reset channel scratch -----------------
        val survivors = ArrayList<BwRequest>(queue.size)
        for (r in queue) {
            if (r.peer.isDisconnecting()) {
                queuedBytes -= (r.requestSize - r.assigned).toLong()
                // return all assigned quota to the peer's channels
                for (bwc in r.channels) bwc.returnQuota(r.assigned)
                r.assigned = 0
                dispatch.add(r)
            } else {
                for (bwc in r.channels) bwc.tmp = 0
                survivors.add(r)
            }
        }
        queue.clear()
        queue.addAll(survivors)

        // --- 2. accumulate competing priorities per channel ----------------------
        val channels = ArrayList<BandwidthChannel>()
        for (r in queue) {
            for (bwc in r.channels) {
                if (bwc.tmp == 0) channels.add(bwc)
                bwc.tmp += r.priority
            }
        }

        // --- 3. refill each involved channel's bucket once -----------------------
        for (ch in channels) ch.updateQuota(dt.toInt())

        // --- 4. assign shares and dispatch finished/expired requests -------------
        val remaining = ArrayList<BwRequest>(queue.size)
        for (r in queue) {
            var a = r.assignBandwidth()
            if (r.assigned == r.requestSize || (r.ttl <= 0 && r.assigned > 0)) {
                a += r.requestSize - r.assigned
                dispatch.add(r)
            } else {
                remaining.add(r)
            }
            queuedBytes -= a.toLong()
        }
        queue.clear()
        queue.addAll(remaining)

        // --- 5. fire callbacks (libtorrent drains back-to-front) -----------------
        for (i in dispatch.indices.reversed()) {
            val bwr = dispatch[i]
            bwr.peer.assignBandwidth(channel, bwr.assigned)
        }
    }
}
