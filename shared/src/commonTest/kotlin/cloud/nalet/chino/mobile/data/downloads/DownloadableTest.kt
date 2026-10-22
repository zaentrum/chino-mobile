package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.PlayInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one rule of what can be downloaded: a title that plays from its
 *  package; anything the server makes from the original while it plays
 *  cannot, yet. */
class DownloadableTest {
    @Test
    fun aTitleThatPlaysFromItsPackageCanBeDownloaded() {
        assertEquals(Downloadability.Yes(DownloadSource.Package), downloadability(PlayInfo(mode = "packaged")))
        // As chino-stream spells it, in any case.
        assertEquals(Downloadability.Yes(DownloadSource.Package), downloadability(PlayInfo(mode = "Packaged")))
    }

    @Test
    fun aCopyOrAnEncodeOfTheOriginalPlaysOnlyAsAStream() {
        for (mode in listOf("passthrough", "remux", "transcode", "live", null)) {
            assertEquals(Downloadability.StreamOnly, downloadability(PlayInfo(mode = mode)), "mode $mode")
        }
    }

    @Test
    fun withoutPlayInfoItIsNotKnown() {
        assertEquals(Downloadability.Unknown, downloadability(null))
    }

    @Test
    fun filmsAndEpisodesHaveAControlASeriesDoesNot() {
        assertTrue(hasDownloadControl("movie"))
        assertTrue(hasDownloadControl("episode"))
        assertFalse(hasDownloadControl("series"))
        assertFalse(hasDownloadControl(null))
    }
}
