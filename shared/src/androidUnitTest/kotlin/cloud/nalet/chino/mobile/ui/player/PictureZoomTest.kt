package cloud.nalet.chino.mobile.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A pinch over the player (detectPinch, as PlayerScreen has it): two fingers
 * spread or closed are the picture's, and the controls under them never see
 * them move (the player's own seek bar under a pinch: SeekBarGesturesTest).
 * One finger is no pinch. And the fill choice, kept on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PictureZoomTest {
    @get:Rule
    val compose = createComposeRule()

    private var begun = 0
    private val spreads = mutableListOf<Float>()
    private var ended: Float? = null
    private var seeks = 0
    private var taps = 0

    private fun player(mayBegin: () -> Boolean = { true }) {
        compose.setContent {
            Box(
                modifier = Modifier
                    .size(400.dp, 200.dp)
                    .testTag("player")
                    .pointerInput(Unit) {
                        detectPinch(
                            mayBegin = mayBegin,
                            onBegin = { begun++ },
                            onSpread = { spreads += it },
                            onEnd = { ended = it },
                        )
                    },
            ) {
                // A seek bar along the bottom, and a button in the middle.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(60.dp)
                        .pointerInput(Unit) { detectHorizontalDragGestures { _, _ -> seeks++ } },
                )
                Box(modifier = Modifier.align(Alignment.Center).size(40.dp).clickable { taps++ })
            }
        }
    }

    @Test
    fun fingersSpreadApartArePinchOutAndHeardAsTheyMove() {
        player()
        compose.onNodeWithTag("player").performTouchInput {
            val near = width * 0.1f
            val far = width * 0.4f
            pinch(Offset(centerX - near, centerY), Offset(centerX - far, centerY), Offset(centerX + near, centerY), Offset(centerX + far, centerY))
        }
        assertEquals(1, begun)
        assertTrue(spreads.isNotEmpty())
        assertTrue(ended!! > 2.5f, "spread ${ended}")
    }

    @Test
    fun fingersBroughtTogetherArePinchIn() {
        player()
        compose.onNodeWithTag("player").performTouchInput {
            val near = width * 0.1f
            val far = width * 0.4f
            pinch(Offset(centerX - far, centerY), Offset(centerX - near, centerY), Offset(centerX + far, centerY), Offset(centerX + near, centerY))
        }
        assertTrue(ended!! < 0.5f, "spread ${ended}")
    }

    @Test
    fun oneFingerIsNoPinch() {
        player()
        compose.onNodeWithTag("player").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f, durationMillis = 300) }
        assertEquals(0, begun)
        assertEquals(null, ended)
    }

    @Test
    fun whereAPinchMayNotBeginTheFingersAreTheControlsAsEver() {
        player(mayBegin = { false })
        compose.onNodeWithTag("player").performTouchInput {
            val y = bottom - 30.dp.toPx()
            pinch(Offset(centerX - width * 0.1f, y), Offset(centerX - width * 0.45f, y), Offset(centerX + width * 0.1f, y), Offset(centerX + width * 0.45f, y))
        }
        assertEquals(0, begun)
        assertEquals(null, ended)
        assertTrue(seeks > 0)
    }

    @Test
    fun theFillChoiceIsKeptOnThePhone() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(PictureFillChoice.of(context).fill)
        PictureFillChoice.of(context).fill = true
        assertTrue(PictureFillChoice.of(context).fill)
        PictureFillChoice.of(context).fill = false
        assertFalse(PictureFillChoice.of(context).fill)
    }
}
