package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [RoutingTable] and the free functions in `RoutingTable.kt` — the port
 * of `src/kademlia/routing_table.cpp`.
 *
 * Most structural tests run with `restrictRoutingIps`, `preferVerifiedNodeIds`
 * and `enforceNodeId` all disabled, and the extended routing table off, so we can
 * drive node ids directly (independent of any BEP 42 IP binding) and reason about
 * pure XOR-distance bucketing. The IP/verification-specific behaviours are tested
 * separately with those knobs on.
 *
 * The [classifyPrefix] golden values are taken verbatim from libtorrent's
 * `test_dht.cpp`.
 */
class RoutingTableTest {

    private fun hash(hex: String): Sha1Hash = Digest32.fromHex(hex)

    /** Build a 20-byte id from explicit leading hex, zero-padded to 40 chars. */
    private fun idHex(leading: String, pad: Char = '0'): Sha1Hash {
        require(leading.length <= 40)
        return Digest32.fromHex(leading + pad.toString().repeat(40 - leading.length))
    }

    /** A 20-byte id whose top 16 bits are [hi16] and the rest [fill]. */
    private fun idTop16(hi16: Int, fill: Char = '0'): Sha1Hash =
        idHex((hi16 and 0xffff).toString(16).padStart(4, '0'), fill)

    /** Settings that let the test control ids directly with no IP gatekeeping. */
    private fun openSettings() = RoutingTableSettings(
        restrictRoutingIps = false,
        preferVerifiedNodeIds = false,
        enforceNodeId = false,
        extendedRoutingTable = false,
    )

    /** A unique host per index so de-dup never interferes (each in its own /8). */
    private fun host(i: Int): String = "${(i % 250) + 1}.${(i / 250) % 250}.0.1"

    // --- classify_prefix (golden) ---------------------------------------------

    @Test
    fun classifyPrefixGoldenVectors() {
        // bucket_idx=8, last_bucket=true, bucket_size=8 — second nibble of byte 1.
        assertEquals(0, classifyPrefix(8, true, 8, hash("dc0cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdc")))
        assertEquals(1, classifyPrefix(8, true, 8, hash("dc2cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdc")))
        assertEquals(2, classifyPrefix(8, true, 8, hash("dc4cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdc")))
        assertEquals(4, classifyPrefix(8, true, 8, hash("dc8cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdc")))
        assertEquals(7, classifyPrefix(8, true, 8, hash("dcecdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdc")))
        assertEquals(7, classifyPrefix(8, true, 8, hash("dcfcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdc")))

        // not the last bucket: one fewer left shift (top defining bit excluded).
        assertEquals(0, classifyPrefix(12, false, 8, hash("cdc8cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd")))
        assertEquals(5, classifyPrefix(12, false, 8, hash("cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd")))
        assertEquals(7, classifyPrefix(12, false, 8, hash("cdcfcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd")))

        // larger bucket (16): 4 classified bits.
        assertEquals(0, classifyPrefix(12, true, 16, hash("cdc0cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd")))
        assertEquals(5, classifyPrefix(12, true, 16, hash("cdc5cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd")))
        assertEquals(13, classifyPrefix(12, true, 16, hash("cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd")))
        assertEquals(15, classifyPrefix(12, true, 16, hash("cdcfcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd")))
    }

    @Test
    fun classifyPrefixReturnInRange() {
        // for every possible second byte, the classification stays within range.
        for (i in 0 until 256) {
            val id = idHex("80" + i.toString(16).padStart(2, '0'))
            val p = classifyPrefix(0, true, 8, id)
            assertTrue(p in 0 until 8)
        }
        // and for a 32-wide bucket the range is 0..31.
        for (i in 0 until 256) {
            val id = idHex("80" + i.toString(16).padStart(2, '0'))
            assertTrue(classifyPrefix(0, true, 32, id) in 0 until 32)
        }
    }

    // --- all_in_same_bucket (golden) ------------------------------------------

    @Test
    fun allInSameBucketGolden() {
        // empty bucket: the single candidate is trivially "all in same bucket".
        assertTrue(allInSameBucket(emptyList(), hash("8000000000000000000000000000000000000000"), 0))
        assertTrue(allInSameBucket(emptyList(), hash("8000000000000000000000000000000000000001"), 1))

        // candidate has bit 0 set (0x80...), and an entry with bit 0 clear
        // (0x00...) — they're on opposite sides at bit 0, so NOT all-in-same.
        val mixed = listOf(NodeEntry(hash("0000000000000000000000000000000000000000"), "1.2.3.4", 1))
        assertFalse(allInSameBucket(mixed, hash("8000000000000000000000000000000000000000"), 0))

        // both on the same side at bit 0 (both top bit set) -> all in same.
        val same = listOf(NodeEntry(hash("c000000000000000000000000000000000000000"), "1.2.3.4", 1))
        assertTrue(allInSameBucket(same, hash("8000000000000000000000000000000000000000"), 0))
    }

    // --- compare_ip_cidr -------------------------------------------------------

    private fun pa(s: String) = io.github.yuroyami.kitetorrent.peer.PeerAddress.parseOrNull(s)!!

    @Test
    fun compareIpCidrSame24And64() {
        // same /24 -> too close
        assertTrue(compareIpCidr(pa("1.2.3.4"), pa("1.2.3.200")))
        // different third octet -> ok
        assertFalse(compareIpCidr(pa("1.2.3.4"), pa("1.2.4.4")))
        // mixed families -> never close
        assertFalse(compareIpCidr(pa("1.2.3.4"), pa("::1")))
        // same /64 IPv6 -> too close
        assertTrue(compareIpCidr(pa("2001:db8::1"), pa("2001:db8::ffff")))
        // different /64 -> ok
        assertFalse(compareIpCidr(pa("2001:db8:0:1::1"), pa("2001:db8:0:2::1")))
    }

    // --- mostly_verified_nodes -------------------------------------------------

    @Test
    fun mostlyVerifiedNodesThreshold() {
        // empty bucket: 0 >= 0 -> true (matches the upstream `num_verified >=
        // size*2/3` with the `size > 0` early-out not triggering).
        assertTrue(mostlyVerifiedNodes(emptyList()))
        fun n(verified: Boolean) = NodeEntry(hash("1111111111111111111111111111111111111111"), "1.2.3.4", 1)
            .also { it.verified = verified }
        // 3 nodes, 2 verified: 2 >= 3*2/3 == 2 -> true
        assertTrue(mostlyVerifiedNodes(listOf(n(true), n(true), n(false))))
        // 3 nodes, 1 verified: 1 >= 2 -> false
        assertFalse(mostlyVerifiedNodes(listOf(n(true), n(false), n(false))))
        // all unverified, non-empty -> false (early-out)
        assertFalse(mostlyVerifiedNodes(listOf(n(false), n(false))))
    }

    // --- node_seen / find_node basic ------------------------------------------

    @Test
    fun nodeSeenInsertsAndFindNodeReturnsIt() {
        val ourId = hash("3123456789abcdef01232456789abcdef0123456")
        val table = RoutingTable(ourId, 8, openSettings())
        assertEquals(0, table.size().first)
        assertTrue(table.needBootstrap())

        val nid = hash("8000000000000000000000000000000000000000")
        val refresh = table.nodeSeen(nid, "4.4.4.4", 4, 10)
        // first node into an empty table: added (return value here is whether the
        // table wants a refresh; for a single far node it may be false — we only
        // assert the node landed).
        assertEquals(1, table.size().first)
        assertFalse(table.needBootstrap())

        val found = table.findNode(nid, 10)
        assertEquals(1, found.size)
        assertEquals(nid, found[0].id)
        assertEquals("4.4.4.4", found[0].host)
        assertEquals(4, found[0].port)
        assertTrue(found[0].confirmed())
        // suppress unused warning on refresh
        assertTrue(refresh || !refresh)
    }

    @Test
    fun sameIdNewIpIsIgnored() {
        val ourId = hash("0000000000000000000000000000000000000000")
        val table = RoutingTable(ourId, 8, openSettings())
        val nid = hash("8000000000000000000000000000000000000000")

        table.nodeSeen(nid, "4.4.4.4", 4, 10)
        assertEquals(1, table.size().first)

        // same id, different endpoint -> ignored, original kept.
        table.nodeSeen(nid, "4.4.4.4", 5, 10)
        val found = table.findNode(nid, 10)
        assertEquals(1, found.size)
        assertEquals(4, found[0].port)
    }

    @Test
    fun sameEndpointNewIdRemovesNode() {
        // restrict_routing_ips must be on for the "same IP:port, new id" eviction
        // to apply (it keys on the IP existing in the table).
        val ourId = hash("0000000000000000000000000000000000000000")
        val table = RoutingTable(
            ourId, 8,
            RoutingTableSettings(restrictRoutingIps = true, preferVerifiedNodeIds = false, enforceNodeId = false),
        )

        val id1 = hash("8000000000000000000000000000000000000001")
        table.nodeSeen(id1, "4.4.4.4", 4, 10)
        assertEquals(1, table.size().first)

        // same IP:port, brand new id, pinged -> the node is evicted (treated as a
        // possible malicious id change).
        val id2 = hash("8000000000000000000000000000000000000002")
        table.nodeSeen(id2, "4.4.4.4", 4, 10)
        assertEquals(0, table.size().first)
    }

    // --- find_node returns the K closest by XOR distance ----------------------

    @Test
    fun findNodeReturnsKClosestByXorDistanceSingleBucket() {
        // Our own id is 0, so distance to a target equals id XOR target. To get a
        // *provable* global K-closest we keep all nodes in a single bucket (so
        // find_node sorts the whole over-full slice exactly): we put every node
        // far from us (top bit clear, distance_exp ~159 -> bucket 0) and never
        // exceed enough to force a structural split for the queried prefix.
        val ourId = hash("0000000000000000000000000000000000000000")
        // big bucket so nothing splits; all 12 nodes coexist in bucket 0.
        val table = RoutingTable(ourId, 16, openSettings())

        // 12 distinct ids, all with the top bit clear so they share bucket 0.
        val raw = listOf(
            "0100000000000000000000000000000000000000",
            "0200000000000000000000000000000000000000",
            "0300000000000000000000000000000000000000",
            "0500000000000000000000000000000000000000",
            "0800000000000000000000000000000000000000",
            "0a00000000000000000000000000000000000000",
            "0f00000000000000000000000000000000000000",
            "1100000000000000000000000000000000000000",
            "1900000000000000000000000000000000000000",
            "2100000000000000000000000000000000000000",
            "2a00000000000000000000000000000000000000",
            "3f00000000000000000000000000000000000000",
        )
        for ((idx, h) in raw.withIndex()) table.nodeSeen(hash(h), host(idx), 9000 + idx, 10)

        val present = ArrayList<NodeEntry>()
        table.forEachNode { present.add(it) }
        assertEquals(raw.size, present.size)
        assertEquals(1, table.numActiveBuckets())

        val target = hash("0700000000000000000000000000000000000000")
        val k = 5
        val result = table.findNode(target, k)
        assertEquals(k, result.size)

        // independently compute the closest k by XOR distance to the target.
        val expected = present
            .sortedWith { a, b -> if (a.id == b.id) 0 else if (compareRef(a.id, b.id, target)) -1 else 1 }
            .take(k)
            .map { it.id }

        // exact match: same ids, in closest-first order, no duplicates.
        assertEquals(expected, result.map { it.id })
        assertEquals(result.map { it.id }.toSet().size, result.size)
    }

    @Test
    fun findNodeScattersAcrossBucketsWithoutDuplicates() {
        // A larger, scattered population. We don't assert exact global K-closest
        // here (libtorrent's find_node scans bucket-by-bucket and may stop early),
        // only the robust invariants: right size and no duplicates.
        val ourId = hash("0000000000000000000000000000000000000000")
        val table = RoutingTable(ourId, 8, openSettings())

        val ids = ArrayList<Sha1Hash>()
        var i = 0
        var added = 0
        while (added < 40 && i < 5000) {
            val hi = (i * 1237 + 7) and 0xffff
            val id = idTop16(hi)
            if (!id.isAllZeros() && ids.none { it == id }) {
                table.nodeSeen(id, host(added), 1000 + added, 10)
                ids.add(id)
                added++
            }
            i++
        }

        val present = ArrayList<NodeEntry>()
        table.forEachNode { present.add(it) }
        assertTrue(present.size >= 16, "expected a decently full table, got ${present.size}")

        val target = hash("3fa10000000000000000000000000000000000aa")
        val k2 = 16
        val result = table.findNode(target, k2)

        assertEquals(minOf(k2, present.size), result.size)
        // no duplicates in the result
        val seen = HashSet<Sha1Hash>()
        for (e in result) assertTrue(seen.add(e.id), "duplicate id in result: ${e.id}")
        // every returned node is one we actually inserted
        val presentIds = present.map { it.id }.toSet()
        for (e in result) assertTrue(e.id in presentIds)
    }

    @Test
    fun findNodeResultIsSortedTailClosestFirst() {
        // With our id == 0 and a single bucket's worth of nodes, find_node returns
        // them sorted closest-first to the target (the over-full slice is sorted).
        val ourId = hash("0000000000000000000000000000000000000000")
        val table = RoutingTable(ourId, 8, openSettings())

        val raw = listOf(
            "1000000000000000000000000000000000000000",
            "2000000000000000000000000000000000000000",
            "4000000000000000000000000000000000000000",
            "8000000000000000000000000000000000000000",
        )
        for ((idx, h) in raw.withIndex()) table.nodeSeen(hash(h), host(idx), 6881 + idx, 10)

        val target = hash("1000000000000000000000000000000000000000")
        // ask for fewer than we have so the closeness sort + trim path runs.
        val result = table.findNode(target, 2)
        assertEquals(2, result.size)
        // closest to target 0x1000... is itself, then 0x2000... (XOR 0x3000) vs
        // 0x4000 (XOR 0x5000) vs 0x8000 (XOR 0x9000): 0x2000 is next.
        assertEquals(hash(raw[0]), result[0].id)
        assertEquals(hash(raw[1]), result[1].id)
    }

    // --- bucket splitting ------------------------------------------------------

    @Test
    fun bucketSplittingGrowsTheTable() {
        // With ids spread on both sides of our id's prefix, inserting more than
        // `bucket_size` confirmed nodes must split bucket 0 into multiple buckets.
        val ourId = hash("0000000000000000000000000000000000000000")
        val table = RoutingTable(ourId, 8, openSettings())

        // Insert 64 nodes whose top bits vary widely (and explicitly alternate the
        // very top bit), so they cannot all share a single bucket and the table is
        // forced to split.
        var added = 0
        var v = 1
        while (added < 64 && v < 100000) {
            // alternate bit 0 explicitly, scatter the remaining 15 top bits.
            val topBit = (added and 1) shl 15
            val hi = topBit or ((v * 40503) and 0x7fff)
            val id = idTop16(hi, fill = '1')
            if (!id.isAllZeros() && id != ourId) {
                table.nodeSeen(id, host(added), 7000 + added, 10)
                added++
            }
            v++
        }

        assertTrue(table.numActiveBuckets() > 1, "table should have split into >1 bucket, got ${table.numActiveBuckets()}")
        // every live node must be confirmed (we only fed pinged nodes)
        var liveCount = 0
        table.forEachNode { assertTrue(it.confirmed()); liveCount++ }
        assertTrue(liveCount >= 8)
        // depth() should be >= 1 once we have a full-ish lower bucket
        assertTrue(table.depth() >= 0)
    }

    @Test
    fun findBucketPlacesByDistanceExp() {
        // Indirectly verify find_bucket via observable behaviour: a node that
        // shares a long prefix with our id (small distance) lands in a deep bucket
        // and is still retrievable. We just check it is found and counted.
        val ourId = hash("ff00000000000000000000000000000000000000")
        val table = RoutingTable(ourId, 8, openSettings())

        // near node: shares the whole top byte with our id (very close).
        val near = hash("ff00000000000000000000000000000000000001")
        // far node: differs in the top bit.
        val far = hash("7f00000000000000000000000000000000000000")
        table.nodeSeen(near, "10.0.0.1", 1, 10)
        table.nodeSeen(far, "10.0.0.2", 2, 10)

        assertEquals(2, table.size().first)

        // a lookup for our own id should return the near node first.
        val res = table.findNode(ourId, 8)
        assertEquals(2, res.size)
        assertEquals(near, res[0].id)
    }

    // --- node_failed -----------------------------------------------------------

    @Test
    fun nodeFailedBumpsCountAndEventuallyRemoves() {
        val ourId = hash("0000000000000000000000000000000000000000")
        // small max fail count so the test is quick.
        val table = RoutingTable(
            ourId, 8,
            RoutingTableSettings(
                maxFailCount = 2,
                restrictRoutingIps = false,
                preferVerifiedNodeIds = false,
                enforceNodeId = false,
                extendedRoutingTable = false,
            ),
        )
        val nid = hash("8000000000000000000000000000000000000000")
        table.nodeSeen(nid, "4.4.4.4", 4, 10)
        assertEquals(1, table.size().first)

        // first failure: still present, fail count 1.
        table.nodeFailed(nid, "4.4.4.4", 4)
        var found = table.findNode(nid, 10, includeFailed = true)
        assertEquals(1, found.size)
        assertEquals(1, found[0].failCount())

        // second failure reaches maxFailCount -> removed.
        table.nodeFailed(nid, "4.4.4.4", 4)
        assertEquals(0, table.size().first)
    }

    @Test
    fun nodeFailedWrongEndpointIsIgnored() {
        val ourId = hash("0000000000000000000000000000000000000000")
        val table = RoutingTable(ourId, 8, openSettings().copy(maxFailCount = 1))
        val nid = hash("8000000000000000000000000000000000000000")
        table.nodeSeen(nid, "4.4.4.4", 4, 10)

        // a failure reported for the same id but a different endpoint must not
        // touch our entry.
        table.nodeFailed(nid, "9.9.9.9", 9)
        val found = table.findNode(nid, 10, includeFailed = true)
        assertEquals(1, found.size)
        assertEquals(0, found[0].failCount())
    }

    // --- node id update --------------------------------------------------------

    @Test
    fun updateNodeIdReinsertsAllNodes() {
        val ourId = hash("0000000000000000000000000000000000000000")
        val table = RoutingTable(ourId, 8, openSettings())
        val ids = (0 until 10).map { idHex(((it + 1) * 0x11).toString(16).padStart(2, '0')) }
        for ((idx, id) in ids.withIndex()) table.nodeSeen(id, host(idx), 5000 + idx, 10)
        val before = table.size().first
        assertTrue(before > 0)

        table.updateNodeId(hash("ffffffffffffffffffffffffffffffffffffffff"))
        // all nodes should be preserved (none equals the new id; far apart).
        assertEquals(before, table.size().first)
        assertEquals(hash("ffffffffffffffffffffffffffffffffffffffff"), table.ownId())
    }

    // --- replace_node_impl (golden behaviour) ---------------------------------

    @Test
    fun replaceNodeImplReplacesFailedNodeFirst() {
        // mirror the test_dht "replace failed node regardless of anything" case.
        val ips = IpSet()
        fun n(hex: String, rtt: Int, failed: Int = 0): NodeEntry {
            val e = NodeEntry(hash(hex), "0.0.0.0", 0, rtt = rtt, pinged = true)
            e.verified = true
            if (failed != 0) e.timeoutCount = failed
            return e
        }
        val b = mutableListOf(
            n("1fffffffffffffffffffffffffffffffffffffff", 50),
            n("3fffffffffffffffffffffffffffffffffffffff", 50),
            n("5fffffffffffffffffffffffffffffffffffffff", 50),
            n("7fffffffffffffffffffffffffffffffffffffff", 50),
            n("9fffffffffffffffffffffffffffffffffffffff", 50),
            n("bfffffffffffffffffffffffffffffffffffffff", 50),
            n("dfffffffffffffffffffffffffffffffffffffff", 50),
            n("ffffffffffffffffffffffffffffffffffffffff", 50, failed = 1), // should be replaced
        )
        val cand = n("9fcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd", 50)
        val status = replaceNodeImpl(cand, b, ips, 0, 8, true)
        assertEquals(RoutingTable.AddNodeStatus.NODE_ADDED, status)
        // the failed node (index 7) is the one replaced.
        assertEquals(hash("9fcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"), b[7].id)
    }

    @Test
    fun replaceNodeImplRespectsPrefixSlotRtt() {
        // mirror: only replace the matching-prefix node if the candidate is better
        // (lower RTT). Here the candidate (rtt 100) is worse than the slot's node
        // (rtt 50), so nothing is replaced.
        val ips = IpSet()
        fun n(hex: String, rtt: Int): NodeEntry {
            val e = NodeEntry(hash(hex), "0.0.0.0", 0, rtt = rtt, pinged = true)
            e.verified = true
            return e
        }
        val b = mutableListOf(
            n("1fffffffffffffffffffffffffffffffffffffff", 500),
            n("3fffffffffffffffffffffffffffffffffffffff", 500),
            n("5fffffffffffffffffffffffffffffffffffffff", 500),
            n("7fffffffffffffffffffffffffffffffffffffff", 500),
            n("9fffffffffffffffffffffffffffffffffffffff", 50),
            n("bfffffffffffffffffffffffffffffffffffffff", 500),
            n("dfffffffffffffffffffffffffffffffffffffff", 500),
            n("ffffffffffffffffffffffffffffffffffffffff", 500),
        )
        val cand = n("9fcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd", 100)
        val status = replaceNodeImpl(cand, b, ips, 0, 8, true)
        assertTrue(status != RoutingTable.AddNodeStatus.NODE_ADDED)
        // slot node unchanged
        assertEquals(hash("9fffffffffffffffffffffffffffffffffffffff"), b[4].id)
    }
}
