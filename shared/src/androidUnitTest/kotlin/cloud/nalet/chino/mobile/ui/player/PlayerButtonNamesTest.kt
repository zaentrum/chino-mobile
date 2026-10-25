package cloud.nalet.chino.mobile.ui.player

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import cloud.nalet.chino.mobile.ui.AccessibilityNodes
import cloud.nalet.chino.mobile.ui.offers
import cloud.nalet.chino.mobile.ui.screenReaderOn
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What TalkBack reads of the player's round buttons: each says what it does
 * and the state it is in, as chino-web's player buttons do - never an
 * unnamed button. Read twice: in Compose's semantics, and as Android hands
 * them to a screen reader and to uiautomator's dump ([AccessibilityNodes]),
 * where a button with a node inside it arrives as a nameless view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Android's nodes are built from the screen's regions, which want the real
// graphics ([AccessibilityNodes]).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerButtonNamesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun controls(
        playing: Boolean = true,
        muted: Boolean = false,
        fullscreen: Boolean = false,
        subtitleOn: String? = "English · for deaf and hard of hearing",
        audioTracks: List<AudioTrack> = emptyList(),
        onPlayPause: () -> Unit = {},
        onAudio: () -> Unit = {},
    ) {
        screenReaderOn(compose.activity)
        compose.setContent {
            BottomControls(
                isPlaying = playing,
                muted = muted,
                volume = 0.8f,
                captionsOn = subtitleOn != null,
                subtitleOn = subtitleOn,
                qualityName = "Medium (720p)",
                fullscreen = fullscreen,
                playbackSpeed = 1.5f,
                openPopover = OpenPopover.NONE,
                canPrev = true,
                canNext = true,
                hasQualities = true,
                info = null,
                audioTracks = audioTracks,
                onPlayPause = onPlayPause,
                onSkipBack = {},
                onSkipForward = {},
                onToggleMute = {},
                onVolumeChange = {},
                onVolumeDragStart = {},
                onVolumeDragEnd = {},
                onToggleCaptions = {},
                onTogglePopover = { if (it == OpenPopover.AUDIO) onAudio() },
                onSelectSpeed = {},
                onToggleFullscreen = {},
                onPrevEpisode = {},
                onNextEpisode = {},
            )
        }
    }

    @Test
    fun everyRoundButtonSaysWhatItDoesAndItsState() {
        var pressed = 0
        controls(onPlayPause = { pressed++ })
        compose.onNodeWithContentDescription("pause").assert(button).performClick()
        compose.onNodeWithContentDescription("volume").assert(button).assert(state("80%"))
        compose.onNodeWithContentDescription("subtitles").assert(button).assert(state("English · for deaf and hard of hearing"))
        compose.onNodeWithContentDescription("speed").assert(button).assert(state("1.5x"))
        compose.onNodeWithContentDescription("quality").assert(button).assert(state("Medium (720p)"))
        compose.onNodeWithContentDescription("previous episode").assert(button)
        compose.onNodeWithContentDescription("next episode").assert(button)
        compose.onNodeWithContentDescription("playback info").assert(button)
        compose.onNodeWithContentDescription("fullscreen").assert(button)
        compose.runOnIdle { assertEquals(1, pressed) }
        // No button there without a name.
        val clickable = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(clickable.isNotEmpty())
        for (node in clickable) {
            val name = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
            assertTrue(name.isNotBlank(), "a button without a name: ${node.config}")
        }
    }

    /** One audio track as the player lists it. */
    private fun audio(language: String, label: String, selected: Boolean): AudioTrack {
        val format = Format.Builder().setId(label).setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(language).setLabel(label).build()
        val group = Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
        return AudioTrack(id = label, label = label, detail = "AAC · Stereo", language = language, name = label, selected = selected, playable = true, group = group, trackIndex = 0)
    }

    @Test
    fun theAudioChipIsANamedButtonItsValueTheTrackPlayingNeverTheCode() {
        var opened = 0
        controls(
            audioTracks = listOf(audio("eng", "English", selected = false), audio("eng", "English · Commentary", selected = true)),
            onAudio = { opened++ },
        )
        compose.onNodeWithContentDescription("audio")
            .assert(button)
            .assert(state("English · Commentary"))
            .performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        // The code is drawn, not read: no "ENG", no "Audio: English".
        compose.onAllNodesWithText("ENG", useUnmergedTree = true).assertCountEquals(0)
        assertTrue(compose.onAllNodes(hasContentDescription("Audio", substring = true, ignoreCase = false)).fetchSemanticsNodes().isEmpty())
        // Lowercase, as "volume", "subtitles", "speed", "quality".
        assertEquals(PlayerButtonName("audio", "German"), PlayerButtons.audio("German"))
        // Still no button without a name.
        for (node in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val name = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
            assertTrue(name.isNotBlank(), "a button without a name: ${node.config}")
        }
    }

    @Test
    fun theAudioChipReachesAndroidAsOneButtonNamedAudioWithTheTrackPlaying() {
        controls(audioTracks = listOf(audio("eng", "English", selected = false), audio("eng", "English · Commentary", selected = true)))
        val chip = compose.onNode(hasContentDescription("audio") and hasClickAction()).fetchSemanticsNode()
        val info = assertNotNull(AccessibilityNodes(compose.activity).of(chip))
        // What the dump lists for it: one clickable Button named "audio",
        // nothing below it - not a nameless view over a node with the name
        // and another with the role.
        assertEquals("android.widget.Button", info.className)
        assertEquals("audio", info.contentDescription?.toString())
        assertEquals("English · Commentary", info.stateDescription?.toString())
        assertTrue(info.isClickable)
        assertEquals(0, info.childCount, "the chip's code or its name as nodes of their own")
    }

    @Test
    fun everyClickableNodeReachesAndroidAsANamedButton() {
        controls(audioTracks = listOf(audio("eng", "English", selected = true)))
        val nodes = AccessibilityNodes(compose.activity)
        val clickable = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { nodes.of(it) }
            .filter { it.isClickable }
        // Those on the test's narrow screen: pause, volume, audio, subtitles,
        // speed, quality, the episodes, fullscreen.
        val names = clickable.map { it.contentDescription?.toString() }
        assertTrue(names.containsAll(listOf("pause", "volume", "audio", "subtitles", "speed", "quality", "fullscreen")), "$names")
        for (info in clickable) {
            assertEquals("android.widget.Button", info.className, "'${info.contentDescription}'")
            assertTrue(!info.contentDescription.isNullOrBlank(), "a clickable ${info.className} without a name")
        }
    }

    /** The seek bar at [positionMs] of [durationMs], its seeks in [seeks]. */
    private fun seekBar(positionMs: Long, durationMs: Long, seeks: MutableList<Long>) {
        screenReaderOn(compose.activity)
        compose.setContent {
            Scrubber(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = positionMs,
                segments = emptyList(),
                trickplayCues = emptyList(),
                trickplayBaseUrl = "",
                streamToken = "",
                onScrubStart = {},
                onScrubUpdate = {},
                onScrubCommit = { seeks += it },
            )
        }
    }

    @Test
    fun theSeekBarIsASliderWithThePositionAndTheLengthThatAScreenReaderCanSet() {
        val seeks = mutableListOf<Long>()
        seekBar(positionMs = 67_000, durationMs = 653_000, seeks = seeks)
        val bar = compose.onNodeWithContentDescription("seek").assert(state("1:07 of 10:53")).fetchSemanticsNode()
        val info = assertNotNull(AccessibilityNodes(compose.activity).of(bar))
        // A slider to Android - a seek bar - named as the buttons are.
        assertEquals("android.widget.SeekBar", info.className)
        assertEquals("seek", info.contentDescription?.toString())
        assertEquals("1:07 of 10:53", info.stateDescription?.toString())
        val range = assertNotNull(info.rangeInfo)
        assertEquals(0f, range.min)
        assertEquals(653f, range.max)
        assertEquals(67f, range.current)
        assertTrue(info.offers(AccessibilityAction.ACTION_SET_PROGRESS.id))
        assertTrue(info.offers(AccessibilityAction.ACTION_SCROLL_FORWARD.id))
        assertTrue(info.offers(AccessibilityAction.ACTION_SCROLL_BACKWARD.id))
        // Set as a screen reader sets it: the player seeks there.
        val nodes = AccessibilityNodes(compose.activity)
        compose.runOnIdle {
            nodes.perform(
                bar,
                AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 300f) },
            )
        }
        compose.runOnIdle { assertEquals(listOf(300_000L), seeks) }
        // Its own gesture moves it a step of 10 s on.
        compose.runOnIdle { nodes.perform(bar, AccessibilityAction.ACTION_SCROLL_FORWARD.id) }
        compose.runOnIdle {
            assertEquals(2, seeks.size)
            assertTrue(seeks[1] in 77_000L..77_200L, "a step from 1:07: ${seeks[1]}")
        }
        // The times beside the bar are its state, not nodes of their own.
        assertEquals(0, info.childCount)
        compose.onAllNodesWithText("1:07").assertCountEquals(0)
        compose.onAllNodesWithText("10:53").assertCountEquals(0)
    }

    @Test
    fun theSeekBarWithoutALengthYetSaysWhereItIsAndSeeksNowhere() {
        val seeks = mutableListOf<Long>()
        seekBar(positionMs = 0, durationMs = 0, seeks = seeks)
        val bar = compose.onNodeWithContentDescription("seek").assert(state("0:00")).fetchSemanticsNode()
        val info = assertNotNull(AccessibilityNodes(compose.activity).of(bar))
        assertTrue(!info.offers(AccessibilityAction.ACTION_SET_PROGRESS.id))
    }

    @Test
    fun theSeekBarsWords() {
        assertEquals(PlayerButtonName("seek", "1:07 of 10:53"), PlayerButtons.seek(67_000, 653_000))
        assertEquals(PlayerButtonName("seek", "1:02:03 of 2:00:00"), PlayerButtons.seek(3_723_000, 7_200_000))
        // Never past the end, never before the start.
        assertEquals(PlayerButtonName("seek", "10:53 of 10:53"), PlayerButtons.seek(700_000, 653_000))
        assertEquals(PlayerButtonName("seek", "0:00 of 10:53"), PlayerButtons.seek(-5, 653_000))
        assertEquals(PlayerButtonName("seek", "0:42"), PlayerButtons.seek(42_000, 0))
        // A step of 10 s: 64 stops between the ends of 10:53, none for 15 s.
        assertEquals(64, seekSteps(653_000))
        assertEquals(0, seekSteps(15_000))
        assertEquals(0, seekSteps(0))
    }

    @Test
    fun theStateChangesWithThePlayer() {
        controls(playing = false, muted = true, fullscreen = true, subtitleOn = null)
        compose.onNodeWithContentDescription("play").assert(button)
        compose.onNodeWithContentDescription("volume").assert(state("muted"))
        compose.onNodeWithContentDescription("subtitles").assert(state("off"))
        compose.onNodeWithContentDescription("exit fullscreen").assert(button)
    }

    @Test
    fun theNamesInWords() {
        assertEquals(PlayerButtonName("pause"), PlayerButtons.playPause(true))
        assertEquals(PlayerButtonName("play"), PlayerButtons.playPause(false))
        assertEquals(PlayerButtonName("volume", "muted"), PlayerButtons.volume(muted = false, volume = 0f))
        assertEquals(PlayerButtonName("volume", "100%"), PlayerButtons.volume(muted = false, volume = 1f))
        assertEquals(PlayerButtonName("speed", "normal"), PlayerButtons.speed(1f))
        assertEquals(PlayerButtonName("subtitles", "off"), PlayerButtons.subtitles(""))
        assertEquals(PlayerButtonName("back"), PlayerButtons.back)
        assertEquals(PlayerButtonName("home"), PlayerButtons.home)
    }
}
