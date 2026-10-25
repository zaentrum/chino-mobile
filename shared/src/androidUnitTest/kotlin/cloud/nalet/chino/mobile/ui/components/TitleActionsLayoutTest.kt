package cloud.nalet.chino.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.Trailer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The title actions as docs/chino draws them, laid out on the JVM: the
 * labels in the mono face, every control 48 dp, the "⌄" pressed over a full
 * 48 dp square, and Home's hero with its buttons beside or under its picture,
 * never on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TitleActionsLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val film = Item(
        id = "f1",
        title = "Harbour Lights",
        year = 2024,
        trailers = listOf(Trailer(url = "https://example.org/trailer", site = "example", title = "Official Trailer")),
    )

    /** The font family a text node is laid out in. */
    private fun fontFamilyOf(text: String): FontFamily? {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().layoutInput.style.fontFamily
    }

    @Test
    fun aLabelledActionIsOneActionTallItsLabelInTheMonoFace() {
        compose.setContent { TitleActionButton(label = "resume 1:43", icon = null, primary = true, onClick = {}) }
        compose.onNodeWithText("resume 1:43").assertHeightIsEqualTo(TITLE_ACTION_HEIGHT)
        // Outside the app's theme the mono face is the platform's monospace:
        // the label takes ChinoMono, not the body face.
        assertEquals(FontFamily.Monospace, fontFamilyOf("resume 1:43"))
    }

    @Test
    fun theCaretIsDrawnFortyWideAndPressedOverAFullSquare() {
        var chosen = 0
        var toggled = 0
        compose.setContent {
            Row { AddToListControl(inWatchlist = false, onToggle = { toggled++ }, onChoose = { chosen++ }, modifier = Modifier.testTag("frame")) }
        }
        // What reads out as "choose lists" is the 48 dp square.
        compose.onNodeWithContentDescription("choose lists").assertWidthIsEqualTo(TITLE_ACTION_HEIGHT).assertHeightIsEqualTo(TITLE_ACTION_HEIGHT)
        // The frame: 48 + 1 + 40.
        compose.onNodeWithTag("frame").assertWidthIsEqualTo(89.dp)
        // A press 4 dp past the frame's edge, in the gap to the next
        // control, still opens the picker; one on the main part toggles.
        compose.onRoot().performTouchInput { click(Offset(93.dp.toPx(), 24.dp.toPx())) }
        compose.onRoot().performTouchInput { click(Offset(20.dp.toPx(), 24.dp.toPx())) }
        compose.runOnIdle {
            assertEquals(1, chosen)
            assertEquals(1, toggled)
        }
    }

    private fun heroAt(width: Dp) {
        compose.setContent {
            Box(modifier = Modifier.width(width)) {
                HeroBanner(pool = listOf(film), baseUrl = "http://localhost", streamToken = "t")
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun onAPhoneTheHerosButtonsAreUnderItsPicture48DpInOneLine() {
        heroAt(360.dp)
        val picture = compose.onNodeWithContentDescription("Harbour Lights").getBoundsInRoot()
        val play = compose.onNodeWithText("play").assertHeightIsEqualTo(TITLE_ACTION_HEIGHT).getBoundsInRoot()
        val details = compose.onNodeWithText("see details").assertHeightIsEqualTo(TITLE_ACTION_HEIGHT).getBoundsInRoot()
        val trailer = compose.onNodeWithContentDescription("trailer")
            .assertHeightIsEqualTo(TITLE_ACTION_HEIGHT).assertWidthIsEqualTo(TITLE_ACTION_HEIGHT).getBoundsInRoot()
        // Nothing on the still: the buttons start 16 dp under it.
        for (b in listOf(play, details, trailer)) assertTrue(b.top >= picture.bottom + HERO_PICTURE_GAP - 0.5.dp, "$b under $picture")
        // One line on a 360 dp phone.
        assertEquals(play.top, details.top)
        assertEquals(play.top, trailer.top)
        assertEquals(FontFamily.Monospace, fontFamilyOf("see details"))
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun onANarrowTabletTheButtonsWrapUnderTheOverviewNeverOverIt() {
        // A tablet held upright: the hero 688 dp wide beside the rail, its
        // buttons' column too narrow for two of them in a row - three rows -
        // and a long title and overview above them.
        val long = film.copy(
            title = "The Lighthouse Keepers of the Northern Coast",
            overview = "Two keepers share a long winter on a rock far out at sea, where the supply boat comes once a " +
                "month and the lamp must never go out. As the storms close in, the logbook fills with entries that " +
                "neither of them remembers writing, and the radio picks up a voice from the mainland every night.",
        )
        compose.setContent {
            Box(modifier = Modifier.width(720.dp)) {
                HeroBanner(pool = listOf(long), baseUrl = "http://localhost", streamToken = "t")
            }
        }
        val overview = compose.onNodeWithText(long.overview!!, substring = true).getBoundsInRoot()
        val buttons = listOf("play", "see details", "trailer").map { compose.onNodeWithText(it).getBoundsInRoot() }
        for (b in buttons) assertTrue(b.top >= overview.bottom + HERO_TEXT_BUTTONS_GAP - 0.5.dp, "$b under $overview")
    }

    @Test
    @Config(qualifiers = "w1024dp-h768dp")
    fun onATabletTheHerosButtonsAreLeftOfItsPicture() {
        heroAt(900.dp)
        val picture = compose.onNodeWithContentDescription("Harbour Lights").getBoundsInRoot()
        for (label in listOf("play", "see details", "trailer")) {
            val b = compose.onNodeWithText(label).assertHeightIsEqualTo(TITLE_ACTION_HEIGHT).getBoundsInRoot()
            assertTrue(b.right <= picture.left, "$label $b left of $picture")
        }
    }
}
