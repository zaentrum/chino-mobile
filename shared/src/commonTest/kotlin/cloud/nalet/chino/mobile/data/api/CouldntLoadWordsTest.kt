package cloud.nalet.chino.mobile.data.api

import cloud.nalet.chino.mobile.ui.browse.couldntLoadTitle
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What a screen that couldn't load says, by the design system's cases
 * (73d357a): the title names what couldn't load in the screen's own word,
 * the sentence is the reason only - "try again" is the button beside it, so
 * the sentence never asks for it - and no status code.
 */
class CouldntLoadWordsTest {
    private fun status(code: Int) = ApiStatusException(code, "/api/v1/items", "catalog unavailable")

    /** What a list says under its title. */
    private fun Throwable.listSentence(): String? = loadFailure(singleThing = false).sentence()

    @Test
    fun eachCaseSaysItsReasonOnly() {
        assertEquals("Can't reach the server.", IOException("no route").listSentence())
        assertEquals("The server ran into a problem.", status(500).listSentence())
        assertEquals("The server ran into a problem.", status(502).listSentence())
        assertEquals("The server is busy.", status(503).listSentence())
        assertEquals("The server is busy.", status(429).listSentence())
        // On a list, a 404 is the app and the server not matching.
        assertEquals("The server didn't accept the request. Check for an app update.", status(404).listSentence())
        assertEquals("The server didn't accept the request. Check for an app update.", status(400).listSentence())
        assertEquals("HTTP 502", status(502).loadDetails())
    }

    @Test
    fun noSentenceAsksToTryAgainOrCarriesACode() {
        val lines = LoadFailure.entries.mapNotNull { it.sentence() } +
            listOf(IOException("x"), status(500), status(503), status(404)).mapNotNull { it.listSentence() }
        for (line in lines) {
            assertFalse(line.contains("try again", ignoreCase = true), line)
            assertFalse(Regex("""HTTP|\b[1-5]\d\d\b""").containsMatchIn(line), line)
        }
    }

    @Test
    fun aListWhoseSignInIsRefusedIsTheSignInAndNoAccessHasItsOwnHeading() {
        // No sentence telling the person to sign in under a "try again"
        // that can't: the sign-in itself (ListCouldntLoad), as a title's page.
        assertEquals(LoadFailure.SignedOut, status(401).loadFailure(singleThing = false))
        assertEquals(null, status(401).listSentence())
        assertEquals(LoadFailure.NoAccess, status(403).loadFailure(singleThing = false))
        assertEquals(null, status(403).listSentence())
        assertEquals("you don't have access to this", NO_ACCESS_TITLE)
    }

    @Test
    fun aBrowsePageNamesWhatCouldntLoadInItsOwnWord() {
        assertEquals("couldn't load the movies", couldntLoadTitle("Movies"))
        assertEquals("couldn't load the shows", couldntLoadTitle("Shows"))
    }
}
