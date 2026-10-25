package cloud.nalet.chino.mobile.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
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
 * A season's control - "download 4 episodes · 3 GB", "3 of 8 downloaded" -
 * is one title action tall, as the episodes' controls under it are
 * (docs/chino title actions: every control 48 dp).
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
    fun howFarTheSeasonIsIsAsTallAsItsOffer() {
        compose.setContent {
            Box {
                StatusPill(
                    label = "3 of 8 downloaded",
                    icon = Lucide.Check,
                    tint = Color.White,
                    percent = null,
                    compact = true,
                    onClick = null,
                    modifier = Modifier.testTag("part"),
                    height = SEASON_CONTROL_HEIGHT,
                )
            }
        }
        compose.onNodeWithTag("part").assertHeightIsEqualTo(TITLE_ACTION_HEIGHT)
    }
}
