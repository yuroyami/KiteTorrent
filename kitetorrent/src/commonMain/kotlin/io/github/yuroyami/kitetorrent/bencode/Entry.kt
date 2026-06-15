package io.github.yuroyami.kitetorrent.bencode

/**
 * One node in a bencoded hierarchy — the owning variant type, port of libtorrent's
 * `entry` (entry.hpp). Use this to *build* structures (a torrent file, a DHT
 * message, resume data) and hand them to [Bencode.encode].
 *
 * A bencode string is a raw byte buffer, not text (BEP 3) — the `pieces` field of a
 * torrent, for example, is a run of concatenated 20-byte SHA-1 hashes. So [Str]
 * holds a [ByteArray]; the [text]/[str] helpers exist for the common case of UTF-8
 * keys and values.
 *
 * Dictionary keys are byte strings too, but in practice always ASCII. [Dict] keeps
 * them as [String]; [Bencode.encode] sorts them by raw byte order (required by the
 * spec — and required for info-hashes to match other clients).
 */
sealed class Entry {

    /** A 64-bit integer (`i…e`). */
    data class Int(val value: Long) : Entry()

    /** A byte string (`<len>:<bytes>`). */
    class Str(val bytes: ByteArray) : Entry() {
        /** The bytes decoded as UTF-8 — for the text fields (announce URLs, names…). */
        val text: String get() = bytes.decodeToString()
        override fun equals(other: Any?) = other is Str && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
        override fun toString() = "Str(${bytes.decodeToString()})"
    }

    /** An ordered list (`l…e`). */
    class ListEntry(val items: MutableList<Entry> = mutableListOf()) : Entry() {
        override fun equals(other: Any?) = other is ListEntry && items == other.items
        override fun hashCode() = items.hashCode()
        override fun toString() = "ListEntry($items)"
    }

    /** A dictionary (`d…e`). Iteration/encoding order is canonical (keys sorted by bytes). */
    class Dict(val map: MutableMap<String, Entry> = LinkedHashMap()) : Entry() {
        override fun equals(other: Any?) = other is Dict && map == other.map
        override fun hashCode() = map.hashCode()
        override fun toString() = "Dict($map)"
    }

    /** Raw, already-bencoded bytes spliced in verbatim (libtorrent's `preformatted_type`). */
    class Preformatted(val bytes: ByteArray) : Entry() {
        override fun equals(other: Any?) = other is Preformatted && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    /** Uninitialized — encodes as an empty string `0:`, matching libtorrent. */
    object Undefined : Entry()

    // --- typed accessors; throw if the entry is the wrong type (like libtorrent) ----

    fun integer(): Long = (this as? Int)?.value
        ?: throw IllegalStateException("entry is not an integer: ${typeName()}")

    fun stringBytes(): ByteArray = (this as? Str)?.bytes
        ?: throw IllegalStateException("entry is not a string: ${typeName()}")

    fun string(): String = (this as? Str)?.text
        ?: throw IllegalStateException("entry is not a string: ${typeName()}")

    fun list(): MutableList<Entry> = (this as? ListEntry)?.items
        ?: throw IllegalStateException("entry is not a list: ${typeName()}")

    fun dict(): MutableMap<String, Entry> = (this as? Dict)?.map
        ?: throw IllegalStateException("entry is not a dict: ${typeName()}")

    /** Dictionary lookup; returns null if this isn't a dict or the key is absent. */
    fun findKey(key: String): Entry? = (this as? Dict)?.map?.get(key)

    /** Dictionary index/insert. Throws if this entry is not a dict. */
    operator fun get(key: String): Entry =
        dict()[key] ?: throw IllegalStateException("key not found: $key")

    operator fun set(key: String, value: Entry) { dict()[key] = value }

    private fun typeName(): String = when (this) {
        is Int -> "int"; is Str -> "string"; is ListEntry -> "list"
        is Dict -> "dict"; is Preformatted -> "preformatted"; Undefined -> "undefined"
    }

    companion object {
        /** Build a string entry from UTF-8 text. */
        fun of(text: String): Entry = Str(text.encodeToByteArray())

        /** Build a string entry from raw bytes. */
        fun of(bytes: ByteArray): Entry = Str(bytes)

        /** Build an integer entry. */
        fun of(value: Long): Entry = Int(value)

        /** Build an empty dictionary. */
        fun dict(): Dict = Dict()

        /** Build an empty list. */
        fun list(): ListEntry = ListEntry()

        /** Convert a parsed [BdecodeNode] tree into an owning, editable [Entry]. */
        fun from(node: BdecodeNode): Entry = when (node.type) {
            BdecodeNode.Type.INT -> Int(node.intValue())
            BdecodeNode.Type.STRING -> Str(node.stringBytes())
            BdecodeNode.Type.LIST -> ListEntry(MutableList(node.listSize()) { from(node.listAt(it)) })
            BdecodeNode.Type.DICT -> Dict(LinkedHashMap<String, Entry>().apply {
                for (i in 0 until node.dictSize()) {
                    val (k, v) = node.dictAt(i)
                    put(k.decodeToString(), from(v))
                }
            })
            BdecodeNode.Type.NONE -> Undefined
        }
    }
}
