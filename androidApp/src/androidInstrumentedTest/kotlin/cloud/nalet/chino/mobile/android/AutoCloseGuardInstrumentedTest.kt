package cloud.nalet.chino.mobile.android

import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.nalet.chino.mobile.ui.player.AutoCloseGuard
import cloud.nalet.chino.mobile.ui.player.HoldInputAfterAutoClose
import cloud.nalet.chino.mobile.ui.player.PlayerClose
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.TestTimeSource

/**
 * The moment after the player closed by itself, on a device: the screen it
 * returned to has no semantics — nothing for TalkBack, Switch Access or
 * Voice Access to focus or act on — and a touch does nothing; after the
 * moment both are the viewer's again. A close by the viewer holds nothing.
 * The page here is a stand-in: one button, "Back", set in place of the app
 * on its own activity's ComposeView (the test APK's empty activity would
 * not run in the app's process).
 */
@RunWith(AndroidJUnit4::class)
class AutoCloseGuardInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private var clicks = 0

    /** The stand-in page, in place of the app's content: on the activity's
     *  own ComposeView, which the rule drives. */
    private fun page(guard: AutoCloseGuard) {
        rule.runOnUiThread {
            val root = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ComposeView
            root.setContent {
                HoldInputAfterAutoClose(guard) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "Back" }
                            .clickable { clicks++ },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    @Test
    fun whileHeldThePageHasNothingForAccessibilityToActOnNorATouch() {
        val guard = AutoCloseGuard(time = TestTimeSource())
        page(guard)
        rule.onAllNodesWithContentDescription("Back").assertCountEquals(1)

        rule.runOnIdle { guard.playerClosed(PlayerClose.ByItself) }
        rule.mainClock.advanceTimeByFrame()
        // What an accessibility service reads: the page is not there.
        rule.onAllNodesWithContentDescription("Back").assertCountEquals(0)
        rule.onRoot().performTouchInput { click(center) }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(0, clicks)

        // The moment is over: the page's semantics are back, and its action
        // and a touch work.
        rule.mainClock.advanceTimeBy(1_100)
        rule.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick)
        rule.onRoot().performTouchInput { click(center) }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(2, clicks)
    }

    @Test
    fun aCloseByTheViewerHoldsNothing() {
        val guard = AutoCloseGuard(time = TestTimeSource())
        page(guard)
        rule.runOnIdle { guard.playerClosed(PlayerClose.ByViewer) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1, clicks)
    }
}
