package io.github.yuroyami.kitetorrent.torrent.resume

import io.github.yuroyami.kitetorrent.Bitfield
import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.error.LibtorrentError
import io.github.yuroyami.kitetorrent.error.TorrentException
import io.github.yuroyami.kitetorrent.peer.PeerAddress
import io.github.yuroyami.kitetorrent.torrent.CreateTorrent
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip tests for [ResumeData], the port of libtorrent's
 * write_resume_data / read_resume_data. We build an [AddTorrentParams], serialise it,
 * parse it back, and assert the key fields survive; we also assert the on-disk
 * `file-format` magic string and check the resume-data conventions (the `pieces`
 * have/verified bit layout, the v4/v6 peer split, the override flags).
 */
class ResumeDataTest {

    // The four 20-byte v1 piece hashes of libtorrent's test1_single.torrent: a single
    // 51200-byte file "a" with 16 KiB pieces (reused from CreateTorrentTest).
    private val piece0 = Digest32.fromHex("c8c4d47d84c77f5f4d4ca429d8af1e796d241c1a")
    private val piece1 = Digest32.fromHex("84b60ade9fcf6b7d4019fb88d18db962c68faeb8")
    private val piece2 = Digest32.fromHex("dff2fc9ddd20873de51de6e2767f77898daf5114")
    private val piece3 = Digest32.fromHex("1aa6285221af2b4f30155e2b1b0e77f7490795c7")

    private fun singleFileTorrentBuffer(): ByteArray =
        CreateTorrent.Builder(name = "a", pieceLength = 16384)
            .addFile("a", 51200)
            .build()
            .setHash(0, piece0)
            .setHash(1, piece1)
            .setHash(2, piece2)
            .setHash(3, piece3)
            .setComment("hello world")
            .setCreator("KiteTorrent test")
            .generateBuffer()

    /** Pull the raw `info`-dict bytes out of a generated .torrent buffer. */
    private fun infoSectionOf(torrentBuffer: ByteArray): ByteArray {
        val root = Bdecode.decode(torrentBuffer)
        return root.dictFindDict("info").dataSection()
    }

    private fun ep(host: String, port: Int) = Endpoint(PeerAddress.parseOrNull(host)!!, port)

    // ---- magic string --------------------------------------------------------

    @Test
    fun file_format_magic_string_is_present() {
        val atp = AddTorrentParams(savePath = "/downloads", infoHashV1 = Digest32.zeros(20))
        val bytes = ResumeData.write(atp)

        // present as a decoded field...
        val rd = Bdecode.decode(bytes)
        assertEquals("libtorrent resume file", rd.dictFindStringValue("file-format"))
        assertEquals(1L, rd.dictFindIntValue("file-version"))

        // ...and literally present in the byte stream.
        val text = bytes.decodeToString()
        assertTrue(text.contains("libtorrent resume file"), "magic string missing from raw bytes")
    }

    // ---- headline round-trip -------------------------------------------------

    @Test
    fun round_trips_key_fields() {
        val have = Bitfield(4).apply { setBit(0); setBit(2) }
        val verified = Bitfield(4).apply { setBit(0) }

        val original = AddTorrentParams(
            infoHashV1 = Digest32.fromHex("0123456789abcdef0123456789abcdef01234567"),
            name = "my torrent",
            savePath = "/home/user/Downloads",
            trackers = listOf("udp://tracker.one:80", "http://tracker.two/announce", "udp://backup:69"),
            trackerTiers = listOf(0, 0, 1),
            urlSeeds = listOf("https://seed.example/data/"),
            httpSeeds = listOf("http://httpseed.example/file"),
            peers = listOf(ep("1.2.3.4", 6881), ep("255.0.0.1", 51413)),
            bannedPeers = listOf(ep("9.9.9.9", 1234)),
            filePriorities = listOf(4, 0, 7, 1),
            piecePriorities = listOf(1, 4, 7, 0),
            havePieces = have,
            verifiedPieces = verified,
            flags = TorrentFlags.PAUSED or TorrentFlags.SEQUENTIAL_DOWNLOAD or TorrentFlags.SUPER_SEEDING,
            storageModeAllocate = true,
            maxUploads = 8,
            maxConnections = 200,
            uploadLimit = 12345,
            downloadLimit = 67890,
            totalUploaded = 9_000_000_000L,
            totalDownloaded = 4_000_000_000L,
            activeTime = 3600,
            finishedTime = 1800,
            seedingTime = 900,
            addedTime = 1_600_000_000L,
            completedTime = 1_600_100_000L,
            lastSeenComplete = 1_600_200_000L,
            numComplete = 12,
            numIncomplete = 34,
            numDownloaded = 56,
        )

        val back = ResumeData.read(ResumeData.write(original))

        // identity
        assertEquals(original.infoHashV1, back.infoHashV1)
        assertEquals("my torrent", back.name)
        assertEquals("/home/user/Downloads", back.savePath)

        // trackers (flattened with tiers)
        assertEquals(original.trackers, back.trackers)
        assertEquals(original.trackerTiers, back.trackerTiers)

        // web seeds
        assertEquals(original.urlSeeds, back.urlSeeds)
        assertEquals(original.httpSeeds, back.httpSeeds)

        // peers (v4)
        assertEquals(original.peers.toSet(), back.peers.toSet())
        assertEquals(original.bannedPeers.toSet(), back.bannedPeers.toSet())

        // priorities
        assertEquals(original.filePriorities, back.filePriorities)
        assertEquals(original.piecePriorities, back.piecePriorities)

        // progress
        assertEquals(have, back.havePieces)
        assertEquals(verified, back.verifiedPieces)

        // limits & stats
        assertEquals(8, back.maxUploads)
        assertEquals(200, back.maxConnections)
        assertEquals(12345, back.uploadLimit)
        assertEquals(67890, back.downloadLimit)
        assertEquals(9_000_000_000L, back.totalUploaded)
        assertEquals(4_000_000_000L, back.totalDownloaded)
        assertEquals(3600, back.activeTime)
        assertEquals(1800, back.finishedTime)
        assertEquals(900, back.seedingTime)
        assertEquals(1_600_000_000L, back.addedTime)
        assertEquals(1_600_100_000L, back.completedTime)
        assertEquals(1_600_200_000L, back.lastSeenComplete)
        assertEquals(12, back.numComplete)
        assertEquals(34, back.numIncomplete)
        assertEquals(56, back.numDownloaded)

        // storage mode
        assertTrue(back.storageModeAllocate)

        // flags we set survive...
        assertTrue(back.hasFlag(TorrentFlags.PAUSED))
        assertTrue(back.hasFlag(TorrentFlags.SEQUENTIAL_DOWNLOAD))
        assertTrue(back.hasFlag(TorrentFlags.SUPER_SEEDING))
        // ...and read_resume_data sets the override flags because trackers/web-seeds
        // were present, and clears need_save_resume + seed_mode (a dont_download file).
        assertTrue(back.hasFlag(TorrentFlags.OVERRIDE_TRACKERS))
        assertTrue(back.hasFlag(TorrentFlags.OVERRIDE_WEB_SEEDS))
        assertFalse(back.hasFlag(TorrentFlags.NEED_SAVE_RESUME))
        assertFalse(back.hasFlag(TorrentFlags.SEED_MODE))
    }

    // ---- pieces bit layout ---------------------------------------------------

    @Test
    fun pieces_string_encodes_have_and_verified_bits() {
        val have = Bitfield(5).apply { setBit(0); setBit(1); setBit(4) }
        val verified = Bitfield(5).apply { setBit(1); setBit(4) }
        val atp = AddTorrentParams(
            infoHashV1 = Digest32.zeros(20),
            havePieces = have,
            verifiedPieces = verified,
        )
        val rd = Bdecode.decode(ResumeData.write(atp))
        val pieces = rd.dictFindStringBytes("pieces")!!
        assertEquals(5, pieces.size)
        // bit0 = have, bit1 = verified
        assertEquals(0b01, pieces[0].toInt() and 0xFF) // have, not verified
        assertEquals(0b11, pieces[1].toInt() and 0xFF) // have + verified
        assertEquals(0b00, pieces[2].toInt() and 0xFF) // neither
        assertEquals(0b00, pieces[3].toInt() and 0xFF)
        assertEquals(0b11, pieces[4].toInt() and 0xFF)
    }

    // ---- peer wire format ----------------------------------------------------

    @Test
    fun peers_split_into_v4_and_v6_blobs() {
        val atp = AddTorrentParams(
            infoHashV1 = Digest32.zeros(20),
            peers = listOf(ep("10.0.0.5", 6881), ep("::1", 6882), ep("192.168.1.1", 6883)),
        )
        val rd = Bdecode.decode(ResumeData.write(atp))

        val v4 = rd.dictFindStringBytes("peers")!!
        val v6 = rd.dictFindStringBytes("peers6")!!
        assertEquals(2 * 6, v4.size) // two IPv4 endpoints, 6 bytes each
        assertEquals(1 * 18, v6.size) // one IPv6 endpoint, 18 bytes

        // first v4 peer is 10.0.0.5:6881
        assertContentEquals(byteArrayOf(10, 0, 0, 5), v4.copyOfRange(0, 4))
        assertEquals(6881, ((v4[4].toInt() and 0xFF) shl 8) or (v4[5].toInt() and 0xFF))

        // and it all comes back
        val back = ResumeData.read(ResumeData.write(atp))
        assertEquals(atp.peers.toSet(), back.peers.toSet())
    }

    // ---- embedded info dict --------------------------------------------------

    @Test
    fun embeds_and_recovers_info_dictionary() {
        val torrentBuffer = singleFileTorrentBuffer()
        val info = infoSectionOf(torrentBuffer)
        val ti = TorrentInfo.parse(torrentBuffer)

        val atp = AddTorrentParams(
            ti = ti,
            infoSection = info,
            infoHashV1 = ti.infoHashV1,
            savePath = "/data",
        )

        val rd = Bdecode.decode(ResumeData.write(atp))
        // the info dict is embedded verbatim
        assertContentEquals(info, rd.dictFindDict("info").dataSection())
        // metadata extras came along
        assertEquals("hello world", rd.dictFindStringValue("comment"))
        assertEquals("KiteTorrent test", rd.dictFindStringValue("created by"))

        val back = ResumeData.read(ResumeData.write(atp))
        assertNotNull(back.ti, "info dict should have been parsed back into a TorrentInfo")
        assertEquals(ti.infoHashV1, back.ti!!.infoHashV1)
        assertEquals("a", back.ti!!.name)
        assertEquals(51200L, back.ti!!.totalSize)
        assertContentEquals(info, back.infoSection)
        assertEquals("/data", back.savePath)
    }

    @Test
    fun mismatching_embedded_info_hash_is_rejected() {
        val torrentBuffer = singleFileTorrentBuffer()
        val info = infoSectionOf(torrentBuffer)

        // claim a v1 hash that does NOT match the embedded info dict.
        val atp = AddTorrentParams(
            infoSection = info,
            infoHashV1 = Digest32.fromHex("ffffffffffffffffffffffffffffffffffffffff"),
            savePath = "/data",
        )
        // write() emits the bogus hash next to the real info dict; read() must reject it.
        val ex = assertFailsWith<TorrentException> { ResumeData.read(ResumeData.write(atp)) }
        assertEquals(LibtorrentError.MISMATCHING_INFO_HASH, ex.error)
    }

    // ---- error handling ------------------------------------------------------

    @Test
    fun rejects_non_dictionary() {
        val notADict = Bencode.encode(Entry.of(42L))
        val ex = assertFailsWith<TorrentException> { ResumeData.read(notADict) }
        assertEquals(LibtorrentError.NOT_A_DICTIONARY, ex.error)
    }

    @Test
    fun rejects_wrong_file_format_tag() {
        val d = Entry.dict()
        d.dict()["file-format"] = Entry.of("something else")
        d.dict()["info-hash"] = Entry.of(Digest32.zeros(20).toByteArray())
        val ex = assertFailsWith<TorrentException> { ResumeData.read(Bencode.encode(d)) }
        assertEquals(LibtorrentError.INVALID_FILE_TAG, ex.error)
    }

    @Test
    fun rejects_missing_info_hash() {
        val d = Entry.dict()
        d.dict()["file-format"] = Entry.of("libtorrent resume file")
        // no info-hash / info-hash2 at all
        val ex = assertFailsWith<TorrentException> { ResumeData.read(Bencode.encode(d)) }
        assertEquals(LibtorrentError.MISSING_INFO_HASH, ex.error)
    }

    // ---- defaults & flag semantics ------------------------------------------

    @Test
    fun minimal_atp_round_trips_with_default_scrape_and_limits() {
        val atp = AddTorrentParams(infoHashV1 = Digest32.zeros(20), savePath = "/x")
        val back = ResumeData.read(ResumeData.write(atp))

        // unknown scrape counts and unlimited rate/connection limits default to -1
        assertEquals(-1, back.numComplete)
        assertEquals(-1, back.numIncomplete)
        assertEquals(-1, back.numDownloaded)
        assertEquals(-1, back.maxUploads)
        assertEquals(-1, back.maxConnections)
        assertEquals(-1, back.uploadLimit)
        assertEquals(-1, back.downloadLimit)
        assertEquals("/x", back.savePath)

        // write_resume_data always emits (possibly empty) `trackers`, `url-list` and
        // `httpseeds` keys, and read_resume_data sets the override flags whenever those
        // keys are *present*, even when empty. So a faithful round-trip sets both override
        // flags. (This is libtorrent's own behavior, supporting "explicitly removed".)
        assertTrue(back.hasFlag(TorrentFlags.OVERRIDE_TRACKERS))
        assertTrue(back.hasFlag(TorrentFlags.OVERRIDE_WEB_SEEDS))
        // need_save_resume always cleared by read
        assertFalse(back.hasFlag(TorrentFlags.NEED_SAVE_RESUME))
        // a default-flags atp keeps auto_managed/paused/apply_ip_filter across the trip
        assertTrue(back.hasFlag(TorrentFlags.AUTO_MANAGED))
        assertTrue(back.hasFlag(TorrentFlags.PAUSED))
        assertTrue(back.hasFlag(TorrentFlags.APPLY_IP_FILTER))
    }

    @Test
    fun renamed_files_and_unfinished_pieces_round_trip() {
        val unfinished = mapOf(
            2 to Bitfield(8).apply { setBit(0); setBit(3) },
            5 to Bitfield(4).apply { setBit(1) },
        )
        val atp = AddTorrentParams(
            infoHashV1 = Digest32.zeros(20),
            renamedFiles = mapOf(0 to "renamed-a.txt", 2 to "renamed-c.txt"),
            unfinishedPieces = unfinished,
        )
        val back = ResumeData.read(ResumeData.write(atp))

        assertEquals("renamed-a.txt", back.renamedFiles[0])
        assertEquals("renamed-c.txt", back.renamedFiles[2])
        assertNull(back.renamedFiles[1]) // the gap is an empty string ⇒ dropped on read

        assertEquals(setOf(2, 5), back.unfinishedPieces.keys)
        // bitmask survives at byte granularity: piece 2 has blocks 0 and 3 set
        assertTrue(back.unfinishedPieces.getValue(2).getBit(0))
        assertTrue(back.unfinishedPieces.getValue(2).getBit(3))
        assertFalse(back.unfinishedPieces.getValue(2).getBit(1))
    }

    @Test
    fun info_hash_v2_round_trips() {
        val v2 = Digest32.fromHex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
        val atp = AddTorrentParams(infoHashV2 = v2, savePath = "/v2")
        val rd = Bdecode.decode(ResumeData.write(atp))
        assertEquals(32, rd.dictFindStringBytes("info-hash2")!!.size)

        val back = ResumeData.read(ResumeData.write(atp))
        assertEquals(v2, back.infoHashV2)
        assertNull(back.infoHashV1)
    }
}
