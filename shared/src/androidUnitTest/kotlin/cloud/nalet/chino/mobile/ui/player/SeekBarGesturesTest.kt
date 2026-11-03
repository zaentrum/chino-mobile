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
 * picture and never seeks; a tap on the bar seeks there, a drag along it
 * where the finger lets go.
 */
@RunWith(RobolectricTestRunner::class)
// A phone held sideways: room for the 800 x 360 player.
@Config(sdk = [34], qualifiers = "w891dp-h411dp")
class SeekBarGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private var pinches = 0
    private val starts = mutableListOf<Long>()
    private val updates = mutableListOf<Long>()
    private val commits = mutableListOf<Long>()
    private var cancels = 0

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
                            onScrubCancel = { cancels++ },
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
    fun aTapOnTheBarSeeksThereOnce() {
        player()
        compose.onNodeWithTag("bar").performTouchInput { click(Offset(centerX + 100f, centerY)) }
        compose.waitForIdle()
        assertEquals(0, pinches)
        // Right of the middle of a 6000 s title, once.
        assertEquals(1, commits.size, "$commits")
        assertTrue(commits.single() > 3_000_000L, "$commits")
    }

    @Test
    fun aDragAlongTheBarSeeksWhereTheFingerLetsGo() {
        player()
        val bar = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("player").performTouchInput {
            down(Offset(bar.center.x - 200f, bar.center.y))
            for (i in 1..20) {
                updatePointerTo(0, Offset(bar.center.x - 200f + i * 20f, bar.center.y))
                move()
            }
            up()
        }
        compose.waitForIdle()
        // One seek, where it was let go - the last place the bar showed -
        // never where the finger first touched.
        assertEquals(1, commits.size, "$commits")
        assertEquals(updates.last(), commits.single())
        assertTrue(commits.single() > starts.single(), "seek $commits from ${starts.single()}")
        assertEquals(0, cancels)
    }

    @Test
    fun aPinchWithAThumbOnTheBarAndAFingerOnThePictureZoomsAndNeverSeeks() {
        player()
        val bar = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val y = bar.center.y
        compose.onNodeWithTag("player").performTouchInput {
            down(0, Offset(bar.center.x - 30f, y))
            down(1, Offset(bar.center.x + 30f, y - 200f))
            for (i in 1..20) {
                updatePointerTo(0, Offset(bar.center.x - 30f - i * 12f, y))
                updatePointerTo(1, Offset(bar.center.x + 30f + i * 12f, y - 200f - i * 6f))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertEquals(1, pinches)
        assertEquals(emptyList(), commits)
        // The thumb's drag began before the pinch did: it ends, seeking nowhere.
        assertEquals(starts.size, cancels)
    }

    @Test
    fun aDragTheSecondFingerTurnsIntoAPinchEndsWithoutASeek() {
        player()
        val bar = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val y = bar.center.y
        compose.onNodeWithTag("player").performTouchInput {
            down(0, Offset(bar.center.x, y))
            for (i in 1..10) {
                updatePointerTo(0, Offset(bar.center.x + i * 15f, y))
                move()
            }
            down(1, Offset(bar.center.x + 150f, y - 200f))
            for (i in 1..20) {
                updatePointerTo(0, Offset(bar.center.x + 150f - i * 10f, y))
                updatePointerTo(1, Offset(bar.center.x + 150f + i * 10f, y - 200f - i * 5f))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertEquals(1, pinches)
        assertEquals(1, starts.size)
        assertEquals(emptyList(), commits)
        assertEquals(1, cancels)
    }
}
