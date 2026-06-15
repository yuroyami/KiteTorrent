package io.github.yuroyami.kitetorrent.stat

/**
 * Tracks the throughput of a single byte stream — pure-Kotlin port of libtorrent's
 * `stat_channel` (stat.hpp, stat.cpp).
 *
 * Each channel keeps three numbers:
 *  - a 64-bit running [total] of every byte ever counted,
 *  - a 32-bit [counter] accumulating the bytes seen *this tick* (reset each
 *    [secondTick]), and
 *  - a 32-bit exponentially-smoothed [rate] in bytes/second.
 *
 * libtorrent uses one of these per direction-and-class (payload vs protocol vs IP
 * overhead); [Stat] bundles six of them. Counters are signed like the C++ (`int32`
 * / `int64`); overflow is asserted away there and simply not guarded here, matching
 * the "callers never feed absurd values" contract.
 */
class StatChannel {

    /** 64-bit running total of all bytes counted. (`m_total_counter`) */
    private var totalCounter: Long = 0L

    /** Bytes accumulated during the current tick, reset by [secondTick]. (`m_counter`) */
    private var counter: Int = 0

    /** Exponentially-smoothed rate, bytes/second. (`m_5_sec_average`) */
    private var fiveSecAverage: Int = 0

    /** Counts [count] freshly transferred bytes into this channel. Must be >= 0. */
    fun add(count: Int) {
        require(count >= 0) { "stat add count must be non-negative, was $count" }
        counter += count
        totalCounter += count
    }

    /**
     * Folds another channel's *current-tick* contribution into this one, mirroring
     * `stat_channel::operator+=`. Note libtorrent adds the source's [counter] to
     * both this counter and this total (it does **not** add the source total).
     */
    operator fun plusAssign(s: StatChannel) {
        counter += s.counter
        totalCounter += s.counter
    }

    /**
     * Advances the smoothed [rate] and resets the per-tick [counter]. Should be
     * called once per "second", with the real elapsed [tickIntervalMs] so the rate
     * is normalised to bytes/second even when ticks drift.
     *
     * Faithful to `stat_channel::second_tick`:
     * ```
     * sample        = counter * 1000 / tickIntervalMs   (64-bit, truncated)
     * fiveSecAvg    = fiveSecAvg * 4 / 5 + sample / 5    (truncated to int32)
     * counter       = 0
     * ```
     */
    fun secondTick(tickIntervalMs: Int) {
        val sample: Long = counter.toLong() * 1000L / tickIntervalMs.toLong()
        fiveSecAverage = (fiveSecAverage.toLong() * 4L / 5L + sample / 5L).toInt()
        counter = 0
    }

    /** The smoothed transfer rate in bytes/second. (`rate()`) */
    fun rate(): Int = fiveSecAverage

    /** Alias for [rate]; libtorrent keeps `low_pass_rate()` as a synonym. */
    fun lowPassRate(): Int = fiveSecAverage

    /** All-time total bytes counted. (`total()`) */
    fun total(): Long = totalCounter

    /** Bytes counted during the current (not-yet-ticked) interval. (`counter()`) */
    fun counter(): Int = counter

    /**
     * Adjusts [total] by [c] without touching the per-tick counter or rate. Used to
     * seed a fresh connection's stats with bytes from earlier sessions.
     * (`offset()`)
     */
    fun offset(c: Long) {
        totalCounter += c
    }

    /** Resets all three numbers to zero. (`clear()`) */
    fun clear() {
        counter = 0
        fiveSecAverage = 0
        totalCounter = 0
    }
}
