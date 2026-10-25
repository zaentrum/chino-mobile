package cloud.nalet.chino.mobile.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings' language setting is one control whatever the server says: a
 * field that opens a menu of one choice under it - as wide as the field,
 * inside the page, never over the top bar - for four languages as for every
 * language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LanguageSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private val dropdown = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList)
    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun setting(choices: List<LanguageChoice>, value: String, picked: MutableList<String>) {
        compose.setContent {
            Box(modifier = Modifier.width(360.dp)) {
                LanguageSetting(name = "subtitle language", value = value, choices = choices, onSelect = { picked += it })
            }
        }
    }

    private fun opensUnderItsFieldAsWideAsIt(choices: List<LanguageChoice>, value: String, firstName: String) {
        val picked = mutableListOf<String>()
        setting(choices, value, picked)
        val field = compose.onNodeWithContentDescription("subtitle language").assert(dropdown).getBoundsInRoot()
        compose.onNodeWithContentDescription("subtitle language").performClick()
        val first = compose.onNode(hasText(firstName) and radio).getBoundsInRoot()
        // Under the field, inside its width: the page's, not a popup's.
        assertTrue(first.top >= field.bottom, "$first under $field")
        assertTrue(first.left >= field.left && first.right <= field.right, "$first within $field")
        compose.onNode(hasText("German") and radio).performClick()
        compose.runOnIdle { assertEquals(listOf("de"), picked) }
        // Picked: the menu closes, the field stays.
        compose.onNode(hasText("German") and radio).assertDoesNotExist()
    }

    @Test
    fun theServersFourLanguagesAreTheSameControlAsEveryLanguage() {
        opensUnderItsFieldAsWideAsIt(subtitleSettingChoices(listOf("en", "de", "zh-Hans"), "off"), "off", "off")
    }

    @Test
    fun everyLanguageIsOneMenuTooTheOwnFirst() {
        opensUnderItsFieldAsWideAsIt(subtitleSettingChoices(emptyList(), "off", device = "de-CH"), "off", "off")
    }

    @Test
    fun subtitlesSetOffTheFieldSaysOffAsThePlayersMenuDoes() {
        setting(subtitleSettingChoices(listOf("en", "de"), "off"), "off", mutableListOf())
        compose.onNodeWithContentDescription("subtitle language")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "off"))
        // Drawn as it is said: "off", lowercase, never "Off".
        compose.onNode(hasText("off") and dropdown).assertExists()
        // The same word on a server that names no languages.
        assertEquals("off", subtitleSettingChoices(emptyList(), "off").first().name)
    }

    @Test
    fun theFieldSaysWhatIsSetAndTheMenuPicksIt() {
        setting(subtitleSettingChoices(listOf("en", "de"), "deu"), "deu", mutableListOf())
        compose.onNodeWithContentDescription("subtitle language")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "German"))
            .performClick()
        compose.onNode(hasText("German") and radio).assertIsSelected()
        compose.onNode(hasText("English") and radio).assertIsNotSelected()
    }
}
