package io.github.yuroyami.kitetorrent

/**
 * A bitfield of arbitrary length — pure-Kotlin port of libtorrent's `bitfield`
 * (bitfield.hpp). Tracks which pieces a peer has / which we have.
 *
 * Stored MSB-first within each byte (bit 0 is `0x80` of byte 0), which is exactly
 * the on-the-wire layout of the BitTorrent `bitfield` message — so [data] can be
 * sent and [assign] can ingest a received bitfield with no reshuffling. libtorrent
 * reaches the same layout via network-byte-order 32-bit words; a byte array is the
 * simpler equivalent on the JVM/Native/JS, where we have no raw-pointer aliasing.
 */
class Bitfield private constructor(
    private var bits: Int,
    private var buf: ByteArray,
) {
    constructor() : this(0, EMPTY)

    /** A bitfield of [bits] length, all zero (or all [value] if given). */
    constructor(bits: Int, value: Boolean = false) : this(0, EMPTY) {
        resize(bits, value)
    }

    /** Number of bits in the bitfield. */
    fun size(): Int = bits

    /** True if the bitfield has zero length. */
    fun isEmpty(): Boolean = bits == 0

    /** Number of bytes backing the bits. */
    fun numBytes(): Int = (bits + 7) / 8

    /** The backing bytes, ready to drop into a wire `bitfield` message. */
    fun data(): ByteArray = if (bits == 0) EMPTY else buf.copyOf(numBytes())

    operator fun get(index: Int): Boolean = getBit(index)

    fun getBit(index: Int): Boolean {
        require(index in 0 until bits) { "bit index $index out of range [0,$bits)" }
        return (buf[index ushr 3].toInt() and (0x80 ushr (index and 7))) != 0
    }

    fun setBit(index: Int) {
        require(index in 0 until bits) { "bit index $index out of range [0,$bits)" }
        buf[index ushr 3] = (buf[index ushr 3].toInt() or (0x80 ushr (index and 7))).toByte()
    }

    fun clearBit(index: Int) {
        require(index in 0 until bits) { "bit index $index out of range [0,$bits)" }
        buf[index ushr 3] = (buf[index ushr 3].toInt() and (0x80 ushr (index and 7)).inv()).toByte()
    }

    fun setBit(index: Int, value: Boolean) {
        if (value) setBit(index) else clearBit(index)
    }

    /** Number of bits set to 1. */
    fun count(): Int {
        var c = 0
        val n = numBytes()
        for (i in 0 until n) c += countOnes(buf[i].toInt() and 0xff)
        return c
    }

    /** True if every bit is 1. */
    fun allSet(): Boolean = bits != 0 && count() == bits

    /** True if no bit is 1. */
    fun noneSet(): Boolean {
        val n = numBytes()
        for (i in 0 until n) if (buf[i].toInt() != 0) return false
        return true
    }

    /** Index of the first set (1) bit, or -1 if none. */
    fun findFirstSet(): Int {
        for (i in 0 until bits) if (getBit(i)) return i
        return -1
    }

    /** Index of the last cleared (0) bit, or -1 if every bit is set. */
    fun findLastClear(): Int {
        for (i in bits - 1 downTo 0) if (!getBit(i)) return i
        return -1
    }

    /** Set every bit to 1 (trailing bits in the final byte are left clear). */
    fun setAll() {
        if (bits == 0) return
        buf.fill(0xFF.toByte(), 0, numBytes())
        clearTrailingBits()
    }

    /** Set every bit to 0. */
    fun clearAll() {
        if (bits == 0) return
        buf.fill(0, 0, numBytes())
    }

    /** Drop all bits, making the bitfield empty. */
    fun clear() {
        bits = 0
        buf = EMPTY
    }

    /** Resize to [newBits] length; bits added on growth take [value]. */
    fun resize(newBits: Int, value: Boolean = false) {
        require(newBits >= 0)
        if (newBits == 0) { clear(); return }
        val newBytes = (newBits + 7) / 8
        val old = buf
        val oldBits = bits
        val nb = ByteArray(newBytes)
        old.copyInto(nb, 0, 0, minOf(old.size, newBytes))
        if (value) {
            // fill new whole bytes
            val firstNewByte = (oldBits + 7) / 8
            for (i in firstNewByte until newBytes) nb[i] = 0xFF.toByte()
            // fill the partial bits between oldBits and the byte boundary
            var i = oldBits
            buf = nb // so setBit works against the new buffer
            bits = newBits
            val boundary = minOf(firstNewByte * 8, newBits)
            while (i < boundary) { setBit(i); i++ }
            clearTrailingBits()
            return
        }
        buf = nb
        bits = newBits
        clearTrailingBits()
    }

    /** Copy [bitCount] bits from [bytes] (rounded up to whole bytes). */
    fun assign(bytes: ByteArray, bitCount: Int) {
        resize(bitCount)
        if (bitCount > 0) {
            bytes.copyInto(buf, 0, 0, (bitCount + 7) / 8)
            clearTrailingBits()
        }
    }

    private fun clearTrailingBits() {
        val rem = bits and 7
        if (rem != 0) {
            val lastByte = numBytes() - 1
            val mask = (0xFF shl (8 - rem)) and 0xFF
            buf[lastByte] = (buf[lastByte].toInt() and mask).toByte()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Bitfield) return false
        if (bits != other.bits) return false
        val n = numBytes()
        for (i in 0 until n) if (buf[i] != other.buf[i]) return false
        return true
    }

    override fun hashCode(): Int = bits * 31 + data().contentHashCode()

    override fun toString(): String = buildString(bits) {
        for (i in 0 until bits) append(if (getBit(i)) '1' else '0')
    }

    companion object {
        private val EMPTY = ByteArray(0)

        private fun countOnes(b: Int): Int {
            var v = b and 0xff
            var c = 0
            while (v != 0) { v = v and (v - 1); c++ }
            return c
        }

        /** Build a bitfield from raw wire bytes carrying [bitCount] bits. */
        fun fromBytes(bytes: ByteArray, bitCount: Int): Bitfield {
            val bf = Bitfield()
            bf.assign(bytes, bitCount)
            return bf
        }
    }
}
