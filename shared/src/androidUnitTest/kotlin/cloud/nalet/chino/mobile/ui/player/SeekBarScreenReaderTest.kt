package cloud.nalet.chino.mobile.ui.player

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import cloud.nalet.chino.mobile.ui.AccessibilityNodes
import cloud.nalet.chino.mobile.ui.screenReaderOn
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seek bar with TalkBack's focus on it while the title plays: it tells
 * the time as the focus lands, then holds it - Android hears no change of
 * its state each second, which TalkBack would read out over the film - and
 * tells again where a seek goes and when the title stops. Off the bar it
 * follows the clock, so the focus landing on it reads the time as it is.
 * The player's wiring as PlayerScreen has it: its 250 ms tick, the focus
 * ([rememberScreenReaderFocus]), the bar's position ([seekBarSpokenMs]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Android's nodes are built from the screen's regions, which want the real
// graphics ([AccessibilityNodes]).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeekBarScreenReaderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val lengthMs = 653_000L
    private var positionMs by mutableStateOf(67_000L)
    private var spokenMs by mutableStateOf(67_000L)
    private var playing = true
    private val seeks = mutableListOf<Long>()
    private lateinit var focus: State<ScreenReaderFocus>

    /** The state changes Android sends a screen reader: what TalkBack reads
     *  out for a node its focus is on. */
    private val stateChanges = mutableListOf<AccessibilityEvent>()

    private fun showPlayer() {
        screenReaderOn(compose.activity)
        // Above the player's view, where the events go on to the screen reader.
        compose.activity.findViewById<ViewGroup>(android.R.id.content).accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                    val state = AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.contentChangeTypes and state != 0) {
                        stateChanges += AccessibilityEvent(event)
                    }
                    return super.onRequestSendAccessibilityEvent(host, child, event)
                }
            }
        compose.setContent {
            focus = rememberScreenReaderFocus()
            Column {
                ChromeButton(icon = Lucide.Pause, onClick = {}, name = PlayerButtons.playPause(true))
                Scrubber(
                    positionMs = positionMs,
                    durationMs = lengthMs,
                    bufferedMs = positionMs,
                    segments = emptyList(),
                    trickplayCues = emptyList(),
                    trickplayBaseUrl = "",
                    streamToken = "",
                    onScrubStart = {},
                    onScrubUpdate = {},
                    // The player's seekTo: it goes there, and the bar says so.
                    onScrubCommit = { ms ->
                        seeks += ms
                        positionMs = ms
                        spokenMs = seekBarSpokenMs(spokenMs, ms, playing = false, focusOnBar = false)
                    },
                    spokenPositionMs = spokenMs,
                )
            }
        }
        settle()
    }

    /** The player's tick: the position played, and what the bar tells. */
    private fun tick(toMs: Long) {
        compose.runOnIdle {
            positionMs = toMs
            spokenMs = seekBarSpokenMs(spokenMs, toMs, playing, focusOnBar = focus.value == ScreenReaderFocus.SeekBar)
        }
        settle()
    }

    /** Lets Compose send what it has for the screen reader (it batches its
     *  changes, a tenth of a second apart). */
    private fun settle() {
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
    }

    private fun bar(): SemanticsNode = compose.onNodeWithContentDescription(PlayerButtons.SEEK_NAME).fetchSemanticsNode()

    private fun barSays(): String? = AccessibilityNodes(compose.activity).of(bar())?.stateDescription?.toString()

    private fun perform(node: SemanticsNode, action: Int) {
        compose.runOnIdle { assertTrue(AccessibilityNodes(compose.activity).perform(node, action)) }
        settle()
    }

    @Test
    fun aFocusedSeekBarHoldsItsTimeWhileTheTitlePlaysAndTellsWhereASeekGoesAndWhereItStops() {
        showPlayer()
        // Off the bar, it follows the clock.
        tick(67_250)
        tick(68_000)
        assertEquals("1:08 of 10:53", barSays())
        assertTrue(stateChanges.isNotEmpty(), "a bar following the clock changes its state")

        // TalkBack's focus lands on it: it is the seek bar, and reads the time as it is.
        perform(bar(), AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        assertEquals(ScreenReaderFocus.SeekBar, focus.value)
        assertEquals("1:08 of 10:53", barSays())

        // Twelve seconds play on: nothing for TalkBack to read out.
        stateChanges.clear()
        var at = 68_000L
        repeat(48) {
            at += 250
            tick(at)
        }
        assertEquals("1:08 of 10:53", barSays())
        assertEquals(emptyList(), stateChanges.map { it.contentChangeTypes })

        // Its step on goes 10 s on from the picture (1:20), not from what it
        // read (1:08) - and it says where it went.
        perform(bar(), AccessibilityAction.ACTION_SCROLL_FORWARD.id)
        val seek = seeks.single()
        assertTrue(seek in 90_000L..90_200L, "a step on from 1:20: $seek")
        assertEquals("1:30 of 10:53", barSays())
        assertTrue(stateChanges.isNotEmpty(), "the seek's new time")

        // Playing on from there, held again.
        stateChanges.clear()
        tick(seek + 2_000)
        tick(seek + 4_000)
        assertEquals("1:30 of 10:53", barSays())
        assertTrue(stateChanges.isEmpty())

        // The title stops: where it stopped.
        playing = false
        tick(seek + 4_000)
        assertEquals("1:34 of 10:53", barSays())

        // TalkBack's focus on the pause button: the bar follows the clock again.
        playing = true
        perform(compose.onNodeWithContentDescription("pause").fetchSemanticsNode(), AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        assertEquals(ScreenReaderFocus.Content, focus.value)
        tick(seek + 9_000)
        assertEquals("1:39 of 10:53", barSays())
    }

    @Test
    fun theBarIsToldApartFromTheOtherControlsByItsNameAndItsKind() {
        val bar = AccessibilityNodeInfo().apply {
            className = "android.widget.SeekBar"
            contentDescription = "seek"
        }
        assertTrue(isSeekBar(bar))
        assertTrue(!isSeekBar(AccessibilityNodeInfo().apply { className = "android.widget.Button"; contentDescription = "seek" }))
        assertTrue(!isSeekBar(AccessibilityNodeInfo().apply { className = "android.widget.SeekBar"; contentDescription = "volume" }))
        assertTrue(!isSeekBar(null))
    }
}
