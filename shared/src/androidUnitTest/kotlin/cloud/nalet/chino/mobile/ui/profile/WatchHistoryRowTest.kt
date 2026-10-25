package cloud.nalet.chino.mobile.ui.profile

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.ui.components.TITLE_ACTION_HEIGHT
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The watch history takes a title off with the title actions' watched
 *  toggle, on - the check, named "watched" - never an eye struck through. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchHistoryRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aWatchedTitlesToggleIsTheWatchedCheckAndTakesItOffWithoutOpeningIt() {
        var opened = 0
        var unwatched = 0
        compose.setContent {
            HistoryRow(
                item = Item(id = "f1", title = "Harbour Lights", kind = "movie", watchedAt = "2026-10-12T18:23:45Z"),
                seriesTitle = null,
                baseUrl = "http://localhost",
                token = "t",
                onClick = { opened++ },
                onUnwatch = { unwatched++ },
            )
        }
        compose.onNodeWithContentDescription("Mark as unwatched").assertDoesNotExist()
        compose.onNodeWithContentDescription("watched")
            .assertHeightIsEqualTo(TITLE_ACTION_HEIGHT)
            .assertWidthIsEqualTo(TITLE_ACTION_HEIGHT)
            .performClick()
        compose.runOnIdle {
            assertEquals(1, unwatched)
            assertEquals(0, opened)
        }
    }
}
