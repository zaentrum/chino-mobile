package cloud.nalet.chino.mobile.data.downloads

/** A download manager's states (Media3's Download.STATE_*), as a platform
 *  reports them. */
enum class ManagerState { Queued, Stopped, Downloading, Completed, Failed, Removing, Restarting }

/** What a download waits for, of the manager's requirements: a connection
 *  (Wi-Fi, where downloads are Wi-Fi only), or the charger - a low battery
 *  makes a download wait for it. */
data class Waiting(val network: Boolean = false, val charging: Boolean = false)

/**
 * A title's status from its download: [state] the manager's (null: it has
 * none - the download did not start, or is [starting] now), [percent] of it
 * fetched, what it [waits] for, whether its subtitles and artwork are there
 * ([extrasDone]), and the [failure] recorded when it last stopped.
 *
 * Queued and waiting downloads say what they wait for - Wi-Fi where downloads
 * are [wifiOnly], else a connection, else the charger - and a queued one
 * that waits for nothing is waiting its turn. A download whose media is in
 * but whose subtitles are not is not on the phone yet: still on its way
 * (99 %), or failed for why its subtitles did not come.
 */
fun downloadStatus(
    state: ManagerState?,
    percent: Float,
    bytes: Long,
    waits: Waiting,
    wifiOnly: Boolean,
    extrasDone: Boolean,
    failure: DownloadFailure?,
    starting: Boolean = false,
): DownloadStatus = when (state) {
    null -> if (starting) DownloadStatus.Queued else DownloadStatus.Failed(failure ?: DownloadFailure.Stopped)
    ManagerState.Queued, ManagerState.Stopped, ManagerState.Restarting -> when {
        waits.network -> if (wifiOnly) DownloadStatus.WaitingForWifi else DownloadStatus.WaitingForConnection
        waits.charging -> DownloadStatus.WaitingForBattery
        else -> DownloadStatus.Queued
    }
    ManagerState.Downloading -> DownloadStatus.Downloading(wholePercent(percent))
    ManagerState.Completed -> when {
        extrasDone -> DownloadStatus.Downloaded(bytes)
        failure != null -> DownloadStatus.Failed(failure)
        else -> DownloadStatus.Downloading(99)
    }
    ManagerState.Failed -> DownloadStatus.Failed(failure ?: DownloadFailure.Stopped)
    ManagerState.Removing -> DownloadStatus.Removing
}

/** A manager's percentage (unknown: negative) in whole percent, never 100
 *  before the download is done. */
fun wholePercent(percent: Float): Int = if (percent.isNaN() || percent < 0f) 0 else percent.toInt().coerceIn(0, 99)
