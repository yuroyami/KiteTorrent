package io.github.yuroyami.kitetorrent.peer

/**
 * The set of "where did we learn about this peer" flags: the pure-Kotlin port
 * of the `peer_source_flags_t` constants in `include/libtorrent/peer_info.hpp`.
 *
 * In libtorrent this is a `bitfield_flag<std::uint8_t, …>`; we model the bitmap
 * as a plain [Int] (KiteTorrent's convention of plain primitives over
 * strong flag types). Each constant below is a single bit, and a peer's
 * [TorrentPeer.source] is the bitwise OR of every source that has ever told us
 * about it.
 *
 * The numeric bit positions are load-bearing: [sourceRank] depends on them, and
 * so does any future serialization, so they must stay exactly as upstream
 * defines them.
 */
object PeerSource {
    /** The peer was received from a tracker announce. `0_bit`. */
    const val TRACKER: Int = 1 shl 0

    /** The peer was received from the DHT. `1_bit`. */
    const val DHT: Int = 1 shl 1

    /** The peer was received through peer exchange (PEX). `2_bit`. */
    const val PEX: Int = 1 shl 2

    /** The peer was received through local service discovery (LSD). `3_bit`. */
    const val LSD: Int = 1 shl 3

    /** The peer was read back from resume data. `4_bit`. */
    const val RESUME_DATA: Int = 1 shl 4

    /** The peer connected to us (an incoming connection). `5_bit`. */
    const val INCOMING: Int = 1 shl 5
}

/**
 * The "peer exchange" hint flags that can accompany a peer when it is added:
 * the port of the `pex_flags_t` constants in
 * `include/libtorrent/pex_flags.hpp`.
 *
 * Again modelled as a plain [Int] bitmap. These carry soft hints (the peer may
 * be a seed, may support uTP, etc.) that [PeerList.addPeer] folds into the
 * stored [TorrentPeer]. Bit positions match upstream.
 */
object PexFlags {
    /** No flags. */
    const val NONE: Int = 0

    /** The peer supports protocol encryption. `0_bit`. */
    const val ENCRYPTION: Int = 1 shl 0

    /** The peer is (probably) a seed / upload-only. `1_bit`. */
    const val SEED: Int = 1 shl 1

    /** The peer supports uTP. `2_bit`. */
    const val UTP: Int = 1 shl 2

    /** The peer supports holepunching. `3_bit`. */
    const val HOLEPUNCH: Int = 1 shl 3

    /** The peer supports BitTorrent v2. `7_bit`. */
    const val LT_V2: Int = 1 shl 7
}

/**
 * Compute the *source rank* of a peer's [source] bitmap: the port of
 * `int source_rank(peer_source_flags_t)` from `src/request_blocks.cpp`.
 *
 * This collapses the (multi-bit) source bitmap into a small priority number used
 * as a tie-breaker when choosing which known peer to connect to next: peers we
 * heard about from "more trustworthy" channels are preferred. The ordering,
 * highest-first, is tracker > LSD > DHT > PEX; the `incoming` and `resume_data`
 * sources contribute nothing (rank 0).
 *
 * The exact bit layout (`1<<5` for tracker down to `1<<2` for PEX) is reproduced
 * verbatim so that the relative magnitudes (and therefore the connect ordering)
 * match libtorrent precisely.
 */
fun sourceRank(source: Int): Int {
    var ret = 0
    if (source and PeerSource.TRACKER != 0) ret = ret or (1 shl 5)
    if (source and PeerSource.LSD != 0) ret = ret or (1 shl 4)
    if (source and PeerSource.DHT != 0) ret = ret or (1 shl 3)
    if (source and PeerSource.PEX != 0) ret = ret or (1 shl 2)
    return ret
}
