package io.github.yuroyami.kitetorrent.session.dht

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.dht.generateId
import io.github.yuroyami.kitetorrent.peer.PeerAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The BEP-42 routing-table admission gate used by [DhtNode]: when enforcement is off every
 * node is admitted (legacy compatibility); when on, only nodes whose id derives from their
 * source IP get in. That is the anti-Sybil hardening. The gate cannot verify a hostname, so
 * it admits any node given by name.
 */
class SecureNodeIdGateTest {

    private val ip = PeerAddress.parseOrNull("8.8.8.8")!!
    private val validId = generateId(ip, Random(42))            // BEP-42-valid for 8.8.8.8
    private val bogusId = Digest32.of(ByteArray(20) { 0 })      // not derived from any IP

    @Test
    fun enforcementOffAdmitsEverything() {
        assertTrue(dhtNodeAccepted(validId, "8.8.8.8", enforce = false))
        assertTrue(dhtNodeAccepted(bogusId, "8.8.8.8", enforce = false))
    }

    @Test
    fun enforcementOnRejectsSpoofedIds() {
        assertTrue(dhtNodeAccepted(validId, "8.8.8.8", enforce = true), "a secure id must be admitted")
        assertFalse(dhtNodeAccepted(bogusId, "8.8.8.8", enforce = true), "a spoofed id must be rejected")
    }

    @Test
    fun hostnamesAreUnverifiableAndAdmitted() {
        assertTrue(dhtNodeAccepted(bogusId, "router.bittorrent.com", enforce = true))
    }
}
