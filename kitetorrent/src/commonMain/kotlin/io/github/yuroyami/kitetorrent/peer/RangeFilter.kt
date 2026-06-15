package io.github.yuroyami.kitetorrent.peer

/**
 * The generic, byte-array keyed range filter — the port of
 * `libtorrent::aux::filter_impl<Addr>` (`src/ip_filter.cpp`,
 * `include/libtorrent/ip_filter.hpp`).
 *
 * libtorrent factors both [IpFilter] (IPv4 + IPv6) and [PortFilter] onto a single
 * templated class `filter_impl<Addr>`, instantiated for `address_v4::bytes_type`
 * (4 bytes), `address_v6::bytes_type` (16 bytes) and `std::uint16_t` (a port).
 * The only thing the template needs of `Addr` is that it is an unsigned, fixed
 * width, big-endian integer supporting `<`, increment and decrement. We capture
 * exactly that by keying every range on a fixed-width **big-endian** [ByteArray]:
 * IPv4 keys are 4 bytes, IPv6 keys 16 bytes, and a port is a 2-byte key. The
 * range arithmetic ([plusOne]/[minusOne]/[zero]/[maxAddr]) is the byte-wise
 * carry/borrow loop from `src/ip_filter.cpp`, which is width-agnostic, so one
 * engine faithfully serves all three instantiations.
 *
 * ### Representation
 * The filter stores a **sorted, non-overlapping** list of [Range]s. Each entry
 * records the address its range *starts* at plus the [Range.access] flags for
 * that range; the *end* of a range is implicit — it runs up to (but not
 * including) the next entry's start, and the final entry runs to the maximum
 * address. The list is never empty: it is seeded with a single `{zero, 0}` entry
 * so that, by construction, every address maps to exactly one range. Coalescing
 * in [addRule] keeps the invariant that no two adjacent ranges carry the same
 * flags, so the list is always the minimum description of the filter.
 *
 * Upstream backs this with a `std::set<range>`; we use a sorted [ArrayList] and a
 * binary-search `upperBound`, which gives the same O(log n) [access] and keeps
 * the entries contiguous for [exportRanges]. The width of every key handed to a
 * single instance must match [keyWidth].
 *
 * This type is `internal`: callers use [IpFilter] / [PortFilter].
 *
 * @property keyWidth the fixed key width in bytes (4, 16 or 2).
 */
internal class RangeFilter(private val keyWidth: Int) {

    /**
     * One entry of the access list — the port of `filter_impl<Addr>::range`.
     * [start] is the (big-endian) address the range begins at; [access] the flags
     * that apply from [start] up to the next entry's start. Both are mutable to
     * mirror the in-place `const_cast` rewrite libtorrent does in `add_rule`.
     */
    internal class Range(var start: ByteArray, var access: Int)

    private val list = ArrayList<Range>()

    init {
        // make the entire range non-blocked (filter_impl ctor seeds {zero, 0}).
        list.add(Range(zeroKey(), 0))
    }

    /**
     * True if the filter contains no effective rules — i.e. it is still the lone
     * seeded `{zero, 0}` entry. Port of `filter_impl<Addr>::empty`.
     */
    fun isEmpty(): Boolean =
        list.isEmpty() ||
            (list.size == 1 && list[0].access == 0 && isZeroKey(list[0].start))

    /**
     * Index of the first entry whose `start` is strictly greater than [key] —
     * the port of `std::set::upper_bound`. Returns [list]`.size` when no entry
     * exceeds [key]. The list is sorted by `start`, so this is a binary search.
     */
    private fun upperBound(key: ByteArray): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareKeys(list[mid].start, key) <= 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Insert a fresh range at its correct sorted position and return that index —
     * the order-preserving port of `std::set::insert`. libtorrent always passes a
     * (correct) position hint, but `std::set` ignores a wrong hint and re-sorts;
     * we reproduce that by placing the entry by its [start] key (a strictly new
     * key, so [upperBound] yields the unique slot). [start] is defensively copied
     * so the caller's array can be reused.
     */
    private fun sortedInsert(start: ByteArray, access: Int): Int {
        val pos = upperBound(start)
        list.add(pos, Range(start.copyOf(), access))
        return pos
    }

    /**
     * Returns the flags for [key] — port of `filter_impl<Addr>::access`. Finds the
     * last range whose start is `<= key` and returns its [Range.access].
     */
    fun access(key: ByteArray): Int {
        var i = upperBound(key)
        if (i != 0) i -= 1
        return list[i].access
    }

    /**
     * Mark every address in the inclusive range \[[first], [last]] with [flags],
     * coalescing with neighbouring ranges so the list stays minimal and no two
     * adjacent ranges share flags. Faithful port of
     * `filter_impl<Addr>::add_rule`; in a case of overlapping ranges the last one
     * applied wins.
     *
     * Precondition: `first <= last` and both keys are [keyWidth] bytes wide.
     *
     * The C++ original walks a `std::set` with iterators; here `i` and `j` are
     * integer indices into [list]. Inserts shift indices to their right up by one
     * and erases shift them down, so every structural mutation below is matched by
     * the same adjustment the equivalent `std::set` iterator would have undergone.
     */
    fun addRule(first: ByteArray, last: ByteArray, flags: Int) {
        // i = upper_bound(first); --i  (begin never qualifies: begin.start==zero<=first)
        var i = upperBound(first) - 1
        // j = upper_bound(last)
        var j = upperBound(last)

        var firstAccess = list[i].access
        val lastAccess = list[j - 1].access

        if (compareKeys(list[i].start, first) != 0 && firstAccess != flags) {
            // i = insert(i, range(first, flags)).
            // Since list[i].start < first, the sorted slot is i+1; sortedInsert
            // returns it. The new entry is at-or-before j, so j shifts right.
            i = sortedInsert(first, flags)
            j += 1
        } else if (i != 0 && list[i - 1].access == flags) {
            i -= 1
            firstAccess = list[i].access
        }

        // if (i != j) erase(next(i), j)
        if (i != j) {
            // remove entries (i+1 .. j-1); count = (j-1) - (i+1) + 1 = j - i - 1
            val removeCount = j - i - 1
            if (removeCount > 0) {
                for (k in 0 until removeCount) list.removeAt(i + 1)
                j -= removeCount
            }
        }

        if (compareKeys(list[i].start, first) == 0) {
            // in-place rewrite (the const_cast optimization)
            list[i].start = first.copyOf()
            list[i].access = flags
        } else if (firstAccess != flags) {
            // insert(i, range(first, flags)); again the real slot is i+1.
            val ins = sortedInsert(first, flags)
            if (ins <= j) j += 1
        }

        // close the tail of the range
        val jStartsRightAfterLast =
            j != list.size && compareKeys(minusOne(list[j].start), last) == 0
        val lastIsMax = j == list.size && isMaxKey(last)
        if (!jStartsRightAfterLast && !lastIsMax) {
            if (lastAccess != flags) {
                // plus_one(last) sorts between prev(j) and j, i.e. exactly at j;
                // j is reassigned to the inserted node, matching insert()'s return.
                j = sortedInsert(plusOne(last), lastAccess)
            }
        }

        if (j != list.size && list[j].access == flags) {
            list.removeAt(j)
        }
    }

    /**
     * The current filter as a minimal, ascending list of `(first, last, flags)`
     * triples — port of `filter_impl<Addr>::export_filter`. `first`/`last` are
     * inclusive big-endian keys; the final range ends at [maxKey].
     */
    fun exportRanges(): List<IpRange> {
        val out = ArrayList<IpRange>(list.size)
        for (idx in list.indices) {
            val firstKey = list[idx].start.copyOf()
            val flags = list[idx].access
            val lastKey =
                if (idx + 1 == list.size) maxKey()
                else minusOne(list[idx + 1].start)
            out.add(IpRange(firstKey, lastKey, flags))
        }
        return out
    }

    // -- fixed-width big-endian key arithmetic (src/ip_filter.cpp helpers) -----

    private fun zeroKey(): ByteArray = ByteArray(keyWidth)

    private fun maxKey(): ByteArray = ByteArray(keyWidth) { 0xFF.toByte() }

    private fun isZeroKey(k: ByteArray): Boolean {
        for (b in k) if (b.toInt() != 0) return false
        return true
    }

    private fun isMaxKey(k: ByteArray): Boolean {
        for (b in k) if ((b.toInt() and 0xFF) != 0xFF) return false
        return true
    }

    companion object {
        /**
         * Big-endian increment with carry — exact port of `aux::plus_one`. Like
         * the C++ original it wraps the all-ones key back to zero, but [addRule]
         * only ever calls it on a key strictly below the maximum, so that wrap is
         * never observed by the filter.
         */
        internal fun plusOne(a: ByteArray): ByteArray {
            val t = a.copyOf()
            for (idx in t.indices.reversed()) {
                val v = t[idx].toInt() and 0xFF
                if (v < 0xFF) {
                    t[idx] = (v + 1).toByte()
                    break
                }
                t[idx] = 0
            }
            return t
        }

        /**
         * Big-endian decrement with borrow — port of `aux::minus_one`.
         */
        internal fun minusOne(a: ByteArray): ByteArray {
            val t = a.copyOf()
            for (idx in t.indices.reversed()) {
                val v = t[idx].toInt() and 0xFF
                if (v > 0) {
                    t[idx] = (v - 1).toByte()
                    break
                }
                t[idx] = 0xFF.toByte()
            }
            return t
        }

        /**
         * Unsigned, big-endian, lexicographic comparison of two equal-width keys —
         * the `Addr::operator<` the filter relies on. Mirrors
         * [PeerAddress.compareTo] within a single family.
         */
        internal fun compareKeys(a: ByteArray, b: ByteArray): Int {
            // Equal width by construction; guard defensively all the same.
            val n = if (a.size < b.size) a.size else b.size
            for (idx in 0 until n) {
                val d = (a[idx].toInt() and 0xFF) - (b[idx].toInt() and 0xFF)
                if (d != 0) return if (d < 0) -1 else 1
            }
            return a.size - b.size
        }
    }
}

/**
 * One coalesced range exported by [RangeFilter.exportRanges] — the port of
 * `libtorrent::ip_range<Addr>` reduced to its raw form. [first] and [last] are
 * inclusive big-endian keys (4/16 bytes for an IP, 2 for a port) and [flags] the
 * access flags ([IpFilter.BLOCKED] or 0).
 */
class IpRange internal constructor(
    val first: ByteArray,
    val last: ByteArray,
    val flags: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is IpRange &&
            first.contentEquals(other.first) &&
            last.contentEquals(other.last) &&
            flags == other.flags

    override fun hashCode(): Int {
        var h = first.fold(0) { acc, b -> acc * 31 + b }
        h = h * 31 + last.fold(0) { acc, b -> acc * 31 + b }
        return h * 31 + flags
    }

    override fun toString(): String = "IpRange(flags=$flags)"
}
