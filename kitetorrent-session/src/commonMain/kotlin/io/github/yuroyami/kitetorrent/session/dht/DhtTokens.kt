package io.github.yuroyami.kitetorrent.session.dht

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.crypto.Hasher
import kotlin.random.Random

/**
 * Write-token generation and verification for an inbound `get_peers` / `announce_peer`
 * (and BEP-44 `get`/`put`) flow. This is the pure, network-free port of
 * `node::generate_token` and `node::verify_token` (`src/kademlia/node.cpp`).
 *
 * A write token is the node's promise that a querier really controls the address it
 * announced from: the node hands one out in every `get_peers`/`get` reply and only
 * accepts an `announce_peer`/`put` that echoes a still-valid token. libtorrent computes
 *
 * ```
 * token = SHA1( address_string ‖ secret ‖ info_hash )[0 .. 4)     // write_token_size = 4
 * ```
 *
 * and keeps **two** rolling secrets so a token minted just before a secret rotation is
 * still accepted just after it: `verify_token` checks the current secret first, then the
 * previous one (`node.cpp` lines 190-202). [rotateSecret] performs the same
 * `m_secret[1] = m_secret[0]; m_secret[0] = random` shuffle that `node::tick` does every
 * few minutes.
 *
 * **One deliberate deviation from libtorrent.** Upstream hashes
 * `addr.address().to_string()`, the textual form of the IP.
 * `commonMain`'s [io.github.yuroyami.kitetorrent.peer.PeerAddress]
 * exposes no canonical literal accessor, and a textual form is anyway ambiguous (`::1`
 * vs `0:0:…:1`). We instead hash the **raw address bytes** the datagram arrived with.
 * This is strictly an internal, per-node secret hash whose only requirements are
 * determinism and that it bind address to info-hash. The byte form satisfies both at
 * least as well as the string form. Tokens are opaque to every other node, so this
 * never has to interoperate with libtorrent's exact byte layout; it only has to
 * round-trip against *this* node's own [verify].
 *
 * The token width is fixed at [TOKEN_SIZE] (4 bytes), matching `write_token_size`.
 */
class DhtTokens(random: Random = Random.Default) {

    /**
     * The two rolling secrets: `m_secret[0]` (current) and `m_secret[1]` (previous).
     * Each is [SECRET_SIZE] random bytes (libtorrent uses a 4-byte `std::array`, but the
     * width is irrelevant to correctness as long as it's secret; we use 16 for margin).
     */
    private var current: ByteArray = random.nextBytes(SECRET_SIZE)
    private var previous: ByteArray = random.nextBytes(SECRET_SIZE)
    private val rng = random

    /**
     * Generate the write token for a querier at [addressBytes] (the network-byte-order
     * IP the datagram came from, 4 or 16 bytes) asking about [infoHash]. Port of
     * `node::generate_token`. Always uses the current secret.
     */
    fun generate(addressBytes: ByteArray, infoHash: Sha1Hash): ByteArray =
        tokenWith(addressBytes, infoHash, current)

    /**
     * `true` if [token] is a valid write token for [addressBytes]+[infoHash] under
     * either the current or the previous secret. This ports `node::verify_token`. A token
     * of the wrong length is rejected outright (the `token.length() != write_token_size`
     * guard).
     */
    fun verify(token: ByteArray, addressBytes: ByteArray, infoHash: Sha1Hash): Boolean {
        if (token.size != TOKEN_SIZE) return false
        return token.contentEquals(tokenWith(addressBytes, infoHash, current)) ||
            token.contentEquals(tokenWith(addressBytes, infoHash, previous))
    }

    /**
     * Roll the secrets: the current becomes the previous and a fresh random current is
     * drawn. This ports the secret rotation in `node::tick`
     * (`m_secret[1] = m_secret[0]; aux::crypto_random_bytes(m_secret[0]);`). Call this on
     * a timer (libtorrent every ~5 minutes) so a leaked token expires.
     */
    fun rotateSecret() {
        previous = current
        current = rng.nextBytes(SECRET_SIZE)
    }

    private fun tokenWith(addressBytes: ByteArray, infoHash: Sha1Hash, secret: ByteArray): ByteArray {
        val full = Hasher()
            .update(addressBytes)
            .update(secret)
            .update(infoHash.toByteArray())
            .final()
        return full.toByteArray().copyOf(TOKEN_SIZE)
    }

    companion object {
        /** Token width in bytes, libtorrent's `write_token_size`. */
        const val TOKEN_SIZE: Int = 4

        /** Per-node secret width. Opaque; only [DhtTokens] ever sees it. */
        const val SECRET_SIZE: Int = 16
    }
}
