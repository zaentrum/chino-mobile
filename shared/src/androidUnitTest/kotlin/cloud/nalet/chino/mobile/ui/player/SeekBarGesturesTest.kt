package cloud.nalet.chino.mobile.ui.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The player's own seek bar (Scrubber) under the player's gestures, stacked
 * as PlaybackSurface stacks them: the tap that wakes the controls and the
 * pinch of the picture on the screen, the controls' tap absorber over the
 * whole of it, the bar at its bottom. A pinch begun on the bar zooms the
 * picture and never seeks; a tap on the bar seeks there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeekBarGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private var pinches = 0
    private val starts = mutableListOf<Long>()
    private val updates = mutableListOf<Long>()
    private val commits = mutableListOf<Long>()

    private fun player() {
        compose.setContent {
            Box(
                modifier = Modifier
                    .size(800.dp, 360.dp)
                    .testTag("player")
                    .pointerInput(Unit) { detectTapGestures(onTap = { }) }
                    .pointerInput(Unit) {
                        detectPinch(mayBegin = { true }, onBegin = { pinches++ }, onSpread = { }, onEnd = { })
                    },
            ) {
                Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { }) }) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("bar")) {
                        Scrubber(
                            positionMs = 1_000_000L,
                            durationMs = 6_000_000L,
                            bufferedMs = 0L,
                            segments = emptyList(),
                            trickplayCues = emptyList(),
                            trickplayBaseUrl = "",
                            streamToken = "",
                            onScrubStart = { starts += it },
                            onScrubUpdate = { updates += it },
                            onScrubCommit = { commits += it },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aPinchWithBothFingersOnTheBarZoomsAndNeverSeeks() {
        player()
        compose.onNodeWithTag("bar").performTouchInput {
            pinch(Offset(centerX - 20f, centerY), Offset(centerX - 300f, centerY), Offset(centerX + 20f, centerY), Offset(centerX + 300f, centerY))
        }
        compose.waitForIdle()
        assertEquals(1, pinches)
        assertEquals(emptyList(), commits)
    }

    @Test
    fun aTapOnTheBarSeeksThere() {
        player()
        compose.onNodeWithTag("bar").performTouchInput { click(Offset(centerX + 100f, centerY)) }
        compose.waitForIdle()
        assertEquals(0, pinches)
        assertTrue(commits.isNotEmpty(), "no seek")
        // Right of the middle of a 6000 s title.
        assertTrue(commits.all { it == commits.first() && it > 3_000_000L }, "$commits")
    }
}
