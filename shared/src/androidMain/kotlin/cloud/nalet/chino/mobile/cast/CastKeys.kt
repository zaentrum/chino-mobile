package cloud.nalet.chino.mobile.cast

import android.os.Build
import android.view.KeyEvent

/**
 * The phone's volume keys while casting set the TV's volume. The Cast
 * framework does that itself through its media session - except on Android 12
 * and 12L, which route the keys of an app's remote session to the phone's own
 * volume instead. There the app's activities pass the keys here; everywhere
 * else they go on to the framework, and the system's volume panel shows the
 * TV's.
 */
object CastKeys {
    /** How far one press moves the TV's volume, of its whole range. */
    private const val STEP = 0.05

    /** True when [event] was a volume key that set the TV's volume. */
    fun handle(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.S && Build.VERSION.SDK_INT != Build.VERSION_CODES.S_V2) return false
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (!up && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val session = ChinoCast.connectedSession() ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            runCatching { session.volume = (session.volume + if (up) STEP else -STEP).coerceIn(0.0, 1.0) }
        }
        return true
    }
}
