package cloud.nalet.chino.mobile

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationMaskAllButUpsideDown
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS
import platform.UIKit.setNeedsUpdateOfSupportedInterfaceOrientations

/**
 * The system chrome around the app as the player asks for it: the status bar
 * and the home indicator hidden while a film plays full screen, and the
 * interface turned to landscape for the full-screen button.
 *
 * SwiftUI owns the root view controller, so only the host can hide the status
 * bar: iosApp/ContentView.swift sets [onImmersiveChanged] and applies it with
 * `.statusBarHidden` / `.persistentSystemOverlays`.
 */
object IosSystemChrome {
    /** Set by the SwiftUI host; called on the main thread with every change. */
    var onImmersiveChanged: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(immersive)
        }

    var immersive: Boolean = false
        private set

    fun setImmersive(on: Boolean) {
        if (on == immersive) return
        immersive = on
        onImmersiveChanged?.invoke(on)
    }

    /**
     * Turns the interface: landscape (true), portrait (false), or back to
     * following the device (null). iOS 16 and later (window-scene geometry
     * requests); on iOS 15 the viewer turns the phone.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun requestOrientation(landscape: Boolean?) {
        val scene = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>().firstOrNull() ?: return
        if (!scene.respondsToSelector(NSSelectorFromString("requestGeometryUpdateWithPreferences:errorHandler:"))) return
        val mask = when (landscape) {
            true -> UIInterfaceOrientationMaskLandscape
            false -> UIInterfaceOrientationMaskPortrait
            null -> UIInterfaceOrientationMaskAllButUpsideDown
        }
        scene.windows.filterIsInstance<UIWindow>().firstOrNull { it.isKeyWindow() }?.rootViewController
            ?.setNeedsUpdateOfSupportedInterfaceOrientations()
        scene.requestGeometryUpdateWithPreferences(UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = mask)) { _ -> }
    }
}
