package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.peer.PeerAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [io.github.yuroyami.kitetorrent.dht] node-id helpers — the port of
 * `src/kademlia/node_id.cpp`.
 *
 * The BEP 42 golden vectors are the canonical ones published in the spec and
 * pinned in libtorrent's own `test/test_dht.cpp` (the `ips[]` / `rs[]` /
 * `prefixes[][3]` table), so they validate the CRC32C wiring and the exact byte
 * layout of [generateIdImpl] independently of our implementation.
 */
class NodeIdTest {

    private fun hash(hex: String): Sha1Hash = Digest32.fromHex(hex)

    // --- distance / XOR metric -------------------------------------------------

    @Test
    fun distanceIsXor() {
        val a = hash("0000000000000000000000000000000000000000")
        val b = hash("ffffffffffffffffffffffffffffffffffffffff")
        // a ^ a == 0
        assertTrue(distance(a, a).isAllZeros())
        // 0 ^ ff..ff == ff..ff
        assertEquals(b, distance(a, b))
        // distance is symmetric
        assertEquals(distance(a, b), distance(b, a))
    }

    @Test
    fun distanceExpMatchesUpstreamConvention() {
        // identical ids -> distance 0 -> 159 - 160 clamped to 0
        val z = hash("0000000000000000000000000000000000000000")
        assertEquals(0, distanceExp(z, z))

        // differ only in the top bit: distance = 0x8000...00, clz = 0,
        // distance_exp = max(159 - 0, 0) = 159.
        val top = hash("8000000000000000000000000000000000000000")
        assertEquals(159, distanceExp(z, top))

        // differ only in the lowest bit: distance = 0x00..01, clz = 159,
        // distance_exp = max(159 - 159, 0) = 0.
        val one = hash("0000000000000000000000000000000000000001")
        assertEquals(0, distanceExp(z, one))

        // differ at bit 158 (second-lowest): distance = 0x00..02, clz = 158,
        // distance_exp = 1.
        val two = hash("0000000000000000000000000000000000000002")
        assertEquals(1, distanceExp(z, two))
    }

    @Test
    fun compareRefOrdersByXorDistance() {
        val ref = hash("1000000000000000000000000000000000000000")
        // n1 is identical to ref (distance 0) -> closer than anything else.
        val n1 = hash("1000000000000000000000000000000000000000")
        val n2 = hash("1000000000000000000000000000000000000001")
        assertTrue(compareRef(n1, n2, ref))
        assertFalse(compareRef(n2, n1, ref))
    }

    @Test
    fun minDistanceExpPicksClosest() {
        val n = hash("0000000000000000000000000000000000000000")
        val ids = listOf(
            hash("8000000000000000000000000000000000000000"), // exp 159
            hash("0000000000000000000000000000000000000002"), // exp 1
            hash("4000000000000000000000000000000000000000"), // exp 158
        )
        assertEquals(1, minDistanceExp(n, ids))
    }

    // --- BEP 42 secure ids (canonical golden vectors) --------------------------

    @Test
    fun bep42CanonicalVectors() {
        // From BEP 42 / libtorrent test_dht.cpp: (ip, r) -> first 3 id bytes.
        // The low 3 bits of byte[2] are random, so only the top 5 are checked.
        data class V(val ip: String, val r: Int, val b0: Int, val b1: Int, val b2: Int)
        val vectors = listOf(
            V("124.31.75.21", 1, 0x5f, 0xbf, 0xbf),
            V("21.75.31.124", 86, 0x5a, 0x3c, 0xe9),
            V("65.23.51.170", 22, 0xa5, 0xd4, 0x32),
            V("84.124.73.14", 65, 0x1b, 0x03, 0x21),
            V("43.213.53.83", 90, 0xe5, 0x6f, 0x6c),
        )
        for (v in vectors) {
            val ip = PeerAddress.parseOrNull(v.ip)!!
            // fixed RNG so the run is reproducible; only the fixed bits are asserted
            val id = generateIdImpl(ip, v.r, Random(0))
            assertEquals(v.b0, id[0], "byte0 for ${v.ip}")
            assertEquals(v.b1, id[1], "byte1 for ${v.ip}")
            assertEquals(v.b2 and 0xf8, id[2] and 0xf8, "byte2 top5 for ${v.ip}")
            assertEquals(v.r and 0xff, id[19], "byte19 (r) for ${v.ip}")
        }
    }

    @Test
    fun bep42FullVectorWithLowBitsKnown() {
        // 124.31.75.21, r=1 produces the well-known full prefix 5fbfbff1... only
        // when the random low-3-bits of byte[2] happen to be 1. We can't control
        // the upstream RNG, but we *can* assert that the fixed bits match the
        // documented full id from test_dht.cpp.
        val ip = PeerAddress.parseOrNull("124.31.75.21")!!
        val id = generateIdImpl(ip, 1, Random(12345))
        val expected = hash("5fbfbff10c5d6a4ec8a88e4c6ab4c28b95eee401")
        assertEquals(expected[0], id[0])
        assertEquals(expected[1], id[1])
        assertEquals(expected[2] and 0xf8, id[2] and 0xf8)
    }

    @Test
    fun generateIdImplIsDeterministicGivenRng() {
        val ip = PeerAddress.parseOrNull("8.8.8.8")!!
        val a = generateIdImpl(ip, 42, Random(99))
        val b = generateIdImpl(ip, 42, Random(99))
        assertEquals(a, b)
        // byte[19] always carries r
        assertEquals(42, a[19])
    }

    @Test
    fun verifyIdAcceptsGeneratedAndRejectsForeign() {
        // a genuinely public IP so isLocal == false and verification runs.
        val ip = PeerAddress.parseOrNull("124.31.75.21")!!
        val good = generateIdImpl(ip, 1, Random(7))
        assertTrue(verifyId(good, ip))

        // an id from a different IP must not verify against this one.
        val otherIp = PeerAddress.parseOrNull("8.8.8.8")!!
        val foreign = generateIdImpl(otherIp, 1, Random(7))
        // overwhelmingly likely to differ in the fixed prefix bits
        assertFalse(verifyId(foreign, ip))
    }

    @Test
    fun verifyIdAlwaysTrueForLocalSource() {
        // 192.168.x is local -> verify_id short-circuits to true for any id.
        val local = PeerAddress.parseOrNull("192.168.1.1")!!
        val anyId = hash("0123456789abcdef0123456789abcdef01234567")
        assertTrue(verifyId(anyId, local))
    }

    @Test
    fun generateIdImplV6UsesEightOctets() {
        // Smoke test the IPv6 path: it must not throw and must place r at byte 19.
        val ip = PeerAddress.parseOrNull("2001:1111:1111:1111:1111:1111:1111:1111")!!
        assertTrue(ip.isV6)
        val id = generateIdImpl(ip, 3, Random(1))
        assertEquals(3, id[19])
        // and it should verify against its own source IP
        assertTrue(verifyId(id, ip))
    }

    // --- prefix helpers --------------------------------------------------------

    @Test
    fun generatePrefixMaskMatchesUpstreamLoop() {
        // 0 bits -> all zero
        assertTrue(generatePrefixMask(0).isAllZeros())

        // 8 bits -> first byte 0xff, rest 0
        val m8 = generatePrefixMask(8)
        assertEquals(0xff, m8[0])
        assertEquals(0x00, m8[1])

        // 4 bits -> top nibble of first byte set: 0xf0
        assertEquals(0xf0, generatePrefixMask(4)[0])

        // 12 bits -> first byte 0xff, second byte 0xf0
        val m12 = generatePrefixMask(12)
        assertEquals(0xff, m12[0])
        assertEquals(0xf0, m12[1])

        // 160 bits -> all ones
        assertEquals(Digest32.max(NODE_ID_SIZE), generatePrefixMask(160))
    }

    @Test
    fun matchingPrefixShiftsThenMasks() {
        val nid = hash("a5d4320000000000000000000000000000000000")
        // offset 0: top byte is 0xa5; (0xa5 & 0xf0) == 0xa0
        assertTrue(matchingPrefix(nid, 0xf0, 0xa0, 0))
        assertFalse(matchingPrefix(nid, 0xf0, 0xb0, 0))
        // offset 8: top byte becomes 0xd4; (0xd4 & 0xf0) == 0xd0
        assertTrue(matchingPrefix(nid, 0xf0, 0xd0, 8))
    }

    @Test
    fun generateRandomIdHasRightSize() {
        val r = generateRandomId(Random(0))
        assertEquals(NODE_ID_SIZE, r.size)
    }
}
