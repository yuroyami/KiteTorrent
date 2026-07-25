package io.github.yuroyami.kitetorrent.bandwidth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [BandwidthChannel] / [BwRequest] / [BandwidthManager], the port of
 * libtorrent's token-bucket rate limiter (aux_/bandwidth_limit.hpp,
 * bandwidth_limit.cpp, aux_/bandwidth_queue_entry.hpp, bandwidth_queue_entry.cpp,
 * aux_/bandwidth_manager.hpp, bandwidth_manager.cpp).
 *
 * Golden quota values are computed by hand from `update_quota`:
 * ```
 * toAdd = (limit * dt + 500) / 1000
 * quotaLeft += toAdd ; if quotaLeft/3 > limit then quotaLeft = limit*3
 * ```
 */
class BandwidthTest {

    /** Records every bandwidth assignment so tests can assert on the callbacks. */
    private class FakeSocket : BandwidthSocket {
        val assigned = ArrayList<Int>()
        var disconnecting = false
        override fun assignBandwidth(channel: Int, amount: Int) { assigned.add(amount) }
        override fun isDisconnecting(): Boolean = disconnecting
        fun total(): Int = assigned.sum()
    }

    // --- BandwidthChannel ------------------------------------------------------

    @Test
    fun unlimitedChannelReportsInfiniteAndIgnoresQuota() {
        val ch = BandwidthChannel() // limit 0 == infinite
        assertEquals(0, ch.throttle())
        assertEquals(BandwidthChannel.inf, ch.quotaLeft())
        ch.useQuota(1_000_000)  // no-op
        ch.updateQuota(1000)    // no-op
        assertEquals(BandwidthChannel.inf, ch.quotaLeft())
    }

    @Test
    fun updateQuotaAddsLimitTimesElapsed() {
        val ch = BandwidthChannel()
        ch.throttle(1000) // 1000 bytes/sec
        assertEquals(0, ch.quotaLeft()) // nothing accrued yet

        ch.updateQuota(1000) // toAdd = (1000*1000+500)/1000 = 1000
        assertEquals(1000, ch.quotaLeft())
        assertEquals(1000, ch.distributeQuota)

        ch.updateQuota(500) // toAdd = (1000*500+500)/1000 = 500 -> 1500
        assertEquals(1500, ch.quotaLeft())
    }

    @Test
    fun useAndReturnQuotaAdjustBucket() {
        val ch = BandwidthChannel()
        ch.throttle(1000)
        ch.updateQuota(1000) // 1000
        ch.useQuota(400)
        assertEquals(600, ch.quotaLeft())
        ch.returnQuota(150)
        assertEquals(750, ch.quotaLeft())
    }

    @Test
    fun quotaIsCappedAtThreeSeconds() {
        val ch = BandwidthChannel()
        ch.throttle(1000)
        // refill repeatedly with no consumption: 1000, 2000, 3000, then capped.
        ch.updateQuota(1000); assertEquals(1000, ch.quotaLeft())
        ch.updateQuota(1000); assertEquals(2000, ch.quotaLeft())
        ch.updateQuota(1000); assertEquals(3000, ch.quotaLeft())
        // 4th would be 4000; 4000/3 = 1333 > 1000 -> clamped to limit*3 = 3000.
        ch.updateQuota(1000); assertEquals(3000, ch.quotaLeft())
    }

    @Test
    fun needQueueingWithdrawsWhenAmpleQuota() {
        val ch = BandwidthChannel()
        ch.throttle(1000)
        ch.updateQuota(1000) // banked 1000, limit 1000
        ch.updateQuota(1000) // banked 2000
        // 2000 - 500 = 1500 >= limit(1000) -> no queueing, 500 withdrawn directly.
        assertFalse(ch.needQueueing(500))
        assertEquals(1500, ch.quotaLeft())
        // 1500 - 1000 = 500 < limit(1000) -> must queue, nothing withdrawn.
        assertTrue(ch.needQueueing(1000))
        assertEquals(1500, ch.quotaLeft())
    }

    // --- BandwidthManager ------------------------------------------------------

    @Test
    fun unmeteredRequestIsGrantedImmediately() {
        val mgr = BandwidthManager(channel = 0)
        val peer = FakeSocket()
        // no channels -> immediate full grant, nothing queued
        assertEquals(500, mgr.requestBandwidth(peer, 500, 1, emptyList()))
        assertEquals(0, mgr.queueSize())
        assertEquals(0L, mgr.queuedBytes())
    }

    @Test
    fun requestWithSlackChannelIsGrantedImmediately() {
        val mgr = BandwidthManager(channel = 0)
        val ch = BandwidthChannel()
        ch.throttle(10_000)
        // bank plenty so needQueueing withdraws instead of queueing
        ch.updateQuota(1000) // 10000
        ch.updateQuota(1000) // 20000 (cap is 30000)
        val peer = FakeSocket()
        // 20000 - 500 = 19500 >= limit(10000) -> granted immediately
        assertEquals(500, mgr.requestBandwidth(peer, 500, 1, listOf(ch)))
        assertEquals(0, mgr.queueSize())
    }

    @Test
    fun throttledRequestIsQueuedThenAssignedInOneRound() {
        val mgr = BandwidthManager(channel = 1)
        val ch = BandwidthChannel()
        ch.throttle(1000)
        val peer = FakeSocket()

        // empty bucket -> needQueueing true -> request is queued, returns 0
        assertEquals(0, mgr.requestBandwidth(peer, 500, 1, listOf(ch)))
        assertEquals(1, mgr.queueSize())
        assertEquals(500L, mgr.queuedBytes())
        assertTrue(mgr.isQueued(peer))

        // one round: refill 1000, request of 500 fully satisfied and dispatched.
        mgr.updateQuotas(1000)
        assertEquals(0, mgr.queueSize())
        assertEquals(0L, mgr.queuedBytes())
        assertEquals(listOf(500), peer.assigned)
        assertFalse(mgr.isQueued(peer))
    }

    @Test
    fun largeRequestIsAssignedOverManyRounds() {
        // request 1000 bytes through a 100 byte/sec channel: ~100 bytes per round.
        val mgr = BandwidthManager(channel = 0)
        val ch = BandwidthChannel()
        ch.throttle(100)
        val peer = FakeSocket()

        assertEquals(0, mgr.requestBandwidth(peer, 1000, 1, listOf(ch)))
        assertEquals(1000L, mgr.queuedBytes())

        // drive rounds until the peer is dispatched (or a generous round cap).
        var rounds = 0
        while (peer.assigned.isEmpty() && rounds < 50) {
            mgr.updateQuotas(1000)
            rounds++
        }
        // dispatched exactly once, with the full request size accumulated.
        assertEquals(1, peer.assigned.size)
        assertEquals(1000, peer.total())
        assertEquals(0, mgr.queueSize())
        assertEquals(0L, mgr.queuedBytes())
        // 1000 bytes at ~100/round needs about 10 rounds.
        assertTrue(rounds in 9..12, "took $rounds rounds")
    }

    @Test
    fun ttlForcesDispatchAtVeryLowRate() {
        // 1 byte/sec serving a 10000-byte request can never finish in 20 rounds;
        // ttl must force a partial dispatch once it expires.
        val mgr = BandwidthManager(channel = 0)
        val ch = BandwidthChannel()
        ch.throttle(1)
        val peer = FakeSocket()
        mgr.requestBandwidth(peer, 10000, 1, listOf(ch))

        var rounds = 0
        while (peer.assigned.isEmpty() && rounds < 40) {
            mgr.updateQuotas(1000)
            rounds++
        }
        // ttl starts at 20 and decrements once per assignBandwidth call.
        assertEquals(1, peer.assigned.size)
        assertTrue(peer.total() in 1..10000)
        assertTrue(rounds <= 21, "ttl should force dispatch by ~20 rounds, took $rounds")
        assertEquals(0, mgr.queueSize())
    }

    @Test
    fun twoEqualPriorityPeersSplitQuota() {
        // 1000 byte/sec channel shared by two peers each asking 1000 at prio 1.
        // tmp = 1 + 1 = 2; each gets distributeQuota * 1 / 2 = 500 in round one.
        val mgr = BandwidthManager(channel = 0)
        val ch = BandwidthChannel()
        ch.throttle(1000)
        val a = FakeSocket()
        val b = FakeSocket()
        mgr.requestBandwidth(a, 1000, 1, listOf(ch))
        mgr.requestBandwidth(b, 1000, 1, listOf(ch))
        assertEquals(2, mgr.queueSize())

        mgr.updateQuotas(1000)
        // neither is full yet (each wanted 1000, each got 500), so both still queued.
        assertEquals(2, mgr.queueSize())
        assertTrue(a.assigned.isEmpty())
        assertTrue(b.assigned.isEmpty())

        // drive to completion; both must end up with their full 1000.
        var rounds = 1
        while (mgr.queueSize() > 0 && rounds < 50) {
            mgr.updateQuotas(1000)
            rounds++
        }
        assertEquals(1000, a.total())
        assertEquals(1000, b.total())
        assertEquals(0L, mgr.queuedBytes())
    }

    @Test
    fun disconnectingPeerIsDispatchedAndQuotaRefunded() {
        val mgr = BandwidthManager(channel = 0)
        val ch = BandwidthChannel()
        ch.throttle(1000)
        val peer = FakeSocket()
        mgr.requestBandwidth(peer, 500, 1, listOf(ch))
        assertEquals(500L, mgr.queuedBytes())

        peer.disconnecting = true
        mgr.updateQuotas(1000)

        // dispatched with whatever it had (0, since nothing assigned before DC).
        assertEquals(listOf(0), peer.assigned)
        assertEquals(0, mgr.queueSize())
        assertEquals(0L, mgr.queuedBytes())
    }

    @Test
    fun closeDrainsQueueWithAssignedSoFar() {
        val mgr = BandwidthManager(channel = 0)
        val ch = BandwidthChannel()
        ch.throttle(100)
        val peer = FakeSocket()
        mgr.requestBandwidth(peer, 1000, 1, listOf(ch))
        // partially assign over a couple of rounds (peer not yet dispatched)
        mgr.updateQuotas(1000)
        mgr.updateQuotas(1000)
        assertTrue(peer.assigned.isEmpty()) // still queued, ~200 assigned

        mgr.close()
        // close dispatches the pending request once, with its accumulated assignment.
        assertEquals(1, peer.assigned.size)
        assertTrue(peer.assigned[0] in 1..1000)
        assertEquals(0, mgr.queueSize())
        assertEquals(0L, mgr.queuedBytes())

        // after close, further requests are refused (return 0, nothing queued).
        val other = FakeSocket()
        assertEquals(0, mgr.requestBandwidth(other, 100, 1, listOf(ch)))
        assertEquals(0, mgr.queueSize())
    }

    @Test
    fun bwRequestRejectsNonPositivePriority() {
        val peer = FakeSocket()
        var threw = false
        try {
            BwRequest(peer, 100, 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "priority 0 must be rejected")
    }
}
