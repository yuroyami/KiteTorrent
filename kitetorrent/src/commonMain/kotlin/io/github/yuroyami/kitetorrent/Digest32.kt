package io.github.yuroyami.kitetorrent

/**
 * Holds an N-bit digest (or any fixed-size bit sequence) — the pure-Kotlin port of
 * libtorrent's `digest32<N>` (sha1_hash.hpp). In libtorrent this is used for
 * info-hashes, piece hashes, peer IDs, DHT node IDs, etc.
 *
 * Backed by a [ByteArray] in big-endian (network) byte order, which is how all
 * these values travel on the wire and in `.torrent` files. The type is immutable:
 * every bit operation returns a fresh [Digest32].
 *
 * libtorrent fixes the width at compile time via the `N` template parameter; here
 * the width is carried by the instance and the [Sha1Hash]/[Sha256Hash] aliases plus
 * the [sha1]/[sha256] factories document intent at call sites.
 */
class Digest32 private constructor(private val n: ByteArray) : Comparable<Digest32> {

    /** Size of the digest in bytes. */
    val size: Int get() = n.size

    /** Returns the unsigned value of byte [i] (0..255). */
    operator fun get(i: Int): Int = n[i].toInt() and 0xff

    /** A defensive copy of the raw bytes, big-endian. */
    fun toByteArray(): ByteArray = n.copyOf()

    /** The 2*size lowercase hex representation (40 chars for SHA-1, 64 for SHA-256). */
    fun toHex(): String = Hex.encode(n)

    /** True if every byte is zero — the additive identity / "unset" sentinel. */
    fun isAllZeros(): Boolean {
        for (b in n) if (b.toInt() != 0) return false
        return true
    }

    // --- bit-wise operators (used by the DHT XOR-distance metric) ---------------

    infix fun xor(o: Digest32): Digest32 = combine(o) { a, b -> a xor b }
    infix fun and(o: Digest32): Digest32 = combine(o) { a, b -> a and b }
    infix fun or(o: Digest32): Digest32 = combine(o) { a, b -> a or b }

    /** Bit-wise NOT — every bit flipped. */
    fun inv(): Digest32 {
        val r = ByteArray(n.size)
        for (i in n.indices) r[i] = n[i].toInt().inv().toByte()
        return Digest32(r)
    }

    private inline fun combine(o: Digest32, op: (Int, Int) -> Int): Digest32 {
        require(o.n.size == n.size) { "digest size mismatch: ${n.size} vs ${o.n.size}" }
        val r = ByteArray(n.size)
        for (i in n.indices) r[i] = op(n[i].toInt(), o.n[i].toInt()).toByte()
        return Digest32(r)
    }

    /**
     * Unsigned lexicographic comparison, MSB first. libtorrent reaches the same
     * ordering by interpreting each 32-bit word in network byte order; on a
     * byte-backed digest that is simply comparing the bytes as unsigned values.
     */
    override fun compareTo(other: Digest32): Int {
        require(other.n.size == n.size) { "digest size mismatch" }
        for (i in n.indices) {
            val d = (n[i].toInt() and 0xff) - (other.n[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return 0
    }

    /** Number of leading zero bits, counted MSB first. Used by the DHT. */
    fun countLeadingZeroes(): Int {
        var count = 0
        for (b in n) {
            val v = b.toInt() and 0xff
            if (v == 0) { count += 8; continue }
            // leading zeros within this byte
            var mask = 0x80
            while (mask != 0 && (v and mask) == 0) { count++; mask = mask ushr 1 }
            break
        }
        return count
    }

    /** Shift the whole digest left by [bits], zero-filling the low end. */
    fun shl(bits: Int): Digest32 {
        require(bits >= 0)
        val r = ByteArray(n.size)
        val byteShift = bits / 8
        val bitShift = bits % 8
        for (i in n.indices) {
            val src = i + byteShift
            val a = if (src < n.size) n[src].toInt() and 0xff else 0
            val b = if (src + 1 < n.size) n[src + 1].toInt() and 0xff else 0
            r[i] = if (bitShift == 0) a.toByte()
            else (((a shl bitShift) or (b ushr (8 - bitShift))) and 0xff).toByte()
        }
        return Digest32(r)
    }

    /** Shift the whole digest right by [bits], zero-filling the high end. */
    fun shr(bits: Int): Digest32 {
        require(bits >= 0)
        val r = ByteArray(n.size)
        val byteShift = bits / 8
        val bitShift = bits % 8
        for (i in n.indices.reversed()) {
            val src = i - byteShift
            val a = if (src >= 0) n[src].toInt() and 0xff else 0
            val b = if (src - 1 >= 0) n[src - 1].toInt() and 0xff else 0
            r[i] = if (bitShift == 0) a.toByte()
            else (((a ushr bitShift) or (b shl (8 - bitShift))) and 0xff).toByte()
        }
        return Digest32(r)
    }

    override fun equals(other: Any?): Boolean =
        other is Digest32 && n.contentEquals(other.n)

    override fun hashCode(): Int = n.contentHashCode()

    override fun toString(): String = toHex()

    companion object {
        const val SHA1_SIZE = 20
        const val SHA256_SIZE = 32

        /** Wrap a copy of [bytes]. Length must be a multiple of 4 (like digest32<N>). */
        fun of(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Digest32 {
            require(length % 4 == 0) { "digest width must be a multiple of 4 bytes, was $length" }
            return Digest32(bytes.copyOfRange(offset, offset + length))
        }

        /** Parse a hex string (40 chars → SHA-1, 64 → SHA-256). */
        fun fromHex(hex: String): Digest32 = of(Hex.decode(hex))

        /** An all-zero digest of [sizeBytes] bytes. */
        fun zeros(sizeBytes: Int): Digest32 = of(ByteArray(sizeBytes))

        /** An all-ones (maximum) digest of [sizeBytes] bytes. */
        fun max(sizeBytes: Int): Digest32 = of(ByteArray(sizeBytes) { 0xFF.toByte() })

        /** A 20-byte (SHA-1 sized) digest from raw bytes. */
        fun sha1(bytes: ByteArray): Digest32 {
            require(bytes.size == SHA1_SIZE) { "SHA-1 hash must be 20 bytes, was ${bytes.size}" }
            return of(bytes)
        }

        /** A 32-byte (SHA-256 sized) digest from raw bytes. */
        fun sha256(bytes: ByteArray): Digest32 {
            require(bytes.size == SHA256_SIZE) { "SHA-256 hash must be 32 bytes, was ${bytes.size}" }
            return of(bytes)
        }
    }
}

/** A 160-bit digest — info-hash v1, piece hash, peer ID, DHT node ID. */
typealias Sha1Hash = Digest32

/** A 256-bit digest — info-hash v2 / BitTorrent v2 merkle nodes. */
typealias Sha256Hash = Digest32
