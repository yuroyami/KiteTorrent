package io.github.yuroyami.kitetorrent.peer

/**
 * A set of rules that uniquely categorises every IP address as allowed or
 * blocked — the port of `libtorrent::ip_filter`
 * (`src/ip_filter.cpp`, `include/libtorrent/ip_filter.hpp`).
 *
 * A default-constructed filter contains a single implicit rule that allows every
 * address (`0.0.0.0 – 255.255.255.255` for IPv4 and the whole `:: – ffff:…:ffff`
 * range for IPv6), so it filters nothing. [addRule] paints inclusive address
 * ranges with [BLOCKED] (or `0` to re-allow), and the filter coalesces them into
 * the minimum number of sorted, non-overlapping ranges. In a case of overlapping
 * ranges the **last one applied wins** (so you can block a wide range then poke a
 * hole back in it).
 *
 * IPv4 and IPv6 are kept in two independent [RangeFilter]s exactly as libtorrent
 * keeps `m_filter4` and `m_filter6`: a v4 query never consults v6 rules and vice
 * versa. Addresses are supplied as literal strings and parsed with
 * [PeerAddress]; their network-byte-order [PeerAddress.bytes] are the keys the
 * underlying engine compares (4 bytes for v4, 16 for v6).
 *
 * Like libtorrent's, [access] is O(log n) in the number of distinct ranges.
 */
class IpFilter {

    private val filter4 = RangeFilter(4)
    private val filter6 = RangeFilter(16)

    /**
     * Returns true if the filter contains no effective rules (both the IPv4 and
     * IPv6 sub-filters are still their seeded allow-all entry). Port of
     * `ip_filter::empty`.
     */
    fun isEmpty(): Boolean = filter4.isEmpty() && filter6.isEmpty()

    /**
     * Adds a rule marking the inclusive range \[[startIp], [endIp]] with [flags]
     * (`0` = allowed, [BLOCKED] = disallowed). Port of `ip_filter::add_rule`.
     *
     * Both endpoints must parse (via [PeerAddress]) as literal addresses **of the
     * same family** — `start.isV4 == last.isV4` — matching libtorrent's
     * precondition `first.is_v4() == last.is_v4()`. Postcondition: `access(x)`
     * returns [flags] for every `x` in \[[startIp], [endIp]].
     *
     * @throws IllegalArgumentException if either endpoint is not a literal IP
     *   address, if they belong to different families, or if `start > last`.
     */
    fun addRule(startIp: String, endIp: String, flags: Int) {
        val first = PeerAddress.parseOrNull(startIp)
            ?: throw IllegalArgumentException("not an IP address: $startIp")
        val last = PeerAddress.parseOrNull(endIp)
            ?: throw IllegalArgumentException("not an IP address: $endIp")
        require(first.isV4 == last.isV4) {
            "ip_filter range endpoints must be the same family: $startIp .. $endIp"
        }
        require(RangeFilter.compareKeys(first.bytes, last.bytes) <= 0) {
            "ip_filter range start must not exceed end: $startIp .. $endIp"
        }
        if (first.isV4) {
            filter4.addRule(first.bytes, last.bytes, flags)
        } else {
            filter6.addRule(first.bytes, last.bytes, flags)
        }
    }

    /**
     * Returns the access flags for the address literal [ip] — `0` when allowed,
     * [BLOCKED] when blocked. Port of `ip_filter::access`. The correct family's
     * sub-filter is consulted based on whether [ip] parses as v4 or v6.
     *
     * @throws IllegalArgumentException if [ip] is not a literal IP address.
     */
    fun access(ip: String): Int {
        val addr = PeerAddress.parseOrNull(ip)
            ?: throw IllegalArgumentException("not an IP address: $ip")
        return if (addr.isV4) filter4.access(addr.bytes) else filter6.access(addr.bytes)
    }

    /**
     * The current IPv4 ranges, minimal and ascending — the first element of
     * `ip_filter::export_filter`'s tuple. Each [IpRange] carries inclusive 4-byte
     * keys and its flags; the list always spans `0.0.0.0 … 255.255.255.255`.
     */
    fun exportFilterV4(): List<IpRange> = filter4.exportRanges()

    /**
     * The current IPv6 ranges, minimal and ascending — the second element of
     * `ip_filter::export_filter`'s tuple. Each [IpRange] carries inclusive 16-byte
     * keys; the list always spans `:: … ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff`.
     */
    fun exportFilterV6(): List<IpRange> = filter6.exportRanges()

    companion object {
        /**
         * The single defined access flag (`ip_filter::access_flags::blocked`):
         * IPs in a range carrying this bit must not be connected to nor accepted
         * as incoming connections.
         */
        const val BLOCKED: Int = 1
    }
}
