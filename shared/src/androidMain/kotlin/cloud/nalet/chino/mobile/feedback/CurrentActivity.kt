package cloud.nalet.chino.mobile.feedback

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Holds a weak reference to the currently-resumed Activity so
 * [captureScreenshot] can PixelCopy its window without threading an Activity
 * through the (platform-agnostic) compose tree. The androidApp Application
 * feeds this from ActivityLifecycleCallbacks — weak so a destroyed Activity
 * is never pinned by a pending screenshot.
 */
object CurrentActivity {
    @Volatile private var resumed: WeakReference<Activity>? = null

    fun onResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    fun onPaused(activity: Activity) {
        // Only clear if this activity is still the tracked one — a NEW
        // activity's onResume can fire before the OLD one's onPause during
        // a transition, and clearing unconditionally would drop the new ref.
        if (resumed?.get() === activity) resumed = null
    }

    fun get(): Activity? = resumed?.get()
}
