package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DhtStorage] — the port of `dht_default_storage`
 * (`src/kademlia/dht_storage.cpp`). Time is supplied explicitly (epoch-seconds) so
 * expiry is fully deterministic.
 */
class DhtStorageTest {

    private val ih = Sha1Hash.fromHex("1111111111111111111111111111111111111111")

    // --- announced peer put/get --------------------------------------------------

    @Test
    fun announceAndGetPeer() {
        val store = DhtStorage()
        val peer = DhtEndpoint.of("1.2.3.4", 6881)!!
        val now = 1_000L

        assertNull(store.getPeers(ih, wantV4 = true, now = now), "unknown info-hash returns null")

        store.announcePeer(ih, peer, now = now, name = "my torrent")
        assertEquals(1, store.torrentCount)
        assertEquals(1, store.peerCount)

        val res = store.getPeers(ih, wantV4 = true, now = now)
        assertNotNull(res)
        assertEquals(1, res.peers.size)
        assertEquals(peer, res.peers[0])
        assertEquals("my torrent", res.name)

        // v6 family is separate and empty here
        assertEquals(0, store.getPeers(ih, wantV4 = false, now = now)!!.peers.size)
    }

    @Test
    fun reAnnounceSamePeerRefreshesNotDuplicates() {
        val store = DhtStorage()
        val peer = DhtEndpoint.of("1.2.3.4", 6881)!!
        store.announcePeer(ih, peer, now = 1_000L, seed = false)
        store.announcePeer(ih, peer, now = 2_000L, seed = true)
        assertEquals(1, store.peerCount, "re-announce updates in place")
    }

    @Test
    fun noseedExcludesSeeds() {
        val store = DhtStorage()
        val seedPeer = DhtEndpoint.of("1.2.3.4", 6881)!!
        val leechPeer = DhtEndpoint.of("5.6.7.8", 6882)!!
        store.announcePeer(ih, seedPeer, now = 1_000L, seed = true)
        store.announcePeer(ih, leechPeer, now = 1_000L, seed = false)

        val all = store.getPeers(ih, wantV4 = true, now = 1_000L, noseed = false)!!
        assertEquals(2, all.peers.size)

        val noSeeds = store.getPeers(ih, wantV4 = true, now = 1_000L, noseed = true)!!
        assertEquals(1, noSeeds.peers.size)
        assertEquals(leechPeer, noSeeds.peers[0])
    }

    @Test
    fun peersExpireOnTick() {
        val store = DhtStorage()
        val peer = DhtEndpoint.of("1.2.3.4", 6881)!!
        store.announcePeer(ih, peer, now = 1_000L)

        // still alive well within the 45-minute window
        store.tick(now = 1_000L + DhtStorage.PEER_LIFETIME_SECONDS - 1)
        assertEquals(1, store.peerCount)

        // past the window: the peer (and its now-empty torrent) is purged
        store.tick(now = 1_000L + DhtStorage.PEER_LIFETIME_SECONDS + 1)
        assertEquals(0, store.peerCount)
        assertEquals(0, store.torrentCount)
    }

    // --- immutable item put/get --------------------------------------------------

    @Test
    fun putAndGetImmutableItem() {
        val store = DhtStorage()
        val item = ImmutableItem.of(Entry.of("Hello World!"))
        val now = 500L

        assertNull(store.getImmutableItem(item.target))

        store.putImmutableItem(item, now)
        assertEquals(1, store.immutableCount)

        val got = store.getImmutableItem(item.target)
        assertNotNull(got)
        assertEquals(item.target, got.target)
        assertContentEqualsBytes(item.bencodedValue, got.bencodedValue)
    }

    @Test
    fun immutableItemExpiresOnTick() {
        val store = DhtStorage()
        val item = ImmutableItem.of(Entry.of(42L))
        store.putImmutableItem(item, now = 0L)
        assertEquals(1, store.immutableCount)

        // within the (clamped 120-minute) lifetime
        store.tick(now = DhtStorage.MIN_ITEM_LIFETIME_SECONDS - 1)
        assertEquals(1, store.immutableCount)

        // past it
        store.tick(now = DhtStorage.MIN_ITEM_LIFETIME_SECONDS + 1)
        assertEquals(0, store.immutableCount)
    }

    // --- mutable item put/get with seq semantics ---------------------------------

    @Test
    fun mutableItemHigherSeqWins() {
        val store = DhtStorage()
        // deterministic 32-byte public key (not a real ed25519 key; storage doesn't verify)
        val pk = ByteArray(DhtKeySizes.PUBLIC_KEY_LEN) { it.toByte() }
        val sig = ByteArray(DhtKeySizes.SIGNATURE_LEN) { 0 }

        val v1 = MutableItem.of(Entry.of("v1"), pk, seq = 1L).also { it.signature = sig }
        val v3 = MutableItem.of(Entry.of("v3"), pk, seq = 3L).also { it.signature = sig }
        val v2 = MutableItem.of(Entry.of("v2"), pk, seq = 2L).also { it.signature = sig }

        // same key + (empty) salt → same slot
        assertEquals(v1.target, v3.target)

        assertTrue(store.putMutableItem(v1, now = 10L))
        assertEquals(1L, store.getMutableItemSeq(v1.target))

        assertTrue(store.putMutableItem(v3, now = 11L), "higher seq replaces")
        assertEquals(3L, store.getMutableItemSeq(v1.target))

        assertFalse(store.putMutableItem(v2, now = 12L), "older seq is rejected")
        assertEquals(3L, store.getMutableItemSeq(v1.target))

        val got = store.getMutableItem(v1.target)
        assertNotNull(got)
        assertEquals(3L, got.seq)
        assertEquals("v3", got.value.string())
    }

    @Test
    fun mutableSeqAbsentWhenEmpty() {
        val store = DhtStorage()
        assertNull(store.getMutableItemSeq(ih))
        assertNull(store.getMutableItem(ih))
    }

    // --- capacity drops ----------------------------------------------------------

    @Test
    fun fullTorrentTableDropsNewAnnounce() {
        val store = DhtStorage(maxTorrents = 1)
        store.announcePeer(ih, DhtEndpoint.of("1.2.3.4", 1)!!, now = 0L)
        val other = Sha1Hash.fromHex("9999999999999999999999999999999999999999")
        store.announcePeer(other, DhtEndpoint.of("5.6.7.8", 2)!!, now = 0L)
        assertEquals(1, store.torrentCount, "second torrent dropped at capacity")
        assertNull(store.getPeers(other, wantV4 = true, now = 0L))
    }

    private fun assertContentEqualsBytes(a: ByteArray, b: ByteArray) {
        assertTrue(a.contentEquals(b), "byte arrays differ")
    }
}
