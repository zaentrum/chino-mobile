package cloud.nalet.chino.mobile.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** The title actions: one size, and add to list's main part named for the
 *  Watchlist it shows and toggles. */
class TitleActionsTest {
    @Test
    fun everyActionIsOneSize() {
        assertEquals(48.dp, TITLE_ACTION_HEIGHT)
        assertEquals(8.dp, TITLE_ACTION_GAP)
    }

    @Test
    fun aQualityIsANameAndItsHeight() {
        // The server's table, for an older server's height-only label.
        assertEquals("Ultra (2160p)", cloud.nalet.chino.mobile.ui.player.qualityTitle(2160))
        assertEquals("High (1080p)", cloud.nalet.chino.mobile.ui.player.qualityTitle(1080))
        assertEquals("Medium (900p)", cloud.nalet.chino.mobile.ui.player.namedQuality("900p"))
        assertEquals("Medium (720p)", cloud.nalet.chino.mobile.ui.player.namedQuality("720p"))
        assertEquals("Low (540p)", cloud.nalet.chino.mobile.ui.player.namedQuality("540p"))
        assertEquals("Low (480p)", cloud.nalet.chino.mobile.ui.player.namedQuality("480p"))
        assertEquals("Lowest (360p)", cloud.nalet.chino.mobile.ui.player.qualityTitle(360))
        assertEquals("Lowest (240p)", cloud.nalet.chino.mobile.ui.player.qualityTitle(240))
        // The server's name: as it gives it, a height never twice.
        assertEquals("Medium (720p)", cloud.nalet.chino.mobile.ui.player.namedQuality("Medium (720p)"))
        assertEquals("Original", cloud.nalet.chino.mobile.ui.player.namedQuality("Original"))
        assertEquals("Auto", cloud.nalet.chino.mobile.ui.player.namedQuality("Auto"))
        assertEquals("High", cloud.nalet.chino.mobile.ui.player.labelForQuality("high"))
        assertEquals("Original", cloud.nalet.chino.mobile.ui.player.labelForQuality("source"))
    }

    @Test
    fun addToListIsNamedForTheWatchlist() {
        assertEquals("add to Watchlist", addToListName(inWatchlist = false))
        assertEquals("in your Watchlist", addToListName(inWatchlist = true))
    }
}
