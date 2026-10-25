package cloud.nalet.chino.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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

    /** The font family a text is laid out in: one, wherever it is - the
     *  hero lays out every title's buttons to keep one height, never shown. */
    private fun fontFamilyOf(text: String): FontFamily? {
        val results = mutableListOf<TextLayoutResult>()
        for (node in compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()) {
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        }
        return results.map { it.layoutInput.style.fontFamily }.distinct().single()
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
    @Config(qualifiers = "w411dp-h891dp")
    fun aPartlyWatchedTitleOnTheHeroSaysResumeAndStartOverAsItsPageDoes() {
        val played = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            Box(modifier = Modifier.width(360.dp)) {
                HeroBanner(
                    pool = listOf(film),
                    baseUrl = "http://localhost",
                    streamToken = "t",
                    plays = mapOf("f1" to cloud.nalet.chino.mobile.ui.home.HeroPlay("f1", "resume 0:58", resumes = true, fromStart = false)),
                    onPlay = { id, startOver -> played += id to startOver },
                )
            }
        }
        val picture = compose.onNodeWithContentDescription("Harbour Lights").getBoundsInRoot()
        val resume = compose.onNodeWithText("resume 0:58").assertHeightIsEqualTo(TITLE_ACTION_HEIGHT).getBoundsInRoot()
        val startOver = compose.onNodeWithText("start over").assertHeightIsEqualTo(TITLE_ACTION_HEIGHT).getBoundsInRoot()
        compose.onNodeWithText("see details").assertHeightIsEqualTo(TITLE_ACTION_HEIGHT)
        // Nothing on the still; "start over" right after "resume".
        for (b in listOf(resume, startOver)) assertTrue(b.top >= picture.bottom + HERO_PICTURE_GAP - 0.5.dp, "$b under $picture")
        assertEquals(resume.top, startOver.top)
        assertEquals(FontFamily.Monospace, fontFamilyOf("start over"))
        compose.onNodeWithText("resume 0:58").performClick()
        compose.onNodeWithText("start over").performClick()
        compose.runOnIdle { assertEquals(listOf("f1" to false, "f1" to true), played) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun theHeroKeepsOneHeightWhateverTheSlideNothingBelowJumpsAsItTurns() {
        // A partly watched title - "resume 0:58" and "start over" wrap to a
        // second line on a phone - and one not started, one line. Here a
        // hero so narrow that each button takes a line of its own: four
        // lines for the one, three for the other.
        val notStarted = film.copy(id = "f2", title = "Quiet Water")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(modifier = Modifier.width(120.dp).testTag("hero")) {
                HeroBanner(
                    pool = listOf(film, notStarted),
                    baseUrl = "http://localhost",
                    streamToken = "t",
                    rotateEveryMs = 5_000L,
                    plays = mapOf("f1" to cloud.nalet.chino.mobile.ui.home.HeroPlay("f1", "resume 0:58", resumes = true, fromStart = false)),
                )
            }
        }
        compose.mainClock.advanceTimeBy(100)
        val first = compose.onNodeWithTag("hero").getBoundsInRoot()
        val firstResumes = compose.onAllNodesWithText("start over").fetchSemanticsNodes().isNotEmpty()
        // The carousel turns, and settles before it turns again.
        compose.mainClock.advanceTimeBy(7_000)
        val second = compose.onNodeWithTag("hero").getBoundsInRoot()
        val secondResumes = compose.onAllNodesWithText("start over").fetchSemanticsNodes().isNotEmpty()
        assertTrue(firstResumes != secondResumes, "both slides shown")
        assertEquals(first.height, second.height)
    }

    @Test
    fun asTallAsTheTallestTakesTheHeightOfTheTallestOtherAndShowsOnlyItsOwn() {
        compose.setContent {
            Box(modifier = Modifier.width(200.dp)) {
                AsTallAsTheTallest(
                    modifier = Modifier.testTag("room"),
                    others = {
                        Box(modifier = Modifier.size(100.dp, 104.dp)) { androidx.compose.material3.Text("another") }
                        Box(modifier = Modifier.size(100.dp, 48.dp))
                    },
                ) { Box(modifier = Modifier.size(100.dp, 48.dp)) { androidx.compose.material3.Text("this one") } }
            }
        }
        compose.onNodeWithTag("room").assertHeightIsEqualTo(104.dp).assertWidthIsEqualTo(100.dp)
        compose.onNodeWithText("this one").assertExists()
        // The others are measured, never shown or read.
        compose.onNodeWithText("another").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun aTitleNotStartedOnTheHeroSaysPlayAloneNoStartOver() {
        heroAt(360.dp)
        compose.onNodeWithText("play").assertHeightIsEqualTo(TITLE_ACTION_HEIGHT)
        compose.onNodeWithText("start over").assertDoesNotExist()
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
