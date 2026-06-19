package io.github.yuroyami.kitetorrent.alert

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.torrent.resume.AddTorrentParams

/**
 * Concrete torrent status/storage/error alerts ported from
 * include/libtorrent/alert_types.hpp (struct bodies) and src/alert.cpp (the
 * `message()` text for each). All `ALERT_TYPE` constants are the exact `seq`
 * values from the `TORRENT_DEFINE_ALERT(...)` macros and are part of the ABI.
 */

/**
 * Posted every time a torrent is successfully added (or fails to be added),
 * carrying the result of the add operation. Ported from
 * `libtorrent::add_torrent_alert` (alert_type 67).
 *
 * libtorrent renders `"added torrent: <name>"` on success, or
 * `"failed to add torrent \"<name>\": [<category>] <error>"` on failure.
 *
 * @property torrentName the torrent name (falls back to the info-hash hex in C++).
 * @property errorMessage the error description, or `null`/empty on success.
 * @property errorCategory the error category name (e.g. `"libtorrent"`), for the failure text.
 */
data class AddTorrentAlert(
    override val torrentName: String?,
    val errorMessage: String? = null,
    val errorCategory: String = "libtorrent",
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "add_torrent"
    override val category: Int get() = AlertCategory.status

    /** A printable torrent name, mirroring the C++ fallback to the info-hash. */
    private fun nameOrDash(): String = torrentName ?: "-"

    override fun message(): String =
        if (!errorMessage.isNullOrEmpty())
            "failed to add torrent \"${nameOrDash()}\": [$errorCategory] $errorMessage"
        else
            "added torrent: ${nameOrDash()}"

    companion object { const val ALERT_TYPE: Int = 67 }
}

/**
 * Posted whenever a torrent is removed. The torrent handle is usually already
 * invalid, so the info-hash identifies it. Ported from
 * `libtorrent::torrent_removed_alert` (alert_type 4).
 *
 * @property infoHash the v1 info-hash of the removed torrent (`info_hashes.get_best()`).
 */
data class TorrentRemovedAlert(
    override val torrentName: String?,
    val infoHash: Sha1Hash,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "torrent_removed"
    override val category: Int get() = AlertCategory.status

    override fun message(): String = torrentPrefix() + " removed"

    companion object { const val ALERT_TYPE: Int = 4 }
}

/**
 * Posted when a torrent transitions from downloader to seed. Generated at most
 * once per torrent. Ported from `libtorrent::torrent_finished_alert`
 * (alert_type 26).
 */
data class TorrentFinishedAlert(
    override val torrentName: String?,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "torrent_finished"
    override val category: Int get() = AlertCategory.status

    override fun message(): String = torrentPrefix() + " torrent finished downloading"

    companion object { const val ALERT_TYPE: Int = 26 }
}

/**
 * Posted whenever a torrent is transitioned into the error state. Ported from
 * `libtorrent::torrent_error_alert` (alert_type 64).
 *
 * libtorrent renders `"<name> ERROR: (<value> <message>) <filename>"` when an
 * error code is set, or `"<name> ERROR: <filename>"` otherwise.
 *
 * @property errorValue the libtorrent error value (`error.value()`), or `0` if unset.
 * @property errorMessage the error description, or `null`/empty if unset.
 * @property filename the file (or object) the error occurred on; may be empty.
 */
data class TorrentErrorAlert(
    override val torrentName: String?,
    val errorValue: Int = 0,
    val errorMessage: String? = null,
    val filename: String = "",
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "torrent_error"
    override val category: Int get() = AlertCategory.error or AlertCategory.status

    override fun message(): String {
        val tail = if (!errorMessage.isNullOrEmpty())
            " ERROR: ($errorValue $errorMessage) $filename"
        else
            " ERROR: $filename"
        return torrentPrefix() + tail
    }

    companion object { const val ALERT_TYPE: Int = 64 }
}

/**
 * Generated whenever a torrent changes its state. Ported from
 * `libtorrent::state_changed_alert` (alert_type 10).
 *
 * @property state the new state.
 * @property prevState the previous state.
 */
data class StateChangedAlert(
    override val torrentName: String?,
    val state: TorrentState,
    val prevState: TorrentState,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "state_changed"
    override val category: Int get() = AlertCategory.status

    override fun message(): String =
        torrentPrefix() + ": state changed to: " + state.label

    companion object { const val ALERT_TYPE: Int = 10 }
}

/**
 * Generated when a finished piece fails its hash check. Ported from
 * `libtorrent::hash_failed_alert` (alert_type 18).
 *
 * @property pieceIndex the index of the piece that failed.
 */
data class HashFailedAlert(
    override val torrentName: String?,
    val pieceIndex: Int,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "hash_failed"
    override val category: Int get() = AlertCategory.status

    override fun message(): String =
        torrentPrefix() + " hash for piece $pieceIndex failed"

    companion object { const val ALERT_TYPE: Int = 18 }
}

/**
 * Posted every time a piece completes downloading and passes the hash check.
 * Ported from `libtorrent::piece_finished_alert` (alert_type 27).
 *
 * @property pieceIndex the index of the piece that finished.
 */
data class PieceFinishedAlert(
    override val torrentName: String?,
    val pieceIndex: Int,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "piece_finished"
    override val category: Int get() = AlertCategory.pieceProgress

    override fun message(): String =
        torrentPrefix() + " piece: $pieceIndex finished downloading"

    companion object { const val ALERT_TYPE: Int = 27 }
}

/**
 * Posted when an individual file completes downloading (all overlapping pieces
 * have passed their hash check). Ported from `libtorrent::file_completed_alert`
 * (alert_type 6).
 *
 * @property index the index of the file that completed.
 */
data class FileCompletedAlert(
    override val torrentName: String?,
    val index: Int,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "file_completed"
    override val category: Int get() = AlertCategory.fileProgress

    override fun message(): String =
        torrentPrefix() + ": file $index finished downloading"

    companion object { const val ALERT_TYPE: Int = 6 }
}

/**
 * Generated when the storage fails to read or write a file; the torrent is
 * paused. Ported from `libtorrent::file_error_alert` (alert_type 43).
 *
 * libtorrent renders `"<name> <operation> (<filename>) error: <message>"`.
 *
 * @property operation the underlying operation name (from `operation_name(op)`).
 * @property filename the file that experienced the error.
 * @property errorMessage the error description.
 */
data class FileErrorAlert(
    override val torrentName: String?,
    val operation: String,
    val filename: String,
    val errorMessage: String,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "file_error"
    override val category: Int
        get() = AlertCategory.status or AlertCategory.error or AlertCategory.storage

    override fun message(): String =
        torrentPrefix() + " " + operation + " (" + filename + ") error: " + errorMessage

    companion object { const val ALERT_TYPE: Int = 43 }
}

/**
 * Generated when metadata has been fully received and can start downloading.
 * Only fires for torrents that fetch metadata from peers. Ported from
 * `libtorrent::metadata_received_alert` (alert_type 45).
 */
data class MetadataReceivedAlert(
    override val torrentName: String?,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "metadata_received"
    override val category: Int get() = AlertCategory.status

    override fun message(): String = torrentPrefix() + " metadata successfully received"

    companion object { const val ALERT_TYPE: Int = 45 }
}

/**
 * Generated when received metadata fails to match the info-hash (corrupt
 * metadata); libtorrent retries automatically. Ported from
 * `libtorrent::metadata_failed_alert` (alert_type 44).
 */
data class MetadataFailedAlert(
    override val torrentName: String?,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "metadata_failed"
    override val category: Int get() = AlertCategory.error

    override fun message(): String = torrentPrefix() + " invalid metadata received"

    companion object { const val ALERT_TYPE: Int = 44 }
}

/**
 * Generated as a response to `save_resume_data()` once the resume state has been
 * written. Ported from `libtorrent::save_resume_data_alert` (alert_type 37).
 *
 * In libtorrent this carries the populated `add_torrent_params` (the `params`
 * member); KiteTorrent reproduces that with [params], the resume-data
 * [AddTorrentParams] from which `write_resume_data()` round-trips. Clients hand
 * this object (or its serialised form) back to `add_torrent()` to restore the
 * torrent. This alert is non-discardable.
 *
 * @property params the resume payload describing how to re-add this torrent.
 */
data class SaveResumeDataAlert(
    override val torrentName: String?,
    val params: AddTorrentParams = AddTorrentParams(),
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "save_resume_data"
    override val category: Int get() = AlertCategory.storage

    override fun message(): String = torrentPrefix() + " resume data generated"

    companion object { const val ALERT_TYPE: Int = 37 }
}

/**
 * Generated instead of [SaveResumeDataAlert] when resume-data generation failed.
 * Ported from `libtorrent::save_resume_data_failed_alert` (alert_type 38).
 *
 * @property errorMessage the description of why resume data was not generated.
 */
data class SaveResumeDataFailedAlert(
    override val torrentName: String?,
    val errorMessage: String,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "save_resume_data_failed"
    override val category: Int get() = AlertCategory.storage or AlertCategory.error

    override fun message(): String =
        torrentPrefix() + " resume data was not generated: " + errorMessage

    companion object { const val ALERT_TYPE: Int = 38 }
}

/**
 * Posted in response to `read_piece()` once the asynchronous read completes.
 * Ported from `libtorrent::read_piece_alert` (alert_type 5). Non-discardable.
 *
 * On success [buffer] holds the full piece payload and [errorMessage] is `null`;
 * on failure the torrent is paused, [buffer] is `null` and [errorMessage]
 * describes what went wrong. libtorrent renders
 * `<name>: read_piece <piece> successful` or `<name>: read_piece <piece> failed: <error>`.
 *
 * @property pieceIndex the index of the piece that was read.
 * @property buffer the piece data on success, or `null` on failure.
 * @property size the number of bytes read (the piece size on success).
 * @property errorMessage the failure description, or `null` on success.
 */
data class ReadPieceAlert(
    override val torrentName: String?,
    val pieceIndex: Int,
    val buffer: ByteArray? = null,
    val size: Int = 0,
    val errorMessage: String? = null,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "read_piece"
    override val category: Int get() = AlertCategory.storage

    override fun message(): String =
        if (!errorMessage.isNullOrEmpty())
            torrentPrefix() + ": read_piece " + pieceIndex + " failed: " + errorMessage
        else
            torrentPrefix() + ": read_piece " + pieceIndex + " successful"

    // [buffer] is a ByteArray, so override structural equals/hashCode like other
    // byte-array-carrying data classes in this catalogue.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReadPieceAlert) return false
        return torrentName == other.torrentName && pieceIndex == other.pieceIndex &&
            size == other.size && errorMessage == other.errorMessage &&
            contentEqualsNullable(buffer, other.buffer)
    }

    override fun hashCode(): Int {
        var h = torrentName?.hashCode() ?: 0
        h = 31 * h + pieceIndex
        h = 31 * h + size
        h = 31 * h + (errorMessage?.hashCode() ?: 0)
        h = 31 * h + (buffer?.contentHashCode() ?: 0)
        return h
    }

    companion object {
        const val ALERT_TYPE: Int = 5

        private fun contentEqualsNullable(a: ByteArray?, b: ByteArray?): Boolean =
            if (a == null || b == null) a === b else a.contentEquals(b)
    }
}

/**
 * A minimal snapshot of a torrent's runtime state, the pure-Kotlin stand-in for
 * the slice of `libtorrent::torrent_status` (include/libtorrent/torrent_status.hpp)
 * that [StateUpdateAlert] carries. Only the headline fields clients typically poll
 * are modelled; the full C++ struct has many more.
 *
 * @property infoHash the v1 info-hash identifying the torrent.
 * @property state the torrent's current state.
 * @property progress fraction of wanted data downloaded, 0.0..1.0 (`torrent_status::progress`).
 * @property totalDone bytes of wanted data downloaded and verified (`total_done`).
 * @property downloadRate current payload download rate in bytes/sec (`download_payload_rate`).
 * @property uploadRate current payload upload rate in bytes/sec (`upload_payload_rate`).
 * @property numPeers number of connected peers (`num_peers`).
 * @property numSeeds number of connected peers that are seeds (`num_seeds`).
 */
data class TorrentStatus(
    val infoHash: Sha1Hash,
    val state: TorrentState,
    val progress: Float = 0f,
    val totalDone: Long = 0L,
    val downloadRate: Int = 0,
    val uploadRate: Int = 0,
    val numPeers: Int = 0,
    val numSeeds: Int = 0,
)

/**
 * Posted in response to `post_torrent_updates()`, carrying the status of every
 * torrent that changed since the last such post. Ported from
 * `libtorrent::state_update_alert` (alert_type 68). Not subject to alert-mask
 * filtering (it is only ever posted on request).
 *
 * libtorrent renders `state updates for <n> torrents`.
 *
 * @property status the changed torrents' status snapshots.
 */
data class StateUpdateAlert(
    val status: List<TorrentStatus>,
) : Alert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "state_update"
    override val category: Int get() = AlertCategory.status

    override fun message(): String = "state updates for " + status.size + " torrents"

    companion object { const val ALERT_TYPE: Int = 68 }
}
