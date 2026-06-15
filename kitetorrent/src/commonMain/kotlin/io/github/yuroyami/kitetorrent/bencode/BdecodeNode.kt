package io.github.yuroyami.kitetorrent.bencode

/**
 * A node in the tree produced by [Bdecode.decode] — port of libtorrent's
 * `bdecode_node` (bdecode.hpp). Every node keeps a reference to the original parsed
 * [buffer] plus the byte span it occupies, so [dataSection] can hand back the exact
 * bytes a node was parsed from. That is what makes correct info-hashing possible:
 * the v1 info-hash is `SHA-1(info-dict's dataSection)`.
 *
 * libtorrent stores the whole tree as a flat array of 8-byte tokens that index back
 * into the buffer (a neat allocation-free trick). This port uses an ordinary object
 * tree with the same public API; the flat-token representation is a future
 * optimization that would not change a single call site.
 */
class BdecodeNode internal constructor(
    val type: Type,
    private val buffer: ByteArray,
    private val nodeStart: Int,
    private val nodeEnd: Int,
    private val intVal: Long = 0L,
    private val strStart: Int = 0,
    private val strLen: Int = 0,
    private val items: List<BdecodeNode>? = null,
    private val entries: List<DictEntry>? = null,
) {
    /** A key→value pair inside a dictionary node. Keys are raw bytes (usually ASCII). */
    class DictEntry(val key: ByteArray, val value: BdecodeNode)

    enum class Type { NONE, DICT, LIST, STRING, INT }

    /** True if this is a real node (not the [none] sentinel / a failed lookup). */
    val isValid: Boolean get() = type != Type.NONE

    /** Byte offset of this node in the originally parsed buffer. */
    fun dataOffset(): Int = nodeStart

    /**
     * The exact bytes this node was parsed from, including the framing characters
     * (`d…e`, `l…e`, `i…e`, `<len>:<bytes>`). For the `info` dictionary this is the
     * preimage of the info-hash.
     */
    fun dataSection(): ByteArray = buffer.copyOfRange(nodeStart, nodeEnd)

    // --- integers --------------------------------------------------------------

    fun intValue(): Long {
        check(type == Type.INT) { "node is not an int: $type" }
        return intVal
    }

    // --- strings ---------------------------------------------------------------

    /** Raw string bytes (not NUL-terminated, may be binary). */
    fun stringBytes(): ByteArray {
        check(type == Type.STRING) { "node is not a string: $type" }
        return buffer.copyOfRange(strStart, strStart + strLen)
    }

    /** String value decoded as UTF-8 — for text fields. */
    fun stringValue(): String {
        check(type == Type.STRING) { "node is not a string: $type" }
        return buffer.decodeToString(strStart, strStart + strLen)
    }

    fun stringLength(): Int {
        check(type == Type.STRING) { "node is not a string: $type" }
        return strLen
    }

    /** Byte offset of the string *content* (after the `<len>:`) in the buffer. */
    fun stringOffset(): Int {
        check(type == Type.STRING) { "node is not a string: $type" }
        return strStart
    }

    // --- lists -----------------------------------------------------------------

    fun listSize(): Int {
        check(type == Type.LIST) { "node is not a list: $type" }
        return items!!.size
    }

    fun listAt(i: Int): BdecodeNode {
        check(type == Type.LIST) { "node is not a list: $type" }
        return items!![i]
    }

    fun listStringValueAt(i: Int, default: String = ""): String {
        if (type != Type.LIST || i < 0 || i >= items!!.size) return default
        val n = items[i]
        return if (n.type == Type.STRING) n.stringValue() else default
    }

    fun listIntValueAt(i: Int, default: Long = 0L): Long {
        if (type != Type.LIST || i < 0 || i >= items!!.size) return default
        val n = items[i]
        return if (n.type == Type.INT) n.intVal else default
    }

    // --- dictionaries ----------------------------------------------------------

    fun dictSize(): Int {
        check(type == Type.DICT) { "node is not a dict: $type" }
        return entries!!.size
    }

    /** The i-th (key, value) pair, in parse order. */
    fun dictAt(i: Int): Pair<ByteArray, BdecodeNode> {
        check(type == Type.DICT) { "node is not a dict: $type" }
        val e = entries!![i]
        return e.key to e.value
    }

    /** Look up [key]; returns the [none] sentinel if absent or this isn't a dict. */
    fun dictFind(key: String): BdecodeNode = dictFind(key.encodeToByteArray())

    fun dictFind(key: ByteArray): BdecodeNode {
        if (type != Type.DICT) return none
        for (e in entries!!) if (e.key.contentEquals(key)) return e.value
        return none
    }

    fun dictFindDict(key: String): BdecodeNode = dictFind(key).takeIf { it.type == Type.DICT } ?: none
    fun dictFindList(key: String): BdecodeNode = dictFind(key).takeIf { it.type == Type.LIST } ?: none
    fun dictFindString(key: String): BdecodeNode = dictFind(key).takeIf { it.type == Type.STRING } ?: none
    fun dictFindInt(key: String): BdecodeNode = dictFind(key).takeIf { it.type == Type.INT } ?: none

    fun dictFindStringValue(key: String, default: String = ""): String {
        val n = dictFind(key)
        return if (n.type == Type.STRING) n.stringValue() else default
    }

    fun dictFindStringBytes(key: String): ByteArray? {
        val n = dictFind(key)
        return if (n.type == Type.STRING) n.stringBytes() else null
    }

    fun dictFindIntValue(key: String, default: Long = 0L): Long {
        val n = dictFind(key)
        return if (n.type == Type.INT) n.intVal else default
    }

    override fun toString(): String = when (type) {
        Type.NONE -> "none"
        Type.INT -> "int($intVal)"
        Type.STRING -> "string(${stringValue()})"
        Type.LIST -> "list[${items!!.size}]"
        Type.DICT -> "dict{${entries!!.size}}"
    }

    companion object {
        /** The sentinel returned for "not found" / default-constructed, type == NONE. */
        val none = BdecodeNode(Type.NONE, ByteArray(0), 0, 0)
    }
}
