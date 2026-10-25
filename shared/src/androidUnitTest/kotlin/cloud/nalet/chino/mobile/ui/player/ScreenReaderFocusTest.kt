package cloud.nalet.chino.mobile.ui.player

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * With TalkBack on, the player knows while its focus is on one of the
 * controls - and keeps them (controlsHideAfterMs): the focus events Compose
 * sends up through its view's parent, read there, everything passing on as
 * before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenReaderFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun event(type: Int) = AccessibilityEvent(type)

    @Test
    fun theWatchReadsFocusOnAndOffAndPassesEveryEventOnToTheDelegateBefore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = FrameLayout(context)
        val host = FrameLayout(context)
        val child = View(context)
        root.addView(host)
        host.addView(child)
        val passed = mutableListOf<Int>()
        val before = object : View.AccessibilityDelegate() {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                passed += event.eventType
                return true
            }
        }
        host.accessibilityDelegate = before
        val seen = mutableListOf<Boolean>()
        val watch = AccessibilityFocusWatch(host) { seen += it }
        host.requestSendAccessibilityEvent(child, event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
        host.requestSendAccessibilityEvent(child, event(AccessibilityEvent.TYPE_VIEW_CLICKED))
        host.requestSendAccessibilityEvent(child, event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED))
        assertEquals(listOf(true, false), seen)
        assertEquals(
            listOf(
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
            ),
            passed,
        )
        watch.release()
        assertSame(before, host.accessibilityDelegate)
    }

    @Test
    fun theComposeContentsFocusEventsAreSeenAndTheParentIsAsItWasOnceThePlayerGoes() {
        // A screen reader on: the window passes the events on to it.
        val manager = compose.activity.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        var view: View? = null
        var showing by mutableStateOf(true)
        compose.setContent {
            view = LocalView.current
            if (showing) {
                val focused by rememberScreenReaderFocused()
                Column {
                    ChromeButton(icon = Lucide.Pause, onClick = {}, name = PlayerButtons.playPause(true))
                    Text(if (focused) "on a control" else "nowhere")
                }
            }
        }
        compose.onNodeWithText("nowhere").assertExists()
        // What Compose does as TalkBack's focus lands on a control and
        // leaves it (AndroidComposeViewAccessibilityDelegateCompat.sendEvent):
        // the event goes up through its view's parent.
        fun send(type: Int) = compose.runOnUiThread { view!!.parent.requestSendAccessibilityEvent(view, event(type)) }
        send(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        compose.onNodeWithText("on a control").assertExists()
        // From one control to the next: off the one, on the other.
        send(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        send(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        compose.onNodeWithText("on a control").assertExists()
        send(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        compose.onNodeWithText("nowhere").assertExists()
        // Gone with the player: the parent's delegate as it was.
        val parent = view!!.parent as ViewGroup
        showing = false
        compose.waitForIdle()
        assertEquals(null, parent.accessibilityDelegate)
    }
}
