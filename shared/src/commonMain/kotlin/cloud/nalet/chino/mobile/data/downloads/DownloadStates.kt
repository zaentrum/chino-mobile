package cloud.nalet.chino.mobile.data.downloads

/** A download manager's states (Media3's Download.STATE_*), as a platform
 *  reports them. */
enum class ManagerState { Queued, Stopped, Downloading, Completed, Failed, Removing, Restarting }

/**
 * A title's status from its download: [state] the manager's (null: it has
 * none - the download did not start, or is [starting] now), [percent] of it
 * fetched, whether it waits for a connection ([waitsForNetwork]), whether
 * its subtitles and artwork are there ([extrasDone]), the [failure] recorded
 * when it last stopped, the [stopReason] it waits under, and whether it
 * waits for a check with the server before it plays ([checkNeeded]).
 *
 * A queued download that waits for a connection says what for - Wi-Fi where
 * downloads are [wifiOnly], else any connection - and one that waits for
 * nothing is waiting its turn. One stopped for room or by the server's
 * switch is a failure the control names. A download whose media is in but
 * whose subtitles are not is not on the phone yet: still on its way (99 %),
 * or failed for why its subtitles did not come.
 */
fun downloadStatus(
    state: ManagerState?,
    percent: Float,
    bytes: Long,
    waitsForNetwork: Boolean,
    wifiOnly: Boolean,
    extrasDone: Boolean,
    failure: DownloadFailure?,
    starting: Boolean = false,
    stopReason: Int = STOP_NONE,
    checkNeeded: Boolean = false,
): DownloadStatus = when (state) {
    null -> if (starting) DownloadStatus.Queued else DownloadStatus.Failed(failure ?: DownloadFailure.Stopped)
    ManagerState.Stopped -> when (stopReason) {
        STOP_NO_SPACE -> DownloadStatus.Failed(DownloadFailure.NoSpace)
        STOP_SERVER_OFF -> DownloadStatus.Failed(DownloadFailure.ServerOff)
        else -> waiting(waitsForNetwork, wifiOnly)
    }
    ManagerState.Queued, ManagerState.Restarting, ManagerState.Removing -> waiting(waitsForNetwork, wifiOnly)
    ManagerState.Downloading -> DownloadStatus.Downloading(wholePercent(percent))
    ManagerState.Completed -> when {
        extrasDone -> if (checkNeeded) DownloadStatus.CheckNeeded(bytes) else DownloadStatus.Downloaded(bytes)
        failure != null -> DownloadStatus.Failed(failure)
        else -> DownloadStatus.Downloading(99)
    }
    ManagerState.Failed -> DownloadStatus.Failed(failure ?: DownloadFailure.Stopped)
}

private fun waiting(waitsForNetwork: Boolean, wifiOnly: Boolean): DownloadStatus = when {
    !waitsForNetwork -> DownloadStatus.Queued
    wifiOnly -> DownloadStatus.WaitingForWifi
    else -> DownloadStatus.WaitingForConnection
}

/** A manager's percentage (unknown: negative) in whole percent, never 100
 *  before the download is done. */
fun wholePercent(percent: Float): Int = if (percent.isNaN() || percent < 0f) 0 else percent.toInt().coerceIn(0, 99)
