package cloud.nalet.chino.mobile.ui.player

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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What TalkBack reads of the player's round buttons: each says what it does
 * and the state it is in, as chino-web's player buttons do - never an
 * unnamed button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerButtonNamesTest {
    @get:Rule
    val compose = createComposeRule()

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
