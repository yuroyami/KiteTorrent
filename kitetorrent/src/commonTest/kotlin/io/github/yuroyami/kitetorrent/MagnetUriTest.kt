package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.error.LibtorrentError
import io.github.yuroyami.kitetorrent.error.TorrentException
import io.github.yuroyami.kitetorrent.torrent.MagnetUri
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden-vector tests for [MagnetUri], the port of libtorrent's `parse_magnet_uri` /
 * `make_magnet_uri` (src/magnet_uri.cpp).
 *
 * The primary info-hash `0496aa386ece056e0bf79d5d39c7df9f533fed92` is the v1 info-hash
 * of libtorrent's own `test1_single.torrent` (see [io.github.yuroyami.kitetorrent.Hex]
 * usage in TorrentInfoTest), so the same identity is exercised across the codebase.
 * The base32 form `ASLKUODOZYCW4C7XTVOTTR67T5JT73MS` is the RFC 4648 base32 encoding of
 * those exact 20 bytes (20 bytes → 32 base32 chars, no padding), computed independently.
 */
class MagnetUriTest {

    private val v1Hex = "0496aa386ece056e0bf79d5d39c7df9f533fed92"
    private val v1Base32 = "ASLKUODOZYCW4C7XTVOTTR67T5JT73MS"
    private val v2Hex = "87f7670fce55d2f949732e3702ff35b8b09bbc73c81a3c20f36850406d6c040d"

    @Test
    fun parses_hex_btih_with_dn_and_tr() {
        val link = MagnetUri.parseMagnetUri(
            "magnet:?xt=urn:btih:$v1Hex&dn=test&tr=http%3A%2F%2Ftracker",
        )
        assertEquals(v1Hex, link.infoHashV1!!.toHex())
        assertNull(link.infoHashV2)
        assertEquals("test", link.displayName)
        // tr value http%3A%2F%2Ftracker decodes to http://tracker, which is a valid url
        assertTrue("http://tracker" in link.trackers)
        assertEquals(listOf("http://tracker"), link.trackers)
        assertEquals(listOf(0), link.trackerTiers)
    }

    @Test
    fun parses_base32_btih() {
        val link = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:$v1Base32&dn=a")
        // base32 of the same 20 bytes must decode to the same v1 info-hash
        assertEquals(v1Hex, link.infoHashV1!!.toHex())
        assertEquals("a", link.displayName)
    }

    @Test
    fun parses_btmh_v2_multihash() {
        val link = MagnetUri.parseMagnetUri("magnet:?xt=urn:btmh:1220$v2Hex&dn=v2name")
        assertNull(link.infoHashV1)
        assertEquals(v2Hex, link.infoHashV2!!.toHex())
        assertEquals("v2name", link.displayName)
        assertTrue(link.hasV2)
        assertEquals(v2Hex, link.infoHashHex()) // v2 is canonical when present
    }

    @Test
    fun parses_hybrid_both_hashes() {
        val link = MagnetUri.parseMagnetUri(
            "magnet:?xt=urn:btih:$v1Hex&xt=urn:btmh:1220$v2Hex&dn=hybrid",
        )
        assertEquals(v1Hex, link.infoHashV1!!.toHex())
        assertEquals(v2Hex, link.infoHashV2!!.toHex())
        assertTrue(link.hasV1 && link.hasV2)
    }

    @Test
    fun multiple_trackers_get_increasing_tiers() {
        val link = MagnetUri.parseMagnetUri(
            "magnet:?xt=urn:btih:$v1Hex" +
                "&tr=udp%3A%2F%2Ftracker.one%3A1337" +
                "&tr=https%3A%2F%2Ftracker.two%2Fannounce",
        )
        assertEquals(
            listOf("udp://tracker.one:1337", "https://tracker.two/announce"),
            link.trackers,
        )
        assertEquals(listOf(0, 1), link.trackerTiers)
    }

    @Test
    fun invalid_tracker_url_is_dropped() {
        // "ftp://..." is not http/https/udp, so is_valid_tracker_url rejects it and the
        // tracker is silently dropped (but the link still parses thanks to the info-hash)
        val link = MagnetUri.parseMagnetUri(
            "magnet:?xt=urn:btih:$v1Hex&tr=ftp%3A%2F%2Fbad&tr=http%3A%2F%2Fgood",
        )
        assertEquals(listOf("http://good"), link.trackers)
        assertEquals(listOf(0), link.trackerTiers)
    }

    @Test
    fun parses_web_seeds() {
        val link = MagnetUri.parseMagnetUri(
            "magnet:?xt=urn:btih:$v1Hex&ws=http%3A%2F%2Fseed%2Ffile&ws=http%3A%2F%2Fseed2",
        )
        assertEquals(listOf("http://seed/file", "http://seed2"), link.webSeeds)
    }

    @Test
    fun parses_peers_and_dht_nodes() {
        val link = MagnetUri.parseMagnetUri(
            "magnet:?xt=urn:btih:$v1Hex&x.pe=1.2.3.4:6881&dht=router.example.com:6881",
        )
        assertEquals(listOf("1.2.3.4:6881"), link.peers)
        assertEquals(listOf("router.example.com" to 6881), link.dhtNodes)
    }

    @Test
    fun dht_ipv6_splits_on_last_colon() {
        val link = MagnetUri.parseMagnetUri(
            "magnet:?xt=urn:btih:$v1Hex&dht=[2001:db8::1]:1234",
        )
        assertEquals(listOf("[2001:db8::1]" to 1234), link.dhtNodes)
    }

    @Test
    fun numeric_suffix_on_name_is_stripped_but_x_pe_is_not() {
        // tr.1 -> name becomes "tr"; x.pe stays "x.pe" (".pe" is not all digits)
        val link = MagnetUri.parseMagnetUri(
            "magnet:?xt=urn:btih:$v1Hex&tr.1=http%3A%2F%2Ft&x.pe=9.9.9.9:1",
        )
        assertEquals(listOf("http://t"), link.trackers)
        assertEquals(listOf("9.9.9.9:1"), link.peers)
    }

    @Test
    fun percent_encoded_xt_is_decoded() {
        // "urn:btih:" with the ':' percent-encoded as %3A; since the value contains '%'
        // it is unescaped first, then matched.
        val encoded = "urn%3Abtih%3A$v1Hex"
        val link = MagnetUri.parseMagnetUri("magnet:?xt=$encoded")
        assertEquals(v1Hex, link.infoHashV1!!.toHex())
    }

    @Test
    fun select_only_parses_indices_and_ranges() {
        val link = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:$v1Hex&so=0,2-4,7")
        assertEquals(setOf(0, 2, 3, 4, 7), link.selectOnly)
    }

    @Test
    fun select_only_skips_garbage_value_entirely() {
        // contains a letter -> whole `so` is rejected, selectOnly stays null
        val link = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:$v1Hex&so=0,abc")
        assertNull(link.selectOnly)
    }

    @Test
    fun missing_scheme_is_unsupported_protocol() {
        val r = MagnetUri.parseMagnetUriOrNull("http://example.com/x.torrent")
        assertNull(r.link)
        assertEquals(LibtorrentError.UNSUPPORTED_URL_PROTOCOL, r.error)
        val ex = assertFailsWith<TorrentException> {
            MagnetUri.parseMagnetUri("http://example.com")
        }
        assertEquals(LibtorrentError.UNSUPPORTED_URL_PROTOCOL, ex.error)
    }

    @Test
    fun no_info_hash_is_missing_info_hash() {
        val r = MagnetUri.parseMagnetUriOrNull("magnet:?dn=name-only&tr=http%3A%2F%2Ft")
        assertNull(r.link)
        assertEquals(LibtorrentError.MISSING_INFO_HASH_IN_URI, r.error)
    }

    @Test
    fun bad_hex_length_is_invalid_info_hash() {
        // 39 hex chars (one short of 40) -> not 40, not 32 -> invalid
        val r = MagnetUri.parseMagnetUriOrNull("magnet:?xt=urn:btih:${v1Hex.dropLast(1)}")
        assertNull(r.link)
        assertEquals(LibtorrentError.INVALID_INFO_HASH, r.error)
    }

    @Test
    fun btmh_wrong_multihash_prefix_is_invalid() {
        // prefix 1221 instead of 1220 -> invalid info-hash
        val r = MagnetUri.parseMagnetUriOrNull("magnet:?xt=urn:btmh:1221$v2Hex")
        assertNull(r.link)
        assertEquals(LibtorrentError.INVALID_INFO_HASH, r.error)
    }

    @Test
    fun make_magnet_uri_round_trips_through_parse() {
        // build a torrent_info from a real single-file torrent, format a magnet link, and
        // make sure parsing it back recovers the v1 info-hash and the name.
        val torrentHex =
            "6431303a6372656174656420627931303a6c6962746f7272656e7431333a6372656174696f" +
            "6e2064617465693134313934393037303065343a696e666f64363a6c656e67746869353132" +
            "303065343a6e616d65313a6131323a7069656365206c656e67746869313633383465363a70" +
            "696563657338303ac8c4d47d84c77f5f4d4ca429d8af1e796d241c1a84b60ade9fcf6b7d40" +
            "19fb88d18db962c68faeb8dff2fc9ddd20873de51de6e2767f77898daf51141aa6285221af" +
            "2b4f30155e2b1b0e77f7490795c76565"
        val ti = TorrentInfo.parse(Hex.decode(torrentHex))
        val uri = MagnetUri.makeMagnetUri(ti)
        // the v1 hash of this torrent is the same one used throughout this test
        assertTrue(uri.startsWith("magnet:?xt=urn:btih:$v1Hex"))
        assertTrue("&dn=a" in uri)

        val back = MagnetUri.parseMagnetUri(uri)
        assertEquals(ti.infoHashV1, back.infoHashV1)
        assertEquals("a", back.displayName)
    }

    @Test
    fun make_magnet_uri_from_pieces_formats_dn_and_tr_encoded() {
        val v1 = Digest32.fromHex(v1Hex)
        val uri = MagnetUri.makeMagnetUri(
            infoHashV1 = v1,
            displayName = "my torrent",
            trackers = listOf("http://tracker/announce"),
        )
        // space -> %20 (escape_string), '/' and ':' -> %2f/%3a (libtorrent uses lowercase hex)
        assertTrue("&dn=my%20torrent" in uri)
        assertTrue("&tr=http%3a%2f%2ftracker%2fannounce" in uri)
        // and it parses back
        val back = MagnetUri.parseMagnetUri(uri)
        assertEquals(v1Hex, back.infoHashV1!!.toHex())
        assertEquals("my torrent", back.displayName)
        assertEquals(listOf("http://tracker/announce"), back.trackers)
    }

    @Test
    fun make_magnet_uri_empty_when_no_info_hash() {
        assertEquals("", MagnetUri.makeMagnetUri())
    }
}
