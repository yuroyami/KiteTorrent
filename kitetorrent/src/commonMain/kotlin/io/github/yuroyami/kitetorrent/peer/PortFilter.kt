package io.github.yuroyami.kitetorrent.peer

/**
 * Maps non-overlapping port ranges to access flags — the port of
 * `libtorrent::port_filter` (`src/ip_filter.cpp`,
 * `include/libtorrent/ip_filter.hpp`).
 *
 * Primarily used to mark destination port ranges that should not be connected
 * to. A default-constructed filter sets the entire port range `0 … 65535` to
 * flag `0` (allowed). Upstream reuses the very same `filter_impl` engine
 * instantiated for `std::uint16_t`; we mirror that by reusing [RangeFilter] with
 * a 2-byte **big-endian** key per port, so a port's coalescing and lookup are
 * identical to the IP case.
 *
 * @see IpFilter for the address-range analogue.
 */
class PortFilter {

    private val filter = RangeFilter(2)

    /** A port (0..65535) as a 2-byte big-endian key, matching `uint16_t`. */
    private fun key(port: Int): ByteArray {
        val p = port and 0xFFFF
        return byteArrayOf(((p ushr 8) and 0xFF).toByte(), (p and 0xFF).toByte())
    }

    /**
     * Returns true if the filter still allows every port (no effective rule).
     * Port of the `filter_impl::empty` behaviour exposed through `port_filter`.
     */
    fun isEmpty(): Boolean = filter.isEmpty()

    /**
     * Sets the flags for the inclusive port range \[[first], [last]] to [flags],
     * overwriting any existing rule for those ports (the range is inclusive, so
     * [last] itself gets the flag). Port of `port_filter::add_rule`.
     *
     * @throws IllegalArgumentException if either bound is outside `0..65535` or
     *   `first > last`.
     */
    fun addRule(first: Int, last: Int, flags: Int) {
        require(first in 0..0xFFFF) { "port out of range: $first" }
        require(last in 0..0xFFFF) { "port out of range: $last" }
        require(first <= last) { "port range start must not exceed end: $first .. $last" }
        filter.addRule(key(first), key(last), flags)
    }

    /**
     * Returns the flags set for [port] — `0` (allowed) or [BLOCKED]. Port of
     * `port_filter::access`.
     *
     * @throws IllegalArgumentException if [port] is outside `0..65535`.
     */
    fun access(port: Int): Int {
        require(port in 0..0xFFFF) { "port out of range: $port" }
        return filter.access(key(port))
    }

    companion object {
        /**
         * The single defined access flag (`port_filter::access_flags::blocked`):
         * destination ports in a range carrying this bit should not be connected
         * to.
         */
        const val BLOCKED: Int = 1
    }
}
