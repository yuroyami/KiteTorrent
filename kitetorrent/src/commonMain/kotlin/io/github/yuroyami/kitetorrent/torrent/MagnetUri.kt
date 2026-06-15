package io.github.yuroyami.kitetorrent.torrent

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Hex
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.error.LibtorrentError
import io.github.yuroyami.kitetorrent.error.TorrentException
import io.github.yuroyami.kitetorrent.util.Base32
import io.github.yuroyami.kitetorrent.util.StringUtil
import io.github.yuroyami.kitetorrent.util.UrlEscape

/**
 * The information extracted from a `magnet:` URI — the pure-Kotlin counterpart of the
 * subset of libtorrent's `add_torrent_params` that [parseMagnetUri] fills in
 * (src/magnet_uri.cpp, `parse_magnet_uri`).
 *
 * libtorrent parses a magnet link straight into an `add_torrent_params`; KiteTorrent
 * has no session/`add_torrent_params` yet, so the parsed fields are collected into this
 * dedicated, immutable struct. Every field maps 1:1 onto an `add_torrent_params` member:
 *
 *  - [infoHashV1] / [infoHashV2] ← `info_hashes.v1` / `info_hashes.v2`
 *  - [displayName] ← `name` (from the `dn` parameter)
 *  - [trackers] / [trackerTiers] ← `trackers` / `tracker_tiers` (from `tr`)
 *  - [webSeeds] ← `url_seeds` (from `ws`)
 *  - [peers] ← `peers` (from `x.pe`); kept as the raw `host:port` text since KiteTorrent
 *    has no endpoint type in commonMain
 *  - [dhtNodes] ← `dht_nodes` (from `dht`)
 *  - [selectOnly] ← the indices the `so` parameter marks for download (see below)
 *
 * The on-the-wire forms of the BitTorrent URN schemes (BEP 9 / BEP 53):
 *  - `xt=urn:btih:<hash>` where `<hash>` is either the 40-character lowercase/upper hex
 *    of the 20-byte SHA-1 info-hash, or its 32-character RFC 4648 base32 encoding.
 *  - `xt=urn:btmh:1220<hash>` (BEP 52) where `1220` is the multihash prefix for
 *    "sha2-256, 32 bytes" and `<hash>` is the 64-character hex of the v2 info-hash.
 */
class MagnetLink(
    /** v1 info-hash (SHA-1) from `xt=urn:btih:`, or null if the link had no v1 URN. */
    val infoHashV1: Sha1Hash?,
    /** v2 info-hash (SHA-256) from `xt=urn:btmh:1220…`, or null if no v2 URN. */
    val infoHashV2: Sha256Hash?,
    /** Display name from the (URL-decoded) `dn` parameter, or null if absent. */
    val displayName: String?,
    /** Tracker URLs from the `tr` parameters, URL-decoded and validated, in link order. */
    val trackers: List<String>,
    /**
     * Tier index assigned to each tracker, aligned with [trackers]. libtorrent assigns a
     * strictly increasing tier to every accepted `tr` (each tracker in its own tier).
     */
    val trackerTiers: List<Int>,
    /** Web seed URLs from the `ws` parameters (BEP 19), URL-decoded. */
    val webSeeds: List<String>,
    /**
     * Peers from `x.pe` parameters, as their raw `host:port` text (e.g. `1.2.3.4:6881`
     * or `[::1]:6881`). libtorrent parses these into `tcp::endpoint`s; with no endpoint
     * type available in commonMain the textual form is preserved verbatim.
     */
    val peers: List<String>,
    /** DHT bootstrap nodes from `dht` parameters, as `host` to `port` pairs. */
    val dhtNodes: List<Pair<String, Int>>,
    /**
     * The set of file indices selected *for* download by the `so` (select-only)
     * parameter, or null when no valid `so` parameter was present. When non-null, every
     * file **not** in this set is to be skipped (libtorrent expresses this by setting
     * `default_dont_download` and giving the listed indices `default_priority`).
     */
    val selectOnly: Set<Int>?,
) {
    val hasV1: Boolean get() = infoHashV1 != null
    val hasV2: Boolean get() = infoHashV2 != null

    /**
     * The canonical info-hash hex for display/lookups: the v2 hash when present (it is
     * the stronger identity), otherwise the v1 hash, or "" if somehow neither is set.
     */
    fun infoHashHex(): String = (infoHashV2 ?: infoHashV1)?.toHex() ?: ""

    override fun toString(): String =
        "MagnetLink(v1=${infoHashV1?.toHex()}, v2=${infoHashV2?.toHex()}, " +
            "dn=$displayName, trackers=${trackers.size}, ws=${webSeeds.size}, " +
            "peers=${peers.size}, dht=${dhtNodes.size})"
}

/**
 * Parsing and formatting of `magnet:` URIs — pure-Kotlin port of `parse_magnet_uri` and
 * `make_magnet_uri` from libtorrent's src/magnet_uri.cpp.
 *
 * The grammar handled is BitTorrent's flavour of the magnet scheme:
 *
 * ```
 * magnet:?xt=urn:btih:<40-hex|32-base32>      v1 info-hash
 *        &xt=urn:btmh:1220<64-hex>            v2 info-hash (BEP 52)
 *        &dn=<display name>                   (URL-encoded)
 *        &tr=<tracker url>                    (URL-encoded, repeatable)
 *        &ws=<web seed url>                   (URL-encoded, repeatable, BEP 19)
 *        &x.pe=<host:port>                    peer (repeatable)
 *        &dht=<host:port>                     DHT node (repeatable)
 *        &so=<index ranges>                   select-only file indices (BEP 53)
 * ```
 *
 * Faithful details carried over from the C++:
 *  - A parameter name may carry a `.<number>` suffix (e.g. `tr.1`); the suffix is
 *    stripped **only** when every character after the period is a digit, so `x.pe`
 *    keeps its `.pe`.
 *  - Names are matched case-insensitively (ASCII).
 *  - For `xt`, the value is URL-decoded only if it actually contains a `%`.
 *  - A `tr` value must URL-decode cleanly and pass [isValidTrackerUrl] to be accepted.
 *  - The link must contain at least one usable info-hash, otherwise parsing fails with
 *    [LibtorrentError.MISSING_INFO_HASH_IN_URI]. A malformed info-hash fails with
 *    [LibtorrentError.INVALID_INFO_HASH], and a non-`magnet:?` input with
 *    [LibtorrentError.UNSUPPORTED_URL_PROTOCOL].
 */
object MagnetUri {

    private const val SCHEME = "magnet:?"
    private const val URN_BTIH = "urn:btih:"
    private const val URN_BTMH = "urn:btmh:"

    /** BEP 52 multihash prefix for the v2 info-hash: sha2-256 (`0x12`), length 32 (`0x20`). */
    private const val MULTIHASH_SHA256_PREFIX = "1220"

    // TODO matches the C++ `constexpr int max_index = 10000` cap in the `so` parser.
    private const val MAX_SELECT_INDEX = 10000

    /**
     * Parse a `magnet:` [uri] into a [MagnetLink]. Throws [TorrentException] on any of
     * the fatal conditions libtorrent flags through `error_code` (bad scheme, malformed
     * info-hash, or no info-hash at all). Mirrors the throwing overload
     * `parse_magnet_uri(string_view)` in src/magnet_uri.cpp.
     */
    fun parseMagnetUri(uri: String): MagnetLink {
        val r = parseMagnetUriOrNull(uri)
        if (r.error != null) throw TorrentException(r.error)
        return r.link!!
    }

    /**
     * Non-throwing variant: returns a [Result] whose [Result.error] is set when parsing
     * failed (and [Result.link] is null), or whose [Result.link] is the parsed link and
     * [Result.error] is null on success. Mirrors the
     * `parse_magnet_uri(string_view, add_torrent_params&, error_code&)` overload.
     */
    fun parseMagnetUriOrNull(uri: String): Result {
        // sv.substr(0, 8) != "magnet:?"
        if (!uri.startsWith(SCHEME)) {
            return Result(null, LibtorrentError.UNSUPPORTED_URL_PROTOCOL)
        }
        var sv = uri.substring(SCHEME.length)

        var displayName: String? = null
        var infoHashV1: Sha1Hash? = null
        var infoHashV2: Sha256Hash? = null
        val trackers = ArrayList<String>()
        val trackerTiers = ArrayList<Int>()
        val webSeeds = ArrayList<String>()
        val peers = ArrayList<String>()
        val dhtNodes = ArrayList<Pair<String, Int>>()
        // selected file indices; non-null once a valid `so` is seen
        var selectOnly: HashSet<Int>? = null

        var tier = 0

        while (sv.isNotEmpty()) {
            // std::tie(name, sv) = split_string(sv, '=');
            val (rawName, afterName) = StringUtil.splitString(sv, '=')
            // std::tie(value, sv) = split_string(sv, '&');
            val (value, afterValue) = StringUtil.splitString(afterName, '&')
            sv = afterValue

            // parameter names are allowed to have a .<number>-suffix; strip it only when
            // the characters after the period are all digits.
            var name = rawName
            val (strippedName, number) = StringUtil.splitString(rawName, '.')
            if (allDigits(number)) name = strippedName

            when {
                StringUtil.stringEqualNoCase(name, "dn") -> {
                    // display name
                    displayName = UrlEscape.unescape(value)
                }

                StringUtil.stringEqualNoCase(name, "tr") -> {
                    // tracker — decode, validate, then record with an increasing tier
                    val tracker = UrlEscape.unescape(value)
                    if (isValidTrackerUrl(tracker)) {
                        trackers.add(tracker)
                        trackerTiers.add(tier++)
                    }
                }

                StringUtil.stringEqualNoCase(name, "ws") -> {
                    // web seed (BEP 19)
                    webSeeds.add(UrlEscape.unescape(value))
                }

                StringUtil.stringEqualNoCase(name, "xt") -> {
                    val err = parseXt(value) { v1, v2 ->
                        if (v1 != null) infoHashV1 = v1
                        if (v2 != null) infoHashV2 = v2
                    }
                    if (err != null) return Result(null, err)
                }

                StringUtil.stringEqualNoCase(name, "so") -> {
                    // select-only file indices (BEP 53). On any unexpected character the
                    // whole parameter is ignored (`continue`).
                    val parsed = parseSelectOnly(value)
                    if (parsed != null) {
                        val set = selectOnly ?: HashSet<Int>().also { selectOnly = it }
                        set.addAll(parsed)
                    }
                }

                StringUtil.stringEqualNoCase(name, "x.pe") -> {
                    // peer endpoint — kept verbatim if it looks like host:port
                    parseEndpoint(value)?.let { peers.add(it) }
                }

                StringUtil.stringEqualNoCase(name, "dht") -> {
                    // dht node: split on the LAST ':' so IPv6 hosts survive
                    val divider = value.lastIndexOf(':')
                    if (divider >= 0) {
                        val port = atoi(value.substring(divider + 1))
                        // port > 0 && port < 65535 (the C++ uses uint16 max == 65535)
                        if (port in 1 until 65535) {
                            dhtNodes.add(value.substring(0, divider) to port)
                        }
                    }
                }
            }
        }

        if (infoHashV1 == null && infoHashV2 == null) {
            return Result(null, LibtorrentError.MISSING_INFO_HASH_IN_URI)
        }

        val link = MagnetLink(
            infoHashV1 = infoHashV1,
            infoHashV2 = infoHashV2,
            displayName = displayName?.takeIf { it.isNotEmpty() },
            trackers = trackers,
            trackerTiers = trackerTiers,
            webSeeds = webSeeds,
            peers = peers,
            dhtNodes = dhtNodes,
            selectOnly = selectOnly,
        )
        return Result(link, null)
    }

    /**
     * Parse a single `xt` value. Returns a [LibtorrentError] on a malformed info-hash
     * (which is fatal to the whole parse in the C++), or null on success — invoking
     * [emit] with the v1 and/or v2 hash it decoded. An `xt` value that is neither a
     * `urn:btih:` nor a `urn:btmh:` URN is silently ignored (emit not called).
     */
    private inline fun parseXt(
        rawValue: String,
        emit: (v1: Sha1Hash?, v2: Sha256Hash?) -> Unit,
    ): LibtorrentError? {
        // only URL-decode if the value actually contains a '%'
        var value = rawValue
        if (value.indexOf('%') >= 0) {
            value = UrlEscape.unescape(value)
        }

        if (value.startsWith(URN_BTIH)) {
            val h = value.substring(URN_BTIH.length)
            val infoHash: Sha1Hash = when (h.length) {
                40 -> {
                    val bytes = hexExact(h) ?: return LibtorrentError.INVALID_INFO_HASH
                    Digest32.of(bytes)
                }
                32 -> {
                    val ih = Base32.decode(h)
                    if (ih.size != 20) return LibtorrentError.INVALID_INFO_HASH
                    Digest32.of(ih)
                }
                else -> return LibtorrentError.INVALID_INFO_HASH
            }
            emit(infoHash, null)
            return null
        }

        if (value.startsWith(URN_BTMH)) {
            var rest = value.substring(URN_BTMH.length)
            // hash must be sha256: the 4-char "1220" multihash prefix
            if (!rest.startsWith(MULTIHASH_SHA256_PREFIX)) {
                return LibtorrentError.INVALID_INFO_HASH
            }
            rest = rest.substring(MULTIHASH_SHA256_PREFIX.length)
            if (rest.length != 64) return LibtorrentError.INVALID_INFO_HASH
            val bytes = hexExact(rest) ?: return LibtorrentError.INVALID_INFO_HASH
            emit(null, Digest32.of(bytes))
            return null
        }

        // unrecognized urn:* — ignored, no error
        return null
    }

    /**
     * Parse the `so` value into the set of indices it selects, or null when the value
     * contains a character other than a digit, `-`, or `,` (in which case libtorrent
     * skips the whole parameter). Faithful to the C++ token loop, including its lenient
     * `continue`-on-garbage behaviour for individual malformed tokens.
     */
    private fun parseSelectOnly(value: String): Set<Int>? {
        // accept only digits, '-' and ',' — any other char rejects the whole value
        for (c in value) {
            if (!StringUtil.isDigit(c) && c != '-' && c != ',') return null
        }

        val out = HashSet<Int>()
        var rest = value
        do {
            val (token, after) = StringUtil.splitString(rest, ',')
            rest = after
            if (token.isEmpty()) continue

            val idx1: Int
            val idx2: Int
            val divider = token.indexOf('-')
            if (divider >= 0) { // it's a range
                if (divider == 0) continue // no start index
                if (divider == token.length - 1) continue // no end index
                idx1 = atoi(token.substring(0, divider))
                if (idx1 < 0 || idx1 > MAX_SELECT_INDEX) continue
                idx2 = atoi(token.substring(divider + 1))
                if (idx2 < 0 || idx2 > MAX_SELECT_INDEX) continue
                if (idx1 > idx2) continue // wrong range limits
            } else { // it's a single index
                idx1 = atoi(token)
                if (idx1 < 0 || idx1 > MAX_SELECT_INDEX) continue
                idx2 = idx1
            }

            for (i in idx1..idx2) out.add(i)
        } while (rest.isNotEmpty())

        return out
    }

    /**
     * libtorrent's `is_valid_tracker_url` (src/parse_url.cpp). Rejects empty URLs, any
     * URL containing a control character or space (RFC 3986 + CRLF-injection defence),
     * and anything not beginning (case-insensitively) with `http://`, `https://`, or
     * `udp://`. The remaining structural check from the C++ — `parse_url_components`
     * succeeding — is reproduced by [parseUrlComponentsOk].
     */
    fun isValidTrackerUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        // reject control characters and space (uc <= 0x20 || uc == 0x7f)
        for (c in url) {
            val uc = c.code
            if (uc <= 0x20 || uc == 0x7f) return false
        }
        if (!StringUtil.stringBeginsNoCase("http://", url) &&
            !StringUtil.stringBeginsNoCase("https://", url) &&
            !StringUtil.stringBeginsNoCase("udp://", url)
        ) {
            return false
        }
        return parseUrlComponentsOk(url)
    }

    /**
     * The success/failure half of libtorrent's `parse_url_components` (src/parse_url.cpp)
     * — we only need the boolean (does it parse?) for [isValidTrackerUrl], not the
     * decomposed pieces. The structural rules reproduced here:
     *  - there is a `://` after the scheme;
     *  - the hostname (after stripping an optional `user:pass@` and an optional `:port`)
     *    is non-empty and contains only LDH characters (`A-Za-z0-9-.`), with IPv6
     *    literals in `[...]` additionally allowing `:%_~`;
     *  - if a port is present it is all digits.
     */
    private fun parseUrlComponentsOk(url: String): Boolean {
        // skip leading whitespace
        var start = 0
        while (start < url.length && StringUtil.isSpace(url[start])) start++

        // protocol up to ':'
        var end = indexOf(url, ':', start)
        if (end >= url.length) return false // no ':' → unsupported_url_protocol
        end++ // past ':'
        if (end >= url.length || url[end] != '/') return false
        end++
        if (end >= url.length || url[end] != '/') return false
        end++
        start = end

        // authority terminates at the first of '/', '?', '#'
        val at = indexOf(url, '@', start)
        val colon = indexOf(url, ':', start)
        val authEnd = minOf(
            indexOf(url, '/', start),
            indexOf(url, '?', start),
            indexOf(url, '#', start),
        )

        if (at < url.length && colon < url.length && colon < at && at < authEnd) {
            // strip user:pass@
            start = at + 1
        }

        val hostname: String
        var ipv6Literal = false
        var portPos: Int
        if (start < url.length && url[start] == '[') {
            val close = indexOf(url, ']', start)
            if (close >= url.length) return false // expected_close_bracket_in_address
            hostname = url.substring(start + 1, close)
            portPos = indexOf(url, ':', close)
            ipv6Literal = true
        } else {
            portPos = indexOf(url, ':', start)
            hostname = if (portPos < authEnd) url.substring(start, portPos)
            else url.substring(start, minOf(authEnd, url.length))
        }

        if (hostname.isEmpty()) return false // invalid_hostname

        for (c in hostname) {
            if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-' || c == '.') continue
            if (ipv6Literal && (c == ':' || c == '%' || c == '_' || c == '~')) continue
            return false // invalid_hostname
        }

        if (portPos < authEnd) {
            var i = portPos + 1
            while (i < authEnd && i < url.length) {
                if (!StringUtil.isDigit(url[i])) return false // invalid_port
                i++
            }
        }

        return true
    }

    /**
     * Parse an `x.pe` peer endpoint, returning its canonical `host:port` text or null if
     * it is not a plausible endpoint. libtorrent feeds the value to `parse_endpoint`
     * (which builds a `tcp::endpoint`); lacking an address type in commonMain we only
     * validate that there is a host and an in-range numeric port, preserving the text.
     * Accepts both `1.2.3.4:6881` and bracketed IPv6 `[::1]:6881`.
     */
    private fun parseEndpoint(value: String): String? {
        if (value.isEmpty()) return null
        val divider = value.lastIndexOf(':')
        if (divider <= 0 || divider == value.length - 1) return null
        val host = value.substring(0, divider)
        val portStr = value.substring(divider + 1)
        if (host.isEmpty()) return null
        for (c in portStr) if (!StringUtil.isDigit(c)) return null
        val port = atoi(portStr)
        if (port !in 1..65535) return null
        return value
    }

    // -------------------------------------------------------------------------
    // make_magnet_uri
    // -------------------------------------------------------------------------

    /**
     * Format a `magnet:` URI for [info] — port of `make_magnet_uri(torrent_info const&)`
     * (which forwards to `make_magnet_uri(add_torrent_params const&)`) in
     * src/magnet_uri.cpp.
     *
     * Emits the v1 and/or v2 info-hash URN(s), then the display name (`dn`), the flat
     * list of tracker URLs (`tr`, across all tiers, in order) and the web seeds (`ws`).
     * Values are percent-encoded with [UrlEscape.escape], exactly like libtorrent's
     * `escape_string`. Returns "" when [info] has no info-hash at all (so no link can be
     * formed), matching the C++ early-out.
     */
    fun makeMagnetUri(info: TorrentInfo): String {
        val sb = StringBuilder()
        sb.append(SCHEME)

        var first = true
        val v1 = info.infoHashV1
        if (v1 != null) {
            sb.append("xt=").append(URN_BTIH).append(v1.toHex())
            first = false
        }
        val v2 = info.infoHashV2
        if (v2 != null) {
            if (!first) sb.append('&')
            sb.append("xt=").append(URN_BTMH).append(MULTIHASH_SHA256_PREFIX).append(v2.toHex())
            first = false
        }

        if (first) {
            // no info-hashes → can't make a magnet link
            return ""
        }

        val name = info.name
        if (name.isNotEmpty()) {
            sb.append("&dn=").append(UrlEscape.escape(name))
        }

        for (tr in info.allTrackers()) {
            sb.append("&tr=").append(UrlEscape.escape(tr))
        }

        for (ws in info.webSeeds) {
            sb.append("&ws=").append(UrlEscape.escape(ws))
        }

        return sb.toString()
    }

    /**
     * Format a `magnet:` URI from explicit pieces — the analogue of
     * `make_magnet_uri(add_torrent_params const&)` for callers that do not have a
     * [TorrentInfo]. At least one of [infoHashV1] / [infoHashV2] must be non-null or ""
     * is returned. [dhtNodes] are emitted as `dht=host:port` and [peers] verbatim as
     * `x.pe=…` (already in `host:port` text form), matching the C++.
     */
    fun makeMagnetUri(
        infoHashV1: Sha1Hash? = null,
        infoHashV2: Sha256Hash? = null,
        displayName: String? = null,
        trackers: List<String> = emptyList(),
        webSeeds: List<String> = emptyList(),
        dhtNodes: List<Pair<String, Int>> = emptyList(),
        peers: List<String> = emptyList(),
    ): String {
        val sb = StringBuilder()
        sb.append(SCHEME)

        var first = true
        if (infoHashV1 != null) {
            sb.append("xt=").append(URN_BTIH).append(infoHashV1.toHex())
            first = false
        }
        if (infoHashV2 != null) {
            if (!first) sb.append('&')
            sb.append("xt=").append(URN_BTMH).append(MULTIHASH_SHA256_PREFIX).append(infoHashV2.toHex())
            first = false
        }
        if (first) return ""

        if (!displayName.isNullOrEmpty()) {
            sb.append("&dn=").append(UrlEscape.escape(displayName))
        }
        for (tr in trackers) sb.append("&tr=").append(UrlEscape.escape(tr))
        for (ws in webSeeds) sb.append("&ws=").append(UrlEscape.escape(ws))
        for (node in dhtNodes) {
            sb.append("&dht=").append(UrlEscape.escape(node.first)).append(':')
                .append(StringUtil.toString(node.second.toLong()))
        }
        for (p in peers) sb.append("&x.pe=").append(p)

        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // small helpers
    // -------------------------------------------------------------------------

    /** True if every char of [s] is an ASCII digit (the empty string counts as true,
     * matching `std::all_of` over an empty range). */
    private fun allDigits(s: String): Boolean {
        for (c in s) if (!StringUtil.isDigit(c)) return false
        return true
    }

    /** Decode [hex] (whose length the caller has already checked to be even);
     * returns null on any non-hex char, like libtorrent's `from_hex` returning false. */
    private fun hexExact(hex: String): ByteArray? = Hex.decodeOrNull(hex)

    /** `std::find`-style: index of [c] in [s] at or after [from], or `s.length` if absent. */
    private fun indexOf(s: String, c: Char, from: Int): Int {
        val i = s.indexOf(c, from)
        return if (i < 0) s.length else i
    }

    /**
     * A minimal `std::atoi`: leading ASCII whitespace then an optional sign then digits,
     * stopping at the first non-digit. Returns 0 when no digits are present, exactly like
     * the C `atoi` libtorrent relies on for the `dht` / `so` ports and indices.
     */
    private fun atoi(s: String): Int {
        var i = 0
        val n = s.length
        while (i < n && StringUtil.isSpace(s[i])) i++
        var sign = 1
        if (i < n && (s[i] == '+' || s[i] == '-')) {
            if (s[i] == '-') sign = -1
            i++
        }
        var acc = 0L
        while (i < n && StringUtil.isDigit(s[i])) {
            acc = acc * 10 + (s[i] - '0')
            // clamp to avoid overflow; callers range-check anyway
            if (acc > Int.MAX_VALUE.toLong() + 1) acc = Int.MAX_VALUE.toLong() + 1
            i++
        }
        val v = sign * acc
        return when {
            v > Int.MAX_VALUE -> Int.MAX_VALUE
            v < Int.MIN_VALUE -> Int.MIN_VALUE
            else -> v.toInt()
        }
    }

    /**
     * Result of [parseMagnetUriOrNull]: exactly one of [link] / [error] is non-null.
     */
    class Result internal constructor(
        /** The parsed link on success, or null when [error] is set. */
        val link: MagnetLink?,
        /** The fatal error on failure, or null when [link] is set. */
        val error: LibtorrentError?,
    ) {
        val isSuccess: Boolean get() = link != null
    }
}
