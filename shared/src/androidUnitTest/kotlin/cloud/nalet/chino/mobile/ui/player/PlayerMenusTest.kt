package cloud.nalet.chino.mobile.ui.player

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import cloud.nalet.chino.mobile.data.api.QualityRung
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player's menus of one choice tell a screen reader which row is
 *  picked: each row a radio button, selected or not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerMenusTest {
    @get:Rule
    val compose = createComposeRule()

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun theSpeedMenusPickedRowIsSelectedTheOthersNot() {
        compose.setContent { SpeedMenuCard(currentSpeed = 1.5f, onSelect = {}) }
        compose.onNodeWithText("1.5x").assertIsSelected().assert(radio)
        compose.onNodeWithText("0.5x").assertIsNotSelected().assert(radio)
        // "normal", lowercase; "2x", never "2.0x".
        compose.onNodeWithText("normal").assertIsNotSelected()
        compose.onNodeWithText("2x").assertExists()
        // The header in the mono face, a heading.
        compose.onNodeWithText("speed").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun theQualityMenusPickedRowIsSelected() {
        val rungs = listOf(QualityRung("high", "High (1080p)"), QualityRung("medium", "Medium (720p)"))
        compose.setContent { QualityMenuCard(qualities = rungs, currentQuality = "medium", onSelect = {}) }
        compose.onNodeWithText("Medium (720p)").assertIsSelected().assert(radio)
        compose.onNodeWithText("High (1080p)").assertIsNotSelected()
    }
}
