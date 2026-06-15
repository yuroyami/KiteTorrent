package io.github.yuroyami.kitetorrent.session.peer

import io.github.yuroyami.kitetorrent.Bitfield

/**
 * The per-connection choke/interest state — the Kotlin counterpart of the four
 * `m_choked` / `m_peer_choked` / `m_interesting` / `m_peer_interested` flag bits in
 * libtorrent's `peer_connection` (peer_connection.hpp), plus the peer's piece
 * availability (`m_have_piece`).
 *
 * libtorrent's naming is from "our" point of view and is easy to misread, so the
 * mapping is spelled out here once:
 *
 * | this field        | libtorrent           | meaning                                   |
 * |-------------------|----------------------|-------------------------------------------|
 * | [amChoking]       | `m_choked`           | we are choking the remote peer            |
 * | [amInterested]    | `m_interesting`      | we are interested in the remote peer      |
 * | [theirChoking]    | `m_peer_choked`      | the remote peer is choking us             |
 * | [theirInterested] | `m_peer_interested`  | the remote peer is interested in us       |
 *
 * Both peers start out choked and not interested, exactly as `peer_connection`'s
 * constructor initialises `m_choked(true)` and leaves the interest bits clear.
 *
 * This is a plain mutable holder with no I/O: [PeerConnection] flips these as
 * messages arrive and are sent, and the session orchestrator reads them to drive the
 * request scheduler / unchoke algorithm (which live above this class).
 */
class PeerState(
    /** Number of pieces in the torrent; sizes [theirBitfield]. */
    val numPieces: Int,
) {
    /** We are choking the remote peer (won't honour its requests). Starts true. */
    var amChoking: Boolean = true
        internal set

    /** We are interested in the remote peer (want to request pieces). Starts false. */
    var amInterested: Boolean = false
        internal set

    /** The remote peer is choking us (won't honour our requests). Starts true. */
    var theirChoking: Boolean = true
        internal set

    /** The remote peer is interested in us. Starts false. */
    var theirInterested: Boolean = false
        internal set

    /**
     * Whether we have received the peer's initial `bitfield` / `have_all` /
     * `have_none` yet. libtorrent's `m_bitfield_received`; a second bitfield is a
     * protocol error.
     */
    var bitfieldReceived: Boolean = false
        internal set

    /**
     * The pieces the remote peer has, as last reported by its `bitfield` plus any
     * subsequent `have` / `have_all` / `have_none`. Sized to [numPieces]; mirrors
     * libtorrent's `m_have_piece`.
     */
    val theirBitfield: Bitfield = Bitfield(numPieces)

    /** Number of pieces the peer has — `theirBitfield.count()`, cached cheaply. */
    val theirPieceCount: Int get() = theirBitfield.count()

    /** True if the peer has advertised every piece (it is a seed). */
    val theirSeed: Boolean get() = numPieces > 0 && theirPieceCount == numPieces

    /** Does the peer have [piece]? False for out-of-range indices. */
    fun theyHave(piece: Int): Boolean =
        piece in 0 until numPieces && theirBitfield[piece]
}
