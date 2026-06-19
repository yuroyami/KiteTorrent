package io.github.yuroyami.kitetorrent.session.peer

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.protocol.Handshake
import io.github.yuroyami.kitetorrent.protocol.PeerMessage
import kotlinx.coroutines.CancellationException

/**
 * One BitTorrent peer session over a single byte-duplex — the coroutine port of
 * libtorrent's `peer_connection` / `bt_peer_connection` (src/peer_connection.cpp,
 * src/bt_peer_connection.cpp), the heart of the wire engine.
 *
 * libtorrent drives this connection through Boost.Asio completion callbacks
 * (`on_receive`, `on_send`) feeding a hand-rolled receive-buffer state machine
 * (`m_recv_buffer`, `state_t`). Here the same protocol *logic* runs as straight-line
 * suspending code: [performHandshake] suspends on the 68-byte exchange, then
 * [receiveLoop] frames and decodes messages one at a time. There is no callback
 * soup and no explicit `state_t` enum — the suspension points *are* the states.
 *
 * Scope. This class owns the parts of `peer_connection` that are *per-connection and
 * local*: the handshake, message framing/decoding, the four choke/interest flags
 * ([PeerState]), and the peer's advertised piece set. It deliberately does **not**
 * own the swarm-wide policy that libtorrent also crams into `peer_connection`:
 *  - the request *scheduler* (`request_a_block`, `send_block_requests`, the
 *    download-queue / pipeline depth),
 *  - the *unchoke* algorithm (`maybe_unchoke_this_peer`, choke rotation),
 *  - piece *verification* and disk writes.
 *
 * Those need the [io.github.yuroyami.kitetorrent.picker.PiecePicker] and
 * [io.github.yuroyami.kitetorrent.session.disk.DiskIo], which are shared across all
 * peers, so they live in the session orchestrator above this class. [receiveLoop]
 * surfaces every decoded [PeerMessage] to an `onMessage` callback after updating the
 * local state, which is exactly the seam the orchestrator hooks: e.g. on a
 * [PeerMessage.Unchoke] it asks the picker for blocks and calls [sendRequest], on a
 * [PeerMessage.Piece] it hands the block to disk. See the per-message notes below
 * for what is handled here vs. deferred.
 *
 * Threading. A [PeerConnection] is single-reader/single-writer like its C++ original
 * (`is_single_thread()`): run [receiveLoop] in one coroutine and call the `send*`
 * helpers from the orchestrator's coroutine. Concurrent writers would interleave
 * frames on the wire.
 */
class PeerConnection(
    private val conn: ByteStream,
    private val infoHash: Sha1Hash,
    private val ourPeerId: Sha1Hash,
    numPieces: Int,
    /**
     * Whether *our* torrent carries BitTorrent-v2 metadata. When true we set the
     * BEP-52 v2-upgrade bit (`reserved[7] |= 0x10`) in our outgoing handshake,
     * advertising willingness to upgrade — exactly libtorrent's
     * `!protocol_v2 && info_hash.has_v2()` condition in `write_handshake()`.
     * Defaults to `false` so existing call sites keep emitting the prior wire bytes.
     */
    private val hasV2: Boolean = false,
) {
    /** The mutable choke/interest/availability state for this connection. */
    val state: PeerState = PeerState(numPieces)

    private val numPieces: Int get() = state.numPieces

    /**
     * The 8 reserved/capability bytes we advertise on this connection. DHT + Fast +
     * Extension-protocol are always set (the engine honours all three: BEP-5 `port`
     * messages, BEP-6 fast hints, BEP-10 `extended`), plus the BEP-52 v2-upgrade bit
     * when [hasV2]. We deliberately advertise *only* bits the engine acts on, so a
     * peer ANDing reserved blocks never over-negotiates a capability we ignore.
     */
    private val ourReserved: ByteArray =
        Handshake.buildReserved(dht = true, fast = true, extended = true, v2 = hasV2)

    /** The remote peer's id, set once [performHandshake] succeeds; null before. */
    var remotePeerId: Sha1Hash? = null
        private set

    /**
     * The remote peer set the BEP-52 v2-upgrade bit. Read from the peer's reserved
     * bytes during the handshake; `false` until a handshake completes.
     */
    var supportsV2: Boolean = false
        private set

    /** The remote peer set the Fast-extension bit (BEP-6). Set during the handshake. */
    var supportsFast: Boolean = false
        private set

    /** The remote peer set the DHT bit (BEP-5). Set during the handshake. */
    var supportsDht: Boolean = false
        private set

    /** The remote peer set the extension-protocol bit (BEP-10). Set during the handshake. */
    var supportsExtended: Boolean = false
        private set

    /** Set once [disconnect] is called or the receive loop terminates; blocks further sends. */
    var disconnected: Boolean = false
        private set

    /** The remote peer's 8 reserved/capability bytes, set by [performHandshake]. */
    var remoteReserved: ByteArray? = null
        private set

    /** A label for the remote end, for logging. */
    val remoteAddress: String get() = conn.remoteAddress

    // --- convenience state mirrors ------------------------------------------------

    /** We are choking the remote peer. See [PeerState.amChoking]. */
    val amChoking: Boolean get() = state.amChoking

    /** We are interested in the remote peer. See [PeerState.amInterested]. */
    val amInterested: Boolean get() = state.amInterested

    /** The remote peer is choking us. See [PeerState.theirChoking]. */
    val theirChoking: Boolean get() = state.theirChoking

    /** The remote peer is interested in us. See [PeerState.theirInterested]. */
    val theirInterested: Boolean get() = state.theirInterested

    /** The pieces the remote peer has advertised. See [PeerState.theirBitfield]. */
    val theirBitfield: Bitfield get() = state.theirBitfield

    // ============================================================================
    // Handshake — bt_peer_connection::write_handshake + the read_info_hash /
    // read_peer_id states of bt_peer_connection::on_receive.
    // ============================================================================

    /**
     * Perform the BitTorrent handshake: write our 68-byte handshake, then read and
     * validate the peer's. Ports `write_handshake()` (the send) and the
     * `read_info_hash` / `read_peer_id` arms of `on_receive` (the receive +
     * validation), collapsed into one suspending exchange.
     *
     * We always send first regardless of which side dialed. For an outgoing
     * connection libtorrent sends in the constructor; for incoming it sends after
     * reading the info-hash (`if (!is_outgoing()) write_handshake()`). Sending first
     * unconditionally is wire-equivalent for the plain (non-encrypted) protocol and
     * lets the peer validate our info-hash in parallel.
     *
     * Validation mirrors the C++: the decoded protocol string must be
     * `"BitTorrent protocol"` ([Handshake.decode] enforces this), the info-hash must
     * equal [infoHash], and a torrent with an all-zero info-hash is rejected (the
     * `associated_info_hash().is_all_zeros()` guard).
     *
     * @return the validated remote handshake (peer-id + reserved capability bytes).
     * @throws HandshakeException if the peer is not speaking BitTorrent, the
     *   info-hash does not match, or our own info-hash is all zeros.
     */
    suspend fun performHandshake(): HandshakeResult {
        require(!infoHash.isAllZeros()) { "cannot handshake on an all-zero info-hash" }

        // 1. write our handshake (write_handshake) — [ourReserved] advertises DHT +
        //    Fast + extension protocol (always honoured) plus the BEP-52 v2-upgrade bit
        //    when our torrent has v2 metadata, exactly as libtorrent's write_handshake().
        conn.write(Handshake.encode(infoHash, ourPeerId, ourReserved))

        // 2. read exactly the 68 fixed bytes (read_info_hash: 28 bytes, then
        //    read_peer_id: 20 bytes — we pull the whole frame in one shot).
        val raw = conn.readExactly(Handshake.LENGTH)

        // 3. parse + validate the protocol identifier (recv_handshake).
        val hs = Handshake.decode(raw)
            ?: throw HandshakeException("peer is not speaking the BitTorrent protocol")

        // 4. info-hash check — errors::invalid_info_hash on mismatch.
        if (hs.infoHash != infoHash) {
            throw HandshakeException(
                "info-hash mismatch: peer offered ${hs.infoHash.toHex()}, expected ${infoHash.toHex()}",
            )
        }

        recordRemoteCaps(hs)
        return HandshakeResult(hs.peerId, hs.reserved, hs.supportsDht, hs.supportsFast, hs.supportsExtended)
    }

    /** Stash the remote peer-id, raw reserved bytes, and decoded capability flags. */
    private fun recordRemoteCaps(hs: Handshake) {
        remotePeerId = hs.peerId
        remoteReserved = hs.reserved
        supportsDht = hs.supportsDht
        supportsFast = hs.supportsFast
        supportsExtended = hs.supportsExtended
        supportsV2 = hs.supportsV2
    }

    /**
     * True iff the remote peer's id equals [ourPeerId] — a self-connect. libtorrent
     * rejects these (`pid == m_our_peer_id` ⇒ `errors::self_connection`). Returns
     * `false` until a handshake has populated [remotePeerId].
     */
    val isSelfConnect: Boolean get() = remotePeerId == ourPeerId

    /**
     * Mark this connection [disconnected] so any subsequent `send*` call fails fast
     * with [ProtocolException] instead of writing onto a stream the orchestrator is
     * about to tear down. Idempotent and safe to call from the orchestrator on a
     * self-connect, a duplicate peer-id, or a protocol error — the analogue of
     * libtorrent's `disconnect(...)` decision point.
     *
     * The underlying byte-stream/socket is owned by the orchestrator (it constructed
     * the [TcpConnection]); closing it stays the orchestrator's responsibility, which
     * also unblocks the [receiveLoop]'s pending `readExactly`. This call only flips the
     * local guard so no half-frame is written in the meantime.
     */
    fun disconnect() {
        disconnected = true
    }

    /** Guard every outgoing write: refuse to emit a frame after [disconnect]. */
    private fun ensureWritable() {
        if (disconnected) throw ProtocolException("connection to $remoteAddress is disconnected")
    }

    /**
     * Complete an **inbound** handshake. The accept loop has already read the remote's
     * 68-byte handshake (to route by info-hash); we validate it and write ours. This is
     * the incoming arm of `bt_peer_connection` (`read_info_hash` → `write_handshake`).
     * [conn] must be positioned just past the remote's handshake bytes.
     */
    suspend fun completeInboundHandshake(remote: Handshake): HandshakeResult {
        require(!infoHash.isAllZeros()) { "cannot handshake on an all-zero info-hash" }
        if (remote.infoHash != infoHash) {
            throw HandshakeException(
                "info-hash mismatch: peer offered ${remote.infoHash.toHex()}, expected ${infoHash.toHex()}",
            )
        }
        conn.write(Handshake.encode(infoHash, ourPeerId, ourReserved))
        recordRemoteCaps(remote)
        return HandshakeResult(remote.peerId, remote.reserved, remote.supportsDht, remote.supportsFast, remote.supportsExtended)
    }

    // ============================================================================
    // Receive loop — the framing half of bt_peer_connection::on_receive +
    // dispatch_message, driven by suspension instead of m_recv_buffer.
    // ============================================================================

    /**
     * Read and dispatch peer-wire messages until the stream closes (or the coroutine
     * is cancelled). Ports the post-handshake framing of `on_receive`: read the
     * 4-byte big-endian length prefix, then the body, then decode and dispatch — the
     * suspending equivalent of libtorrent waiting for `packet_size` bytes in
     * `m_recv_buffer` before calling `dispatch_message`.
     *
     * Framing is done here directly off [ByteStream.readExactly] rather than through
     * [PeerMessage.tryReadMessage] (which assumes a pre-filled buffer): read 4 length
     * bytes, decode the unsigned length, treat `0` as a keep-alive, then
     * `readExactly(len)` the body and hand it to [PeerMessage.decode].
     *
     * Before invoking [onMessage], this updates the *local* connection state for the
     * protocol-defining messages so the orchestrator always sees a consistent
     * [state]:
     *  - [PeerMessage.Choke] / [PeerMessage.Unchoke] flip [PeerState.theirChoking]
     *    (`incoming_choke` / `incoming_unchoke`).
     *  - [PeerMessage.Interested] / [PeerMessage.NotInterested] flip
     *    [PeerState.theirInterested] (`incoming_interested` /
     *    `incoming_not_interested`).
     *  - [PeerMessage.Have] sets a bit in [PeerState.theirBitfield] (`incoming_have`).
     *  - [PeerMessage.Bitfield] validates the size and records availability
     *    (`incoming_bitfield`); a bad size throws [ProtocolException].
     *  - [PeerMessage.HaveAll] / [PeerMessage.HaveNone] set/clear all bits
     *    (`incoming_have_all` / `incoming_have_none`).
     *
     * Everything else (requests we receive, blocks we receive, cancels, fast-extension
     * hints, the extended protocol) is decoded and forwarded *without* side effects —
     * those drive disk/picker policy that lives in the orchestrator. [onMessage] is
     * invoked for *every* decoded message, including the state-changing ones, so the
     * orchestrator can react (e.g. pick blocks on unchoke).
     *
     * The loop ends normally when [ByteStream.readExactly] reports end-of-stream;
     * frame-length sanity is enforced ([BadFrameLengthException] for an unaddressable
     * length, [ProtocolException] for a length exceeding [maxMessageLength]).
     *
     * @param onMessage invoked for each decoded message after state is updated.
     * @param maxMessageLength reject any single message body larger than this (a
     *   hostile-peer guard; defaults to [DEFAULT_MAX_MESSAGE_LENGTH], generous enough
     *   for a max-size `piece` block).
     */
    suspend fun receiveLoop(
        maxMessageLength: Int = DEFAULT_MAX_MESSAGE_LENGTH,
        onMessage: suspend (PeerMessage) -> Unit,
    ) {
        while (true) {
            // 4-byte big-endian length prefix. readExactly throws at end-of-stream;
            // we let that propagate as loop termination (see receiveLoopCatching).
            val lenBytes = conn.readExactly(4)
            val len = readUInt32BE(lenBytes)

            if (len == 0L) {
                // keep-alive: no id, no body. write_keepalive() sends {0,0,0,0}.
                onMessage(PeerMessage.KeepAlive)
                continue
            }
            if (len > Int.MAX_VALUE.toLong()) {
                throw BadFrameLengthException(len)
            }
            val bodyLen = len.toInt()
            if (bodyLen > maxMessageLength) {
                throw ProtocolException("peer announced oversized message: $bodyLen > $maxMessageLength bytes")
            }

            val body = conn.readExactly(bodyLen)
            val msg = PeerMessage.decode(body)
            applyIncoming(msg)
            onMessage(msg)
        }
    }

    /**
     * Like [receiveLoop] but swallows the end-of-stream / cancellation exception so a
     * normal peer disconnect returns instead of throwing. Protocol violations
     * ([ProtocolException], [BadFrameLengthException]) still propagate — a peer that
     * breaks framing must be disconnected, which libtorrent does the same way.
     */
    suspend fun receiveLoopCatching(
        maxMessageLength: Int = DEFAULT_MAX_MESSAGE_LENGTH,
        onMessage: suspend (PeerMessage) -> Unit,
    ) {
        try {
            receiveLoop(maxMessageLength, onMessage)
        } catch (e: CancellationException) {
            // structured-concurrency cancellation must propagate, never be swallowed:
            // the parent scope is shutting us down. Mark the connection over and rethrow.
            disconnected = true
            throw e
        } catch (e: ProtocolException) {
            throw e
        } catch (e: BadFrameLengthException) {
            throw e
        } catch (_: Throwable) {
            // end-of-stream / socket closed: the connection is over normally.
            disconnected = true
        }
    }

    /**
     * Apply the local state mutations a received message implies — the flag flips and
     * availability bookkeeping from the various `incoming_*` handlers, minus the
     * picker/disk side effects that the orchestrator owns.
     */
    private fun applyIncoming(msg: PeerMessage) {
        when (msg) {
            is PeerMessage.Choke -> state.theirChoking = true        // incoming_choke
            is PeerMessage.Unchoke -> state.theirChoking = false     // incoming_unchoke
            is PeerMessage.Interested -> state.theirInterested = true        // incoming_interested
            is PeerMessage.NotInterested -> state.theirInterested = false    // incoming_not_interested
            is PeerMessage.Have -> {
                // incoming_have: record the new piece if it's in range. Out-of-range
                // is a protocol error in libtorrent; we clamp-ignore here and let the
                // orchestrator decide (it can inspect the forwarded message).
                if (msg.piece in 0 until numPieces) {
                    state.theirBitfield.setBit(msg.piece)
                }
            }
            is PeerMessage.Bitfield -> applyBitfield(msg.bitfield)
            is PeerMessage.HaveAll -> {                              // incoming_have_all (BEP-6)
                state.bitfieldReceived = true
                if (numPieces > 0) state.theirBitfield.setAll()
            }
            is PeerMessage.HaveNone -> {                            // incoming_have_none (BEP-6)
                state.bitfieldReceived = true
                if (numPieces > 0) state.theirBitfield.clearAll()
            }
            else -> { /* request/piece/cancel/port/fast-hints/extended: forwarded as-is */ }
        }
    }

    /**
     * Ingest a received `bitfield` — `incoming_bitfield` / `on_bitfield`. The wire
     * payload must be exactly `ceil(numPieces / 8)` bytes (libtorrent:
     * `packet_size - 1 != (num_pieces + 7) / 8` ⇒ `invalid_bitfield_size`). The
     * decoded [Bitfield] is sized to `payloadBytes * 8`, so we check the byte count,
     * then copy the first [numPieces] bits — trailing pad bits are ignored.
     */
    private fun applyBitfield(bits: Bitfield) {
        // During a metadata-only connection (magnet, before we know the piece count) the
        // PeerConnection is created with numPieces == 0; accept and ignore any bitfield.
        if (numPieces == 0) {
            state.bitfieldReceived = true
            return
        }
        val expectedBytes = (numPieces + 7) / 8
        if (bits.numBytes() != expectedBytes) {
            throw ProtocolException(
                "invalid bitfield size: ${bits.numBytes()} bytes, expected $expectedBytes for $numPieces pieces",
            )
        }
        // Spare-bit conformance: the pad bits in the final byte (those beyond numPieces)
        // MUST be zero per BEP-3. A peer that sets any spare bit is non-conformant; treat
        // it as a protocol error rather than silently masking, so the orchestrator
        // disconnects. The decoded bitfield is sized to expectedBytes*8, so any index in
        // [numPieces, bits.size()) is a pad bit we can inspect directly.
        for (i in numPieces until bits.size()) {
            if (bits[i]) {
                throw ProtocolException(
                    "peer set spare bit $i in bitfield (only $numPieces pieces; pad bits must be zero)",
                )
            }
        }
        state.bitfieldReceived = true
        // Copy the meaningful prefix into our correctly-sized bitfield. bits may carry
        // up to 7 extra pad bits in its final byte; theirBitfield is sized to numPieces.
        val dst = state.theirBitfield
        for (i in 0 until numPieces) {
            dst.setBit(i, bits[i])
        }
    }

    // ============================================================================
    // Outgoing message helpers — one per write_* sender in bt_peer_connection.cpp.
    // Each encodes via PeerMessage and writes the full frame; the ones that change
    // our advertised state also flip the matching flag, like the C++ senders do.
    // ============================================================================

    /** Send `interested` and set [PeerState.amInterested] (`write_interested`). */
    suspend fun sendInterested() {
        ensureWritable()
        if (!state.amInterested) state.amInterested = true
        conn.write(PeerMessage.Interested.encode())
    }

    /** Send `not_interested` and clear [PeerState.amInterested] (`write_not_interested`). */
    suspend fun sendNotInterested() {
        ensureWritable()
        if (state.amInterested) state.amInterested = false
        conn.write(PeerMessage.NotInterested.encode())
    }

    /** Send `choke` and set [PeerState.amChoking] (`write_choke`). */
    suspend fun sendChoke() {
        ensureWritable()
        if (!state.amChoking) state.amChoking = true
        conn.write(PeerMessage.Choke.encode())
    }

    /** Send `unchoke` and clear [PeerState.amChoking] (`write_unchoke`). */
    suspend fun sendUnchoke() {
        ensureWritable()
        if (state.amChoking) state.amChoking = false
        conn.write(PeerMessage.Unchoke.encode())
    }

    /** Send `have` for [piece] (`write_have`), announcing we just completed it. */
    suspend fun sendHave(piece: Int) {
        ensureWritable()
        require(piece in 0 until numPieces) { "have piece $piece out of range [0,$numPieces)" }
        conn.write(PeerMessage.Have(piece).encode())
    }

    /**
     * Send our `bitfield` (`write_bitfield`). libtorrent sends this once, right after
     * the handshake, before any other message. [bitfield] is sent verbatim; its size
     * should match the torrent's piece count.
     */
    suspend fun sendBitfield(bitfield: Bitfield) {
        ensureWritable()
        conn.write(PeerMessage.Bitfield(bitfield).encode())
    }

    /** Send `have_all` (BEP-6, `write_have_all`) — used in place of a full bitfield when seeding. */
    suspend fun sendHaveAll() {
        ensureWritable()
        conn.write(PeerMessage.HaveAll.encode())
    }

    /** Send `have_none` (BEP-6, `write_have_none`) — used in place of an empty bitfield. */
    suspend fun sendHaveNone() {
        ensureWritable()
        conn.write(PeerMessage.HaveNone.encode())
    }

    /** Send `request` for [length] bytes at [begin] within [piece] (`write_request`). */
    suspend fun sendRequest(piece: Int, begin: Int, length: Int) {
        ensureWritable()
        conn.write(PeerMessage.Request(piece, begin, length).encode())
    }

    /** Send `cancel` for a previously requested block (`write_cancel`). */
    suspend fun sendCancel(piece: Int, begin: Int, length: Int) {
        ensureWritable()
        conn.write(PeerMessage.Cancel(piece, begin, length).encode())
    }

    /**
     * Send a `piece` block — [block] bytes at [begin] within [piece] (`write_piece`).
     * The orchestrator reads the block from disk and calls this when honouring a
     * request from an unchoked, interested peer.
     */
    suspend fun sendPiece(piece: Int, begin: Int, block: ByteArray) {
        ensureWritable()
        conn.write(PeerMessage.Piece(piece, begin, block).encode())
    }

    /** Send `reject_request` (BEP-6, `write_reject_request`) — refuse a request we won't serve. */
    suspend fun sendRejectRequest(piece: Int, begin: Int, length: Int) {
        ensureWritable()
        conn.write(PeerMessage.RejectRequest(piece, begin, length).encode())
    }

    /** Send `port` (BEP-5, `write_dht_port`) advertising our DHT UDP [port]. */
    suspend fun sendPort(port: Int) {
        ensureWritable()
        conn.write(PeerMessage.Port(port).encode())
    }

    /** Send a `keep-alive` (four zero bytes, `write_keepalive`). */
    suspend fun sendKeepAlive() {
        ensureWritable()
        conn.write(PeerMessage.KeepAlive.encode())
    }

    /**
     * Send an `extended` message (BEP-10). [extId] 0 is the extension handshake; any
     * other id is one the peer advertised in its handshake `m` dict. [payload] is the
     * bencoded (+ optional appended data) extension message body.
     */
    suspend fun sendExtended(extId: Int, payload: ByteArray) {
        ensureWritable()
        conn.write(PeerMessage.Extended(extId, payload).encode())
    }

    /** Send `hash_request` (id 21, BEP-52) — ask for merkle hashes (`write_hash_request`). */
    suspend fun sendHashRequest(msg: PeerMessage.HashRequest) {
        ensureWritable()
        conn.write(msg.encode())
    }

    /** Send `hashes` (id 22, BEP-52) — the merkle hashes answering a request (`write_hashes`). */
    suspend fun sendHashes(msg: PeerMessage.Hashes) {
        ensureWritable()
        conn.write(msg.encode())
    }

    /** Send `hash_reject` (id 23, BEP-52) — decline a `hash_request` (`write_hash_reject`). */
    suspend fun sendHashReject(msg: PeerMessage.HashReject) {
        ensureWritable()
        conn.write(msg.encode())
    }

    companion object {
        /**
         * Default ceiling on a single decoded message body. A `piece` message is
         * `9 + block_size`; with the protocol-max 16 KiB block plus headroom for an
         * unusually large `bitfield` this 1 MiB cap is comfortably above anything a
         * conforming peer sends while bounding a hostile allocation.
         */
        const val DEFAULT_MAX_MESSAGE_LENGTH: Int = 1 shl 20 // 1 MiB

        /** Read a 4-byte big-endian length prefix as an unsigned [Long] (0..2^32-1). */
        private fun readUInt32BE(b: ByteArray): Long =
            ((b[0].toLong() and 0xff) shl 24) or
                ((b[1].toLong() and 0xff) shl 16) or
                ((b[2].toLong() and 0xff) shl 8) or
                (b[3].toLong() and 0xff)
    }
}

/**
 * The validated outcome of [PeerConnection.performHandshake]: the remote peer's id
 * and its 8 reserved capability bytes, with the three feature flags BitTorrent
 * negotiates through them decoded for convenience. Mirrors what libtorrent stashes in
 * `set_pid()` + `m_reserved_bits` after a successful handshake.
 */
class HandshakeResult(
    /** The remote peer's 20-byte id. */
    val peerId: Sha1Hash,
    /** The raw 8 reserved bytes as they arrived. */
    val reserved: ByteArray,
    /** Peer set the DHT bit (BEP-5). */
    val supportsDht: Boolean,
    /** Peer set the Fast-extension bit (BEP-6). */
    val supportsFast: Boolean,
    /** Peer set the extension-protocol bit (BEP-10). */
    val supportsExtended: Boolean,
)

/**
 * Thrown by [PeerConnection.performHandshake] when the peer is not speaking
 * BitTorrent or offers the wrong info-hash — the conditions libtorrent turns into an
 * `errors::invalid_info_hash` disconnect.
 */
class HandshakeException(message: String) : RuntimeException(message)

/**
 * Thrown by [PeerConnection.receiveLoop] when a peer violates the wire protocol
 * (e.g. a wrong-sized bitfield, or an oversized frame) — the family of
 * `peer_error` disconnects in libtorrent. The connection must be closed.
 */
class ProtocolException(message: String) : RuntimeException(message)

/**
 * Thrown by [PeerConnection.receiveLoop] when a peer announces a frame longer than
 * an addressable [ByteArray] (> `Int.MAX_VALUE`) — certainly malformed/hostile.
 * Distinct from [ProtocolException] so callers can log the offending length.
 */
class BadFrameLengthException(val declaredLength: Long) :
    RuntimeException("peer announced frame length $declaredLength (exceeds Int.MAX_VALUE)")
