package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.downloads.DeviceKind
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertTrue(PlaybackFailure.NotAccepted.canTryAgain)
        assertFalse(PlaybackFailure.NotYetAvailable.canTryAgain)
        assertFalse(PlaybackFailure.NotOnThisDevice.canTryAgain)
        // No access: "back"; a refused sign-in: the sign-in, no empty state.
        assertFalse(PlaybackFailure.NoAccess.canTryAgain)
        assertFalse(PlaybackFailure.SignInRefused.canTryAgain)
        assertTrue(PlaybackFailure.SignInRefused.goesToSignIn)
        assertEquals(listOf(PlaybackFailure.SignInRefused), PlaybackFailure.entries.filter { it.goesToSignIn })
    }

    @Test
    fun noAccessIsTheDesignSystemsHeadingWithNothingUnderIt() {
        assertEquals("you don't have access to this", PlaybackFailure.NoAccess.heading)
        assertNull(PlaybackFailure.NoAccess.screenSentence())
        assertEquals(COULDNT_PLAY_TITLE, PlaybackFailure.Busy.heading)
        assertEquals("The server is busy.", PlaybackFailure.Busy.screenSentence())
    }

    @Test
    fun aStreamTokenAPauseOutlivedIsSignedAnewNeverAQualityThatCouldNotBeStreamed() {
        // The first 401: signed anew, rebuilt where it was.
        assertEquals(MediaRefusal.SignAnew, mediaRefusalOf(401, signedAnewAgoMs = null))
        // A 401 on the token just minted: it stops - and says nothing of a sign-in.
        val again = mediaRefusalOf(401, signedAnewAgoMs = 5_000)
        assertEquals(MediaRefusal.Stop(PlaybackFailure.NotAccepted), again)
        // Another pause hours later outlives the new one too: signed anew again.
        assertEquals(MediaRefusal.SignAnew, mediaRefusalOf(401, signedAnewAgoMs = SIGN_ANEW_WINDOW_MS))
        // No access stops at once.
        assertEquals(MediaRefusal.Stop(PlaybackFailure.NoAccess), mediaRefusalOf(403, signedAnewAgoMs = null))
        // Anything else is recovered from as before: a step down, a retry.
        for (status in listOf(null, 404, 500, 502, 503)) assertNull(mediaRefusalOf(status, signedAnewAgoMs = null), "$status")
    }

    @Test
    fun theSessionsLinksAreSignedWithTheNewTokenAndNothingElseChanges() {
        val master = "https://media.example/api/v1/items/f1/play/master.m3u8?stream=old.sig&caps=hevc&q=high"
        assertEquals(
            "https://media.example/api/v1/items/f1/play/master.m3u8?stream=new.sig&caps=hevc&q=high",
            resignedUrl(master, old = "old.sig", fresh = "new.sig"),
        )
        val sidecar = "https://media.example/api/v1/play/subs/42.vtt?stream=old.sig"
        assertEquals("https://media.example/api/v1/play/subs/42.vtt?stream=new.sig", resignedUrl(sidecar, "old.sig", "new.sig"))
        // A link without the token, or no token to replace, stays as it is.
        assertEquals("file:///data/master.m3u8", resignedUrl("file:///data/master.m3u8", "old.sig", "new.sig"))
        assertEquals(master, resignedUrl(master, "", "new.sig"))
    }
}
