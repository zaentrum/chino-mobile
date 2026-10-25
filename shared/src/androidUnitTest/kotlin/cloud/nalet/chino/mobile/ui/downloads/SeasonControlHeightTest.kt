package cloud.nalet.chino.mobile.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import cloud.nalet.chino.mobile.ui.components.TITLE_ACTION_HEIGHT
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * A season's control - "download 4 episodes · 3 GB" - is one title action
 * tall, as the episodes' controls under it are (docs/chino title actions:
 * every control 48 dp); how far it is ("1 of 4 downloaded") is a status
 * beside it, as tall, drawn as no button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeasonControlHeightTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSeasonsOfferAndHowFarItIsAreFortyEightDpTall() {
        assertEquals(TITLE_ACTION_HEIGHT, SEASON_CONTROL_HEIGHT)
        compose.setContent {
            Box {
                StatusPill(
                    label = "download 4 episodes · 3 GB",
                    icon = Lucide.Download,
                    tint = Color.White,
                    percent = null,
                    compact = true,
                    onClick = {},
                    modifier = Modifier.testTag("offer"),
                    height = SEASON_CONTROL_HEIGHT,
                )
            }
        }
        compose.onNodeWithTag("offer").assertHeightIsEqualTo(TITLE_ACTION_HEIGHT)
    }

    @Test
    fun howFarTheSeasonIsIsAStatusNotAButton() {
        compose.setContent {
            Box {
                DownloadStatusLine(
                    label = "1 of 4 downloaded",
                    complete = false,
                    modifier = Modifier.testTag("part").heightIn(min = SEASON_CONTROL_HEIGHT),
                )
            }
        }
        // As tall as the offer beside it, and nothing to press.
        compose.onNodeWithTag("part").assertHeightIsEqualTo(TITLE_ACTION_HEIGHT).assert(hasClickAction().not())
        compose.onNodeWithText("1 of 4 downloaded").assert(hasClickAction().not())
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertCountEquals(0)
    }
}
