package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.BdecodeError
import io.github.yuroyami.kitetorrent.bencode.BdecodeException
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.crypto.Hasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BencodeTest {

    private fun ascii(s: String) = ByteArray(s.length) { s[it].code.toByte() }
    private fun str(b: ByteArray) = b.decodeToString()

    @Test
    fun decode_canonical_example() {
        // The exact example from libtorrent's bdecode.hpp header diagram.
        val buf = ascii("d1:ad1:bi1e1:c4:abcde1:di3ee")
        val root = Bdecode.decode(buf)
        assertEquals(BdecodeNode.Type.DICT, root.type)
        assertEquals(2, root.dictSize())
        assertEquals(3L, root.dictFindIntValue("d"))
        val a = root.dictFindDict("a")
        assertEquals(1L, a.dictFindIntValue("b"))
        assertEquals("abcd", a.dictFindStringValue("c"))
    }

    @Test
    fun data_section_is_exact_bytes() {
        val buf = ascii("d1:ad1:bi1e1:c4:abcde1:di3ee")
        val root = Bdecode.decode(buf)
        // the value at key "a" is the inner dict "d1:bi1e1:c4:abcde"
        assertEquals("d1:bi1e1:c4:abcde", str(root.dictFind("a").dataSection()))
        assertEquals("i3e", str(root.dictFind("d").dataSection()))
        assertEquals(buf.size, root.dataSection().size)
    }

    @Test
    fun encode_roundtrip() {
        assertEquals("i42e", str(Bencode.encode(Entry.of(42L))))
        assertEquals("i-7e", str(Bencode.encode(Entry.of(-7L))))
        assertEquals("4:spam", str(Bencode.encode(Entry.of("spam"))))
        assertEquals("0:", str(Bencode.encode(Entry.of(""))))
        val list = Entry.list().apply { items.add(Entry.of("a")); items.add(Entry.of(1L)) }
        assertEquals("l1:ai1ee", str(Bencode.encode(list)))
    }

    @Test
    fun encode_sorts_dict_keys_canonically() {
        // insert keys out of order; encoder must emit them sorted by byte value
        val d = Entry.dict()
        d["zebra"] = Entry.of(1L)
        d["apple"] = Entry.of(2L)
        d["mango"] = Entry.of(3L)
        assertEquals("d5:applei2e5:mangoi3e5:zebrai1ee", str(Bencode.encode(d)))
    }

    @Test
    fun binary_strings_survive_roundtrip() {
        // bytes that are NOT valid UTF-8 — like a real `pieces` field
        val raw = ByteArray(256) { it.toByte() }
        val encoded = Bencode.encode(Entry.of(raw))
        val node = Bdecode.decode(encoded)
        assertTrue(raw.contentEquals(node.stringBytes()))
    }

    @Test
    fun info_hash_pipeline_matches() {
        // Build a torrent the way create_torrent does, then verify that hashing the
        // standalone-encoded info dict equals hashing the info node's dataSection
        // pulled back out of the full decoded torrent. This is the real info-hash path.
        val info = Entry.dict()
        info["name"] = Entry.of("test.bin")
        info["piece length"] = Entry.of(16384L)
        info["length"] = Entry.of(1234L)
        info["pieces"] = Entry.of(ByteArray(20) { (it * 9 + 1).toByte() })

        val infoHashStandalone = Hasher.hash(Bencode.encode(info))

        val torrent = Entry.dict()
        torrent["announce"] = Entry.of("http://tracker.example/announce")
        torrent["info"] = info
        torrent["creation date"] = Entry.of(1_700_000_000L)

        val decoded = Bdecode.decode(Bencode.encode(torrent))
        val infoNode = decoded.dictFind("info")
        val infoHashFromFile = Hasher.hash(infoNode.dataSection())

        assertEquals(infoHashStandalone, infoHashFromFile)
        assertEquals(40, infoHashFromFile.toHex().length)
    }

    @Test
    fun entry_from_node_roundtrips_to_same_bytes() {
        val original = ascii("d4:listli1ei2ei3ee3:numi99e3:str5:helloe")
        val node = Bdecode.decode(original)
        val rebuilt = Bencode.encode(Entry.from(node))
        assertTrue(original.contentEquals(rebuilt))
    }

    @Test
    fun malformed_inputs_report_errors() {
        // truncated integer (no terminating 'e')
        assertEquals(BdecodeError.UNEXPECTED_EOF,
            assertFailsWith<BdecodeException> { Bdecode.decode(ascii("i123")) }.error)
        // empty integer
        assertEquals(BdecodeError.EXPECTED_DIGIT,
            assertFailsWith<BdecodeException> { Bdecode.decode(ascii("ie")) }.error)
        // string longer than the buffer
        assertEquals(BdecodeError.UNEXPECTED_EOF,
            assertFailsWith<BdecodeException> { Bdecode.decode(ascii("10:abc")) }.error)
        // dict with a non-string key
        assertEquals(BdecodeError.EXPECTED_DIGIT,
            assertFailsWith<BdecodeException> { Bdecode.decode(ascii("di1ei2ee")) }.error)
        // garbage
        assertNull(Bdecode.decodeOrNull(ascii("xyz")))
    }

    @Test
    fun integer_overflow_detected() {
        assertEquals(BdecodeError.OVERFLOW,
            assertFailsWith<BdecodeException> { Bdecode.decode(ascii("i99999999999999999999e")) }.error)
    }

    @Test
    fun depth_limit_enforced() {
        // 50 nested lists with a limit of 10 must fail
        val nested = "l".repeat(50) + "e".repeat(50)
        assertEquals(BdecodeError.DEPTH_EXCEEDED,
            assertFailsWith<BdecodeException> { Bdecode.decode(ascii(nested), depthLimit = 10) }.error)
    }
}
