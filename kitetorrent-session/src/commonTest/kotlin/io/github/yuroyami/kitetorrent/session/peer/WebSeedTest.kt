package io.github.yuroyami.kitetorrent.session.peer

import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pure (no-network) tests for [WebSeed]'s BEP-19 URL + `Range` computation, pinned
 * against libtorrent's `web_peer_connection::write_request` + `file_storage::map_block`.
 *
 * The arithmetic lives in [WebSeedMapper] precisely so it can be tested without an
 * [io.ktor.client.HttpClient] (the common test source set has no platform engine).
 * The torrents are built as real bencoded bytes and run through [TorrentInfo.parse],
 * because [io.github.yuroyami.kitetorrent.torrent.FileStorage] has an internal
 * constructor and can't be hand-rolled from this module.
 */
class WebSeedTest {

    // ---- torrent builders (bencode → TorrentInfo) -----------------------------

    /**
     * Build a multi-file v1 torrent: root dir [name], piece size [pieceLength], and
     * one `{length, path:[component]}` entry per (path,size) in [files]. A path with a
     * '/' is split into nested path components, matching how a real `.torrent` stores
     * sub-directories.
     */
    private fun multiFileTorrent(
        name: String,
        pieceLength: Int,
        files: List<Pair<String, Long>>,
    ): TorrentInfo {
        val fileList = Entry.list().apply {
            for ((path, size) in files) {
                items.add(
                    Entry.Dict().apply {
                        map["length"] = Entry.of(size)
                        map["path"] = Entry.list().apply {
                            for (c in path.split('/')) items.add(Entry.of(c))
                        }
                    },
                )
            }
        }
        val totalSize = files.sumOf { it.second }
        val numPieces = ((totalSize + pieceLength - 1) / pieceLength).toInt()
        val info = Entry.Dict().apply {
            map["name"] = Entry.of(name)
            map["piece length"] = Entry.of(pieceLength.toLong())
            map["files"] = fileList
            // pieces blob: 20 bytes per piece (content irrelevant to range math)
            map["pieces"] = Entry.of(ByteArray(20 * numPieces))
        }
        val root = Entry.Dict().apply { map["info"] = info }
        return TorrentInfo.parse(Bencode.encode(root))
    }

    /** Build a single-file v1 torrent of [size] bytes named [name]. */
    private fun singleFileTorrent(name: String, pieceLength: Int, size: Long): TorrentInfo {
        val numPieces = ((size + pieceLength - 1) / pieceLength).toInt()
        val info = Entry.Dict().apply {
            map["name"] = Entry.of(name)
            map["piece length"] = Entry.of(pieceLength.toLong())
            map["length"] = Entry.of(size)
            map["pieces"] = Entry.of(ByteArray(20 * numPieces))
        }
        val root = Entry.Dict().apply { map["info"] = info }
        return TorrentInfo.parse(Bencode.encode(root))
    }

    /** Normalise a base URL the way [WebSeed] would, then map a request. */
    private fun rangesFor(
        torrent: TorrentInfo,
        baseUrl: String,
        piece: Int,
        offset: Int,
        length: Int,
    ): List<HttpRange> {
        val url = WebSeedMapper.normalizeBaseUrl(baseUrl, torrent)
        return WebSeedMapper.rangesFor(torrent, url, piece, offset, length)
    }

    // ---- the headline case: a block straddling a file boundary ----------------

    @Test
    fun rangesFor_blockStraddlingFileBoundary_splitsAcrossFilesWithFileRelativeOffsets() {
        // root "stuff", piece=16; files a=10, b=10, c=12 → total 32, 2 pieces.
        // Layout in the concatenated stream:
        //   file a  [ 0,10)
        //   file b  [10,20)
        //   file c  [20,32)
        val t = multiFileTorrent(
            name = "stuff",
            pieceLength = 16,
            files = listOf("a.bin" to 10L, "b.bin" to 10L, "c.bin" to 12L),
        )

        // request piece 0, offset 5, length 12 → absolute [5,17): crosses the a|b
        // boundary at 10. Expect two ranges:
        //   file a: bytes 5..9   (file-relative 5..9)
        //   file b: bytes 10..16 (file-relative 0..6)
        val ranges = rangesFor(t, "http://host/path/", piece = 0, offset = 5, length = 12)

        assertEquals(2, ranges.size, "block crossing one boundary → two ranges")

        assertEquals("http://host/path/a.bin", ranges[0].url)
        assertEquals(5L, ranges[0].firstByte)
        assertEquals(9L, ranges[0].lastByte)
        assertEquals("bytes=5-9", ranges[0].rangeHeaderValue)
        assertEquals(5L, ranges[0].length)

        assertEquals("http://host/path/b.bin", ranges[1].url)
        assertEquals(0L, ranges[1].firstByte)
        assertEquals(6L, ranges[1].lastByte)
        assertEquals("bytes=0-6", ranges[1].rangeHeaderValue)
        assertEquals(7L, ranges[1].length)

        // the two slices must exactly cover the requested 12 bytes
        assertEquals(12L, ranges.sumOf { it.length })
    }

    @Test
    fun rangesFor_blockSpanningThreeFiles_yieldsThreeRanges() {
        // Same layout; a request that touches all three files.
        // piece 0, offset 8, length 16 → absolute [8,24):
        //   a:  8..9  (rel 8..9)
        //   b: 10..19 (rel 0..9)
        //   c: 20..23 (rel 0..3)
        val t = multiFileTorrent(
            name = "stuff",
            pieceLength = 16,
            files = listOf("a.bin" to 10L, "b.bin" to 10L, "c.bin" to 12L),
        )

        val ranges = rangesFor(t, "http://host/d/", piece = 0, offset = 8, length = 16)
        assertEquals(3, ranges.size)

        assertEquals(Triple("http://host/d/a.bin", 8L, 9L), ranges[0].triple())
        assertEquals(Triple("http://host/d/b.bin", 0L, 9L), ranges[1].triple())
        assertEquals(Triple("http://host/d/c.bin", 0L, 3L), ranges[2].triple())
        assertEquals(16L, ranges.sumOf { it.length })
    }

    @Test
    fun rangesFor_blockWhollyInsideOneFile_yieldsSingleRange() {
        val t = multiFileTorrent(
            name = "stuff",
            pieceLength = 16,
            files = listOf("a.bin" to 10L, "b.bin" to 10L, "c.bin" to 12L),
        )

        // absolute [11,14) sits entirely inside file b [10,20) → rel 1..3
        val ranges = rangesFor(t, "http://host/d/", piece = 0, offset = 11, length = 3)
        assertEquals(1, ranges.size)
        assertEquals(Triple("http://host/d/b.bin", 1L, 3L), ranges[0].triple())
    }

    @Test
    fun rangesFor_secondPiece_addsPieceLengthToAbsoluteOffset() {
        val t = multiFileTorrent(
            name = "stuff",
            pieceLength = 16,
            files = listOf("a.bin" to 10L, "b.bin" to 10L, "c.bin" to 12L),
        )
        // piece 1, offset 0, length 4 → absolute [16,20): inside file b → rel 6..9
        val ranges = rangesFor(t, "http://host/d/", piece = 1, offset = 0, length = 4)
        assertEquals(1, ranges.size)
        assertEquals(Triple("http://host/d/b.bin", 6L, 9L), ranges[0].triple())
    }

    // ---- nested paths get percent-encoded with separators preserved -----------

    @Test
    fun rangesFor_nestedPathIsPercentEncodedKeepingSlashes() {
        // a file in a sub-directory with a space in the name
        val t = multiFileTorrent(
            name = "root",
            pieceLength = 16,
            files = listOf("sub dir/my file.bin" to 20L),
        )

        val ranges = rangesFor(t, "http://host/base/", piece = 0, offset = 0, length = 8)
        assertEquals(1, ranges.size)
        // escape_path keeps '/' but encodes the spaces → %20
        assertEquals("http://host/base/sub%20dir/my%20file.bin", ranges[0].url)
        assertEquals("bytes=0-7", ranges[0].rangeHeaderValue)
    }

    @Test
    fun multiFileBaseUrl_getsTrailingSlashEnsured() {
        // base URL WITHOUT trailing slash must still produce host/base/<file>
        val t = multiFileTorrent(
            name = "root",
            pieceLength = 16,
            files = listOf("a.bin" to 20L),
        )
        assertEquals("http://host/base/", WebSeedMapper.normalizeBaseUrl("http://host/base", t))
        assertEquals("http://host/base/a.bin", rangesFor(t, "http://host/base", 0, 0, 4)[0].url)
    }

    // ---- single-file torrent URL rules ----------------------------------------

    @Test
    fun singleFile_urlEndingInSlash_appendsEscapedName_andRangeIsAbsolute() {
        // base ends in '/': libtorrent appends the escaped torrent name.
        val t = singleFileTorrent(name = "big movie.mkv", pieceLength = 16, size = 40L)
        assertEquals("http://host/files/big%20movie.mkv", WebSeedMapper.normalizeBaseUrl("http://host/files/", t))

        // single-file: the range is against that one URL and offsets are torrent-absolute.
        // piece 1, offset 4, length 8 → absolute [20,28)
        val ranges = rangesFor(t, "http://host/files/", piece = 1, offset = 4, length = 8)
        assertEquals(1, ranges.size)
        assertEquals(Triple("http://host/files/big%20movie.mkv", 20L, 27L), ranges[0].triple())
    }

    @Test
    fun singleFile_urlWithFilename_isUsedVerbatim() {
        // base already names the file (no trailing slash) → used as-is.
        val t = singleFileTorrent(name = "movie.mkv", pieceLength = 16, size = 40L)
        assertEquals("http://host/files/movie.mkv", WebSeedMapper.normalizeBaseUrl("http://host/files/movie.mkv", t))
        assertEquals("http://host/files/movie.mkv", rangesFor(t, "http://host/files/movie.mkv", 0, 0, 4)[0].url)
    }

    // ---- bounds checking ------------------------------------------------------

    @Test
    fun rangesFor_zeroLength_isEmpty() {
        val t = singleFileTorrent(name = "f.bin", pieceLength = 16, size = 40L)
        assertTrue(rangesFor(t, "http://host/f.bin", 0, 0, 0).isEmpty())
    }

    @Test
    fun rangesFor_pastEnd_throws() {
        // total = 40; last piece is index 2 size 8 ([32,40)). Asking for 16 there
        // would run to 48 > 40.
        val t = singleFileTorrent(name = "f.bin", pieceLength = 16, size = 40L)
        assertFailsWith<IllegalArgumentException> { rangesFor(t, "http://host/f.bin", piece = 2, offset = 0, length = 16) }
    }

    @Test
    fun rangesFor_pieceOutOfRange_throws() {
        val t = singleFileTorrent(name = "f.bin", pieceLength = 16, size = 40L)
        assertFailsWith<IllegalArgumentException> { rangesFor(t, "http://host/f.bin", piece = 99, offset = 0, length = 1) }
    }

    private fun HttpRange.triple() = Triple(url, firstByte, lastByte)
}
