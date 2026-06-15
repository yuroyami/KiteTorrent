package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.alert.AddTorrentAlert
import io.github.yuroyami.kitetorrent.alert.Alert
import io.github.yuroyami.kitetorrent.alert.AlertCategory
import io.github.yuroyami.kitetorrent.alert.DhtAnnounceAlert
import io.github.yuroyami.kitetorrent.alert.DhtBootstrapAlert
import io.github.yuroyami.kitetorrent.alert.ExternalIpAlert
import io.github.yuroyami.kitetorrent.alert.FileErrorAlert
import io.github.yuroyami.kitetorrent.alert.HashFailedAlert
import io.github.yuroyami.kitetorrent.alert.ListenSucceededAlert
import io.github.yuroyami.kitetorrent.alert.PeerConnectAlert
import io.github.yuroyami.kitetorrent.alert.PeerConnectDirection
import io.github.yuroyami.kitetorrent.alert.PerformanceAlert
import io.github.yuroyami.kitetorrent.alert.PerformanceWarning
import io.github.yuroyami.kitetorrent.alert.PieceFinishedAlert
import io.github.yuroyami.kitetorrent.alert.ProtocolVersion
import io.github.yuroyami.kitetorrent.alert.SaveResumeDataAlert
import io.github.yuroyami.kitetorrent.alert.SessionStatsAlert
import io.github.yuroyami.kitetorrent.alert.StateChangedAlert
import io.github.yuroyami.kitetorrent.alert.TorrentErrorAlert
import io.github.yuroyami.kitetorrent.alert.TorrentFinishedAlert
import io.github.yuroyami.kitetorrent.alert.TorrentState
import io.github.yuroyami.kitetorrent.alert.TrackerAnnounceAlert
import io.github.yuroyami.kitetorrent.alert.TrackerErrorAlert
import io.github.yuroyami.kitetorrent.alert.TrackerEvent
import io.github.yuroyami.kitetorrent.alert.TrackerReplyAlert
import io.github.yuroyami.kitetorrent.crypto.Sha1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the alert notification model ([Alert] and the concrete alert types).
 *
 * The ground truth here is libtorrent's own behaviour: the integer
 * `alert_type` constants from the `TORRENT_DEFINE_ALERT(...)` macros in
 * include/libtorrent/alert_types.hpp, the short `what()` names from the
 * `alert_name()` table in src/alert.cpp, and the exact `message()` text each
 * alert's `message()` method renders in src/alert.cpp. All expected strings
 * below are reproduced verbatim from those C++ implementations, including the
 * characteristic double space that appears when the torrent handle is invalid
 * (`torrent_alert::message()` returns `" - "`).
 */
class AlertTest {

    // --- type constants are stable and match libtorrent's `seq` values --------

    @Test
    fun alertTypeConstantsMatchLibtorrent() {
        assertEquals(9, PerformanceAlert.ALERT_TYPE)
        assertEquals(10, StateChangedAlert.ALERT_TYPE)
        assertEquals(11, TrackerErrorAlert.ALERT_TYPE)
        assertEquals(15, TrackerReplyAlert.ALERT_TYPE)
        assertEquals(17, TrackerAnnounceAlert.ALERT_TYPE)
        assertEquals(18, HashFailedAlert.ALERT_TYPE)
        assertEquals(23, PeerConnectAlert.ALERT_TYPE)
        assertEquals(26, TorrentFinishedAlert.ALERT_TYPE)
        assertEquals(27, PieceFinishedAlert.ALERT_TYPE)
        assertEquals(37, SaveResumeDataAlert.ALERT_TYPE)
        assertEquals(43, FileErrorAlert.ALERT_TYPE)
        assertEquals(47, ExternalIpAlert.ALERT_TYPE)
        assertEquals(49, ListenSucceededAlert.ALERT_TYPE)
        assertEquals(55, DhtAnnounceAlert.ALERT_TYPE)
        assertEquals(62, DhtBootstrapAlert.ALERT_TYPE)
        assertEquals(64, TorrentErrorAlert.ALERT_TYPE)
        assertEquals(67, AddTorrentAlert.ALERT_TYPE)
        assertEquals(70, SessionStatsAlert.ALERT_TYPE)
    }

    // --- the instance `type` mirrors the companion constant -------------------

    @Test
    fun instanceTypeMatchesCompanion() {
        val a: Alert = TorrentFinishedAlert("x")
        assertEquals(TorrentFinishedAlert.ALERT_TYPE, a.type)
        assertEquals(26, a.type)
    }

    // --- `what()` short names match the alert_name() table --------------------

    @Test
    fun whatNamesMatchLibtorrent() {
        assertEquals("torrent_finished", TorrentFinishedAlert("x").what)
        assertEquals("state_changed", StateChangedAlert("x", TorrentState.SEEDING, TorrentState.FINISHED).what)
        assertEquals("piece_finished", PieceFinishedAlert("x", 0).what)
        assertEquals("hash_failed", HashFailedAlert("x", 0).what)
        assertEquals("peer_connect", PeerConnectAlert("x", "1.2.3.4:1", direction = PeerConnectDirection.IN).what)
        assertEquals("tracker_announce", TrackerAnnounceAlert("x", "u", "e", TrackerEvent.NONE).what)
        assertEquals("listen_succeeded", ListenSucceededAlert("127.0.0.1", 1).what)
        assertEquals("dht_bootstrap", DhtBootstrapAlert().what)
        assertEquals("session_stats", SessionStatsAlert(longArrayOf()).what)
    }

    // --- message() text, reproduced verbatim from src/alert.cpp ---------------

    @Test
    fun torrentFinishedMessage() {
        assertEquals(
            "My Torrent torrent finished downloading",
            TorrentFinishedAlert("My Torrent").message(),
        )
        // invalid handle -> torrent_alert::message() == " - " (note the double space)
        assertEquals(
            " -  torrent finished downloading",
            TorrentFinishedAlert(null).message(),
        )
    }

    @Test
    fun stateChangedMessage() {
        val a = StateChangedAlert("T", TorrentState.DOWNLOADING, TorrentState.CHECKING_FILES)
        assertEquals("T: state changed to: downloading", a.message())
    }

    @Test
    fun pieceAndHashMessages() {
        assertEquals("T piece: 42 finished downloading", PieceFinishedAlert("T", 42).message())
        assertEquals("T hash for piece 7 failed", HashFailedAlert("T", 7).message())
    }

    @Test
    fun peerConnectMessageBuildsOnPeerPrefix() {
        val a = PeerConnectAlert(
            torrentName = "T",
            endpoint = "1.2.3.4:6881",
            direction = PeerConnectDirection.OUT,
            socketType = "TCP",
        )
        assertEquals(
            "T peer [ 1.2.3.4:6881 client: Unknown ] outgoing connection to peer (TCP)",
            a.message(),
        )
    }

    @Test
    fun trackerAnnounceMessageBuildsOnTrackerPrefix() {
        val a = TrackerAnnounceAlert(
            torrentName = "T",
            trackerUrl = "http://tr/announce",
            localEndpoint = "0.0.0.0:0",
            event = TrackerEvent.STARTED,
            version = ProtocolVersion.V1,
        )
        assertEquals(
            "T (http://tr/announce)[0.0.0.0:0] v1 sending announce (started)",
            a.message(),
        )
    }

    @Test
    fun listenSucceededMessage() {
        assertEquals(
            "successfully listening on [TCP] 127.0.0.1:6881",
            ListenSucceededAlert("127.0.0.1", 6881, "TCP").message(),
        )
        // IPv6 literal gets bracketed by formatEndpoint, like print_endpoint
        assertEquals(
            "successfully listening on [TCP] [::1]:6881",
            ListenSucceededAlert("::1", 6881, "TCP").message(),
        )
    }

    @Test
    fun dhtBootstrapMessageIsConstant() {
        assertEquals("DHT bootstrap complete", DhtBootstrapAlert().message())
    }

    @Test
    fun sessionStatsMessageJoinsValues() {
        assertEquals(
            "session stats (3 values): 1, 2, 3",
            SessionStatsAlert(longArrayOf(1, 2, 3)).message(),
        )
        assertEquals(
            "session stats (0 values): ",
            SessionStatsAlert(longArrayOf()).message(),
        )
    }

    @Test
    fun saveResumeDataMessage() {
        assertEquals("T resume data generated", SaveResumeDataAlert("T").message())
    }

    @Test
    fun fileErrorMessage() {
        val a = FileErrorAlert(
            torrentName = "T",
            operation = "file_write",
            filename = "/path/to/file.dat",
            errorMessage = "No such file or directory",
        )
        assertEquals(
            "T file_write (/path/to/file.dat) error: No such file or directory",
            a.message(),
        )
    }

    @Test
    fun addTorrentMessageSuccessAndFailure() {
        assertEquals("added torrent: Ubuntu", AddTorrentAlert("Ubuntu").message())
        assertEquals(
            "failed to add torrent \"Ubuntu\": [libtorrent] duplicate torrent",
            AddTorrentAlert("Ubuntu", errorMessage = "duplicate torrent").message(),
        )
    }

    @Test
    fun torrentErrorMessageWithAndWithoutCode() {
        assertEquals(
            "T ERROR: (2 hash check failed) f.dat",
            TorrentErrorAlert("T", errorValue = 2, errorMessage = "hash check failed", filename = "f.dat").message(),
        )
        assertEquals(
            "T ERROR: f.dat",
            TorrentErrorAlert("T", filename = "f.dat").message(),
        )
    }

    @Test
    fun dhtAnnounceMessageContainsHexInfoHash() {
        val ih = Digest32.sha1(Sha1.hash("hello".encodeToByteArray()))
        val a = DhtAnnounceAlert("1.2.3.4", 6881, ih)
        assertEquals("incoming dht announce: 1.2.3.4:6881 (${ih.toHex()})", a.message())
        // sanity: hash hex is lowercase and 40 chars
        assertEquals(40, ih.toHex().length)
        assertTrue(ih.toHex().all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun performanceWarningLabel() {
        val a = PerformanceAlert("T", PerformanceWarning.UPLOAD_LIMIT_TOO_LOW)
        assertEquals(
            "T: performance warning: upload limit too low (download rate will suffer)",
            a.message(),
        )
    }

    // --- category bitmask values match libtorrent's alert_category bits -------

    @Test
    fun categoryBitsAreStable() {
        assertEquals(1 shl 0, AlertCategory.error)
        assertEquals(1 shl 1, AlertCategory.peer)
        assertEquals(1 shl 3, AlertCategory.storage)
        assertEquals(1 shl 4, AlertCategory.tracker)
        assertEquals(1 shl 5, AlertCategory.connect)
        assertEquals(1 shl 6, AlertCategory.status)
        assertEquals(1 shl 10, AlertCategory.dht)
        assertEquals(1 shl 11, AlertCategory.stats)
        assertEquals(-1, AlertCategory.all)
    }

    @Test
    fun categoryOfEachAlertMatchesStaticCategory() {
        assertEquals(AlertCategory.status, TorrentFinishedAlert("x").category)
        assertEquals(AlertCategory.pieceProgress, PieceFinishedAlert("x", 0).category)
        assertEquals(AlertCategory.connect, PeerConnectAlert("x", "1:1", direction = PeerConnectDirection.IN).category)
        assertEquals(AlertCategory.tracker, TrackerAnnounceAlert("x", "u", "e", TrackerEvent.NONE).category)
        assertEquals(AlertCategory.dht, DhtBootstrapAlert().category)
        assertEquals(AlertCategory.stats, SessionStatsAlert(longArrayOf()).category)
        // combined categories use bit-or
        assertEquals(
            AlertCategory.error or AlertCategory.status,
            TorrentErrorAlert("x").category,
        )
        assertEquals(
            AlertCategory.status or AlertCategory.error or AlertCategory.storage,
            FileErrorAlert("x", "op", "f", "e").category,
        )
        // membership test (how the alert_mask is used)
        assertTrue(TorrentErrorAlert("x").category and AlertCategory.error != 0)
    }
}
