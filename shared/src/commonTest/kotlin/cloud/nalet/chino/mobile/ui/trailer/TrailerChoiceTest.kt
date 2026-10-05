package cloud.nalet.chino.mobile.ui.trailer

import cloud.nalet.chino.mobile.data.model.Extra
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.Trailer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which trailer the Trailer button plays: a trailer this server plays
 *  first, else the title's link, else none — as every client picks it. */
class TrailerChoiceTest {
    @Test
    fun aTrailerBeforeATeaserWhateverTheServersOrder() {
        assertEquals("t", localTrailer(listOf(extra("z", "teaser"), extra("t", "trailer")))?.id)
        assertEquals(listOf("trailer", "teaser"), TRAILER_KINDS)
    }

    @Test
    fun aTeaserWhenThereIsNoTrailer() {
        assertEquals("z", localTrailer(listOf(extra("f", "featurette"), extra("z", "teaser")))?.id)
    }

    @Test
    fun theFirstOfAKindInTheServersOrder() {
        assertEquals("t1", localTrailer(listOf(extra("t1", "trailer"), extra("t2", "trailer")))?.id)
    }

    @Test
    fun theTitlesOwnTrailerBeforeASeasons() {
        val extras = listOf(extra("s2", "trailer", season = 2), extra("all", "trailer"), extra("s0", "trailer", season = 0))
        assertEquals("all", localTrailer(extras)?.id)
        // A season's trailer when there is no other.
        assertEquals("s2", localTrailer(listOf(extra("s2", "trailer", season = 2), extra("s3", "trailer", season = 3)))?.id)
        // A season's trailer still before a teaser.
        assertEquals("s2", localTrailer(listOf(extra("z", "teaser"), extra("s2", "trailer", season = 2)))?.id)
    }

    @Test
    fun otherKindsAndExtrasThatCannotPlayAreLeftOut() {
        assertNull(localTrailer(listOf(extra("f", "featurette"), extra("b", "behind-the-scenes"), extra("x", "promo"))))
        assertNull(localTrailer(listOf(extra("gone", "trailer", playPath = ""))))
        assertNull(localTrailer(listOf(extra("elsewhere", "trailer", local = false))))
        assertNull(localTrailer(listOf(extra("", "trailer"))))
        assertEquals("ok", localTrailer(listOf(extra("gone", "trailer", playPath = ""), extra("ok", "teaser")))?.id)
        assertNull(localTrailer(emptyList()))
    }

    @Test
    fun theLinkIsYouTubesOfficialTrailerElseOneCalledATrailerElseTheFirst() {
        val official = Trailer("https://www.youtube.com/watch?v=b", site = "YouTube", title = "Official Trailer")
        val spot = Trailer("https://www.youtube.com/watch?v=a", site = "YouTube", title = "TV Spot")
        val other = Trailer("https://video.example.org/t", site = "Elsewhere", title = "Official Trailer")
        assertEquals(official, pickTrailer(listOf(other, spot, official)))
        assertEquals(spot, pickTrailer(listOf(other, spot)))
        assertEquals(other, pickTrailer(listOf(other)))
        assertNull(pickTrailer(emptyList()))
    }

    @Test
    fun theButtonPlaysTheLocalTrailerElseOpensTheLinkElseThereIsNone() {
        val link = Trailer("https://www.youtube.com/watch?v=x1", site = "YouTube", title = "Official Trailer")
        val local = extra("t", "trailer")
        assertEquals(TrailerChoice.Local(local), trailerChoice(item(extras = listOf(local), trailers = listOf(link))))
        assertEquals(TrailerChoice.Link(link), trailerChoice(item(extras = listOf(extra("f", "featurette")), trailers = listOf(link))))
        assertNull(trailerChoice(item()))
    }

    @Test
    fun aLinksButtonNamesItsSite() {
        assertEquals("Watch on YouTube", trailerLinkLabel(Trailer("https://youtu.be/x1", site = "YouTube")))
        assertEquals("Watch the trailer", trailerLinkLabel(Trailer("https://video.example.org/t")))
    }

    private fun extra(
        id: String,
        kind: String,
        season: Int? = null,
        local: Boolean = true,
        playPath: String = "/api/v1/items/m1/extras/$id/play/master.m3u8",
    ) = Extra(id = id, kind = kind, title = kind, seasonNumber = season, local = local, playPath = playPath)

    private fun item(extras: List<Extra> = emptyList(), trailers: List<Trailer> = emptyList()) =
        Item(id = "m1", title = "A Film", kind = "movie", extras = extras, trailers = trailers)
}
