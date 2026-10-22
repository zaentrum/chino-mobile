package cloud.nalet.chino.mobile.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler
import cloud.nalet.chino.mobile.shared.R

/**
 * The foreground service downloads run in, with its notification: what is
 * on its way and how far. Media3's DownloadService does the rest. While the
 * downloads wait for a connection it does not run (DownloadEngine stops it):
 * below Android 12 its scheduler ([WorkManagerScheduler]) starts it again
 * once the connection is back; from 12 the app does as it comes to the
 * front, and the manager runs them while the process lives.
 */
class ChinoDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.chino_downloads_channel,
    0,
) {
    override fun onCreate() {
        running = true
        super.onCreate()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
        // Stopped while the downloads wait, Media3's last update of its
        // notification may have gone out as a plain one: the service takes
        // it with it, and nothing stays in the shade for a download that
        // waits.
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
    }

    override fun getDownloadManager(): DownloadManager = DownloadEngine.get(this).manager

    override fun getScheduler(): Scheduler? =
        // From Android 12 a foreground service cannot be started from the
        // background; Media3 then keeps this one running instead and uses
        // no scheduler.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) WorkManagerScheduler(this, SCHEDULER_WORK) else null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        downloadNotification(this, downloads, notMetRequirements)

    /** Android 15 gives a data sync service six hours a day: past them the
     *  downloads pause, and carry on when the app is opened again
     *  (AndroidDownloadsPlatform.watch resumes them). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadEngine.get(this).manager.pauseDownloads()
        stopSelf()
    }

    companion object {
        /** Whether the service runs in this process now. */
        @Volatile
        var running = false
            private set

        const val CHANNEL_ID = "chino_downloads"
        private const val NOTIFICATION_ID = 7301
        private const val SCHEDULER_WORK = "chino-downloads"
    }
}

/** The notification: the title on its way - a film by its title, an
 *  episode with its series and code - with how far it is and how many wait;
 *  or what the downloads wait for, for the moment before the service stops
 *  for them. Never one word twice: nothing active and nothing waiting is the
 *  channel's name alone. Plain words, as the app's. */
private fun downloadNotification(context: Context, downloads: List<Download>, notMet: Int): Notification {
    val active = downloads.filter { it.state == Download.STATE_DOWNLOADING }
    val waiting = downloads.count {
        (it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING) && it.stopReason == Download.STOP_REASON_NONE
    }
    val titleOf = { d: Download -> d.request.data.takeIf { it.isNotEmpty() }?.decodeToString() }
    val title = when {
        active.size == 1 -> titleOf(active[0]) ?: "Downloads"
        else -> "Downloads"
    }
    val wifiOnly = DownloadEngine.get(context).preferences.value.wifiOnly
    val text: String? = when {
        notMet and (Requirements.NETWORK or Requirements.NETWORK_UNMETERED) != 0 ->
            if (wifiOnly) "Waiting for Wi-Fi" else "Waiting for a connection"
        active.isNotEmpty() -> {
            val percent = active.first().percentDownloaded.takeIf { it >= 0 }?.toInt()?.coerceIn(0, 99)
            buildString {
                append("Downloading")
                if (percent != null) append(' ').append(percent).append('%')
                if (waiting > 0) append(" · ").append(waiting).append(" queued")
            }
        }
        waiting > 0 -> "Queued"
        else -> null
    }
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    val builder = NotificationCompat.Builder(context, ChinoDownloadService.CHANNEL_ID)
        .setSmallIcon(R.drawable.chino_ic_stat_download)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    if (active.isNotEmpty() && notMet == 0) {
        val percent = active.first().percentDownloaded
        if (percent >= 0) builder.setProgress(100, percent.toInt().coerceIn(0, 100), false) else builder.setProgress(100, 0, true)
    }
    return builder.build()
}
