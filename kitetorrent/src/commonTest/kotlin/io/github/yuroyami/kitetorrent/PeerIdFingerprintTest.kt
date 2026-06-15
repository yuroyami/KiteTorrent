package io.github.yuroyami.kitetorrent

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [generateFingerprint] / [generatePeerId], the port of libtorrent's
 * fingerprint.hpp + generate_peer_id.cpp peer-id construction.
 *
 * Golden values are derived by hand from the C++ logic:
 *  - `version_to_char`: 0..9 -> '0'..'9', 10+ -> 'A'.. (src/fingerprint.cpp)
 *  - the `"-XXmmtt-"` byte layout (src/fingerprint.cpp `generate_fingerprint`)
 *  - fingerprint at front, random tail behind (src/generate_peer_id.cpp)
 */
class PeerIdFingerprintTest {

    // --- generate_fingerprint --------------------------------------------------

    @Test
    fun canonicalLibtorrentFingerprint() {
        // libtorrent 2.0, revision 0, tag 0 -> the well-known "-LT2000-" prefix.
        assertEquals("-LT2000-", generateFingerprint("LT", 2, 0, 0, 0))
    }

    @Test
    fun kiteTorrentDefaultFingerprint() {
        // tag "KT", version 0.0.1, tag 0.
        assertEquals("-KT0010-", generateFingerprint("KT", 0, 0, 1))
        assertEquals("-KT0010-", defaultFingerprint())
    }

    @Test
    fun fingerprintIsAlwaysEightChars() {
        val fp = generateFingerprint("KT", 0, 0, 1)
        assertEquals(8, fp.length)
        assertEquals('-', fp[0])
        assertEquals('-', fp[7])
        assertEquals('K', fp[1])
        assertEquals('T', fp[2])
    }

    @Test
    fun versionTenAndAboveUseLetters() {
        // version_to_char: 10 -> 'A', 35 -> 'Z'
        assertEquals('A', versionToChar(10))
        assertEquals('Z', versionToChar(35))
        assertEquals('9', versionToChar(9))
        assertEquals('0', versionToChar(0))
        assertEquals("-AZA000-", generateFingerprint("AZ", 10, 0, 0, 0))
    }

    @Test
    fun shortTagFallsBackToDashDash() {
        // libtorrent: if (name.size() < 2) name = "--";
        assertEquals("---0000-", generateFingerprint("X", 0))
    }

    @Test
    fun negativeVersionRejected() {
        assertFailsWith<IllegalArgumentException> { generateFingerprint("KT", -1) }
    }

    // --- generate_peer_id ------------------------------------------------------

    @Test
    fun peerIdIsTwentyBytes() {
        val id = generatePeerId()
        assertEquals(20, id.size)
        assertEquals(PEER_ID_SIZE, id.size)
    }

    @Test
    fun peerIdStartsWithFingerprintBytes() {
        val fp = "-KT0010-"
        val id = generatePeerId(fingerprint = fp, random = Random(12345))
        val bytes = id.toByteArray()
        val fpBytes = fp.encodeToByteArray()
        for (i in fpBytes.indices) {
            assertEquals(
                fpBytes[i], bytes[i],
                "peer-id byte $i should equal fingerprint char '${fp[i]}'",
            )
        }
    }

    @Test
    fun peerIdTailIsUrlSafePrintable() {
        // After the 8-char fingerprint, the remaining 12 bytes must all be drawn
        // from the URL-safe printable alphabet libtorrent's url_random uses.
        val urlSafe = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_.!~*()"
        val id = generatePeerId(fingerprint = "-KT0010-", random = Random(99))
        val bytes = id.toByteArray()
        for (i in 8 until 20) {
            val c = bytes[i].toInt().toChar()
            assertTrue(c in urlSafe, "tail byte $i = '$c' (0x${bytes[i].toInt() and 0xff}) is not URL-safe")
        }
    }

    @Test
    fun deterministicOverloadProducesExactBytes() {
        // 8-char fingerprint -> 12-byte tail.
        val tail = ByteArray(12) { (it + 1).toByte() } // 01,02,...,0c
        val id = generatePeerId("-KT0010-", tail)
        val bytes = id.toByteArray()

        // front 8 bytes == fingerprint ASCII
        assertEquals("-KT0010-", bytes.copyOfRange(0, 8).decodeToString())
        // tail 12 bytes == supplied
        assertTrue(tail.contentEquals(bytes.copyOfRange(8, 20)))
        // full hex pin: 2d4b54303031302d + 0102030405060708090a0b0c
        assertEquals("2d4b54303031302d0102030405060708090a0b0c", id.toHex())
    }

    @Test
    fun deterministicOverloadRejectsWrongTailLength() {
        assertFailsWith<IllegalArgumentException> {
            generatePeerId("-KT0010-", ByteArray(11)) // needs 12
        }
    }

    @Test
    fun seededRandomIsReproducible() {
        val a = generatePeerId(random = Random(2026))
        val b = generatePeerId(random = Random(2026))
        assertEquals(a.toHex(), b.toHex())
    }

    @Test
    fun longFingerprintIsTruncatedToTwentyBytes() {
        // A 25-char "fingerprint" -> truncated to 20 bytes, leaving a zero-length tail.
        val long = "ABCDEFGHIJKLMNOPQRSTUVWXY" // 25 chars
        val id = generatePeerId(fingerprint = long, random = Random(1))
        assertEquals(20, id.size)
        // First 20 chars of the string land verbatim in the id.
        assertEquals("ABCDEFGHIJKLMNOPQRST", id.toByteArray().decodeToString())
    }
}
