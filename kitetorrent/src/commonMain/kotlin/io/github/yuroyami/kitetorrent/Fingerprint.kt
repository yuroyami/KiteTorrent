package io.github.yuroyami.kitetorrent

/**
 * Client fingerprint generation. This is the pure-Kotlin port of libtorrent's
 * `generate_fingerprint` (fingerprint.hpp / src/fingerprint.cpp).
 *
 * A fingerprint is the 8-character prefix BitTorrent clients stamp on the front
 * of their 20-byte peer-id so that peers can recognise each other's software and
 * version. libtorrent emits the "Azureus-style" form
 *
 *     "-XXmmtt-"      e.g. "-LT2000-"  (libtorrent 2.0, revision 0, tag 0)
 *
 * where
 *  - char 0       : always `'-'`
 *  - chars 1..2   : the two-character client tag (`"LT"` for libtorrent itself)
 *  - chars 3..6   : the major, minor, revision and tag version digits, each
 *                   encoded by [versionToChar]
 *  - char 7       : always `'-'`
 *
 * There is an informal registry of client tags at
 * http://wiki.theory.org/BitTorrentSpecification#peer_id . A few from the table
 * in fingerprint.hpp: `LT` libtorrent, `UT` uTorrent, `qB` qBittorrent,
 * `AZ` Azureus, `DE` Deluge, `TL` Tribler.
 *
 * KiteTorrent's own tag is `"KT"` ([DEFAULT_CLIENT_TAG]).
 */

/** KiteTorrent's two-character client tag, stamped into generated peer-ids. */
const val DEFAULT_CLIENT_TAG: String = "KT"

/**
 * Encode a single version component as one fingerprint character. This is the port
 * of the anonymous `version_to_char(int v)` in src/fingerprint.cpp.
 *
 * libtorrent maps `0..9` to the digits `'0'..'9'` and any value `>= 10` to the
 * letters `'A','B','C',...` (i.e. 10 -> 'A', 35 -> 'Z'). This widens the per-slot
 * range past a single decimal digit, base-36 style. Negative values are a
 * precondition violation upstream (`TORRENT_ASSERT_FAIL`); here they throw.
 *
 * Note libtorrent does not bound the upper end, so `v == 36` would roll past
 * `'Z'` into `'['` and beyond. We reproduce that behaviour rather than clamping,
 * but the version slots are small in practice.
 */
fun versionToChar(v: Int): Char {
    require(v >= 0) { "fingerprint version component must be >= 0, was $v" }
    return if (v < 10) ('0' + v) else ('A' + (v - 10))
}

/**
 * Build an 8-character client fingerprint string in libtorrent's Azureus style,
 * porting `generate_fingerprint(std::string name, int major, int minor,
 * int revision, int tag)`.
 *
 * @param clientTag the two-character client identifier (e.g. `"KT"`, `"LT"`).
 *        libtorrent requires exactly two characters; if fewer than two are given
 *        it substitutes `"--"` (matching `if (name.size() < 2) name = "--"`).
 *        More than two characters is a precondition violation upstream. We require
 *        exactly two (after the short-string fallback) to avoid silently truncating.
 * @param major    major version, encoded by [versionToChar] into slot 3.
 * @param minor    minor version, encoded into slot 4. Defaults to 0.
 * @param tiny     revision version (libtorrent's `revision`), slot 5. Defaults to 0.
 * @param tag      build/tag version, slot 6. Defaults to 0.
 * @return the `"-XXmmtt-"` style 8-character prefix.
 */
fun generateFingerprint(
    clientTag: String,
    major: Int,
    minor: Int = 0,
    tiny: Int = 0,
    tag: Int = 0,
): String {
    require(major >= 0) { "major must be >= 0, was $major" }
    require(minor >= 0) { "minor must be >= 0, was $minor" }
    require(tiny >= 0) { "revision (tiny) must be >= 0, was $tiny" }
    require(tag >= 0) { "tag must be >= 0, was $tag" }

    // libtorrent: `if (name.size() < 2) name = "--";`
    val name = if (clientTag.length < 2) "--" else clientTag
    require(name.length == 2) {
        "client tag must be exactly two characters, was \"$clientTag\" (${clientTag.length} chars)"
    }

    val ret = StringBuilder(8)
    ret.append('-')                 // ret[0]
    ret.append(name[0])             // ret[1]
    ret.append(name[1])             // ret[2]
    ret.append(versionToChar(major))    // ret[3]
    ret.append(versionToChar(minor))    // ret[4]
    ret.append(versionToChar(tiny))     // ret[5]
    ret.append(versionToChar(tag))      // ret[6]
    ret.append('-')                 // ret[7]
    return ret.toString()
}

/**
 * KiteTorrent's default fingerprint, `"-KT0010-"`: tag `"KT"` at version
 * [Version.MAJOR].[Version.MINOR].[Version.TINY] (0.0.1) with tag 0.
 *
 * Kept as a single source of truth so peer-id generation and any settings layer
 * agree on the prefix.
 */
fun defaultFingerprint(): String =
    generateFingerprint(DEFAULT_CLIENT_TAG, Version.MAJOR, Version.MINOR, Version.TINY)
