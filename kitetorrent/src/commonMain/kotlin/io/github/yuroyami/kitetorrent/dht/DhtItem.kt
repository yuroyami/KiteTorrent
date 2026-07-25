package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Ed25519
import io.github.yuroyami.kitetorrent.crypto.Sha1
import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder

/**
 * The fixed-size ed25519 / sequence-number value types used by BEP-44 mutable DHT
 * items. This is the port of libtorrent's `dht::public_key`, `dht::secret_key`,
 * `dht::signature` and `dht::sequence_number` (include/libtorrent/kademlia/types.hpp).
 *
 * libtorrent fixes these widths as `std::array<char, len>`; here they're plain
 * [ByteArray]s validated against [PUBLIC_KEY_LEN] / [SECRET_KEY_LEN] /
 * [SIGNATURE_LEN]. The `sequence_number` is a 64-bit value, so a Kotlin [Long].
 */
object DhtKeySizes {
    /** ed25519 public key length, from `public_key::len`. */
    const val PUBLIC_KEY_LEN = 32

    /** ed25519 secret (private) key length, from `secret_key::len`. */
    const val SECRET_KEY_LEN = 64

    /** ed25519 signature length, from `signature::len`. */
    const val SIGNATURE_LEN = 64

    /**
     * Maximum salt length the DHT will accept for a mutable put. libtorrent rejects a
     * `salt` longer than this in `node::incoming_request` ("salt too big", 207).
     */
    const val MAX_SALT_LEN = 64

    /**
     * Maximum size of the bencoded `v` value libtorrent will store/sign for a DHT
     * item. `node::incoming_request` rejects a `v` larger than this ("message too
     * big", 205). The `assign` path asserts the bencoding fits in 1000 bytes.
     */
    const val MAX_VALUE_LEN = 1000
}

/**
 * Builds the canonical byte buffer that gets signed/verified for a BEP-44 mutable
 * item. This is a direct port of the anonymous `canonical_string()` in
 * src/kademlia/item.cpp.
 *
 * The layout is, with the salt omitted entirely when empty:
 *
 *     4:salt<len>:<salt>3:seqi<seq>e1:v<bencoded-value>
 *
 * Note that `<bencoded-value>` is spliced in verbatim: it must already be valid
 * bencoding (libtorrent asserts this). So for a string value "Hello World!" the
 * tail is `1:v12:Hello World!`, and the whole thing for seq=1 with no salt is
 * `3:seqi1e1:v12:Hello World!`, which is the example in the BEP-44 spec.
 *
 * libtorrent assembles this with `snprintf` into a fixed 1200-byte stack buffer; we
 * build it allocation-checked into a [ByteArrayBuilder]. The bytes produced are
 * identical, and that is what matters: they are the ed25519 message.
 */
internal fun canonicalString(value: ByteArray, seq: Long, salt: ByteArray): ByteArray {
    // Pre-size: salt framing (~ "4:saltNN:" + salt) + seq framing + "1:v" + value.
    val out = ByteArrayBuilder(value.size + salt.size + 48)
    if (salt.isNotEmpty()) {
        // "4:salt" then "<len>:" then the raw salt bytes
        out.appendAscii("4:salt")
        out.appendAscii(salt.size.toString())
        out.append(':'.code.toByte())
        out.append(salt)
    }
    out.appendAscii("3:seqi")
    out.appendAscii(seq.toString())
    out.appendAscii("e1:v")
    out.append(value)
    return out.toByteArray()
}

/**
 * Free functions mirroring the file-scope helpers in src/kademlia/item.cpp:
 * `item_target_id`, `sign_mutable_item`, `verify_mutable_item`.
 */
object DhtItems {

    /**
     * Target hash for an **immutable** item, from `sha1_hash item_target_id(span<char
     * const> v)` in item.cpp. The argument is the *already bencoded* value; the
     * target is simply its SHA-1.
     */
    fun immutableTargetId(bencodedValue: ByteArray): Sha1Hash =
        Sha1Hash.sha1(Sha1.hash(bencodedValue))

    /**
     * Target hash for a **mutable** item, from `sha1_hash item_target_id(span<char
     * const> salt, public_key const& pk)` in item.cpp:
     * `sha1(public_key || salt)` (salt omitted when empty).
     */
    fun mutableTargetId(publicKey: ByteArray, salt: ByteArray = EMPTY): Sha1Hash {
        require(publicKey.size == DhtKeySizes.PUBLIC_KEY_LEN) {
            "public key must be ${DhtKeySizes.PUBLIC_KEY_LEN} bytes, was ${publicKey.size}"
        }
        val h = Sha1()
        h.update(publicKey)
        if (salt.isNotEmpty()) h.update(salt)
        return Sha1Hash.sha1(h.digest())
    }

    /**
     * Produce an ed25519 signature over the canonical BEP-44 buffer. This is the port of
     * `signature sign_mutable_item(...)`. [bencodedValue] must be valid bencoding.
     */
    fun signMutableItem(
        bencodedValue: ByteArray,
        salt: ByteArray,
        seq: Long,
        publicKey: ByteArray,
        secretKey: ByteArray,
    ): ByteArray {
        val msg = canonicalString(bencodedValue, seq, salt)
        return Ed25519.sign(msg, publicKey, secretKey)
    }

    /**
     * Verify an ed25519 signature over the canonical BEP-44 buffer. This is the port
     * of `bool verify_mutable_item(...)`.
     */
    fun verifyMutableItem(
        bencodedValue: ByteArray,
        salt: ByteArray,
        seq: Long,
        publicKey: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val msg = canonicalString(bencodedValue, seq, salt)
        return Ed25519.verify(signature, msg, publicKey)
    }

    internal val EMPTY = ByteArray(0)
}

/**
 * A DHT data item, the port of `class dht::item` (item.cpp, item.hpp). An item is
 * either **immutable** (target = SHA-1 of the bencoded value, BEP-44 §"Immutable
 * items") or **mutable** (target = SHA-1(public key + salt), signed by an ed25519
 * key, BEP-44 §"Mutable items").
 *
 * libtorrent's `item` carries an owning `entry m_value`; here the value is held both
 * as a parsed [Entry] ([value]) and as its canonical bencoding ([bencodedValue]),
 * because the bencoding is what feeds the target hash and the signature and must be
 * byte-stable. Use [ImmutableItem] / [MutableItem] rather than constructing this
 * directly.
 */
sealed class DhtItem {
    /** The item's value as an editable [Entry]. */
    abstract val value: Entry

    /** The exact bencoding of [value]: the bytes that travel as `a["v"]`. */
    abstract val bencodedValue: ByteArray

    /** True for a BEP-44 mutable (signed) item. */
    abstract val isMutable: Boolean

    /** The DHT key this item is stored under. */
    abstract val target: Sha1Hash
}

/**
 * An immutable BEP-44 item, `item(entry v)` in libtorrent. Its [target] is the
 * SHA-1 of the bencoded value, so the value cannot change without changing the key.
 */
class ImmutableItem private constructor(
    override val value: Entry,
    override val bencodedValue: ByteArray,
) : DhtItem() {

    override val isMutable: Boolean get() = false

    override val target: Sha1Hash by lazy { DhtItems.immutableTargetId(bencodedValue) }

    companion object {
        /** Build from an editable [Entry] (it is bencoded immediately). */
        fun of(value: Entry): ImmutableItem = ImmutableItem(value, Bencode.encode(value))

        /** Build from a parsed `v` node, e.g. the `v` of an incoming get/put. */
        fun of(value: BdecodeNode): ImmutableItem =
            ImmutableItem(Entry.from(value), value.dataSection())

        /**
         * Reconstruct from raw bencoding (e.g. the bytes pulled off the wire). The
         * bytes are kept verbatim so the target hash matches the sender's.
         */
        fun fromBencoded(bencodedValue: ByteArray): ImmutableItem =
            ImmutableItem(Entry.from(Bdecode.decode(bencodedValue)), bencodedValue)
    }
}

/**
 * A mutable BEP-44 item: the signed variant of `dht::item`. It holds the ed25519
 * public key, a salt (possibly empty), a 64-bit sequence number and a signature
 * over [DhtItems.signMutableItem]'s canonical buffer.
 *
 * [target] is `SHA-1(publicKey + salt)`, so the same key + salt always map to the
 * same slot and a higher [seq] supersedes a lower one (the DHT keeps the highest
 * sequence number it can verify). Construct an unsigned shell with [of], then call
 * [sign]; or rebuild a received item with [received].
 */
class MutableItem private constructor(
    override val value: Entry,
    override val bencodedValue: ByteArray,
    /** ed25519 public key, 32 bytes, from `pk()`. */
    val publicKey: ByteArray,
    /** Salt, possibly empty, from `salt()`. */
    val salt: ByteArray,
    /** Sequence number, from `seq()`. Defaults to 0 like libtorrent's `m_seq{0}`. */
    var seq: Long,
    /** ed25519 signature, 64 bytes, or null until [sign]ed. From `sig()`. */
    var signature: ByteArray?,
) : DhtItem() {

    init {
        require(publicKey.size == DhtKeySizes.PUBLIC_KEY_LEN) {
            "public key must be ${DhtKeySizes.PUBLIC_KEY_LEN} bytes, was ${publicKey.size}"
        }
        require(salt.size <= DhtKeySizes.MAX_SALT_LEN) {
            "salt must be at most ${DhtKeySizes.MAX_SALT_LEN} bytes, was ${salt.size}"
        }
        val sig = signature
        require(sig == null || sig.size == DhtKeySizes.SIGNATURE_LEN) {
            "signature must be ${DhtKeySizes.SIGNATURE_LEN} bytes, was ${sig?.size}"
        }
    }

    override val isMutable: Boolean get() = true

    override val target: Sha1Hash get() = DhtItems.mutableTargetId(publicKey, salt)

    /** True once a non-null signature is present. */
    val isSigned: Boolean get() = signature != null

    /**
     * The exact bytes that are (or would be) signed: the BEP-44 canonical buffer
     * for this item's value, seq and salt. Exposed so callers can audit or re-verify.
     */
    fun signingBuffer(): ByteArray = canonicalString(bencodedValue, seq, salt)

    /**
     * Sign this item with the given ed25519 key pair, storing the resulting
     * signature. This mirrors `item::assign(entry v, salt, seq, pk, sk)`, which signs
     * the bencoded value and remembers `m_sig`.
     *
     * @return the 64-byte signature (also stored in [signature]).
     */
    fun sign(secretKey: ByteArray, pubKey: ByteArray = publicKey): ByteArray {
        require(pubKey.contentEquals(publicKey)) { "public key does not match the item's key" }
        val sig = DhtItems.signMutableItem(bencodedValue, salt, seq, publicKey, secretKey)
        signature = sig
        return sig
    }

    /**
     * Verify this item's stored signature against its public key. This is the port of the
     * `verify_mutable_item` check in `item::assign(bdecode_node, salt, seq, pk,
     * sig)`. Returns false if unsigned.
     */
    fun verify(): Boolean {
        val sig = signature ?: return false
        return DhtItems.verifyMutableItem(bencodedValue, salt, seq, publicKey, sig)
    }

    companion object {
        /**
         * Build an unsigned mutable item from an editable value. Call [sign] before
         * sending. Mirrors libtorrent constructing an `item` then `assign`-ing.
         */
        fun of(
            value: Entry,
            publicKey: ByteArray,
            seq: Long,
            salt: ByteArray = DhtItems.EMPTY,
        ): MutableItem =
            MutableItem(value, Bencode.encode(value), publicKey.copyOf(), salt.copyOf(), seq, null)

        /**
         * Rebuild a mutable item received over the wire, verifying its signature.
         * This ports `bool item::assign(bdecode_node const& v, salt, seq, pk, sig)`,
         * which returns false (here: null) when verification fails.
         *
         * [value] is the parsed `v` node; its [BdecodeNode.dataSection] is the exact
         * bencoding that was signed, so the signature round-trips.
         */
        fun received(
            value: BdecodeNode,
            publicKey: ByteArray,
            seq: Long,
            signature: ByteArray,
            salt: ByteArray = DhtItems.EMPTY,
        ): MutableItem? {
            val bencoded = value.dataSection()
            if (!DhtItems.verifyMutableItem(bencoded, salt, seq, publicKey, signature)) return null
            return MutableItem(
                Entry.from(value), bencoded, publicKey.copyOf(), salt.copyOf(), seq, signature.copyOf(),
            )
        }
    }
}
