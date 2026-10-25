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

    @Test
    fun eachCaseSaysItsReasonOnly() {
        assertEquals("Can't reach the server.", IOException("no route").listFailureSentence())
        assertEquals("The server ran into a problem.", status(500).listFailureSentence())
        assertEquals("The server ran into a problem.", status(502).listFailureSentence())
        assertEquals("The server is busy.", status(503).listFailureSentence())
        assertEquals("The server is busy.", status(429).listFailureSentence())
        // On a list, a 404 is the app and the server not matching.
        assertEquals("The server didn't accept the request. Check for an app update.", status(404).listFailureSentence())
        assertEquals("The server didn't accept the request. Check for an app update.", status(400).listFailureSentence())
        assertEquals("HTTP 502", status(502).loadDetails())
    }

    @Test
    fun noSentenceAsksToTryAgainOrCarriesACode() {
        val lines = LoadFailure.entries.mapNotNull { it.sentence() } +
            listOf(IOException("x"), status(500), status(503), status(404)).map { it.listFailureSentence() }
        for (line in lines) {
            assertFalse(line.contains("try again", ignoreCase = true), line)
            assertFalse(Regex("""HTTP|\b[1-5]\d\d\b""").containsMatchIn(line), line)
        }
    }

    @Test
    fun aBrowsePageNamesWhatCouldntLoadInItsOwnWord() {
        assertEquals("couldn't load the movies", couldntLoadTitle("Movies"))
        assertEquals("couldn't load the shows", couldntLoadTitle("Shows"))
    }
}
