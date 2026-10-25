package cloud.nalet.chino.mobile.ui.player

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Where a screen reader's focus - TalkBack's - is on a screen's content. */
internal enum class ScreenReaderFocus {
    /** Not on it, or no screen reader runs. */
    None,

    /** On something of it other than the player's seek bar. */
    Content,

    /** On the player's seek bar ([Scrubber]), which holds what it says
     *  there while the title plays ([seekBarSpokenMs]). */
    SeekBar,
}

/**
 * Where a screen reader's focus - TalkBack's - is on this screen's content.
 * Compose says so in the accessibility events its view sends up through
 * its parent (focused, focus cleared), which [AccessibilityFocusWatch] reads
 * there on their way. [ScreenReaderFocus.None] while no screen reader runs:
 * then no such event is sent. A screen reader turned off with its focus
 * here (TalkBack's volume-key shortcut) sends no focus cleared - Compose
 * sends nothing once accessibility is off - so its touch exploration going
 * off, or accessibility as a whole, says it instead.
 */
@Composable
internal fun rememberScreenReaderFocus(): State<ScreenReaderFocus> {
    val view = LocalView.current
    val focus = remember { mutableStateOf(ScreenReaderFocus.None) }
    DisposableEffect(view) {
        val watch = (view.parent as? ViewGroup)?.let { parent -> AccessibilityFocusWatch(parent) { where -> focus.value = where } }
        val manager = view.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val exploring = AccessibilityManager.TouchExplorationStateChangeListener { on -> if (!on) focus.value = ScreenReaderFocus.None }
        val enabled = AccessibilityManager.AccessibilityStateChangeListener { on -> if (!on) focus.value = ScreenReaderFocus.None }
        manager?.addTouchExplorationStateChangeListener(exploring)
        manager?.addAccessibilityStateChangeListener(enabled)
        onDispose {
            manager?.removeTouchExplorationStateChangeListener(exploring)
            manager?.removeAccessibilityStateChangeListener(enabled)
            watch?.release()
            focus.value = ScreenReaderFocus.None
        }
    }
    return focus
}

/** Whether [node] - what a screen reader has its focus on - is the player's
 *  seek bar: the slider named as [PlayerButtons.seek] names it. */
internal fun isSeekBar(node: AccessibilityNodeInfo?): Boolean =
    node != null && node.className?.toString() == "android.widget.SeekBar" &&
        node.contentDescription?.toString() == PlayerButtons.SEEK_NAME

/**
 * Reads the accessibility focus events a child of [host] sends up - a
 * screen reader's focus moved onto something of it, or off - and tells
 * [onChange] where it is: on the seek bar or elsewhere on it, the node the
 * child's provider has focused as the event goes up ([isSeekBar]). Everything
 * passes on as before: the delegate [host] had (read where the platform lets
 * it, from Android 10) answers as it did, and is put back on [release].
 */
internal class AccessibilityFocusWatch(private val host: ViewGroup, private val onChange: (ScreenReaderFocus) -> Unit) {
    private val before: View.AccessibilityDelegate? = if (Build.VERSION.SDK_INT >= 29) host.accessibilityDelegate else null

    private fun focusIn(child: View): ScreenReaderFocus {
        // Compose has the node as focused before it sends the event.
        val node = runCatching { child.accessibilityNodeProvider?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }.getOrNull()
        return if (isSeekBar(node)) ScreenReaderFocus.SeekBar else ScreenReaderFocus.Content
    }

    private val delegate = object : View.AccessibilityDelegate() {
        override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> onChange(focusIn(child))
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> onChange(ScreenReaderFocus.None)
            }
            return before?.onRequestSendAccessibilityEvent(host, child, event) ?: super.onRequestSendAccessibilityEvent(host, child, event)
        }

        override fun sendAccessibilityEvent(host: View, eventType: Int) {
            before?.sendAccessibilityEvent(host, eventType) ?: super.sendAccessibilityEvent(host, eventType)
        }

        override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
            before?.sendAccessibilityEventUnchecked(host, event) ?: super.sendAccessibilityEventUnchecked(host, event)
        }

        override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent): Boolean =
            before?.dispatchPopulateAccessibilityEvent(host, event) ?: super.dispatchPopulateAccessibilityEvent(host, event)

        override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
            before?.onPopulateAccessibilityEvent(host, event) ?: super.onPopulateAccessibilityEvent(host, event)
        }

        override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
            before?.onInitializeAccessibilityEvent(host, event) ?: super.onInitializeAccessibilityEvent(host, event)
        }

        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            before?.onInitializeAccessibilityNodeInfo(host, info) ?: super.onInitializeAccessibilityNodeInfo(host, info)
        }

        @androidx.annotation.RequiresApi(26)
        override fun addExtraDataToAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo, extraDataKey: String, arguments: Bundle?) {
            before?.addExtraDataToAccessibilityNodeInfo(host, info, extraDataKey, arguments)
                ?: super.addExtraDataToAccessibilityNodeInfo(host, info, extraDataKey, arguments)
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
            before?.performAccessibilityAction(host, action, args) ?: super.performAccessibilityAction(host, action, args)

        override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProvider? =
            if (before != null) before.getAccessibilityNodeProvider(host) else super.getAccessibilityNodeProvider(host)
    }

    init {
        host.accessibilityDelegate = delegate
    }

    /** Stops reading, and gives [host] back the delegate it had. */
    fun release() {
        if (Build.VERSION.SDK_INT < 29 || host.accessibilityDelegate === delegate) host.accessibilityDelegate = before
    }
}
