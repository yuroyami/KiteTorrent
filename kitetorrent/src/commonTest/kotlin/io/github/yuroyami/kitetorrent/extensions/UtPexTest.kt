package io.github.yuroyami.kitetorrent.extensions

import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BEP-11 `ut_pex` codec tests. They check the compact 4-byte-IP + 2-byte-port encoding
 * against an exact golden byte vector and round-trips added/dropped IPv4 (and IPv6)
 * peers with their flags.
 */
class UtPexTest {

    // --- golden compact encoding ----------------------------------------------

    @Test
    fun compactIpv4_goldenBytes() {
        // 1.2.3.4:6881 -> IP 01 02 03 04, port 6881 = 0x1AE1 -> 1a e1
        val peer = UtPex.peerFromIpv4("1.2.3.4", 6881)
        assertNotNull(peer)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), peer.ip)
        assertEquals(6881, peer.port)

        val wire = UtPex.encode(added = listOf(peer))
        val root = Bdecode.decode(wire)
        val added = root.dictFindStringBytes("added")
        assertNotNull(added)
        // the exact 6 compact bytes: 01 02 03 04 1a e1
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x1a, 0xe1.toByte()), added)
    }

    @Test
    fun parseBack_fromGoldenCompactBytes() {
        // construct the wire dict by hand around the known compact bytes and parse it
        // added = 01 02 03 04 1a e1 (1.2.3.4:6881), added.f = one zero flag byte
        val compact = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x1a, 0xe1.toByte())
        val flags = byteArrayOf(0x00)
        val wire = buildPexDict(added = compact, addedF = flags)

        val msg = UtPex.parse(wire)
        assertNotNull(msg)
        assertEquals(1, msg.added.size)
        val p = msg.added[0]
        assertEquals("1.2.3.4", p.host)
        assertEquals(6881, p.port)
        assertEquals("1.2.3.4:6881", p.hostPort)
        assertEquals(0, p.flags)
    }

    // --- round trips -----------------------------------------------------------

    @Test
    fun ipv4RoundTrip_twoPeersWithFlags() {
        val a = UtPex.peerFromIpv4("1.2.3.4", 6881, flags = UtPex.PexFlags.SEED)!!
        val b = UtPex.peerFromIpv4("10.0.0.1", 51413, flags = UtPex.PexFlags.UTP or UtPex.PexFlags.ENCRYPTION)!!

        val wire = UtPex.encode(added = listOf(a, b))
        val msg = UtPex.parse(wire)
        assertNotNull(msg)
        assertEquals(2, msg.added.size)

        val byHost = msg.added.associateBy { it.host }
        assertEquals(6881, byHost["1.2.3.4"]!!.port)
        assertEquals(UtPex.PexFlags.SEED, byHost["1.2.3.4"]!!.flags)
        assertEquals(51413, byHost["10.0.0.1"]!!.port)
        assertEquals(UtPex.PexFlags.UTP or UtPex.PexFlags.ENCRYPTION, byHost["10.0.0.1"]!!.flags)
    }

    @Test
    fun droppedHasNoFlags() {
        val dropped = UtPex.peerFromIpv4("8.8.8.8", 80)!!
        val wire = UtPex.encode(added = emptyList(), dropped = listOf(dropped))
        val msg = UtPex.parse(wire)
        assertNotNull(msg)
        assertEquals(0, msg.added.size)
        assertEquals(1, msg.dropped.size)
        assertEquals("8.8.8.8", msg.dropped[0].host)
        assertEquals(80, msg.dropped[0].port)
        assertEquals(0, msg.dropped[0].flags)

        // dropped key must carry no flags string of its own
        val root = Bdecode.decode(wire)
        assertEquals(BdecodeNode.Type.NONE, root.dictFind("dropped.f").type)
    }

    @Test
    fun portBoundaries() {
        // port 1 and 65535 are the edges that exercise both bytes
        val lo = UtPex.peerFromIpv4("1.1.1.1", 1)!!
        val hi = UtPex.peerFromIpv4("2.2.2.2", 65535)!!
        val msg = UtPex.parse(UtPex.encode(listOf(lo, hi)))!!
        val byHost = msg.added.associateBy { it.host }
        assertEquals(1, byHost["1.1.1.1"]!!.port)
        assertEquals(65535, byHost["2.2.2.2"]!!.port)
    }

    // --- libtorrent-faithful parse quirks -------------------------------------

    @Test
    fun addedIgnoredWhenFlagLengthMismatches() {
        // libtorrent: only parse `added` if added.f.length == added.length / 6.
        // Two peers (12 bytes) but only one flag byte -> the whole added list is dropped.
        val twoPeers = byteArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x1a, 0xe1.toByte(),
            0x05, 0x06, 0x07, 0x08, 0x1a, 0xe1.toByte(),
        )
        val oneFlag = byteArrayOf(0x00)
        val wire = buildPexDict(added = twoPeers, addedF = oneFlag)
        val msg = UtPex.parse(wire)
        assertNotNull(msg)
        assertEquals(0, msg.added.size)
    }

    @Test
    fun ltV2FlagStrippedOnReceive() {
        // 0x10 (pex_lt_v2) must be masked off; 0x02 (seed) must survive.
        val compact = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x1a, 0xe1.toByte())
        val flags = byteArrayOf((UtPex.PexFlags.LT_V2 or UtPex.PexFlags.SEED).toByte())
        val wire = buildPexDict(added = compact, addedF = flags)
        val msg = UtPex.parse(wire)!!
        assertEquals(1, msg.added.size)
        assertEquals(UtPex.PexFlags.SEED, msg.added[0].flags)
        assertEquals(0, msg.added[0].flags and UtPex.PexFlags.LT_V2)
    }

    @Test
    fun parse_rejectsNonDict() {
        assertNull(UtPex.parse("i0e".encodeToByteArray()))
        assertNull(UtPex.parse(ByteArray(0)))
        assertNull(UtPex.parse("garbage".encodeToByteArray()))
    }

    @Test
    fun parse_emptyDictYieldsEmptyLists() {
        val msg = UtPex.parse("de".encodeToByteArray())
        assertNotNull(msg)
        assertTrue(msg.added.isEmpty())
        assertTrue(msg.dropped.isEmpty())
    }

    @Test
    fun trailingPartialEntryIgnored() {
        // 7 bytes is one full 6-byte peer plus a stray byte; the stray is dropped.
        val compact = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x1a, 0xe1.toByte(), 0x7f)
        // added.f length must equal added.length / 6 == 1 for the list to be read
        val flags = byteArrayOf(0x00)
        val wire = buildPexDict(added = compact, addedF = flags)
        val msg = UtPex.parse(wire)!!
        assertEquals(1, msg.added.size)
        assertEquals("1.2.3.4", msg.added[0].host)
    }

    // --- IPv6 ------------------------------------------------------------------

    @Test
    fun ipv6RoundTrip() {
        // 2001:db8::1 expands to bytes 20 01 0d b8 00..00 01, port 6881
        val ip6 = ByteArray(16)
        ip6[0] = 0x20; ip6[1] = 0x01; ip6[2] = 0x0d; ip6[3] = 0xb8.toByte(); ip6[15] = 0x01
        val peer = UtPex.PexPeer(ip6, 6881, UtPex.PexFlags.HOLEPUNCH)
        assertTrue(peer.isV6)

        val wire = UtPex.encode(added = listOf(peer))
        // it must land in added6 / added6.f, not the v4 keys
        val root = Bdecode.decode(wire)
        assertEquals(18, root.dictFindStringBytes("added6")!!.size)
        assertEquals(0, root.dictFindStringBytes("added")!!.size)

        val msg = UtPex.parse(wire)!!
        assertEquals(1, msg.added.size)
        val p = msg.added[0]
        assertTrue(p.isV6)
        assertContentEquals(ip6, p.ip)
        assertEquals(6881, p.port)
        assertEquals(UtPex.PexFlags.HOLEPUNCH, p.flags)
        assertEquals("2001:db8:0:0:0:0:0:1", p.host)
    }

    @Test
    fun mixedV4AndV6Split() {
        val v4 = UtPex.peerFromIpv4("3.4.5.6", 1000)!!
        val ip6 = ByteArray(16).also { it[15] = 0x01 } // ::1
        val v6 = UtPex.PexPeer(ip6, 2000)
        val wire = UtPex.encode(added = listOf(v4, v6))

        val root = Bdecode.decode(wire)
        assertEquals(6, root.dictFindStringBytes("added")!!.size)   // one v4 peer
        assertEquals(18, root.dictFindStringBytes("added6")!!.size) // one v6 peer

        val msg = UtPex.parse(wire)!!
        assertEquals(2, msg.added.size)
        assertEquals(1, msg.added.count { it.isV4 })
        assertEquals(1, msg.added.count { it.isV6 })
    }

    // --- helper: assemble a raw PEX dict from compact byte strings -------------

    /**
     * Build a bencode dict `{added:<compact>, added.f:<flags>}` (and any v6 keys if
     * given) without going through [UtPex.encode], so a test can drive [UtPex.parse]
     * with arbitrary, possibly-malformed compact strings.
     */
    private fun buildPexDict(
        added: ByteArray? = null,
        addedF: ByteArray? = null,
        dropped: ByteArray? = null,
        added6: ByteArray? = null,
        added6F: ByteArray? = null,
        dropped6: ByteArray? = null,
    ): ByteArray {
        // emit keys in canonical (sorted) order manually:
        // added < added.f < added6 < added6.f < dropped < dropped6
        val parts = ArrayList<Pair<String, ByteArray>>()
        if (added != null) parts.add("added" to added)
        if (addedF != null) parts.add("added.f" to addedF)
        if (added6 != null) parts.add("added6" to added6)
        if (added6F != null) parts.add("added6.f" to added6F)
        if (dropped != null) parts.add("dropped" to dropped)
        if (dropped6 != null) parts.add("dropped6" to dropped6)
        parts.sortBy { it.first }

        val out = ArrayList<Byte>()
        out.add('d'.code.toByte())
        for ((k, v) in parts) {
            val kb = k.encodeToByteArray()
            for (b in kb.size.toString().encodeToByteArray()) out.add(b)
            out.add(':'.code.toByte())
            for (b in kb) out.add(b)
            for (b in v.size.toString().encodeToByteArray()) out.add(b)
            out.add(':'.code.toByte())
            for (b in v) out.add(b)
        }
        out.add('e'.code.toByte())
        return out.toByteArray()
    }
}
