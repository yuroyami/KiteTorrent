package io.github.yuroyami.kitetorrent.bandwidth

/**
 * A single token bucket — pure-Kotlin port of libtorrent's `bandwidth_channel`
 * (aux_/bandwidth_limit.hpp, bandwidth_limit.cpp).
 *
 * Each channel enforces one rate limit (bytes/second). A peer connection belongs
 * to several at once (its own limit, its torrent's limit, the global limit, …) and
 * the [BandwidthManager] hands it the minimum the most-constrained channel allows.
 *
 * ### Token bucket
 * [updateQuota] is called every scheduling round with the elapsed milliseconds and
 * pours `limit * dt / 1000` fresh bytes into [quotaLeft]. The bucket is capped at
 * three seconds' worth of limit so an idle channel cannot hoard unbounded burst.
 * Consumers withdraw with [useQuota] and refund unused quota on disconnect with
 * [returnQuota].
 *
 * A [limit] of `0` means *infinite* (unlimited) — such a channel always reports
 * [inf] quota and ignores [updateQuota] / [useQuota] / [returnQuota].
 *
 * The fields [tmp] and [distributeQuota] are scratch space owned by the manager
 * during a distribution round; see [BandwidthManager.updateQuotas].
 */
class BandwidthChannel {

    /**
     * Scratch accumulator the manager uses while distributing quota: the sum of the
     * priorities of all requests competing for this channel in the current round.
     */
    var tmp: Int = 0

    /** The number of bytes this channel offers for distribution this round. */
    var distributeQuota: Int = 0

    /** Bytes accrued but not yet handed out. 64-bit to survive cap arithmetic. */
    private var quotaLeft: Long = 0L

    /** Bytes/second cap; `0` means infinite. (`m_limit`) */
    private var limit: Int = 0

    /**
     * Sets the rate limit to [value] bytes/second; `0` means infinite. Must be in
     * `0 until inf` — at or beyond [inf] the token-bucket math could overflow.
     */
    fun throttle(value: Int) {
        require(value >= 0) { "throttle limit must be non-negative, was $value" }
        require(value < inf) { "throttle limit must be below inf, was $value" }
        limit = value
    }

    /** The current rate limit in bytes/second; `0` means infinite. */
    fun throttle(): Int = limit

    /** Bytes currently available, or [inf] when the channel is unlimited. */
    fun quotaLeft(): Int {
        if (limit == 0) return inf
        return maxOf(quotaLeft, 0L).toInt()
    }

    /**
     * Pours [dtMilliseconds] worth of fresh quota into the bucket, faithful to
     * `bandwidth_channel::update_quota`:
     *
     * ```
     * toAdd = (limit * dt + 500) / 1000        // rounded to nearest byte
     * quotaLeft += toAdd, saturating at inf
     * if quotaLeft / 3 > limit: quotaLeft = limit * 3   // 3-second burst cap
     * distributeQuota = max(quotaLeft, 0)
     * ```
     *
     * An unlimited channel (limit 0) does nothing.
     */
    fun updateQuota(dtMilliseconds: Int) {
        if (limit == 0) return

        // "toAdd" never overflows int64: limit < int32 max, dt is a small duration.
        val toAdd: Long = (limit.toLong() * dtMilliseconds.toLong() + 500L) / 1000L

        if (toAdd > inf - quotaLeft) {
            quotaLeft = inf.toLong()
        } else {
            quotaLeft += toAdd
            if (quotaLeft / 3L > limit.toLong()) quotaLeft = limit.toLong() * 3L
            // quotaLeft never overflows int64 but may exceed int32; clamp to inf.
            quotaLeft = minOf(quotaLeft, inf.toLong())
        }

        distributeQuota = maxOf(quotaLeft, 0L).toInt()
    }

    /**
     * Refunds [amount] bytes to the bucket — used when a consumer disconnects with
     * quota it never spent. No-op on an unlimited channel. (`return_quota`)
     */
    fun returnQuota(amount: Int) {
        require(amount >= 0) { "return amount must be non-negative, was $amount" }
        if (limit == 0) return
        quotaLeft += amount
    }

    /** Withdraws [amount] bytes from the bucket. No-op on an unlimited channel. (`use_quota`) */
    fun useQuota(amount: Int) {
        require(amount >= 0) { "use amount must be non-negative, was $amount" }
        if (limit == 0) return
        quotaLeft -= amount
    }

    /**
     * Fast path that lets a consumer skip the manager's queue: if more than one
     * second of quota is already banked, [amount] is withdrawn directly and this
     * returns false (no queueing needed). Otherwise nothing is withdrawn and it
     * returns true. Faithful to `bandwidth_channel::need_queueing`.
     */
    fun needQueueing(amount: Int): Boolean {
        if (quotaLeft - amount < limit) return true
        quotaLeft -= amount
        return false
    }

    companion object {
        /** Sentinel for "infinite" quota / limit — `int32` max, as in libtorrent. */
        const val inf: Int = Int.MAX_VALUE
    }
}
