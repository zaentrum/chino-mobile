package cloud.nalet.chino.mobile.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.app.TaskStackBuilder
import cloud.nalet.chino.mobile.shared.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.google.android.gms.cast.framework.R as CastR

/**
 * Keeps the app running while the TV plays a title whose position this phone
 * writes ([ChinoCast.nowPlaying]) - a foreground service of the media
 * playback kind. Without it Android stops or freezes an app in the
 * background: locked, or in another app, the phone would no longer hear the
 * TV, and the position it keeps when casting stops would be an old one. It
 * stops once the TV no longer plays such a title and the last position is
 * written.
 *
 * Google's media notification - the title, the device, play or pause, stop -
 * stays what the viewer sees and uses; a foreground service needs one of its
 * own, which is this one: quiet, at the bottom of the shade, not on the lock
 * screen. From Android 13 on, an app that asks for no notifications shows
 * none of its own there; the system lists the app as active instead.
 */
class CastPlaybackService : Service() {
    private val scope = MainScope()
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started as a foreground service: in the foreground at once, even
        // should the title have ended meanwhile.
        if (!foreground(ChinoCast.nowPlaying.value)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            scope.launch {
                combine(ChinoCast.nowPlaying, ChinoCast.writing) { now, writing -> now to writing }.collect { (now, writing) ->
                    when {
                        now != null -> notificationManager().notify(NOTIFICATION_ID, notification(now))
                        writing == 0 -> stop()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun foreground(now: CastNowPlaying?): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(now), MEDIA_PLAYBACK)
        true
    } catch (e: RuntimeException) {
        // Not allowed now (Android 12 on: from the background; 14 on:
        // without its permission): the writes go on while the app is open.
        false
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(now: CastNowPlaying?): Notification {
        channel()
        val controls = Intent(this, CastExpandedControlsActivity::class.java)
        val open = TaskStackBuilder.create(this)
            .addNextIntentWithParentStack(controls)
            .getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(CastR.drawable.cast_ic_notification_small_icon)
            .setContentTitle(now?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.chino_cast_keeping_place_title))
            .setContentText(getString(R.string.chino_cast_keeping_place, castDeviceInLine(now?.device)))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.chino_cast_channel), NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private companion object {
        const val CHANNEL_ID = "chino_cast_place"
        const val NOTIFICATION_ID = 0x0ca57

        /** ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK (API 29); 0 before. */
        val MEDIA_PLAYBACK: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
    }
}
