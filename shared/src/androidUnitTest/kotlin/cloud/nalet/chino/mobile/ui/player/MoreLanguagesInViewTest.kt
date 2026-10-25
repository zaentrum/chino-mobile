package cloud.nalet.chino.mobile.ui.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "more languages", opened at the foot of a long subtitles menu, scrolls the
 * menu so its first rows show - nothing new waiting under the menu's edge
 * until the person scrolls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w914dp-h411dp")
class MoreLanguagesInViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val group = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()),
        false,
        intArrayOf(C.FORMAT_HANDLED),
        booleanArrayOf(false),
    )

    /** A sidecar in [lang], named as the menu names it. */
    private fun track(lang: String) = SubtitleTrack(
        id = lang,
        label = subtitleLabel(SubtitleLabelInput(lang)),
        language = lang,
        tag = subtitleLanguage(lang, null),
        title = null,
        key = "sidecar:$lang",
        forced = false,
        image = false,
        sdh = false,
        selected = false,
        group = group,
        trackIndex = 0,
    )

    // A server set up for twelve languages: the menu is taller than its
    // room, "more languages" at its foot.
    private val server = listOf("en", "de", "fr", "es", "it", "nl", "sv", "da", "fi", "pl", "pt", "ja")

    @Test
    fun openedAtTheFootTheMenuScrollsSoItsFirstRowsShow() {
        val tracks = (server + listOf("ara", "bul", "cat", "ces", "ell", "heb")).map(::track)
        compose.setContent {
            CaptionsMenuCard(tracks = tracks, subtitlePref = "off", audioPref = "orig", serverLanguages = server, onSelect = {})
        }
        // Scrolled to the foot, as far as it goes: "more languages" the last row.
        compose.onNodeWithText("more languages").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Arabic").assertDoesNotExist()
        compose.onNodeWithText("more languages").performClick()
        // Its first rows in view, the row that opened them above them.
        compose.onNodeWithText("Arabic").assertIsDisplayed()
        compose.onNodeWithText("Bulgarian").assertIsDisplayed()
        compose.onNodeWithText("Catalan").assertIsDisplayed()
        compose.onNodeWithText("more languages").assertIsDisplayed()
        // The menu's top went up out of view to make room.
        compose.onNodeWithText("subtitles").assertIsNotDisplayed()
    }
}
