package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.downloads.DeviceKind
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A playback that stopped for good says what happened in words, by the
 *  design system's couldn't-load cases; the code goes under details. */
class PlaybackFailureTest {
    @Test
    fun eachCaseHasTheDesignSystemsSentence() {
        val cases = listOf(
            null to "Can't reach the server.",
            500 to "The server ran into a problem.",
            502 to "The server ran into a problem.",
            503 to "The server is busy.",
            429 to "The server is busy.",
            400 to "The server didn't accept the request. Check for an app update.",
            404 to "The server didn't accept the request. Check for an app update.",
            401 to "The server no longer accepts this phone's sign-in.",
            403 to "The server doesn't let this account play it.",
        )
        for ((status, sentence) in cases) assertEquals(sentence, playbackFailureOf(status).sentence(), "$status")
        assertEquals("This tablet can't play it.", PlaybackFailure.NotOnThisDevice.sentence(DeviceKind.Tablet))
        assertEquals("This title isn't available to stream yet.", PlaybackFailure.NotYetAvailable.sentence())
    }

    @Test
    fun aRequestThatFailedBeforeThePlayerIsReadByItsStatusTheCodeUnderDetails() {
        val e = ApiStatusException(503, "/api/v1/items/x/play/info", "catalog unavailable")
        assertEquals(PlaybackFailure.Busy, e.playbackFailure())
        assertEquals("HTTP 503", e.playbackDetails())
        assertEquals(PlaybackFailure.NoAnswer, IOException("no route").playbackFailure())
        assertEquals("no answer", IOException("no route").playbackDetails())
        // No sentence carries a status, a path or an engine code.
        for (f in PlaybackFailure.entries) {
            val line = f.sentence()
            assertFalse(Regex("""HTTP|ERROR_|/api|\b[1-5]\d\d\b""").containsMatchIn(line), line)
            assertTrue(line.endsWith("."), line)
        }
    }

    @Test
    fun tryAgainOnlyWhereItCanHelp() {
        assertTrue(PlaybackFailure.NoAnswer.canTryAgain)
        assertTrue(PlaybackFailure.Busy.canTryAgain)
        assertFalse(PlaybackFailure.NotYetAvailable.canTryAgain)
        assertFalse(PlaybackFailure.NotOnThisDevice.canTryAgain)
    }
}
