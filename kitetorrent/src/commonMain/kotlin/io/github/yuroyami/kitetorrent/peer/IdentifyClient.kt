package io.github.yuroyami.kitetorrent.peer

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.util.StringUtil

/**
 * Peer-id → human client identification — the pure-Kotlin port of libtorrent's
 * `identify_client` machinery (src/identify_client.cpp / include/libtorrent/identify_client.hpp).
 *
 * BitTorrent clients stamp an identifying prefix on the front of their 20-byte
 * peer-id. There are a handful of competing conventions:
 *
 *  - **Azureus style** `"-XXmmtt-"` — a two-letter client code (`XX`) followed by
 *    four base-36-ish version digits, bracketed by `'-'`. The overwhelming majority
 *    of modern clients use this (`-UT2210-` = uTorrent 2.2.10, `-lt0D60-` = rTorrent,
 *    `-qB4500-` = qBittorrent, …). See the [NameMap] table.
 *  - **Shadow style** — a single leading letter then version characters; either
 *    base-36 digits up to a `"--"` separator at offset 4, or three raw bytes.
 *  - **Mainline style** `"M1-2-3--"` — the original BitTorrent client: a letter then
 *    dash-separated decimal version fields.
 *  - **Non-standard** special cases (XBT, Opera, BitComet's `exbc`, eXeem, Bits on
 *    Wheels, Experimental, …) handled before the structured parsers.
 *
 * The informal registry of two-letter codes lives at
 * http://wiki.theory.org/BitTorrentSpecification#peer_id .
 *
 * This is the *parsing* counterpart to KiteTorrent's
 * [io.github.yuroyami.kitetorrent.generateFingerprint] (the *generation* side).
 *
 * Public entry points:
 *  - [identifyClient] — libtorrent's `identify_client` / `identify_client_impl`,
 *    returning a display string such as `"uTorrent 2.2.10"` or `"Unknown [....]"`.
 *  - [parseClientInfo] — libtorrent's `client_fingerprint`, returning a structured
 *    [ClientInfo] for the three well-formed styles, or `null` otherwise.
 */

/**
 * A decoded peer-id fingerprint — the port of libtorrent's `fingerprint` struct as
 * produced by `parse_az_style` / `parse_shadow_style` / `parse_mainline_style`.
 *
 * @property name    the resolved human client name (e.g. `"uTorrent"`,
 *                    `"libtorrent"`). When the two-letter code is not in the table
 *                    this is the raw one- or two-letter code itself, matching
 *                    libtorrent's `lookup()` fallback.
 * @property code    the raw client code that was matched: two characters for
 *                   Azureus style (`"UT"`), one character for Shadow / Mainline
 *                   (`"M"`). Useful for callers that want the tag rather than the
 *                   prettified name.
 * @property style   which encoding the peer-id matched.
 * @property major   major version number.
 * @property minor   minor version number.
 * @property revision revision (libtorrent's `revision_version`).
 * @property tag     build/tag version (libtorrent's `tag_version`); `0` and not
 *                   rendered for Shadow / Mainline styles.
 */
data class ClientInfo(
    val name: String,
    val code: String,
    val style: ClientStyle,
    val major: Int,
    val minor: Int,
    val revision: Int,
    val tag: Int,
) {
    /**
     * The libtorrent `lookup()` display string: `"<name> <major>.<minor>.<revision>"`
     * with an optional `".<tag>"` suffix when [tag] is non-zero.
     */
    fun displayString(): String {
        val base = "$name $major.$minor.$revision"
        return if (tag != 0) "$base.$tag" else base
    }
}

/** Which peer-id encoding a [ClientInfo] was decoded from. */
enum class ClientStyle { AZUREUS, SHADOW, MAINLINE }

/**
 * Map a 20-byte peer-id to a human-readable client name and version — the port of
 * libtorrent's `identify_client` (`aux::identify_client_impl`).
 *
 * Resolution order is identical to libtorrent:
 *  1. all-zero peer-id → `"Unknown"`.
 *  2. the non-standard [GenericMappings] table (offset + literal-prefix matches).
 *  3. the bespoke special cases: Bits on Wheels, eXeem, the two Experimental
 *     builds.
 *  4. Azureus style, then Shadow style, then Mainline style — each rendered via the
 *     two-letter-code lookup table.
 *  5. first-12-bytes-zero → `"Generic"`.
 *  6. otherwise `"Unknown [....]"` with non-printable bytes shown as `'.'`.
 *
 * @param peerId the 20-byte peer-id ([Sha1Hash] / [io.github.yuroyami.kitetorrent.Digest32]).
 * @return a display string; never `null` (unrecognised ids fall through to
 *         `"Generic"` or `"Unknown [...]"`).
 */
fun identifyClient(peerId: Sha1Hash): String = IdentifyClient.identify(peerId)

/**
 * Structured peer-id fingerprint extraction — the port of libtorrent's
 * `client_fingerprint`. Recognises only the three well-formed styles (Azureus,
 * Shadow, Mainline) and returns `null` for everything else (including the
 * non-standard special cases that [identifyClient] still names).
 *
 * @param peerId the 20-byte peer-id.
 * @return a [ClientInfo] for a recognised style, or `null`.
 */
fun parseClientInfo(peerId: Sha1Hash): ClientInfo? = IdentifyClient.parse(peerId)

/**
 * Implementation namespace for peer-id identification. Mirrors the anonymous
 * namespace + `aux::identify_client_impl` in src/identify_client.cpp. Kept
 * `internal` so the lookup tables and helpers don't leak into the public API; the
 * public surface is the free functions [identifyClient] / [parseClientInfo].
 */
internal object IdentifyClient {

    // -------------------------------------------------------------------------
    // digit / version decoding
    // -------------------------------------------------------------------------

    /**
     * libtorrent `decode_digit`: a base-36-ish digit where `'0'..'9'` map to `0..9`
     * and everything else is treated as a letter, `c - 'A' + 10` (so `'A' -> 10`,
     * `'Z' -> 35`, lowercase and punctuation produce libtorrent's exact — if odd —
     * arithmetic results). [c] is an unsigned byte value (0..255).
     */
    private fun decodeDigit(c: Int): Int {
        // is_digit(char(c))
        if (c.toChar() in '0'..'9') return c - '0'.code
        return c - 'A'.code + 10
    }

    // -------------------------------------------------------------------------
    // two-letter-code lookup table (libtorrent `name_map`)
    // -------------------------------------------------------------------------

    /**
     * An entry in libtorrent's `name_map`: the one- or two-character client code and
     * its human name. Single-letter codes (`"M"`, `"A"`, …) are stored verbatim;
     * the trailing NUL libtorrent relies on is modelled by treating a missing second
     * character as code `0` in [lookupName].
     */
    private class MapEntry(val code: String, val name: String)

    // Must stay ordered exactly as libtorrent's `name_map` (alphabetically by the
    // C-string code, with NUL < any printable char so single-letter codes sort
    // before two-letter codes sharing the first letter — e.g. "A" before "AB").
    private val NameMap: Array<MapEntry> = arrayOf(
        MapEntry("7T", "aTorrent for android"),
        MapEntry("A", "ABC"),
        MapEntry("AB", "AnyEvent BitTorrent"),
        MapEntry("AG", "Ares"),
        MapEntry("AR", "Arctic Torrent"),
        MapEntry("AT", "Artemis"),
        MapEntry("AV", "Avicora"),
        MapEntry("AX", "BitPump"),
        MapEntry("AZ", "Azureus"),
        MapEntry("A~", "Ares"),
        MapEntry("BB", "BitBuddy"),
        MapEntry("BC", "BitComet"),
        MapEntry("BE", "baretorrent"),
        MapEntry("BF", "Bitflu"),
        MapEntry("BG", "BTG"),
        MapEntry("BI", "BiglyBT"),
        MapEntry("BL", "BitBlinder"),
        MapEntry("BP", "BitTorrent Pro"),
        MapEntry("BR", "BitRocket"),
        MapEntry("BS", "BTSlave"),
        MapEntry("BT", "BitTorrent"),
        MapEntry("BU", "BigUp"),
        MapEntry("BW", "BitWombat"),
        MapEntry("BX", "BittorrentX"),
        MapEntry("CD", "Enhanced CTorrent"),
        MapEntry("CT", "CTorrent"),
        MapEntry("DE", "Deluge"),
        MapEntry("DP", "Propagate Data Client"),
        MapEntry("EB", "EBit"),
        MapEntry("ES", "electric sheep"),
        MapEntry("FC", "FileCroc"),
        MapEntry("FT", "FoxTorrent"),
        MapEntry("FW", "FrostWire"),
        MapEntry("FX", "Freebox BitTorrent"),
        MapEntry("GS", "GSTorrent"),
        MapEntry("HK", "Hekate"),
        MapEntry("HL", "Halite"),
        MapEntry("HN", "Hydranode"),
        MapEntry("IL", "iLivid"),
        MapEntry("KC", "Koinonein"),
        MapEntry("KG", "KGet"),
        MapEntry("KT", "KTorrent"),
        MapEntry("LC", "LeechCraft"),
        MapEntry("LH", "LH-ABC"),
        MapEntry("LK", "Linkage"),
        MapEntry("LP", "lphant"),
        MapEntry("LR", "LibreTorrent"),
        MapEntry("LT", "libtorrent"),
        MapEntry("LW", "Limewire"),
        MapEntry("M", "Mainline"),
        MapEntry("ML", "MLDonkey"),
        MapEntry("MO", "Mono Torrent"),
        MapEntry("MP", "MooPolice"),
        MapEntry("MR", "Miro"),
        MapEntry("MT", "Moonlight Torrent"),
        MapEntry("NX", "Net Transport"),
        MapEntry("O", "Osprey Permaseed"),
        MapEntry("OS", "OneSwarm"),
        MapEntry("OT", "OmegaTorrent"),
        MapEntry("PD", "Pando"),
        MapEntry("Q", "BTQueue"),
        MapEntry("QD", "QQDownload"),
        MapEntry("QT", "Qt 4"),
        MapEntry("R", "Tribler"),
        MapEntry("RT", "Retriever"),
        MapEntry("RZ", "RezTorrent"),
        MapEntry("S", "Shadow"),
        MapEntry("SB", "Swiftbit"),
        MapEntry("SD", "Xunlei"),
        MapEntry("SK", "spark"),
        MapEntry("SN", "ShareNet"),
        MapEntry("SS", "SwarmScope"),
        MapEntry("ST", "SymTorrent"),
        MapEntry("SZ", "Shareaza"),
        MapEntry("S~", "Shareaza (beta)"),
        MapEntry("T", "BitTornado"),
        MapEntry("TB", "Torch"),
        MapEntry("TL", "Tribler"),
        MapEntry("TN", "Torrent.NET"),
        MapEntry("TR", "Transmission"),
        MapEntry("TS", "TorrentStorm"),
        MapEntry("TT", "TuoTu"),
        MapEntry("U", "UPnP"),
        MapEntry("UL", "uLeecher"),
        MapEntry("UM", "uTorrent Mac"),
        MapEntry("UT", "uTorrent"),
        MapEntry("VG", "Vagaa"),
        MapEntry("WT", "BitLet"),
        MapEntry("WY", "FireTorrent"),
        MapEntry("XF", "Xfplay"),
        MapEntry("XL", "Xunlei"),
        MapEntry("XS", "XSwifter"),
        MapEntry("XT", "XanTorrent"),
        MapEntry("XX", "Xtorrent"),
        MapEntry("ZO", "Zona"),
        MapEntry("ZT", "ZipTorrent"),
        MapEntry("lt", "rTorrent"),
        MapEntry("pX", "pHoeniX"),
        MapEntry("qB", "qBittorrent"),
        MapEntry("st", "SharkTorrent"),
    )

    /**
     * The two characters of a code, with a missing second character treated as NUL
     * (code 0) — exactly how libtorrent's C-strings behave for single-letter codes.
     */
    private fun codeChar(code: String, i: Int): Int = if (i < code.length) code[i].code else 0

    /**
     * libtorrent `compare_id`: lexicographic order on the first two code characters,
     * with NUL (single-letter codes) sorting before any printable character.
     * Returns negative / zero / positive like a comparator.
     */
    private fun compareId(a0: Int, a1: Int, b0: Int, b1: Int): Int {
        if (a0 != b0) return a0 - b0
        return a1 - b1
    }

    /**
     * Resolve a two-character client code to a name via binary search over
     * [NameMap] — the port of `lookup()`'s `std::lower_bound` + equality check.
     *
     * @param c0 first code character's value, @param c1 second (NUL/0 for
     *        single-letter codes). Returns the table name on an exact two-char
     *        match, otherwise the raw code text (one char if [c1] is 0, else two),
     *        matching libtorrent's fallback to "the one or two letter code".
     */
    private fun lookupName(c0: Int, c1: Int): String {
        // std::lower_bound: first entry whose code is NOT < (c0,c1).
        var lo = 0
        var hi = NameMap.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val e = NameMap[mid]
            if (compareId(codeChar(e.code, 0), codeChar(e.code, 1), c0, c1) < 0) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        if (lo < NameMap.size) {
            val e = NameMap[lo]
            // std::equal(f.name, f.name + 2, i->id): both code chars match.
            if (codeChar(e.code, 0) == c0 && codeChar(e.code, 1) == c1) return e.name
        }
        // Fallback: the raw one- or two-letter code.
        return if (c1 == 0) c0.toChar().toString() else "${c0.toChar()}${c1.toChar()}"
    }

    // -------------------------------------------------------------------------
    // non-standard mappings (libtorrent `generic_mappings`)
    // -------------------------------------------------------------------------

    /** An entry in libtorrent's `generic_mappings`: a prefix to match at a byte offset. */
    private class GenericEntry(val offset: Int, val id: String, val name: String)

    private val GenericMappings: Array<GenericEntry> = arrayOf(
        GenericEntry(0, "Deadman Walking-", "Deadman"),
        GenericEntry(5, "Azureus", "Azureus 2.0.3.2"),
        GenericEntry(0, "DansClient", "XanTorrent"),
        GenericEntry(4, "btfans", "SimpleBT"),
        GenericEntry(0, "PRC.P---", "Bittorrent Plus! II"),
        GenericEntry(0, "P87.P---", "Bittorrent Plus!"),
        GenericEntry(0, "S587Plus", "Bittorrent Plus!"),
        GenericEntry(0, "martini", "Martini Man"),
        GenericEntry(0, "Plus---", "Bittorrent Plus"),
        GenericEntry(0, "turbobt", "TurboBT"),
        GenericEntry(0, "a00---0", "Swarmy"),
        GenericEntry(0, "a02---0", "Swarmy"),
        GenericEntry(0, "T00---0", "Teeweety"),
        GenericEntry(0, "BTDWV-", "Deadman Walking"),
        GenericEntry(2, "BS", "BitSpirit"),
        GenericEntry(0, "-SP", "BitSpirit 3.6"),
        GenericEntry(0, "Pando-", "Pando"),
        GenericEntry(0, "LIME", "LimeWire"),
        GenericEntry(0, "btuga", "BTugaXP"),
        GenericEntry(0, "oernu", "BTugaXP"),
        GenericEntry(0, "Mbrst", "Burst!"),
        GenericEntry(0, "PEERAPP", "PeerApp"),
        GenericEntry(0, "Plus", "Plus!"),
        GenericEntry(0, "-Qt-", "Qt"),
        GenericEntry(0, "exbc", "BitComet"),
        GenericEntry(0, "DNA", "BitTorrent DNA"),
        GenericEntry(0, "-G3", "G3 Torrent"),
        GenericEntry(0, "-FG", "FlashGet"),
        GenericEntry(0, "-ML", "MLdonkey"),
        GenericEntry(0, "-MG", "Media Get"),
        GenericEntry(0, "XBT", "XBT"),
        GenericEntry(0, "OP", "Opera"),
        GenericEntry(2, "RS", "Rufus"),
        GenericEntry(0, "AZ2500BT", "BitTyrant"),
        GenericEntry(0, "btpd/", "BitTorrent Protocol Daemon"),
        GenericEntry(0, "TIX", "Tixati"),
        GenericEntry(0, "QVOD", "Qvod"),
    )

    /**
     * libtorrent `find_string`: true if the bytes of [pid] starting at [offset]
     * begin with the ASCII bytes of [search]. The C++ uses `std::equal(search,
     * search + strlen(search), id)` which reads `search.length` bytes from `id`
     * without a bounds check; the peer-id is always 20 bytes and every `search`
     * here fits, so a guarded compare is faithful and safe.
     */
    private fun findString(pid: IntArray, offset: Int, search: String): Boolean {
        if (offset < 0) return false
        if (offset + search.length > pid.size) return false
        for (i in search.indices) {
            if (pid[offset + i] != search[i].code) return false
        }
        return true
    }

    // -------------------------------------------------------------------------
    // structured parsers
    // -------------------------------------------------------------------------

    /**
     * libtorrent `parse_az_style`: match `"-XXmmtt-"`. Returns a [ClientInfo] with
     * [ClientStyle.AZUREUS] or `null`.
     */
    private fun parseAzStyle(pid: IntArray): ClientInfo? {
        // id[0] != '-' || !is_print(id[1]) || id[3..6] < '0' || id[7] != '-'
        if (pid[0] != '-'.code) return null
        if (!StringUtil.isPrint(pid[1].toChar())) return null
        if (pid[2] < '0'.code) return null
        if (pid[3] < '0'.code) return null
        if (pid[4] < '0'.code) return null
        if (pid[5] < '0'.code) return null
        if (pid[6] < '0'.code) return null
        if (pid[7] != '-'.code) return null

        val c0 = pid[1]
        val c1 = pid[2]
        return ClientInfo(
            name = lookupName(c0, c1),
            code = "${c0.toChar()}${c1.toChar()}",
            style = ClientStyle.AZUREUS,
            major = decodeDigit(pid[3]),
            minor = decodeDigit(pid[4]),
            revision = decodeDigit(pid[5]),
            tag = decodeDigit(pid[6]),
        )
    }

    /**
     * libtorrent `parse_shadow_style`: a leading alpha/digit then either base-36
     * version chars up to a `"--"` at offset 4, or three raw version bytes (with
     * `id[8] == 0` and bytes <= 127). Returns a [ClientInfo] with
     * [ClientStyle.SHADOW] or `null`.
     */
    private fun parseShadowStyle(pid: IntArray): ClientInfo? {
        val lead = pid[0].toChar()
        if (!StringUtil.isAlpha(lead) && !StringUtil.isDigit(lead)) return null

        val major: Int
        val minor: Int
        val revision: Int

        // std::equal(id.begin()+4, id.begin()+6, "--")
        if (pid[4] == '-'.code && pid[5] == '-'.code) {
            if (pid[1] < '0'.code) return null
            if (pid[2] < '0'.code) return null
            if (pid[3] < '0'.code) return null
            major = decodeDigit(pid[1])
            minor = decodeDigit(pid[2])
            revision = decodeDigit(pid[3])
        } else {
            // id[8] != 0 || id[1] > 127 || id[2] > 127 || id[3] > 127
            if (pid[8] != 0) return null
            if (pid[1] > 127) return null
            if (pid[2] > 127) return null
            if (pid[3] > 127) return null
            major = pid[1]
            minor = pid[2]
            revision = pid[3]
        }

        val c0 = pid[0]
        return ClientInfo(
            // ret.name[0] = id[0]; ret.name[1] = 0;  -> single-letter code lookup.
            name = lookupName(c0, 0),
            code = c0.toChar().toString(),
            style = ClientStyle.SHADOW,
            major = major,
            minor = minor,
            revision = revision,
            tag = 0,
        )
    }

    /**
     * libtorrent `parse_mainline_style`: emulate `sscanf("%1c%3d-%3d-%3d--", ...)`.
     * One printable char, then up to three decimal fields separated by single `-`,
     * followed by `--`. Returns a [ClientInfo] with [ClientStyle.MAINLINE] or `null`.
     */
    private fun parseMainlineStyle(pid: IntArray): ClientInfo? {
        // "%1c" — first char, must be printable (checked after the scan in C++).
        val nameChar = pid[0].toChar()
        if (!StringUtil.isPrint(nameChar)) return null

        // Walk the rest emulating sscanf's "%3d-%3d-%3d--".
        val sc = ScanCursor(pid, 1)
        val major = sc.scanInt() ?: return null
        if (!sc.expect('-')) return null
        val minor = sc.scanInt() ?: return null
        if (!sc.expect('-')) return null
        val revision = sc.scanInt() ?: return null
        if (!sc.expect('-')) return null
        if (!sc.expect('-')) return null

        return ClientInfo(
            name = lookupName(pid[0], 0),
            code = nameChar.toString(),
            style = ClientStyle.MAINLINE,
            major = major,
            minor = minor,
            revision = revision,
            tag = 0,
        )
    }

    /**
     * A minimal cursor over the peer-id bytes that reproduces the slice of `sscanf`
     * behaviour `parse_mainline_style` relies on: `%3d` (a width-3 signed decimal
     * field that must consume at least one digit) and literal-character matching.
     *
     * `sscanf`'s `%d` skips leading whitespace and accepts an optional sign; for the
     * mainline peer-ids in the wild only digits appear, but we honour the optional
     * leading `+`/`-` and the width-3 cap to stay faithful to the C runtime.
     */
    private class ScanCursor(private val pid: IntArray, var pos: Int) {

        /**
         * Parse a `%3d` field: optional sign, then 1..3 decimal digits, at most 3
         * input characters consumed total. Returns the value, or `null` if no digit
         * was found (a conversion failure, which makes `sscanf` stop and report
         * fewer assignments — i.e. a non-match for us).
         */
        fun scanInt(): Int? {
            var consumed = 0
            var negative = false
            // optional sign counts toward the field width
            if (pos < pid.size && consumed < 3 && (pid[pos] == '+'.code || pid[pos] == '-'.code)) {
                negative = pid[pos] == '-'.code
                pos++
                consumed++
            }
            var value = 0
            var sawDigit = false
            while (pos < pid.size && consumed < 3 && pid[pos].toChar() in '0'..'9') {
                value = value * 10 + (pid[pos] - '0'.code)
                pos++
                consumed++
                sawDigit = true
            }
            if (!sawDigit) return null
            return if (negative) -value else value
        }

        /** Match a single literal character, advancing on success. */
        fun expect(c: Char): Boolean {
            if (pos < pid.size && pid[pos] == c.code) {
                pos++
                return true
            }
            return false
        }
    }

    // -------------------------------------------------------------------------
    // public-facing implementation
    // -------------------------------------------------------------------------

    /**
     * The 20 peer-id bytes as unsigned 0..255 ints — convenient for the byte
     * comparisons the C++ does directly on `std::uint8_t`.
     */
    private fun bytesOf(peerId: Sha1Hash): IntArray {
        val raw = peerId.toByteArray()
        return IntArray(raw.size) { raw[it].toInt() and 0xff }
    }

    /** Port of `client_fingerprint`: az → shadow → mainline, else null. */
    fun parse(peerId: Sha1Hash): ClientInfo? {
        if (peerId.size != 20) return null
        val pid = bytesOf(peerId)
        parseAzStyle(pid)?.let { return it }
        parseShadowStyle(pid)?.let { return it }
        return parseMainlineStyle(pid)
    }

    /** Port of `aux::identify_client_impl`. */
    fun identify(peerId: Sha1Hash): String {
        // Guard: the C++ assumes exactly 20 bytes; mirror its indexing range.
        if (peerId.size != 20 || peerId.isAllZeros()) return "Unknown"
        val pid = bytesOf(peerId)

        // --- non-standard encodings ---
        for (e in GenericMappings) {
            if (findString(pid, e.offset, e.id)) return e.name
        }

        // Bits on Wheels: "-BOW" with id[7]=='-' -> "Bits on Wheels " + id[4..6]
        if (findString(pid, 0, "-BOW") && pid[7] == '-'.code) {
            val ver = buildString {
                append(pid[4].toChar())
                append(pid[5].toChar())
                append(pid[6].toChar())
            }
            return "Bits on Wheels $ver"
        }

        // eXeem: "eX" -> "eXeem ('" + id[2..13] + "')"
        if (findString(pid, 0, "eX")) {
            val user = buildString {
                for (i in 2 until 14) append(pid[i].toChar())
            }
            return "eXeem ('$user')"
        }

        // first 12 bytes all zero?
        var isEquZero = true
        for (i in 0 until 12) {
            if (pid[i] != 0) {
                isEquZero = false
                break
            }
        }

        if (isEquZero && pid[12] == 0x97) return "Experimental 3.2.1b2"
        if (isEquZero && pid[12] == 0) return "Experimental 3.1"

        // --- structured styles, rendered via lookup ---
        parseAzStyle(pid)?.let { return it.displayString() }
        parseShadowStyle(pid)?.let { return it.displayString() }
        parseMainlineStyle(pid)?.let { return it.displayString() }

        if (isEquZero) return "Generic"

        // Unknown [....] with non-printables shown as '.'
        return buildString {
            append("Unknown [")
            for (c in pid) {
                append(if (StringUtil.isPrint(c.toChar())) c.toChar() else '.')
            }
            append("]")
        }
    }
}
