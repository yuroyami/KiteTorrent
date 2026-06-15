package io.github.yuroyami.kitetorrent.alert

import io.github.yuroyami.kitetorrent.Sha1Hash

/**
 * Additional torrent lifecycle / storage alerts ported from
 * include/libtorrent/alert_types.hpp and src/alert.cpp. These round out the
 * common set ([TorrentStatusAlerts.kt] holds the headline status alerts). All
 * extend [TorrentAlert].
 */

/**
 * Generated as a response to `pause()` once all disk IO is complete and files
 * are closed. Ported from `libtorrent::torrent_paused_alert` (alert_type 39).
 */
data class TorrentPausedAlert(
    override val torrentName: String?,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "torrent_paused"
    override val category: Int get() = AlertCategory.status

    override fun message(): String = torrentPrefix() + " paused"

    companion object { const val ALERT_TYPE: Int = 39 }
}

/**
 * Generated as a response to `resume()` when a torrent goes from paused to
 * active. Ported from `libtorrent::torrent_resumed_alert` (alert_type 40).
 */
data class TorrentResumedAlert(
    override val torrentName: String?,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "torrent_resumed"
    override val category: Int get() = AlertCategory.status

    override fun message(): String = torrentPrefix() + " resumed"

    companion object { const val ALERT_TYPE: Int = 40 }
}

/**
 * Posted when a torrent finishes checking and is ready to start downloading.
 * Ported from `libtorrent::torrent_checked_alert` (alert_type 41).
 */
data class TorrentCheckedAlert(
    override val torrentName: String?,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "torrent_checked"
    override val category: Int get() = AlertCategory.status

    override fun message(): String = torrentPrefix() + " checked"

    companion object { const val ALERT_TYPE: Int = 41 }
}

/**
 * Generated when a request to delete a torrent's files completes. Ported from
 * `libtorrent::torrent_deleted_alert` (alert_type 35).
 *
 * @property infoHash the v1 info-hash of the deleted torrent (`info_hashes.get_best()`).
 */
data class TorrentDeletedAlert(
    override val torrentName: String?,
    val infoHash: Sha1Hash,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "torrent_deleted"
    override val category: Int get() = AlertCategory.storage

    override fun message(): String = torrentPrefix() + " deleted"

    companion object { const val ALERT_TYPE: Int = 35 }
}

/**
 * Generated when a request to delete a torrent's files fails. Ported from
 * `libtorrent::torrent_delete_failed_alert` (alert_type 36).
 *
 * @property errorMessage the description of why deletion failed.
 * @property infoHash the v1 info-hash of the torrent whose files failed to delete.
 */
data class TorrentDeleteFailedAlert(
    override val torrentName: String?,
    val errorMessage: String,
    val infoHash: Sha1Hash,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "torrent_delete_failed"
    override val category: Int get() = AlertCategory.storage or AlertCategory.error

    override fun message(): String =
        torrentPrefix() + " torrent deletion failed: " + errorMessage

    companion object { const val ALERT_TYPE: Int = 36 }
}

/**
 * Generated when all disk IO has completed and a torrent's files have been moved
 * (after `move_storage()`). Ported from `libtorrent::storage_moved_alert`
 * (alert_type 33).
 *
 * libtorrent renders `<name> moved storage from "<oldPath>" to: "<newPath>"`.
 *
 * @property newPath the path the storage was moved to.
 * @property oldPath the path the storage was moved from.
 */
data class StorageMovedAlert(
    override val torrentName: String?,
    val newPath: String,
    val oldPath: String,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "storage_moved"
    override val category: Int get() = AlertCategory.storage

    override fun message(): String =
        torrentPrefix() + " moved storage from \"" + oldPath + "\" to: \"" + newPath + "\""

    companion object { const val ALERT_TYPE: Int = 33 }
}

/**
 * Generated when an attempt to move a torrent's storage fails. Ported from
 * `libtorrent::storage_moved_failed_alert` (alert_type 34).
 *
 * libtorrent renders `<name> storage move failed. <operation> (<filePath>): <errorMessage>`.
 *
 * @property operation the underlying operation that failed (`operation_name`).
 * @property filePath the file the error happened on, if any.
 * @property errorMessage the error description.
 */
data class StorageMovedFailedAlert(
    override val torrentName: String?,
    val operation: String,
    val filePath: String,
    val errorMessage: String,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "storage_moved_failed"
    override val category: Int get() = AlertCategory.storage

    override fun message(): String =
        torrentPrefix() + " storage move failed. " + operation + " (" + filePath + "): " + errorMessage

    companion object { const val ALERT_TYPE: Int = 34 }
}

/**
 * Posted in response to `rename_file()` when the rename succeeds. Ported from
 * `libtorrent::file_renamed_alert` (alert_type 7).
 *
 * libtorrent renders `<name>: file <index> renamed from "<oldName>" to "<newName>"`.
 *
 * @property index the index of the file that was renamed.
 * @property newName the new file name.
 * @property oldName the previous file name.
 */
data class FileRenamedAlert(
    override val torrentName: String?,
    val index: Int,
    val newName: String,
    val oldName: String,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "file_renamed"
    override val category: Int get() = AlertCategory.storage

    override fun message(): String =
        torrentPrefix() + ": file " + index + " renamed from \"" + oldName + "\" to \"" + newName + "\""

    companion object { const val ALERT_TYPE: Int = 7 }
}

/**
 * Posted in response to `rename_file()` when the rename fails. Ported from
 * `libtorrent::file_rename_failed_alert` (alert_type 8).
 *
 * libtorrent renders `<name>: failed to rename file <index>: <errorMessage>`.
 *
 * @property index the index of the file that was to be renamed.
 * @property errorMessage the filesystem error description.
 */
data class FileRenameFailedAlert(
    override val torrentName: String?,
    val index: Int,
    val errorMessage: String,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "file_rename_failed"
    override val category: Int get() = AlertCategory.storage

    override fun message(): String =
        torrentPrefix() + ": failed to rename file " + index + ": " + errorMessage

    companion object { const val ALERT_TYPE: Int = 8 }
}

/**
 * Generated when a fast-resume file is passed to `add_torrent()` but the on-disk
 * files do not match it. Ported from `libtorrent::fastresume_rejected_alert`
 * (alert_type 53).
 *
 * libtorrent renders `<name> fast resume rejected. <operation>(<filePath>): <errorMessage>`.
 *
 * @property operation the underlying operation that failed (`operation_name`).
 * @property filePath the file the error happened on, if any.
 * @property errorMessage the description of why the resume file was rejected.
 */
data class FastresumeRejectedAlert(
    override val torrentName: String?,
    val operation: String,
    val filePath: String,
    val errorMessage: String,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "fastresume_rejected"
    override val category: Int get() = AlertCategory.status or AlertCategory.error

    override fun message(): String =
        torrentPrefix() + " fast resume rejected. " + operation + "(" + filePath + "): " + errorMessage

    companion object { const val ALERT_TYPE: Int = 53 }
}

/**
 * Generated when an HTTP/web-seed name lookup or request fails. Ported from
 * `libtorrent::url_seed_alert` (alert_type 42).
 *
 * libtorrent renders `<name> url seed (<serverUrl>) failed: <errorMessage>`.
 *
 * @property serverUrl the web-seed URL the error is associated with.
 * @property errorMessage the error description (possibly from the server).
 */
data class UrlSeedAlert(
    override val torrentName: String?,
    val serverUrl: String,
    val errorMessage: String,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "url_seed"
    override val category: Int get() = AlertCategory.peer or AlertCategory.error

    override fun message(): String =
        torrentPrefix() + " url seed (" + serverUrl + ") failed: " + errorMessage

    companion object { const val ALERT_TYPE: Int = 42 }
}

/**
 * Generated when a limit is reached that may hurt upload/download performance.
 * Ported from `libtorrent::performance_alert` (alert_type 9).
 *
 * libtorrent renders `<name>: performance warning: <warning-label>`.
 *
 * @property warning the specific performance warning.
 */
data class PerformanceAlert(
    override val torrentName: String?,
    val warning: PerformanceWarning,
) : TorrentAlert() {
    override val type: Int get() = ALERT_TYPE
    override val what: String get() = "performance"
    override val category: Int get() = AlertCategory.performanceWarning

    override fun message(): String =
        torrentPrefix() + ": performance warning: " + warning.label

    companion object { const val ALERT_TYPE: Int = 9 }
}
