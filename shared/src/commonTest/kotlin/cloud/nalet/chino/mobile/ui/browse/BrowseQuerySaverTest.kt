package cloud.nalet.chino.mobile.ui.browse

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The grid's filters come back from saved state as they were: back from a
 *  title, or after the activity's recreation. */
class BrowseQuerySaverTest {
    private val scope = SaverScope { true }

    private fun roundTrip(q: BrowseQuery): BrowseQuery? {
        val saved = assertNotNull(with(BrowseQuerySaver) { scope.save(q) })
        return BrowseQuerySaver.restore(saved)
    }

    @Test
    fun everyFilterComesBackAsItWasSaved() {
        val q = BrowseQuery(genre = "Animation", yearMin = 2000, yearMax = 2009, ratingMin = 7.0, sort = "newest")
        assertEquals(q, roundTrip(q))
    }

    @Test
    fun noFiltersComeBackAsNone() {
        assertEquals(BrowseQuery(), roundTrip(BrowseQuery()))
        assertEquals(BrowseQuery(sort = "rating"), roundTrip(BrowseQuery(sort = "rating")))
    }
}
