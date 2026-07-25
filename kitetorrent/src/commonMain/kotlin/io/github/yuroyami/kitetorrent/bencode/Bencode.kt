package io.github.yuroyami.kitetorrent.bencode

import io.github.yuroyami.kitetorrent.io.ByteArrayBuilder

/**
 * Bencode *encoder*, the port of libtorrent's `bencode()` and `bencode_recursive`
 * (bencode.hpp). Turns an [Entry] tree into canonical bencoded bytes.
 *
 * Dictionary keys are emitted in ascending raw-byte order. This is mandatory: the
 * info-hash of a torrent is the SHA-1/SHA-256 of its bencoded `info` dictionary, so
 * any client that sorts keys differently would compute a different hash and fail to
 * find peers. libtorrent gets canonical order for free because its `entry` dict is a
 * `std::map`; we sort explicitly here.
 */
object Bencode {

    /** Encode [e] to a fresh byte array. */
    fun encode(e: Entry): ByteArray {
        val out = ByteArrayBuilder(256)
        encodeInto(out, e)
        return out.toByteArray()
    }

    /** Encode [e], appending to an existing [out] buffer. */
    fun encodeInto(out: ByteArrayBuilder, e: Entry) {
        when (e) {
            is Entry.Int -> {
                out.append('i'.code.toByte())
                out.appendAscii(e.value.toString())
                out.append('e'.code.toByte())
            }
            is Entry.Str -> {
                out.appendAscii(e.bytes.size.toString())
                out.append(':'.code.toByte())
                out.append(e.bytes)
            }
            is Entry.ListEntry -> {
                out.append('l'.code.toByte())
                for (item in e.items) encodeInto(out, item)
                out.append('e'.code.toByte())
            }
            is Entry.Dict -> {
                out.append('d'.code.toByte())
                // Canonical order: sort keys by raw (UTF-8) byte value, unsigned.
                val keys = e.map.keys.toMutableList()
                keys.sortWith { a, b -> compareKeysByBytes(a, b) }
                for (k in keys) {
                    val kb = k.encodeToByteArray()
                    out.appendAscii(kb.size.toString())
                    out.append(':'.code.toByte())
                    out.append(kb)
                    encodeInto(out, e.map.getValue(k))
                }
                out.append('e'.code.toByte())
            }
            is Entry.Preformatted -> out.append(e.bytes)
            Entry.Undefined -> {
                // libtorrent writes an empty string for an undefined entry
                out.append('0'.code.toByte())
                out.append(':'.code.toByte())
            }
        }
    }

    private fun compareKeysByBytes(a: String, b: String): Int {
        val ab = a.encodeToByteArray()
        val bb = b.encodeToByteArray()
        val n = minOf(ab.size, bb.size)
        for (i in 0 until n) {
            val d = (ab[i].toInt() and 0xff) - (bb[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return ab.size - bb.size
    }
}
