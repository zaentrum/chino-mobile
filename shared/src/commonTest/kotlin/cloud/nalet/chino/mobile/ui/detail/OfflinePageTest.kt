package cloud.nalet.chino.mobile.ui.detail

import cloud.nalet.chino.mobile.data.api.Episode
import cloud.nalet.chino.mobile.data.downloads.UNREACHABLE_BANNER
import cloud.nalet.chino.mobile.data.downloads.UNREACHABLE_BANNER_NOTHING
import kotlin.test.Test
import kotlin.test.assertEquals

/** The page built from the phone says only what is true of this title:
 *  that it plays, and how many episodes are on the phone. */
class OfflinePageTest {
    @Test
    fun yourDownloadsStillPlayOnlyWhereThisTitlePlays() {
        // A film on the phone that plays.
        assertEquals(UNREACHABLE_BANNER, offlineBanner(listOf("f1"), waitingForCheck = emptySet()))
        // A film waiting for its check with the server: its Play is dimmed.
        assertEquals(UNREACHABLE_BANNER_NOTHING, offlineBanner(listOf("f1"), waitingForCheck = setOf("f1")))
        // A series: one of its episodes plays.
        assertEquals(UNREACHABLE_BANNER, offlineBanner(listOf("e1", "e2"), waitingForCheck = setOf("e1")))
        assertEquals(UNREACHABLE_BANNER_NOTHING, offlineBanner(listOf("e1", "e2"), waitingForCheck = setOf("e1", "e2")))
        // Another title of the phone's that plays changes nothing on this page.
        assertEquals(UNREACHABLE_BANNER_NOTHING, offlineBanner(listOf("f1"), waitingForCheck = setOf("f1", "other")))
    }

    @Test
    fun theSeriesFromThePhoneCountsEpisodesNotFiles() {
        // A file that holds S03E18-19, its holder alone on the phone; and S03E20.
        val held = Episode(id = "e18", title = "Part one", seasonNumber = 3, episodeNumber = 18, episodeEnd = 19)
        val single = Episode(id = "e20", title = "Part three", seasonNumber = 3, episodeNumber = 20)
        assertEquals(2, episodesOnThePhone(listOf(held)))
        assertEquals(3, episodesOnThePhone(listOf(held, single)))
        assertEquals(1, episodesOnThePhone(listOf(single)))
        // No numbers known: one each.
        assertEquals(2, episodesOnThePhone(listOf(Episode(id = "a", title = "A"), Episode(id = "b", title = "B"))))
    }
}
