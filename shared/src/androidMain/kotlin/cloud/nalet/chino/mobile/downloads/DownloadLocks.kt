package cloud.nalet.chino.mobile.downloads

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.media3.exoplayer.offline.Download

/**
 * Keeps Wi-Fi and the processor awake while a download is on its way. With
 * the screen off or the app in the background, Android otherwise lets Wi-Fi
 * save power and the processor sleep between packets, and a download goes on
 * at a fraction of its speed, though its service runs in the foreground. Held
 * only while the manager downloads ([shouldHold]) and let go the moment it
 * stops, waits or ends: a download waiting for Wi-Fi keeps nothing awake.
 * The processor's lock runs out by itself after [CPU_TIMEOUT_MS] unless
 * [keep] renews it, so a lock never outlives the downloads that wanted it.
 */
internal class DownloadLocks(context: Context) {
    private val app = context.applicationContext

    private val wifi: WifiManager.WifiLock? = runCatching {
        (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG)
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    private val cpu: PowerManager.WakeLock? = runCatching {
        (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    /** Whether the locks are held now. */
    @Volatile
    var held = false
        private set

    /** Holds the locks while [on], lets them go otherwise. Holding them
     *  again renews the processor's. */
    fun hold(on: Boolean) {
        if (on) {
            runCatching { wifi?.takeUnless { it.isHeld }?.acquire() }
            runCatching { cpu?.acquire(CPU_TIMEOUT_MS) }
            held = true
        } else {
            runCatching { wifi?.takeIf { it.isHeld }?.release() }
            runCatching { cpu?.takeIf { it.isHeld }?.release() }
            held = false
        }
    }

    /** Renews the processor's lock while the locks are held: a download
     *  still on its way, its time not up yet. */
    fun keep() {
        if (held) runCatching { cpu?.acquire(CPU_TIMEOUT_MS) }
    }

    companion object {
        private const val TAG = "chino:downloads"

        /** How long the processor's lock lasts unless it is renewed. */
        const val CPU_TIMEOUT_MS = 10L * 60 * 1000

        /** Whether downloads are on their way: one downloading, the manager
         *  not paused, and nothing it waits for (a connection, Wi-Fi). */
        fun shouldHold(states: List<Int>, notMetRequirements: Int, paused: Boolean): Boolean =
            !paused && notMetRequirements == 0 && states.any { it == Download.STATE_DOWNLOADING }
    }
}
