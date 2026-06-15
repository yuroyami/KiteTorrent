package io.github.yuroyami.kitetorrent.bandwidth

/**
 * One consumer's outstanding bandwidth request — pure-Kotlin port of libtorrent's
 * `bw_request` (aux_/bandwidth_queue_entry.hpp, bandwidth_queue_entry.cpp).
 *
 * A request asks for [requestSize] bytes on behalf of [peer], competing at the
 * given [priority] (1 is normal). It belongs to up to [MAX_BANDWIDTH_CHANNELS]
 * [BandwidthChannel]s — the channels that need to ration it (its own limit, its
 * torrent's, the global one, …). The [BandwidthManager] accumulates [assigned]
 * bytes across rounds until it reaches [requestSize] or [ttl] runs out, then fires
 * the consumer's `assignBandwidth` callback.
 */
class BwRequest(
    /** The consumer awaiting bandwidth. */
    val peer: BandwidthSocket,
    /** Total bytes requested. (`request_size`) */
    val requestSize: Int,
    /** Scheduling priority; 1 is normal, higher gets a larger share. (`priority`) */
    val priority: Int,
) {
    init {
        require(priority > 0) { "bandwidth request priority must be positive, was $priority" }
    }

    /** Bytes assigned to this request so far. (`assigned`) */
    var assigned: Int = 0

    /**
     * Remaining scheduling rounds before the request is force-dispatched even if
     * only partially filled — guarantees progress at very low rate limits.
     * Starts at 20, matching libtorrent. (`ttl`)
     */
    var ttl: Int = 20

    /**
     * The channels rationing this request, in order. libtorrent uses a fixed array
     * of 10 slots terminated by a null; a list of at most [MAX_BANDWIDTH_CHANNELS]
     * is the faithful equivalent.
     */
    val channels: MutableList<BandwidthChannel> = ArrayList(MAX_BANDWIDTH_CHANNELS)

    /**
     * Assigns one round's worth of bandwidth from the most-limiting channel and
     * withdraws it from every channel. Returns the number of bytes assigned this
     * call. Faithful to `bw_request::assign_bandwidth`:
     *
     * - the request can take at most `requestSize - assigned` more bytes;
     * - each channel's share is `distributeQuota * priority / tmp`, and the channel
     *   that allows the least caps the assignment (unlimited channels — throttle 0 —
     *   and channels with no competing priority are skipped);
     * - [ttl] decrements every call regardless.
     */
    fun assignBandwidth(): Int {
        var quota = requestSize - assigned
        ttl--
        if (quota == 0) return quota

        for (ch in channels) {
            if (ch.throttle() == 0) continue
            if (ch.tmp == 0) continue
            val share = (ch.distributeQuota.toLong() * priority.toLong() / ch.tmp.toLong()).toInt()
            quota = minOf(share, quota)
        }
        assigned += quota
        for (ch in channels) ch.useQuota(quota)
        return quota
    }

    companion object {
        /** A consumer belongs to at most this many channels. (`max_bandwidth_channels`) */
        const val MAX_BANDWIDTH_CHANNELS: Int = 10
    }
}
