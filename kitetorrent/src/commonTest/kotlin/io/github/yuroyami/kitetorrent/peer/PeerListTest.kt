package io.github.yuroyami.kitetorrent.peer

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the peer-list port ([PeerList], [TorrentPeer], [peerPriority],
 * [PeerAddress], [Crc32c]).
 *
 * Three families of checks:
 *  1. **Golden cryptographic vectors** — [Crc32c] against the canonical
 *     CRC-32C test string, and [peerPriority] against every vector in
 *     libtorrent's own `test/test_peer_priority.cpp` (the two absolute BEP 40
 *     values plus the same-port / masked /24/16 cases). These are exact ground
 *     truth copied from upstream.
 *  2. **Address predicates** — [PeerAddress.isLocal] / link-local parsing.
 *  3. **Data-structure behaviour** — dedupe by endpoint, ban / seed handling,
 *     and connect-candidate selection skipping banned, connected and (when
 *     finished) seed peers, with the documented ranking tie-breakers.
 *
 * A seeded [Random] is injected into every [PeerList] so the (randomized)
 * weeding cursor is deterministic; none of these tests hit the size cap anyway.
 */
class PeerListTest {

    private fun newList() = PeerList(Random(1234))

    // -- CRC-32C golden vector -------------------------------------------------

    @Test
    fun crc32cCanonicalVector() {
        // CRC-32C (Castagnoli) of the ASCII string "123456789" is 0xE3069283.
        val v = Crc32c.compute("123456789".encodeToByteArray())
        assertEquals(0xE3069283.toInt(), v)
    }

    // -- peer_priority golden vectors (test/test_peer_priority.cpp) ------------

    /** CRC-32C of a hex string, mirroring upstream's `hash_buffer`. */
    private fun hashBuffer(hex: String): Int {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((digit(hex[i * 2]) shl 4) or digit(hex[i * 2 + 1])).toByte()
        }
        return Crc32c.compute(out)
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> 10 + (c - 'a')
        in 'A'..'F' -> 10 + (c - 'A')
        else -> throw IllegalArgumentException("bad hex $c")
    }

    private fun prio(h1: String, p1: Int, h2: String, p2: Int): Int =
        peerPriority(PeerAddress.parseOrNull(h1)!!, p1, PeerAddress.parseOrNull(h2)!!, p2)

    @Test
    fun peerPrioritySameAddressHashesPorts() {
        // same IP -> hash the two ports, sorted: 0x12c then 0x4d2 => "012c04d2"
        assertEquals(hashBuffer("012c04d2"), prio("230.12.123.3", 0x4d2, "230.12.123.3", 0x12c))
    }

    @Test
    fun peerPrioritySame24() {
        assertEquals(hashBuffer("e60c7b01e60c7b03"), prio("230.12.123.1", 0x4d2, "230.12.123.3", 0x12c))
    }

    @Test
    fun peerPrioritySame16() {
        // masked by 0xffffff55
        assertEquals(hashBuffer("e60c1701e60c7b01"), prio("230.12.23.1", 0x4d2, "230.12.123.3", 0x12c))
    }

    @Test
    fun peerPriorityDifferent16() {
        // masked by 0xffff5555
        assertEquals(hashBuffer("e60c5101e6781501"), prio("230.120.23.1", 0x4d2, "230.12.123.3", 0x12c))
    }

    @Test
    fun peerPriorityBep40AbsoluteVectors() {
        assertEquals(0xec2d7224.toInt(), prio("123.213.32.10", 0, "98.76.54.32", 0))
        assertEquals(0x99568189.toInt(), prio("123.213.32.10", 0, "123.213.32.234", 0))
    }

    @Test
    fun peerPriorityIpv6MaskedVectors() {
        // /48 shared prefix -> mask from byte 6 onward with 0x55
        assertEquals(
            hashBuffer("ffffffffff0fff555555555555555555" + "ffffffffffffff555555555555555555"),
            prio(
                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 0x4d2,
                "ffff:ffff:ff0f:ffff:ffff:ffff:ffff:ffff", 0x12c,
            ),
        )
        // differ in /32 -> full 0xffffffffffff5555... mask
        assertEquals(
            hashBuffer("ffff0fffffff55555555555555555555" + "ffffffffffff55555555555555555555"),
            prio(
                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 0x4d2,
                "ffff:0fff:ffff:ffff:ffff:ffff:ffff:ffff", 0x12c,
            ),
        )
        // identical v6 addresses fall back to the port hash
        assertEquals(
            hashBuffer("012c04d2"),
            prio(
                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 0x4d2,
                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 0x12c,
            ),
        )
    }

    @Test
    fun peerPriorityIsSymmetric() {
        assertEquals(
            prio("230.12.123.1", 0x4d2, "230.12.123.3", 0x12c),
            prio("230.12.123.3", 0x12c, "230.12.123.1", 0x4d2),
        )
    }

    // -- address predicates ---------------------------------------------------

    @Test
    fun localAddressDetection() {
        assertTrue(PeerAddress.parseOrNull("192.168.0.1")!!.isLocal)
        assertTrue(PeerAddress.parseOrNull("10.1.2.3")!!.isLocal)
        assertTrue(PeerAddress.parseOrNull("172.16.5.5")!!.isLocal)
        assertTrue(PeerAddress.parseOrNull("127.0.0.1")!!.isLocal)
        assertTrue(PeerAddress.parseOrNull("169.254.1.1")!!.isLocal)
        assertTrue(PeerAddress.parseOrNull("100.64.0.1")!!.isLocal)
        assertFalse(PeerAddress.parseOrNull("8.8.8.8")!!.isLocal)
        assertFalse(PeerAddress.parseOrNull("172.32.0.1")!!.isLocal) // just outside 172.16/12
    }

    @Test
    fun ipv6ParseAndLinkLocal() {
        val a = PeerAddress.parseOrNull("fe80::1")
        assertNotNull(a)
        assertTrue(a.isV6)
        assertTrue(a.isV6LinkLocal)
        assertEquals(16, a.bytes.size)

        val full = PeerAddress.parseOrNull("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
        assertNotNull(full)
        assertFalse(full.isV6LinkLocal)
        for (b in full.bytes) assertEquals(0xFF.toByte(), b)

        assertNull(PeerAddress.parseOrNull("not-an-ip"))
    }

    // -- add / dedupe ---------------------------------------------------------

    @Test
    fun addPeerDedupesByEndpoint() {
        val list = newList()
        val state = TorrentState()

        val p1 = list.addPeer("1.2.3.4", 6881, PeerSource.TRACKER, state = state)
        assertNotNull(p1)
        assertTrue(state.firstTimeSeen)

        // adding the same endpoint again returns the SAME object and is not "first time"
        val p2 = list.addPeer("1.2.3.4", 6881, PeerSource.PEX, state = state)
        assertSame(p1, p2)
        assertFalse(state.firstTimeSeen)
        assertEquals(1, list.numPeers())

        // the source bits accumulate
        assertEquals(PeerSource.TRACKER or PeerSource.PEX, p1.source)

        // a different port on the same IP is, in single-connection mode, the same peer
        val p3 = list.addPeer("1.2.3.4", 7000, PeerSource.TRACKER, state = state)
        assertSame(p1, p3)
        assertEquals(1, list.numPeers())

        // a genuinely different IP is a new peer
        val p4 = list.addPeer("5.6.7.8", 6881, PeerSource.TRACKER, state = state)
        assertNotNull(p4)
        assertTrue(state.firstTimeSeen)
        assertEquals(2, list.numPeers())
    }

    @Test
    fun addPeerRejectsInvalidEndpoints() {
        val list = newList()
        assertNull(list.addPeer("1.2.3.4", 0, PeerSource.TRACKER))   // port 0
        assertNull(list.addPeer("1.2.3.4", 1, PeerSource.TRACKER))   // port 1
        assertNull(list.addPeer("0.0.0.0", 6881, PeerSource.TRACKER)) // unspecified
        assertNull(list.addPeer("fe80::1", 6881, PeerSource.TRACKER)) // v6 link-local
        assertEquals(0, list.numPeers())
    }

    @Test
    fun multipleConnectionsPerIpKeepsDistinctPorts() {
        val list = newList()
        val state = TorrentState().apply { allowMultipleConnectionsPerIp = true }

        val a = list.addPeer("1.2.3.4", 6881, PeerSource.TRACKER, state = state)
        val b = list.addPeer("1.2.3.4", 7000, PeerSource.TRACKER, state = state)
        assertNotNull(a)
        assertNotNull(b)
        assertFalse(a === b)
        assertEquals(2, list.numPeers())
    }

    // -- ban / seed bookkeeping ----------------------------------------------

    @Test
    fun banRemovesConnectCandidate() {
        val list = newList()
        val state = TorrentState()

        val p = list.addPeer("1.2.3.4", 6881, PeerSource.TRACKER, state = state)!!
        assertTrue(list.isConnectCandidate(p))
        assertEquals(1, list.numConnectCandidates())

        assertTrue(list.banPeer(p))
        assertTrue(p.banned)
        assertFalse(list.isConnectCandidate(p))
        assertEquals(0, list.numConnectCandidates())
    }

    @Test
    fun seedAndFinishedAffectCandidacy() {
        val list = newList()
        val state = TorrentState()

        val p = list.addPeer("1.2.3.4", 6881, PeerSource.TRACKER, state = state)!!
        list.setSeed(p, true)
        assertTrue(p.seed)
        assertEquals(1, list.numSeeds())

        // while not finished, a seed is still a connect candidate
        assertTrue(list.isConnectCandidate(p))

        // once finished, a seed is no longer a candidate
        state.isFinished = true
        list.recalculateConnectCandidates(state)
        assertFalse(list.isConnectCandidate(p))
        assertEquals(0, list.numConnectCandidates())

        // clearing the seed flag restores it (still finished), and decrements numSeeds
        list.setSeed(p, false)
        assertEquals(0, list.numSeeds())
        assertTrue(list.isConnectCandidate(p))
    }

    @Test
    fun failcountThresholdRemovesCandidate() {
        val list = newList()
        val p = list.addPeer("1.2.3.4", 6881, PeerSource.TRACKER)!!
        assertTrue(list.isConnectCandidate(p))

        // default max_failcount is 3; three failures should drop it
        list.incFailcount(p)
        list.incFailcount(p)
        assertTrue(list.isConnectCandidate(p)) // failcount 2 < 3
        list.incFailcount(p)
        assertEquals(3, p.failcount)
        assertFalse(list.isConnectCandidate(p)) // failcount 3 >= 3
        assertEquals(0, list.numConnectCandidates())
    }

    // -- connect-candidate selection -----------------------------------------

    @Test
    fun connectOnePeerSkipsBannedAndConnected() {
        val list = newList()
        val state = TorrentState()

        val a = list.addPeer("8.8.8.8", 6881, PeerSource.TRACKER, state = state)!!
        val banned = list.addPeer("8.8.8.9", 6881, PeerSource.TRACKER, state = state)!!
        val connected = list.addPeer("8.8.8.10", 6881, PeerSource.TRACKER, state = state)!!
        val c = list.addPeer("8.8.8.11", 6881, PeerSource.TRACKER, state = state)!!

        list.banPeer(banned)
        list.setConnected(connected, true)

        // only `a` and `c` are real candidates now
        assertEquals(2, list.numConnectCandidates())

        // drain all candidates; a real caller marks each returned peer connected,
        // which removes it from candidacy (otherwise connect_one_peer keeps
        // offering the same peers, exactly as upstream does).
        val returned = HashSet<TorrentPeer>()
        var next = list.connectOnePeer(100, state)
        while (next != null) {
            assertFalse(next.banned, "connectOnePeer returned a banned peer")
            assertFalse(next.connected, "connectOnePeer returned a connected peer")
            assertTrue(next.connectable)
            returned.add(next)
            list.setConnected(next, true) // simulate dialing it
            next = list.connectOnePeer(100, state)
        }

        assertTrue(a in returned)
        assertTrue(c in returned)
        assertFalse(banned in returned)
        assertFalse(connected in returned)
        assertEquals(2, returned.size)
        assertEquals(0, list.numConnectCandidates())
    }

    @Test
    fun connectOnePeerPrefersLocalThenLowerFailcount() {
        val list = newList()
        val state = TorrentState()

        // a remote peer and a LAN peer, both fresh candidates
        val remote = list.addPeer("8.8.8.8", 6881, PeerSource.TRACKER, state = state)!!
        val local = list.addPeer("192.168.0.5", 6881, PeerSource.TRACKER, state = state)!!

        // tie-breaker chain: equal failcount(0) -> local wins
        val first = list.connectOnePeer(100, state)
        assertSame(local, first)

        // now give the local peer a higher failcount than the remote;
        // lower failcount wins outright, so remote should be preferred next round
        list.clear()
        val state2 = TorrentState()
        val remote2 = list.addPeer("8.8.8.8", 6881, PeerSource.TRACKER, state = state2)!!
        val local2 = list.addPeer("192.168.0.5", 6881, PeerSource.TRACKER, state = state2)!!
        list.setFailcount(local2, 2)
        val pick = list.connectOnePeer(100, state2)
        assertSame(remote2, pick)
    }

    @Test
    fun reconnectBackoffSkipsRecentlyTriedPeer() {
        val list = newList()
        val state = TorrentState() // min_reconnect_time = 60

        val p = list.addPeer("8.8.8.8", 6881, PeerSource.TRACKER, state = state)!!
        // simulate having just connected at session-time 100 with no failures
        list.setConnected(p, true)
        list.onConnectionClosed(p, sessionTime = 100, failed = false, state = state)
        assertEquals(100, p.lastConnected)
        assertEquals(0, p.failcount)

        // at session-time 130 (< 100 + (0+1)*60) it is still backing off -> no candidate
        assertNull(list.connectOnePeer(130, state))

        // at session-time 161 (>= 100 + 60) it is eligible again
        assertSame(p, list.connectOnePeer(161, state))
    }

    @Test
    fun erasePeerUpdatesCountsAndReportsErased() {
        val list = newList()
        val state = TorrentState()

        val a = list.addPeer("8.8.8.8", 6881, PeerSource.TRACKER, state = state)!!
        val b = list.addPeer("8.8.8.9", 6881, PeerSource.TRACKER, state = state)!!
        list.setSeed(b, true)
        assertEquals(2, list.numPeers())
        assertEquals(1, list.numSeeds())
        assertEquals(2, list.numConnectCandidates())

        state.clearErased()
        list.erasePeer(b, state)
        assertEquals(1, list.numPeers())
        assertEquals(0, list.numSeeds())
        assertEquals(1, list.numConnectCandidates())
        assertEquals(listOf(b), state.erased)
        assertFalse(list.hasPeer(b))
        assertTrue(list.hasPeer(a))
    }

    @Test
    fun peersStaySortedByAddress() {
        val list = newList()
        // insert out of order; the stored vector must come back address-sorted
        list.addPeer("9.9.9.9", 6881, PeerSource.TRACKER)
        list.addPeer("1.1.1.1", 6881, PeerSource.TRACKER)
        list.addPeer("5.5.5.5", 6881, PeerSource.TRACKER)

        val hosts = list.peers().map { it.host }
        assertEquals(listOf("1.1.1.1", "5.5.5.5", "9.9.9.9"), hosts)
    }
}
