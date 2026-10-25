package cloud.nalet.chino.mobile.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * A card's menu says its actions as the title row does, in the mono face and
 * lowercase, as chino-web's card menu: "play" or "resume", "mark as watched"
 * or "watched", "remove from continue watching".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private fun fontFamilyOf(text: String): FontFamily? {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().layoutInput.style.fontFamily
    }

    @Test
    fun aCardInContinueWatchingSaysResumeWatchedAndRemoveInTheMonoFace() {
        val done = mutableListOf<String>()
        compose.setContent {
            CardMenu(
                expanded = true,
                onDismiss = { done += "closed" },
                watched = true,
                resumes = true,
                onPlay = { done += "played" },
                onToggleWatched = { done += "toggled" },
                onRemoveFromContinueWatching = { done += "removed" },
            )
        }
        for (label in listOf("resume", "watched", "remove from continue watching")) {
            assertEquals(FontFamily.Monospace, fontFamilyOf(label), label)
        }
        compose.onNodeWithText("resume").performClick()
        compose.runOnIdle { assertEquals(listOf("closed", "played"), done) }
    }

    @Test
    fun aCardNotStartedSaysPlayAndMarkAsWatched() {
        compose.setContent {
            CardMenu(expanded = true, onDismiss = {}, watched = false, onPlay = {}, onToggleWatched = {})
        }
        compose.onNodeWithText("play").assertExists()
        compose.onNodeWithText("mark as watched").assertExists()
        compose.onNodeWithText("remove from continue watching").assertDoesNotExist()
    }

    @Test
    fun theWordsAreLowercase() {
        for (w in listOf(CARD_PLAY, CARD_RESUME, watchedName(false), watchedName(true), REMOVE_FROM_CONTINUE_WATCHING)) {
            assertEquals(w.lowercase(), w)
        }
        assertEquals("mark as watched", watchedName(false))
        assertEquals("watched", watchedName(true))
    }
}
