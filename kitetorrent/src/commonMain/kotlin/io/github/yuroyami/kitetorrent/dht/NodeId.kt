package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.peer.Crc32c
import io.github.yuroyami.kitetorrent.peer.PeerAddress
import kotlin.random.Random

/**
 * A DHT node id. The pure-Kotlin port of libtorrent's `node_id`
 * (`include/libtorrent/kademlia/node_id.hpp`), which is simply
 * `using node_id = sha1_hash;`, a 160-bit value. We reuse [Digest32]
 * ([Sha1Hash]) unchanged: its [Digest32.xor] is the Kademlia XOR metric and its
 * [Digest32.countLeadingZeroes] is what [distanceExp] is built on.
 *
 * This file ports the free functions in `src/kademlia/node_id.cpp`:
 *  - [distance] / [compareRef] / [distanceExp] / [minDistanceExp]: the XOR
 *    metric helpers.
 *  - [generateIdImpl] / [generateId] / [verifyId]: BEP 42 "secure" node ids
 *    derived from the node's external IP via CRC32C.
 *  - [generateRandomId]: a random 160-bit id.
 *  - [matchingPrefix] / [generatePrefixMask]: bit-prefix helpers used by the
 *    routing table's bucket refresh logic.
 *
 * Secret-id functions (`make_id_secret`/`generate_secret_id`/`verify_secret_id`)
 * are intentionally omitted: they exist only for the DHT's `announce`-token /
 * `sample_infohashes` machinery in `node.cpp`, which is outside this module's
 * routing-table scope, and they rely on a process-global mutable `secret`.
 */

/** The number of bytes in a [NodeId] (160 bits). */
const val NODE_ID_SIZE: Int = Digest32.SHA1_SIZE

/**
 * The distance between two nodes under the Kademlia XOR metric:
 * `distance(n1, n2) = n1 ^ n2`. Direct port of `node_id distance(...)`.
 */
fun distance(n1: Sha1Hash, n2: Sha1Hash): Sha1Hash = n1 xor n2

/**
 * `true` iff `distance(n1, ref) < distance(n2, ref)`, i.e. n1 is strictly closer
 * to `ref` than n2 is. Port of `bool compare_ref(...)`. This is the comparator
 * [RoutingTable.findNode] uses to order results by closeness to the target.
 */
fun compareRef(n1: Sha1Hash, n2: Sha1Hash, ref: Sha1Hash): Boolean =
    (n1 xor ref) < (n2 xor ref)

/**
 * Returns `n` such that `2^n <= distance(n1, n2) < 2^(n+1)`. It is useful for
 * finding which bucket a node belongs to. The value is the number of trailing bits
 * after the shared bit prefix of `n1` and `n2`; if the very first bits differ it is
 * 159 (see the note below).
 *
 * Faithful port of `int distance_exp(...)`:
 * ```
 * return std::max(159 - distance(n1, n2).count_leading_zeroes(), 0);
 * ```
 * libtorrent's own comment flags that returning `159 - clz` (rather than
 * `160 - clz`) is "a little bit weird", but all of the routing-table code is
 * tuned to this convention, so we reproduce it exactly. The off-by-one is part
 * of the contract every caller (notably [RoutingTable]) relies on.
 */
fun distanceExp(n1: Sha1Hash, n2: Sha1Hash): Int {
    val v = 159 - distance(n1, n2).countLeadingZeroes()
    return if (v > 0) v else 0
}

/**
 * The minimum [distanceExp] from `n1` to any id in [ids]. Port of
 * `int min_distance_exp(...)`. [ids] must be non-empty (libtorrent asserts it).
 * The seed value 160 mirrors the upstream constant (see [distanceExp] for why).
 */
fun minDistanceExp(n1: Sha1Hash, ids: List<Sha1Hash>): Int {
    require(ids.isNotEmpty()) { "min_distance_exp requires at least one id" }
    var min = 160
    for (id in ids) {
        val d = distanceExp(n1, id)
        if (d < min) min = d
    }
    return min
}

// --- BEP 42 secure node ids -------------------------------------------------

/** IPv4 octet masks from `generate_id_impl` (`v4mask`). */
private val V4_MASK = intArrayOf(0x03, 0x0f, 0x3f, 0xff)

/** IPv6 octet masks from `generate_id_impl` (`v6mask`). */
private val V6_MASK = intArrayOf(0x01, 0x03, 0x07, 0x0f, 0x1f, 0x3f, 0x7f, 0xff)

/**
 * Generate a BEP 42 ("secure") node id from an external IP address and a chosen
 * 8-bit "r" value, the deterministic core of id generation. Faithful port of
 * `node_id generate_id_impl(address const& ip_, std::uint32_t r)`.
 *
 * The construction (https://www.bittorrent.org/beps/bep_0042.html):
 *  1. Take the first 4 (IPv4) or 8 (IPv6) octets of the address and AND each
 *     with the corresponding [V4_MASK]/[V6_MASK] byte.
 *  2. OR `(r & 0x7) << 5` into octet 0.
 *  3. CRC32C those masked octets (in network-byte order, see below).
 *  4. `id[0] = crc >> 24`, `id[1] = crc >> 16`,
 *     `id[2] = ((crc >> 8) & 0xf8) | rand(0..7)` (top 5 bits fixed, low 3 random).
 *  5. `id[3..18]` = random bytes; `id[19] = r & 0xff`.
 *
 * **CRC32C byte order.** libtorrent reads the masked octets back as a
 * `std::uint32_t`/`std::uint64_t` and feeds them to its CRC32C, which (on the
 * common little-endian path, and per the BEP test vectors) is equivalent to
 * running CRC32C over the masked octets in their original array order. Our
 * [Crc32c.compute] processes bytes in array order, so we pass the masked octets
 * straight in. This matches the canonical BEP 42 vectors
 * (e.g. `124.31.75.21`, r=1 → prefix `5f bf bf…`), which the test suite pins.
 *
 * @param ip the node's external address (4-byte IPv4 or 16-byte IPv6).
 * @param r the 8-bit value placed in `id[19]`; only its low 3 bits affect the CRC.
 * @param random RNG for the random portion (defaults to [Random.Default]; pass a
 *        seeded instance for reproducible ids in tests). libtorrent uses
 *        `random(max)` which is inclusive of `max`, so `rand(0..7)` and
 *        `rand(0..255)` are realised as `nextInt(0, 8)` / `nextInt(0, 256)`.
 */
fun generateIdImpl(ip: PeerAddress, r: Int, random: Random = Random.Default): Sha1Hash {
    val numOctets = if (ip.isV6) 8 else 4
    val mask = if (ip.isV6) V6_MASK else V4_MASK

    // masked leading octets of the address
    val octets = ByteArray(numOctets)
    for (i in 0 until numOctets) {
        octets[i] = ((ip.bytes[i].toInt() and 0xff) and mask[i]).toByte()
    }
    // ip[0] |= (r & 0x7) << 5;
    octets[0] = ((octets[0].toInt() and 0xff) or ((r and 0x7) shl 5)).toByte()

    val c = Crc32c.compute(octets) // unsigned 32-bit CRC32C of the masked octets

    val id = ByteArray(NODE_ID_SIZE)
    id[0] = ((c ushr 24) and 0xff).toByte()
    id[1] = ((c ushr 16) and 0xff).toByte()
    // top 5 bits from the CRC, low 3 bits random
    id[2] = ((((c ushr 8) and 0xf8) or random.nextInt(0, 8)) and 0xff).toByte()
    for (i in 3..18) id[i] = (random.nextInt(0, 256) and 0xff).toByte()
    id[19] = (r and 0xff).toByte()

    return Digest32.sha1(id)
}

/**
 * Generate a fully random BEP 42 secure node id for [ip], choosing `r` at
 * random. Port of `node_id generate_id(address const& ip)`:
 * `return generate_id_impl(ip, random(0xffffffff));`. Only the low byte of `r`
 * (and its low 3 bits, via the CRC) matter, so we draw a random 8-bit `r`.
 */
fun generateId(ip: PeerAddress, random: Random = Random.Default): Sha1Hash =
    generateIdImpl(ip, random.nextInt(0, 256), random)

/**
 * A fully random 160-bit node id, with no IP binding. Port of
 * `node_id generate_random_id()` (which hashes 20 random bytes; the SHA-1 there
 * is just a whitening step over an already-random buffer, so 20 fresh random
 * bytes are an equivalent uniform 160-bit value).
 */
fun generateRandomId(random: Random = Random.Default): Sha1Hash =
    Digest32.sha1(random.nextBytes(NODE_ID_SIZE))

/**
 * Verify that [nid] is a valid secure id for a node observed at [sourceIp].
 * Port of `bool verify_id(node_id const& nid, address const& source_ip)`.
 *
 * Local/private source addresses are always accepted (their secure-id derivation
 * would be meaningless), matching `if (aux::is_local(source_ip)) return true;`.
 * Otherwise we recompute the secure prefix using `nid[19]` as `r` and require the
 * first two bytes to match and the top 5 bits of the third byte to match.
 */
fun verifyId(nid: Sha1Hash, sourceIp: PeerAddress): Boolean {
    if (sourceIp.isLocal) return true
    val h = generateIdImpl(sourceIp, nid[19])
    return nid[0] == h[0] &&
        nid[1] == h[1] &&
        (nid[2] and 0xf8) == (h[2] and 0xf8)
}

// --- prefix helpers ---------------------------------------------------------

/**
 * `true` iff, after shifting [nid] left by [offset] bits, the top byte ANDed with
 * [mask] equals [prefix]. Port of
 * `bool matching_prefix(node_id const& nid, int mask, int prefix, int offset)`.
 */
fun matchingPrefix(nid: Sha1Hash, mask: Int, prefix: Int, offset: Int): Boolean {
    val id = nid.shl(offset)
    return (id[0] and mask) == prefix
}

/**
 * A node id whose top [bits] bits are 1 and the rest 0. Port of
 * `node_id generate_prefix_mask(int bits)`. [bits] must be in `0..160`.
 *
 * Reproduces the upstream loop exactly:
 * ```
 * for (; b < bits - 7; b += 8) mask[b/8] |= 0xff;
 * if (bits < 160) mask[b/8] |= (0xff << (8 - (bits & 7))) & 0xff;
 * ```
 */
fun generatePrefixMask(bits: Int): Sha1Hash {
    require(bits in 0..NODE_ID_SIZE * 8) { "bits must be in 0..160, was $bits" }
    val mask = ByteArray(NODE_ID_SIZE)
    var b = 0
    while (b < bits - 7) {
        mask[b / 8] = (mask[b / 8].toInt() or 0xff).toByte()
        b += 8
    }
    if (bits < NODE_ID_SIZE * 8) {
        val extra = (0xff shl (8 - (bits and 7))) and 0xff
        mask[b / 8] = (mask[b / 8].toInt() or extra).toByte()
    }
    return Digest32.sha1(mask)
}
