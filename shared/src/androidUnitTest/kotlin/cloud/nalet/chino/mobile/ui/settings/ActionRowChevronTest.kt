package cloud.nalet.chino.mobile.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** A Settings row ends in the chevron icon every other screen draws, not a
 *  typed ">". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionRowChevronTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aRowEndsInTheChevronIconNotATypedCharacter() {
        var opened = 0
        compose.setContent { ActionRow(label = "Change server", subtitle = "media.example.com", onClick = { opened++ }) }
        compose.onNodeWithText(">").assertDoesNotExist()
        compose.onNodeWithText("Change server").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
