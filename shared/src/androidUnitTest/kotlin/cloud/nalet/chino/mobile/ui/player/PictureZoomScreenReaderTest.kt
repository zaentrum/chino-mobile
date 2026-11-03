package cloud.nalet.chino.mobile.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import cloud.nalet.chino.mobile.ui.AccessibilityNodes
import cloud.nalet.chino.mobile.ui.screenReaderOn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fill and fit for a screen reader (pictureZoomAction, as PlayerScreen puts it
 * on the picture): what Android hands TalkBack - the picture, named by the
 * title playing, a stop its focus lands on, with one action, "zoom to fill",
 * or "zoom to fit" while it fills the screen, which does what a pinch does.
 * Where a pinch would do nothing, no stop and no action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PictureZoomScreenReaderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var filled by mutableStateOf(false)
    private var applies by mutableStateOf(true)
    private var toggles = 0

    private fun showPicture() {
        screenReaderOn(compose.activity)
        compose.setContent {
            val zoom = if (applies) PictureZoomAction(filled) { toggles++; filled = !filled } else null
            Box(modifier = Modifier.size(400.dp, 200.dp).pictureZoomAction("A Film", zoom))
        }
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
    }

    private fun picture() = compose.onNodeWithContentDescription("A Film").fetchSemanticsNode()

    private fun actionLabels(): List<String> {
        val info = assertNotNull(AccessibilityNodes(compose.activity).of(picture()))
        // The standard actions have no label; a custom one has its name.
        return info.actionList.mapNotNull { it.label?.toString() }
    }

    @Test
    fun thePictureIsAStopOfItsOwnWithZoomToFillThenZoomToFit() {
        showPicture()
        val info = assertNotNull(AccessibilityNodes(compose.activity).of(picture()))
        assertTrue(info.isScreenReaderFocusable, "TalkBack's focus never lands on it")
        assertEquals(listOf("zoom to fill"), actionLabels())
        // TalkBack performs it: as a pinch out.
        val action = info.actionList.first { it.label?.toString() == "zoom to fill" }
        compose.runOnIdle { assertTrue(AccessibilityNodes(compose.activity).perform(picture(), action.id)) }
        compose.waitForIdle()
        assertEquals(1, toggles)
        assertEquals(listOf("zoom to fit"), actionLabels())
    }

    @Test
    fun whereAPinchWouldDoNothingThereIsNoStopAndNoAction() {
        applies = false
        showPicture()
        assertEquals(0, compose.onAllNodesWithContentDescription("A Film").fetchSemanticsNodes().size)
    }

    @Test
    fun theActionsInWords() {
        assertEquals(PlayerButtonName("zoom to fill"), PlayerButtons.zoom(filled = false))
        assertEquals(PlayerButtonName("zoom to fit"), PlayerButtons.zoom(filled = true))
    }
}
