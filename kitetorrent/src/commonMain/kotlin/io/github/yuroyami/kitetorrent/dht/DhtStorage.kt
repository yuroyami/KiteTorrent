package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Sha1Hash

/**
 * In-memory DHT data store — a pure-Kotlin port of libtorrent's
 * `dht_default_storage` (`src/kademlia/dht_storage.cpp`). It holds the three things
 * a DHT node serves:
 *
 *  - **announced peers** per info-hash (BEP-5 `announce_peer` / `get_peers`),
 *    bucketed by address family and aged out after [PEER_LIFETIME_SECONDS],
 *  - **immutable items** keyed by SHA-1(value) (BEP-44), and
 *  - **mutable items** keyed by SHA-1(public key ‖ salt) (BEP-44), where a higher
 *    sequence number supersedes a lower one.
 *
 * **Time.** libtorrent calls `aux::time_now()` internally; per this module's
 * contract that ambient clock is replaced by an explicit `now` parameter
 * (epoch **seconds** as a [Long]) threaded through [announcePeer], [getPeers],
 * [putImmutableItem], [putMutableItem] and [tick]. This keeps the structure pure
 * and deterministically testable.
 *
 * **Scope.** This is the data structure only. The transport-side machinery in
 * `node::incoming_request` — write-token generation/verification, BEP-42 id checks,
 * the `infohashes_sample` for BEP-51, the popularity-based eviction
 * (`pick_least_important_item`) and the `dht_max_*` capacity caps — lives in the
 * node layer and is intentionally not duplicated here. [maxPeersPerTorrent] and
 * [maxItems] provide the same drop-when-full behaviour the settings drive upstream.
 */
class DhtStorage(
    /** Cap on stored peers per torrent (`dht_max_peers`, default 2000). */
    val maxPeersPerTorrent: Int = DEFAULT_MAX_PEERS,
    /** Cap on stored immutable + mutable items, each table (`dht_max_dht_items`, default 700). */
    val maxItems: Int = DEFAULT_MAX_ITEMS,
    /** Cap on the number of distinct torrents tracked (`dht_max_torrents`, default 2000). */
    val maxTorrents: Int = DEFAULT_MAX_TORRENTS,
) {

    /** One announced peer — port of the anonymous `peer_entry` in dht_storage.cpp. */
    class PeerEntry(
        /** The peer's endpoint (address + port). */
        val endpoint: DhtEndpoint,
        /** Epoch-seconds the announce was last (re)heard; drives expiry. */
        var added: Long,
        /** True if the peer announced itself as a seed (`a["seed"]`). */
        var seed: Boolean,
    )

    /** One tracked torrent — port of the anonymous `torrent_entry`. */
    private class TorrentEntry {
        /** Optional torrent name (`a["n"]`), capped at 100 chars like libtorrent. */
        var name: String? = null

        /** IPv4 announcers. */
        val peers4 = ArrayList<PeerEntry>()

        /** IPv6 announcers. */
        val peers6 = ArrayList<PeerEntry>()

        fun isEmpty(): Boolean = peers4.isEmpty() && peers6.isEmpty()
    }

    /** The last time (epoch-seconds) an item was heard about — for [tick] expiry. */
    private class ItemSlot(val item: DhtItem, var lastSeen: Long)

    private val torrents = LinkedHashMap<Sha1Hash, TorrentEntry>()
    private val immutableTable = LinkedHashMap<Sha1Hash, ItemSlot>()
    private val mutableTable = LinkedHashMap<Sha1Hash, ItemSlot>()

    // --- counters (port of dht_storage_counters) ---------------------------------

    /** Number of distinct torrents currently tracked. */
    val torrentCount: Int get() = torrents.size

    /** Total announced peers across all torrents. */
    val peerCount: Int get() = torrents.values.sumOf { it.peers4.size + it.peers6.size }

    /** Number of stored immutable items. */
    val immutableCount: Int get() = immutableTable.size

    /** Number of stored mutable items. */
    val mutableCount: Int get() = mutableTable.size

    // --- peers (announce_peer / get_peers) ---------------------------------------

    /**
     * Record an `announce_peer` — port of `dht_default_storage::announce_peer`. The
     * peer is keyed by address family; re-announcing the same endpoint refreshes its
     * timestamp/seed flag in place. A new torrent is created unless [maxTorrents] is
     * reached, and a new peer is added unless [maxPeersPerTorrent] is reached.
     *
     * @param now current time in epoch-seconds, used as the peer's `added` stamp.
     */
    fun announcePeer(
        infoHash: Sha1Hash,
        endpoint: DhtEndpoint,
        now: Long,
        seed: Boolean = false,
        name: String? = null,
    ) {
        val t = torrents[infoHash] ?: run {
            if (torrents.size >= maxTorrents) return  // at capacity, drop the announce
            TorrentEntry().also { torrents[infoHash] = it }
        }

        // store a torrent name if we have none and one was supplied
        if (t.name == null && !name.isNullOrEmpty()) {
            t.name = name.take(100)
        }

        val peers = if (endpoint.isV4) t.peers4 else t.peers6
        val existing = peers.firstOrNull { it.endpoint == endpoint }
        if (existing != null) {
            existing.added = now
            existing.seed = seed
        } else if (peers.size >= maxPeersPerTorrent) {
            return  // at capacity, drop the announce
        } else {
            peers.add(PeerEntry(endpoint, now, seed))
        }
    }

    /**
     * Fetch the announced peers for [infoHash] in the requester's address family —
     * the data half of `dht_default_storage::get_peers`. [noseed] excludes seeds
     * (for a seeding requester); [wantV4] picks `peers4` vs `peers6`. At most
     * [maxReply] peers are returned (taken in stored order, not sampled — sampling
     * is a transport concern). The optional torrent name is returned alongside.
     *
     * @param now current time; used only to opportunistically skip already-expired
     *   peers without mutating the store (call [tick] to actually purge).
     * @return a [PeersResult], or `null` if the info-hash is unknown.
     */
    fun getPeers(
        infoHash: Sha1Hash,
        wantV4: Boolean,
        now: Long,
        noseed: Boolean = false,
        maxReply: Int = DEFAULT_MAX_PEERS_REPLY,
    ): PeersResult? {
        val t = torrents[infoHash] ?: return null
        val peers = if (wantV4) t.peers4 else t.peers6
        val cutoff = now - PEER_LIFETIME_SECONDS
        val out = ArrayList<DhtEndpoint>(minOf(maxReply, peers.size))
        for (p in peers) {
            if (out.size >= maxReply) break
            if (noseed && p.seed) continue
            if (p.added < cutoff) continue  // already stale; tick() will remove it
            out.add(p.endpoint)
        }
        return PeersResult(out, t.name)
    }

    /** Result of [getPeers]: the selected peer endpoints plus the torrent name (if any). */
    class PeersResult(val peers: List<DhtEndpoint>, val name: String?)

    // --- immutable items (BEP-44) ------------------------------------------------

    /**
     * Store an immutable item — port of `put_immutable_item`. The key is
     * [DhtItem.target] (= SHA-1 of the bencoded value). Re-putting the same target
     * just refreshes its last-seen stamp. When the table is full a put of a *new*
     * target is dropped (libtorrent instead evicts the least-important item; that
     * popularity heuristic is a node-layer concern, so here a full table simply
     * refuses new targets).
     *
     * @param now current time in epoch-seconds (the item's last-seen stamp).
     */
    fun putImmutableItem(item: ImmutableItem, now: Long) {
        val target = item.target
        val slot = immutableTable[target]
        if (slot != null) {
            slot.lastSeen = now
            return
        }
        if (immutableTable.size >= maxItems) return
        immutableTable[target] = ItemSlot(item, now)
    }

    /**
     * Fetch a stored immutable item by [target] — port of `get_immutable_item`.
     * Returns null if absent.
     */
    fun getImmutableItem(target: Sha1Hash): ImmutableItem? =
        immutableTable[target]?.item as? ImmutableItem

    // --- mutable items (BEP-44) --------------------------------------------------

    /**
     * Store or update a mutable item — port of `put_mutable_item`. The key is
     * [MutableItem.target] (= SHA-1(public key ‖ salt)). If a slot already exists,
     * the new item replaces it **only when its sequence number is strictly greater**
     * (`if (item.seq < seq)`), so older writes can never clobber newer ones; either
     * way the last-seen stamp is refreshed. A new slot is dropped when the table is
     * full (see [putImmutableItem] on eviction).
     *
     * The item must already be signed/verified — [MutableItem.verify] is the
     * caller's responsibility (the node layer rejects bad signatures before calling
     * this), mirroring libtorrent verifying in `incoming_request` before
     * `put_mutable_item`.
     *
     * @param now current time in epoch-seconds (the item's last-seen stamp).
     * @return true if the item was inserted or replaced; false if an equal/older
     *   sequence number left the stored item unchanged or the table was full.
     */
    fun putMutableItem(item: MutableItem, now: Long): Boolean {
        val target = item.target
        val slot = mutableTable[target]
        if (slot == null) {
            if (mutableTable.size >= maxItems) return false
            mutableTable[target] = ItemSlot(item, now)
            return true
        }
        val current = slot.item as MutableItem
        if (current.seq < item.seq) {
            mutableTable[target] = ItemSlot(item, now)
            return true
        }
        // equal or older sequence number: keep the stored value, refresh last-seen.
        slot.lastSeen = now
        return false
    }

    /**
     * Fetch a stored mutable item by [target] — port of `get_mutable_item`.
     * Returns null if absent.
     */
    fun getMutableItem(target: Sha1Hash): MutableItem? =
        mutableTable[target]?.item as? MutableItem

    /**
     * The stored sequence number for [target], or null if absent — port of
     * `get_mutable_item_seq`. The node layer uses this for the BEP-44 `cas` /
     * old-sequence checks before a put.
     */
    fun getMutableItemSeq(target: Sha1Hash): Long? =
        (mutableTable[target]?.item as? MutableItem)?.seq

    // --- maintenance -------------------------------------------------------------

    /**
     * Age out expired peers and items — port of `dht_default_storage::tick`.
     * Peers older than [PEER_LIFETIME_SECONDS] are purged and torrents left with no
     * peers are removed; immutable/mutable items not heard about within
     * [itemLifetimeSeconds] are dropped.
     *
     * @param now current time in epoch-seconds.
     * @param itemLifetimeSeconds item lifetime; libtorrent clamps this to a minimum
     *   of 120 minutes, which we replicate.
     */
    fun tick(now: Long, itemLifetimeSeconds: Long = DEFAULT_ITEM_LIFETIME_SECONDS) {
        val peerCutoff = now - PEER_LIFETIME_SECONDS
        val torrentIter = torrents.entries.iterator()
        while (torrentIter.hasNext()) {
            val t = torrentIter.next().value
            t.peers4.removeAll { it.added < peerCutoff }
            t.peers6.removeAll { it.added < peerCutoff }
            if (t.isEmpty()) torrentIter.remove()
        }

        if (itemLifetimeSeconds <= 0L) return
        val lifetime = if (itemLifetimeSeconds < MIN_ITEM_LIFETIME_SECONDS) {
            MIN_ITEM_LIFETIME_SECONDS
        } else {
            itemLifetimeSeconds
        }
        val itemCutoff = now - lifetime
        removeStale(immutableTable, itemCutoff)
        removeStale(mutableTable, itemCutoff)
    }

    /** Drop every slot in [table] whose last-seen stamp is older than [cutoff]. */
    private fun removeStale(table: MutableMap<Sha1Hash, ItemSlot>, cutoff: Long) {
        val it = table.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.lastSeen < cutoff) it.remove()
        }
    }

    companion object {
        /**
         * How long an announced peer is kept — libtorrent purges peers older than
         * `announce_interval * 3 / 2` where `announce_interval` is 30 minutes, i.e.
         * 45 minutes = 2700 seconds.
         */
        const val PEER_LIFETIME_SECONDS: Long = 45L * 60L

        /** Default per-torrent peer cap (`dht_max_peers`). */
        const val DEFAULT_MAX_PEERS: Int = 2000

        /** Default peers returned per `get_peers` reply (`dht_max_peers_reply`). */
        const val DEFAULT_MAX_PEERS_REPLY: Int = 100

        /** Default per-table item cap (`dht_max_dht_items`). */
        const val DEFAULT_MAX_ITEMS: Int = 700

        /** Default tracked-torrent cap (`dht_max_torrents`). */
        const val DEFAULT_MAX_TORRENTS: Int = 2000

        /** Minimum item lifetime libtorrent enforces: 120 minutes. */
        const val MIN_ITEM_LIFETIME_SECONDS: Long = 120L * 60L

        /** Default item lifetime (`dht_item_lifetime`), 2 hours. */
        const val DEFAULT_ITEM_LIFETIME_SECONDS: Long = MIN_ITEM_LIFETIME_SECONDS
    }
}
