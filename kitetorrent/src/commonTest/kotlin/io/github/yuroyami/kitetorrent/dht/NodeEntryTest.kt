package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [NodeEntry] — the port of `src/kademlia/node_entry.cpp` and the
 * inline accessors in `node_entry.hpp`. The counter/flag semantics are pinned
 * verbatim from the C++ (sentinels `0xff` "never pinged", `0xffff` "rtt
 * unknown"), and the RTT EWMA reproduces `rtt*2/3 + new/3`.
 */
class NodeEntryTest {

    private fun hash(hex: String): Sha1Hash = Digest32.fromHex(hex)
    private val anId = hash("0123456789abcdef0123456789abcdef01234567")

    @Test
    fun defaultsForHeardAboutEntry() {
        // node_entry(ep): unknown id, not pinged, rtt unknown, not verified.
        val e = NodeEntry("8.8.8.8", 6881)
        assertTrue(e.id.isAllZeros())
        assertEquals(NodeEntry.RTT_UNKNOWN, e.rtt)
        assertEquals(NodeEntry.NOT_PINGED, e.timeoutCount)
        assertFalse(e.pinged())
        assertFalse(e.confirmed())
        assertFalse(e.verified)
    }

    @Test
    fun pingedEntryStartsConfirmed() {
        val e = NodeEntry(anId, "8.8.8.8", 6881, rtt = 42, pinged = true)
        assertTrue(e.pinged())
        assertTrue(e.confirmed())
        assertEquals(0, e.timeoutCount)
        assertEquals(42, e.rtt)
        assertEquals(0, e.failCount())
    }

    @Test
    fun timedOutBumpsFailCountOnlyWhenPinged() {
        val notPinged = NodeEntry(anId, "8.8.8.8", 6881)
        notPinged.timedOut()
        // never-pinged: stays at the sentinel, fail count reported as 0
        assertEquals(NodeEntry.NOT_PINGED, notPinged.timeoutCount)
        assertEquals(0, notPinged.failCount())

        val pinged = NodeEntry(anId, "8.8.8.8", 6881, pinged = true)
        pinged.timedOut()
        assertEquals(1, pinged.failCount())
        assertFalse(pinged.confirmed())
        pinged.timedOut()
        assertEquals(2, pinged.failCount())
        pinged.resetFailCount()
        assertEquals(0, pinged.failCount())
        assertTrue(pinged.confirmed())
    }

    @Test
    fun setPingedPromotesSentinel() {
        val e = NodeEntry(anId, "8.8.8.8", 6881)
        assertFalse(e.pinged())
        e.setPinged()
        assertTrue(e.pinged())
        assertEquals(0, e.timeoutCount)
        // calling again must not clobber a real fail count
        e.timedOut()
        e.setPinged()
        assertEquals(1, e.timeoutCount)
    }

    @Test
    fun updateRttFollowsUpstreamEwma() {
        val e = NodeEntry(anId, "8.8.8.8", 6881, pinged = true)
        // first real sample replaces the unknown value... but pinged ctor set
        // rtt to the supplied value already; use the unknown default explicitly.
        val u = NodeEntry(anId, "8.8.8.8", 6881, rtt = NodeEntry.RTT_UNKNOWN, pinged = true)
        u.updateRtt(300)
        assertEquals(300, u.rtt) // rtt was unknown -> takes the sample wholesale

        // then EWMA: rtt*2/3 + new/3 with integer truncation.
        // 300*2/3 + 30/3 = 200 + 10 = 210
        u.updateRtt(30)
        assertEquals(210, u.rtt)

        // 0xffff sample is ignored
        val before = u.rtt
        u.updateRtt(NodeEntry.RTT_UNKNOWN)
        assertEquals(before, u.rtt)

        // sanity: constructing without an explicit rtt leaves it at the unknown sentinel
        assertEquals(NodeEntry.RTT_UNKNOWN, e.rtt)
    }

    @Test
    fun isBetterThanRanksVerifiedThenLowerRtt() {
        val verifiedFast = NodeEntry(anId, "8.8.8.8", 6881, rtt = 10, pinged = true).also { it.verified = true }
        val verifiedSlow = NodeEntry(anId, "8.8.8.8", 6882, rtt = 100, pinged = true).also { it.verified = true }
        val unverifiedFast = NodeEntry(anId, "8.8.8.8", 6883, rtt = 1, pinged = true).also { it.verified = false }

        // verified always beats unverified, even if the unverified one is faster
        assertTrue(verifiedSlow.isBetterThan(unverifiedFast))
        assertFalse(unverifiedFast.isBetterThan(verifiedSlow))

        // within the same verified-ness, lower RTT wins
        assertTrue(verifiedFast.isBetterThan(verifiedSlow))
        assertFalse(verifiedSlow.isBetterThan(verifiedFast))
    }

    @Test
    fun verifiedFlagSetFromSecureIdAndIp() {
        // build a BEP 42 valid id for a public IP, then an entry for it.
        val ip = "124.31.75.21"
        val pa = io.github.yuroyami.kitetorrent.peer.PeerAddress.parseOrNull(ip)!!
        val secure = generateIdImpl(pa, 1, Random(3))
        val good = NodeEntry(secure, ip, 6881, pinged = true)
        assertTrue(good.verified)

        // a random id for the same public IP should not be verified.
        val bogus = hash("1111111111111111111111111111111111111111")
        val bad = NodeEntry(bogus, ip, 6881, pinged = true)
        assertFalse(bad.verified)
    }

    @Test
    fun sameEndpointMatchesHostAndPort() {
        val e = NodeEntry(anId, "1.2.3.4", 6881)
        assertTrue(e.sameEndpoint("1.2.3.4", 6881))
        assertFalse(e.sameEndpoint("1.2.3.4", 6882))
        assertFalse(e.sameEndpoint("1.2.3.5", 6881))
    }
}
