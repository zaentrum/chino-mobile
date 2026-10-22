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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.google.android.gms.cast.framework.R as CastR

/**
 * Keeps the app running while casting needs it ([ChinoCast.keepAlive]): a
 * load, a recovery or a signing of the links anew under way, the TV's media
 * this phone's, a position on its way to the server - a foreground service
 * of the media playback kind. Without it Android stops or freezes an app in
 * the background: locked, or in another app, the phone would no longer hear
 * the TV, and the position it keeps when casting stops would be an old one.
 * Once nothing needs it, it waits a moment, looks again, and ends.
 *
 * Google's media notification - the title, the device, play or pause, stop -
 * stays what the viewer sees and uses; a foreground service needs one of its
 * own, which is this one: quiet (no sound, no light), at the bottom of the
 * shade, not on the lock screen, one line under the app's name. From Android
 * 13 on, an app that asks for no notifications shows none of its own there;
 * the system lists the app as active instead.
 */
class CastPlaybackService : Service() {
    private val scope = MainScope()
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ChinoCast.serviceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started as a foreground service: in the foreground at once, even
        // should nothing need it any more.
        if (!foreground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            scope.launch {
                combine(ChinoCast.keepAlive, ChinoCast.state) { keep, cast -> keep to cast.device }.collectLatest { (keep, device) ->
                    if (keep) {
                        notificationManager().notify(NOTIFICATION_ID, notification(device))
                    } else {
                        // A moment, and a look at how things stand then: a
                        // reload's statuses, a write, come and go.
                        delay(STOP_GRACE_MS)
                        if (!ChinoCast.keepAliveNow()) stop()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ChinoCast.serviceRunning = false
        // No notification of ours outlives the service.
        notificationManager().cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun foreground(): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(ChinoCast.state.value.device), MEDIA_PLAYBACK)
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

    private fun notification(device: String?): Notification {
        channel()
        val controls = Intent(this, CastExpandedControlsActivity::class.java)
        val open = TaskStackBuilder.create(this)
            .addNextIntentWithParentStack(controls)
            .getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(CastR.drawable.cast_ic_notification_small_icon)
            // One line, under the app's name: no title of our own.
            .setContentText(getString(R.string.chino_cast_keeping_place, castDeviceInLine(device)))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    /** The Cast framework's channel - the one casting's notifications are
     *  in, the framework's and this one - made as the framework makes it,
     *  should this notification come first; named "Casting" for both
     *  (media_notification_channel_name). The earlier builds' own channels
     *  go. */
    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager()
        for (old in OLD_CHANNEL_IDS) manager.getNotificationChannel(old)?.let { manager.deleteNotificationChannel(old) }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.chino_cast_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private companion object {
        /** The Cast framework's own channel id. */
        const val CHANNEL_ID = "cast_media_notification"
        val OLD_CHANNEL_IDS = listOf("chino_cast_keeping_place", "chino_cast_place")
        const val NOTIFICATION_ID = 0x0ca57

        /** How long the service waits, once nothing needs it, before it
         *  looks again and ends. */
        const val STOP_GRACE_MS = 10_000L

        /** ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK (API 29); 0 before. */
        val MEDIA_PLAYBACK: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
    }
}
