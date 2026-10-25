package cloud.nalet.chino.mobile.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import org.robolectric.Shadows.shadowOf

/**
 * What Android hands a screen reader - and what uiautomator's dump lists -
 * for the Compose content of [activity]: the AccessibilityNodeInfo Compose
 * builds for each node, not the merged semantics a Compose test reads. A
 * node can look right there and still reach TalkBack as a nameless view,
 * its name and its role on nodes of their own below it. Compose builds
 * them from the screen's regions: the test runs with Robolectric's native
 * graphics (`@GraphicsMode(NATIVE)`), and a screen reader on
 * ([screenReaderOn]).
 */
internal class AccessibilityNodes(activity: Activity) {
    private val view: View = checkNotNull(activity.window.decorView.composeView()) { "no Compose view on screen" }

    /** The node info Android has for [node]; null where it has none (a
     *  node that isn't on screen, or one cleared into its parent). */
    fun of(node: SemanticsNode): AccessibilityNodeInfo? = provider().createAccessibilityNodeInfo(node.id)

    /** Does to [node] what a screen reader does with [action] ([args]): what
     *  TalkBack sends, through the same door. */
    fun perform(node: SemanticsNode, action: Int, args: Bundle? = null): Boolean = provider().performAction(node.id, action, args)

    private fun provider() = checkNotNull(view.accessibilityNodeProvider) { "the Compose view has no node provider" }

    private fun View.composeView(): View? = when (this) {
        is ViewRootForTest -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).composeView() }
        else -> null
    }
}

/** A screen reader on, as TalkBack is: Compose builds its nodes for
 *  Android only then. Before the content is set. */
internal fun screenReaderOn(activity: Activity) {
    val manager = shadowOf(activity.getSystemService(AccessibilityManager::class.java))
    manager.setEnabled(true)
    manager.setTouchExplorationEnabled(true)
    manager.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo().apply { feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN }))
}

/** The action Android offers a screen reader for [id] ([AccessibilityNodeInfo.AccessibilityAction]). */
internal fun AccessibilityNodeInfo.offers(id: Int): Boolean = actionList.any { it.id == id }
