package io.github.yuroyami.kitetorrent.peer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the IP/port filter port ([IpFilter], [PortFilter], [RangeFilter]).
 *
 * The structural expectations (exact coalesced ranges after a sequence of
 * [IpFilter.addRule] calls) are copied verbatim from libtorrent's own
 * `test/test_ip_filter.cpp` — `expected1`, the four range-joining cases, the two
 * multi-overlap cases, the IPv6 case and the `port_filter` case are ground truth
 * from upstream. The assignment's own scenario (block `10/8`, probe the
 * boundaries, coalesce an overlapping insert, exercise IPv6) is layered on top.
 *
 * Ranges are checked through [IpFilter.exportFilterV4] / [exportFilterV6], whose
 * `(first,last,flags)` triples are compared as dotted-quad / colon-hex strings so
 * a failure prints something readable.
 */
class IpFilterTest {

    // -- helpers ---------------------------------------------------------------

    private fun v4(s: String): ByteArray = PeerAddress.parseOrNull(s)!!.bytes
    private fun v6(s: String): ByteArray = PeerAddress.parseOrNull(s)!!.bytes

    private fun v4str(b: ByteArray): String =
        b.joinToString(".") { (it.toInt() and 0xFF).toString() }

    /** Render the exported v4 ranges as `start-end:flags` strings. */
    private fun dumpV4(f: IpFilter): List<String> =
        f.exportFilterV4().map { "${v4str(it.first)}-${v4str(it.last)}:${it.flags}" }

    private fun rangeStr(first: String, last: String, flags: Int) = "$first-$last:$flags"

    private val B = IpFilter.BLOCKED

    /** The canonical `expected1` from upstream (1.0.0.0–3.0.0.0 blocked). */
    private val expected1 = listOf(
        rangeStr("0.0.0.0", "0.255.255.255", 0),
        rangeStr("1.0.0.0", "3.0.0.0", B),
        rangeStr("3.0.0.1", "255.255.255.255", 0),
    )

    // -- upstream test_ip_filter.cpp golden vectors ----------------------------

    @Test
    fun joiningRangesAtEnd() {
        val f = IpFilter()
        f.addRule("1.0.0.0", "2.0.0.0", B)
        f.addRule("2.0.0.1", "3.0.0.0", B)
        assertEquals(expected1, dumpV4(f))
    }

    @Test
    fun joiningRangesAtStart() {
        val f = IpFilter()
        f.addRule("2.0.0.1", "3.0.0.0", B)
        f.addRule("1.0.0.0", "2.0.0.0", B)
        assertEquals(expected1, dumpV4(f))
    }

    @Test
    fun joiningOverlappingRangesAtStart() {
        val f = IpFilter()
        f.addRule("2.0.0.1", "3.0.0.0", B)
        f.addRule("1.0.0.0", "2.4.0.0", B)
        assertEquals(expected1, dumpV4(f))
    }

    @Test
    fun joiningOverlappingRangesAtEnd() {
        val f = IpFilter()
        f.addRule("1.0.0.0", "2.4.0.0", B)
        f.addRule("2.0.0.1", "3.0.0.0", B)
        assertEquals(expected1, dumpV4(f))
    }

    @Test
    fun joiningMultipleOverlappingRanges1() {
        val f = IpFilter()
        f.addRule("1.0.0.0", "2.0.0.0", B)
        f.addRule("3.0.0.0", "4.0.0.0", B)
        f.addRule("5.0.0.0", "6.0.0.0", B)
        f.addRule("7.0.0.0", "8.0.0.0", B)
        f.addRule("1.0.1.0", "9.0.0.0", B)
        assertEquals(
            listOf(
                rangeStr("0.0.0.0", "0.255.255.255", 0),
                rangeStr("1.0.0.0", "9.0.0.0", B),
                rangeStr("9.0.0.1", "255.255.255.255", 0),
            ),
            dumpV4(f),
        )
    }

    @Test
    fun joiningMultipleOverlappingRanges2() {
        val f = IpFilter()
        f.addRule("1.0.0.0", "2.0.0.0", B)
        f.addRule("3.0.0.0", "4.0.0.0", B)
        f.addRule("5.0.0.0", "6.0.0.0", B)
        f.addRule("7.0.0.0", "8.0.0.0", B)
        f.addRule("0.0.1.0", "7.0.4.0", B)
        assertEquals(
            listOf(
                rangeStr("0.0.0.0", "0.0.0.255", 0),
                rangeStr("0.0.1.0", "8.0.0.0", B),
                rangeStr("8.0.0.1", "255.255.255.255", 0),
            ),
            dumpV4(f),
        )
    }

    @Test
    fun exportInvariantHolds() {
        // The upstream invariant: ranges are contiguous, adjacent ranges have
        // distinct flags, access() at each range's first and last byte agrees
        // with that range's flags, and consecutive ranges abut (plusOne of one
        // range's last == next range's first).
        val f = IpFilter()
        f.addRule("1.0.0.0", "2.0.0.0", B)
        f.addRule("0.0.1.0", "7.0.4.0", B)
        val ranges = f.exportFilterV4()
        assertTrue(ranges.isNotEmpty())
        assertEquals("0.0.0.0", v4str(ranges.first().first))
        assertEquals("255.255.255.255", v4str(ranges.last().last))
        for (idx in ranges.indices) {
            val r = ranges[idx]
            assertEquals(r.flags, f.access(v4str(r.first)))
            assertEquals(r.flags, f.access(v4str(r.last)))
            if (idx + 1 < ranges.size) {
                // adjacent ranges abut and differ in flags
                assertTrue(RangeFilter.plusOne(r.last).contentEquals(ranges[idx + 1].first))
                assertTrue(r.flags != ranges[idx + 1].flags)
            }
        }
    }

    // -- assignment scenario: block 10/8 --------------------------------------

    @Test
    fun blockClassA10() {
        val f = IpFilter()
        f.addRule("10.0.0.0", "10.255.255.255", B)

        assertEquals(B, f.access("10.5.5.5"))
        assertEquals(0, f.access("9.255.255.255"))
        assertEquals(0, f.access("11.0.0.0"))
        // boundaries are inclusive
        assertEquals(B, f.access("10.0.0.0"))
        assertEquals(B, f.access("10.255.255.255"))
        assertEquals(0, f.access("0.0.0.0"))
        assertEquals(0, f.access("255.255.255.255"))

        assertEquals(
            listOf(
                rangeStr("0.0.0.0", "9.255.255.255", 0),
                rangeStr("10.0.0.0", "10.255.255.255", B),
                rangeStr("11.0.0.0", "255.255.255.255", 0),
            ),
            dumpV4(f),
        )
    }

    @Test
    fun overlappingInsertCoalesces() {
        // Two blocked ranges that overlap and extend each other collapse into one.
        val f = IpFilter()
        f.addRule("10.0.0.0", "10.255.255.255", B)
        f.addRule("10.128.0.0", "11.0.0.0", B) // overlaps 10/8 and extends to 11.0.0.0
        assertEquals(
            listOf(
                rangeStr("0.0.0.0", "9.255.255.255", 0),
                rangeStr("10.0.0.0", "11.0.0.0", B),
                rangeStr("11.0.0.1", "255.255.255.255", 0),
            ),
            dumpV4(f),
        )
        assertEquals(B, f.access("11.0.0.0"))
        assertEquals(0, f.access("11.0.0.1"))
    }

    @Test
    fun lastAppliedWinsCarvesHole() {
        // Block 10/8, then re-allow 10.5/16 in the middle: last rule applied wins.
        val f = IpFilter()
        f.addRule("10.0.0.0", "10.255.255.255", B)
        f.addRule("10.5.0.0", "10.5.255.255", 0)
        assertEquals(
            listOf(
                rangeStr("0.0.0.0", "9.255.255.255", 0),
                rangeStr("10.0.0.0", "10.4.255.255", B),
                rangeStr("10.5.0.0", "10.5.255.255", 0),
                rangeStr("10.6.0.0", "10.255.255.255", B),
                rangeStr("11.0.0.0", "255.255.255.255", 0),
            ),
            dumpV4(f),
        )
        assertEquals(0, f.access("10.5.42.42"))
        assertEquals(B, f.access("10.4.255.255"))
        assertEquals(B, f.access("10.6.0.0"))
    }

    // -- IPv6 ------------------------------------------------------------------

    @Test
    fun ipv6Ranges() {
        val f = IpFilter()
        f.addRule("2::1", "3::", B)
        f.addRule("1::", "2::", B)

        val ranges = f.exportFilterV6()
        // first range spans :: .. just-before 1::, last spans 3::1 .. all-ones
        assertEquals(3, ranges.size)
        assertTrue(ranges.first().first.contentEquals(v6("::")))
        assertTrue(ranges.last().last.contentEquals(v6("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")))
        // the blocked middle range is exactly 1:: .. 3::
        assertTrue(ranges[1].first.contentEquals(v6("1::")))
        assertTrue(ranges[1].last.contentEquals(v6("3::")))
        assertEquals(B, ranges[1].flags)
        assertEquals(0, ranges[0].flags)
        assertEquals(0, ranges[2].flags)

        // access spot-checks
        assertEquals(B, f.access("2::5"))
        assertEquals(B, f.access("1::"))
        assertEquals(B, f.access("3::"))
        assertEquals(0, f.access("3::1"))
        assertEquals(0, f.access("4::"))
        assertEquals(0, f.access("::1"))
    }

    @Test
    fun v4AndV6AreIndependent() {
        val f = IpFilter()
        f.addRule("10.0.0.0", "10.255.255.255", B)
        // a v6 query must not be affected by the v4 rule
        assertEquals(0, f.access("10::"))
        assertEquals(0, f.access("::ffff:10.5.5.5")) // v6-mapped form is its own family here
        // and the v6 side is still empty
        assertEquals(1, f.exportFilterV6().size)
    }

    // -- empty / default behaviour --------------------------------------------

    @Test
    fun defaultEmpty() {
        run {
            val f = IpFilter()
            assertTrue(f.isEmpty())
            f.addRule("1::", "2::", B)
            assertFalse(f.isEmpty())
        }
        run {
            val f = IpFilter()
            f.addRule("0.0.1.0", "7.0.4.0", B)
            assertFalse(f.isEmpty())
        }
        run {
            // adding an allow (flag 0) rule changes nothing → still empty
            val f = IpFilter()
            f.addRule("0.0.1.0", "7.0.4.0", 0)
            assertTrue(f.isEmpty())
        }
    }

    @Test
    fun defaultAllowsEverything() {
        val f = IpFilter()
        assertEquals(0, f.access("0.0.0.0"))
        assertEquals(0, f.access("255.255.255.255"))
        assertEquals(0, f.access("8.8.8.8"))
        assertEquals(0, f.access("::"))
        assertEquals(0, f.access("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))
        // a fresh filter exports exactly one all-spanning range per family
        assertEquals(1, f.exportFilterV4().size)
        assertEquals(1, f.exportFilterV6().size)
    }

    // -- port filter (upstream test_ip_filter.cpp port_filter case) -----------

    @Test
    fun portFilter() {
        val pf = PortFilter()

        // default constructed port filter allows any port
        assertEquals(0, pf.access(0))
        assertEquals(0, pf.access(65535))
        assertEquals(0, pf.access(6881))
        assertTrue(pf.isEmpty())

        // block port 100 - 300
        pf.addRule(100, 300, PortFilter.BLOCKED)
        assertFalse(pf.isEmpty())

        assertEquals(0, pf.access(0))
        assertEquals(0, pf.access(99))
        assertEquals(PortFilter.BLOCKED, pf.access(100))
        assertEquals(PortFilter.BLOCKED, pf.access(150))
        assertEquals(PortFilter.BLOCKED, pf.access(300))
        assertEquals(0, pf.access(301))
        assertEquals(0, pf.access(6881))
        assertEquals(0, pf.access(65535))
    }

    // -- low-level key arithmetic (src/ip_filter.cpp plus_one/minus_one) ------

    @Test
    fun keyArithmeticCarriesAcrossBytes() {
        // plus_one(0.0.0.255) == 0.0.1.0 ; minus_one(0.0.1.0) == 0.0.0.255
        assertTrue(RangeFilter.plusOne(byteArrayOf(0, 0, 0, 255.toByte()))
            .contentEquals(byteArrayOf(0, 0, 1, 0)))
        assertTrue(RangeFilter.minusOne(byteArrayOf(0, 0, 1, 0))
            .contentEquals(byteArrayOf(0, 0, 0, 255.toByte())))
        // plus_one(255.255.255.255) saturates (stays all-ones in the only caller
        // path that would hit it, which the engine avoids), minus_one(0.0.0.0)
        // wraps to all-ones — these mirror the byte loop exactly.
        assertTrue(RangeFilter.plusOne(byteArrayOf(-1, -1, -1, -1))
            .contentEquals(byteArrayOf(0, 0, 0, 0)))
        assertTrue(RangeFilter.minusOne(byteArrayOf(0, 0, 0, 0))
            .contentEquals(byteArrayOf(-1, -1, -1, -1)))
    }
}
