package io.github.yuroyami.kitetorrent

import kotlin.random.Random

/**
 * A 20-byte BitTorrent peer id — the pure-Kotlin port of libtorrent's
 * `using peer_id = sha1_hash;` (peer_id.hpp). Structurally it is just a
 * 20-byte digest, so it aliases [Sha1Hash] exactly as upstream aliases
 * `sha1_hash`.
 *
 * A peer id is constructed (not hashed) — the leading bytes carry the client
 * [fingerprint][generateFingerprint] (e.g. `"-KT0010-"`) and the remaining bytes
 * are random, so each session announces a stable client signature plus a unique
 * per-session tail.
 */
typealias PeerId = Sha1Hash

/** The length of a peer id in bytes (== SHA-1 width). */
const val PEER_ID_SIZE: Int = Digest32.SHA1_SIZE

/**
 * The URL-safe printable character set libtorrent uses to fill the random tail
 * of a peer id — the port of the `printable[]` table in `url_random`
 * (src/string_util.cpp).
 *
 * These are "http-accepted characters, excluding `'` since some buggy trackers
 * don't support that". Using printable characters (rather than arbitrary 0..255
 * bytes) keeps the whole peer id safe to embed in the percent-encoded `info_hash`
 * / `peer_id` query parameters of an HTTP tracker announce.
 *
 * Note the C++ code draws from `sizeof(printable) - 2` entries, i.e. it excludes
 * the trailing NUL terminator *and* the final character (`')'`). We reproduce
 * that off-by-one faithfully via [URL_RANDOM_ALPHABET], whose length already
 * equals that draw count.
 */
private const val URL_RANDOM_ALPHABET: String =
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_.!~*("

/**
 * Fill [dest] (a window into a peer-id buffer) with URL-safe random characters,
 * porting `url_random(span<char> dest)` from src/string_util.cpp.
 *
 * Each byte is an independently chosen character from [URL_RANDOM_ALPHABET].
 */
private fun urlRandom(dest: ByteArray, fromIndex: Int, random: Random) {
    val alphabet = URL_RANDOM_ALPHABET
    for (i in fromIndex until dest.size) {
        dest[i] = alphabet[random.nextInt(alphabet.length)].code.toByte()
    }
}

/**
 * Generate a 20-byte peer id whose leading bytes are the client [fingerprint]
 * and whose remaining bytes are URL-safe random characters — the port of
 * `aux::generate_peer_id(session_settings const&)`
 * (src/generate_peer_id.cpp).
 *
 * libtorrent's logic, reproduced here:
 *  1. take the configured fingerprint string (`settings_pack::peer_fingerprint`);
 *  2. if it is longer than 20 bytes, truncate it to 20;
 *  3. copy it into the front of the id;
 *  4. fill whatever remains with `url_random`.
 *
 * The fingerprint is encoded as ASCII (it is `'-'`, two tag chars and four
 * base-36 version chars — all single-byte). Each fingerprint character occupies
 * exactly one id byte.
 *
 * @param fingerprint the client prefix, defaulting to KiteTorrent's
 *        [defaultFingerprint] (`"-KT0010-"`). Strings longer than [PEER_ID_SIZE]
 *        are truncated to 20 bytes, matching `print.resize(ret.size())`.
 * @param random the [Random] source for the tail. Defaults to [Random.Default];
 *        pass a seeded `Random` for reproducible ids in tests.
 */
fun generatePeerId(
    fingerprint: String = defaultFingerprint(),
    random: Random = Random.Default,
): PeerId {
    val out = ByteArray(PEER_ID_SIZE)

    // libtorrent: if (print.size() > ret.size()) print.resize(ret.size());
    val printBytes = fingerprint.encodeToByteArray()
    val printLen = if (printBytes.size > PEER_ID_SIZE) PEER_ID_SIZE else printBytes.size

    // copy the fingerprint into the front of the id
    printBytes.copyInto(out, destinationOffset = 0, startIndex = 0, endIndex = printLen)

    // fill the rest with URL-safe random bytes (the no-op case printLen == 20 is fine)
    urlRandom(out, printLen, random)

    return Digest32.sha1(out)
}

/**
 * Deterministic peer-id construction: place the [fingerprint] at the front and
 * use the caller-supplied [randomTail] for the remaining bytes verbatim. This is
 * the testable counterpart of [generatePeerId] — no RNG is consulted.
 *
 * The [randomTail] must be exactly the number of bytes left after the (possibly
 * truncated) fingerprint, i.e. `20 - min(fingerprint.length, 20)`, so that the
 * fingerprint and tail together fill all 20 bytes. Unlike [generatePeerId] the
 * tail bytes are used as-is (they are not constrained to the URL-safe alphabet),
 * which lets tests pin an exact 20-byte value.
 *
 * @throws IllegalArgumentException if [randomTail] is not the required length.
 */
fun generatePeerId(fingerprint: String, randomTail: ByteArray): PeerId {
    val out = ByteArray(PEER_ID_SIZE)

    val printBytes = fingerprint.encodeToByteArray()
    val printLen = if (printBytes.size > PEER_ID_SIZE) PEER_ID_SIZE else printBytes.size
    val expectedTail = PEER_ID_SIZE - printLen
    require(randomTail.size == expectedTail) {
        "randomTail must be $expectedTail bytes for a ${printLen}-byte fingerprint, was ${randomTail.size}"
    }

    printBytes.copyInto(out, destinationOffset = 0, startIndex = 0, endIndex = printLen)
    randomTail.copyInto(out, destinationOffset = printLen, startIndex = 0, endIndex = expectedTail)

    return Digest32.sha1(out)
}
