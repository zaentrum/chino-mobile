package cloud.nalet.chino.mobile.ui.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The sign-in screen says "movies", as the app's tab and docs/chino's
 *  words do, never "films". */
class SignInLineTest {
    @Test
    fun theLineSaysMoviesAndTheServersHost() {
        assertEquals("Movies and series — sign in to media.example.org.", signInLine("https://media.example.org/api"))
        assertEquals("Movies and series — sign in to your account.", signInLine(null))
        assertEquals("Movies and series — sign in to your account.", signInLine("https://"))
        for (line in listOf(signInLine(null), signInLine("https://media.example.org"))) {
            assertFalse(line.contains("film", ignoreCase = true), line)
        }
    }
}
