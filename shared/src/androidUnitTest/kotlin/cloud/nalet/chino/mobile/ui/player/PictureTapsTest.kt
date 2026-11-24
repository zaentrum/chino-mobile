package cloud.nalet.chino.mobile.ui.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The taps on the player's picture as the player takes them (change 11.C54 of
 * the specification, its section 11.4.4 and figure 11.13), stacked as
 * PlaybackSurface stacks them: the player with its taps, the mark over it,
 * and a bar at its foot that keeps its own taps. Two taps in a half of the
 * player skip, with the mark in the middle of that half, which goes 0.6
 * seconds after the last tap; a single tap is one once 0.3 seconds have
 * passed; a tap on the bar is the bar's. The clock is the test's.
 */
@RunWith(RobolectricTestRunner::class)
// A phone held sideways: room for the 800 x 360 player.
@Config(sdk = [34], qualifiers = "w891dp-h411dp")
class PictureTapsTest {
    @get:Rule
    val compose = createComposeRule()

    /** A title of ten minutes at its first minute. */
    private val positionMs = 60_000L
    private var durationMs = 600_000L

    private var offered = true
    private var singleTaps = 0
    private val skips = mutableListOf<Long>()
    private var barTaps = 0

    private fun player() {
        compose.setContent {
            val taps = rememberPictureTaps()
            Box(
                modifier = Modifier
                    .size(800.dp, 360.dp)
                    .testTag("player")
                    .pictureTaps { atMs, rightHalf, oneFinger ->
                        taps.tap(
                            atMs = atMs,
                            forward = rightHalf,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            offered = doubleTapOffered(oneFingerAlone = oneFinger, screenReaderOn = !offered, menuOpen = false, cardShown = false),
                            onSingleTap = { singleTaps++ },
                            onSkip = { skips += it },
                        )
                    },
            ) {
                SkipMarkOverlay(mark = taps.mark)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(80.dp)
                        .testTag("bar")
                        .pointerInput(Unit) { detectTapGestures(onTap = { barTaps++ }) },
                )
            }
        }
        compose.waitForIdle()
        // From here the test moves the clock.
        compose.mainClock.autoAdvance = false
    }

    /** Taps in the middle of the picture's right half, or of its left, each
     *  [apartMs] after the one before. */
    private fun taps(count: Int, right: Boolean, apartMs: Long = 150) {
        compose.onNodeWithTag("player").performTouchInput {
            val at = Offset(if (right) width * 0.75f else width * 0.25f, height * 0.4f)
            repeat(count) { i ->
                if (i > 0) advanceEventTime(apartMs)
                click(at)
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun change11_C54_twoTapsInTheRightHalfSkipForwardWithTheMarkInTheMiddleOfThatHalf() {
        player()

        taps(2, right = true)

        assertEquals(listOf(positionMs + 10_000), skips)
        assertEquals(0, singleTaps)
        // The half of the whole player: its middle at three quarters across.
        val mark = compose.onNodeWithText("+10 s").getBoundsInRoot()
        val player = compose.onNodeWithTag("player").getBoundsInRoot()
        val middleOfTheHalf = player.left + (player.right - player.left) * 0.75f
        assertTrue(((mark.left + mark.right) / 2 - middleOfTheHalf).value in -1f..1f, "the mark's middle at ${(mark.left + mark.right) / 2}, the half's at $middleOfTheHalf")
    }

    @Test
    fun change11_C54_twoTapsInTheLeftHalfSkipBackWithTheMarkThere() {
        player()

        taps(2, right = false)

        assertEquals(listOf(positionMs - 10_000), skips)
        val mark = compose.onNodeWithText("−10 s").getBoundsInRoot()
        val player = compose.onNodeWithTag("player").getBoundsInRoot()
        val middleOfTheHalf = player.left + (player.right - player.left) * 0.25f
        assertTrue(((mark.left + mark.right) / 2 - middleOfTheHalf).value in -1f..1f)
    }

    @Test
    fun change11_C54_theMarkGoes600MsAfterTheLastTap() {
        player()
        taps(2, right = true)
        assertTrue(shows("+10 s"))

        compose.mainClock.advanceTimeBy(500)
        assertTrue(shows("+10 s"), "the mark stays within 0.6 seconds of the last tap")
        compose.mainClock.advanceTimeBy(200)
        assertTrue(!shows("+10 s"), "the mark goes 0.6 seconds after the last tap")
        // The double tap was no single tap, then or later.
        assertEquals(0, singleTaps)
    }

    @Test
    fun change11_C54_furtherTapsAddUpAlsoOnTheMarkItself() {
        player()

        // The third and the fourth tap land where the mark stands.
        taps(4, right = true, apartMs = 250)

        assertEquals(listOf(positionMs + 10_000, positionMs + 20_000, positionMs + 30_000), skips)
        assertTrue(shows("+30 s"))
        assertEquals(0, singleTaps)
    }

    @Test
    fun change11_C54_aSingleTapIsOneOnceThe300MsHavePassed() {
        player()

        taps(1, right = true)
        assertEquals(0, singleTaps)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(0, singleTaps, "a single tap waits for a second one")

        compose.mainClock.advanceTimeBy(200)
        assertEquals(1, singleTaps)
        assertEquals(emptyList(), skips)
    }

    @Test
    fun change11_C54_aTapInTheLeftHalfThenOneInTheRightAreTwoSingleTaps() {
        player()

        taps(1, right = false)
        compose.mainClock.advanceTimeBy(100)
        taps(1, right = true)
        // The left one is a single tap as the right one comes.
        assertEquals(1, singleTaps)

        compose.mainClock.advanceTimeBy(400)
        assertEquals(2, singleTaps)
        assertEquals(emptyList(), skips)
    }

    @Test
    fun change11_C54_twoTapsOnABarAreTheBarsNotASkip() {
        player()

        compose.onNodeWithTag("bar").performTouchInput {
            click(center)
            advanceEventTime(150)
            click(center)
        }
        compose.mainClock.advanceTimeBy(1_000)

        assertEquals(2, barTaps)
        assertEquals(emptyList(), skips)
        assertEquals(0, singleTaps)
    }

    /** A touch of two fingers in the picture's right half that never moves:
     *  no pinch. */
    private fun twoFingerTouch() {
        compose.onNodeWithTag("player").performTouchInput {
            down(0, Offset(width * 0.7f, height * 0.4f))
            down(1, Offset(width * 0.8f, height * 0.4f))
            up(0)
            up(1)
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun change11_C54_twoTouchesOfTwoFingersAreNoDoubleTap() {
        player()

        twoFingerTouch()
        compose.mainClock.advanceTimeBy(100)
        twoFingerTouch()

        // Each is a single tap at once, as a touch of two fingers was.
        assertEquals(emptyList(), skips)
        assertEquals(2, singleTaps)
        assertTrue(!shows("+10 s"))
    }

    @Test
    fun change11_C54_aTapAndThenATouchOfTwoFingersAreNoDoubleTap() {
        player()

        taps(1, right = true)
        compose.mainClock.advanceTimeBy(100)
        twoFingerTouch()

        // The tap that waited is a single tap, done first; then the touch.
        assertEquals(emptyList(), skips)
        assertEquals(2, singleTaps)
        // And a tap of one finger after it begins anew.
        taps(2, right = true)
        assertEquals(listOf(positionMs + 10_000), skips)
    }

    @Test
    fun change11_C54_whileTheTitlesLengthIsNotKnownForwardDoesNothingAndShowsNoMark() {
        durationMs = 0
        player()

        taps(2, right = true)

        assertEquals(emptyList(), skips)
        assertTrue(!shows("+10 s"))
        assertEquals(0, singleTaps)
    }

    @Test
    fun change11_C54_whereNoDoubleTapIsOfferedEachTapIsASingleTapAtOnce() {
        // A screen reader on, a menu open, or a card over the picture.
        offered = false
        player()

        taps(2, right = true)

        assertEquals(2, singleTaps)
        assertEquals(emptyList(), skips)
        assertTrue(!shows("+10 s"))
    }
}
