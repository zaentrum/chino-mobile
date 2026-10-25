package cloud.nalet.chino.mobile.ui.browse

import cloud.nalet.chino.mobile.ui.shell.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The line at the end of a grid: its count in the screen's own word, its
 *  right number - never "catalogue", never "1 shows". */
class EndOfListLineTest {
    @Test
    fun theScreensOwnWordInItsRightNumber() {
        assertEquals("12 shows in all.", endOfListLine(12, "Shows"))
        assertEquals("1 show in all.", endOfListLine(1, "Shows"))
        assertEquals("240 movies in all.", endOfListLine(240, "Movies"))
        assertEquals("1 movie in all.", endOfListLine(1, "Movies"))
        for (line in listOf(endOfListLine(1, "Shows"), endOfListLine(3, "Movies"))) {
            assertFalse("catalog" in line, line)
            assertFalse(Regex("""\b1 \w+s\b""").containsMatchIn(line), line)
        }
    }

    @Test
    fun theTabAndItsScreenSayOneWordItsHeadingInTheMonoFacesLowercase() {
        // docs/chino words: one word for one thing - the tab's name is its
        // screen's word, never "Series" over "Shows".
        assertEquals("Shows", Section.Series.label)
        assertEquals("shows", pageHeading(Section.Series.label))
        assertEquals("movies", pageHeading(Section.Movies.label))
        assertEquals("1 show in all.", endOfListLine(1, Section.Series.label))
        assertEquals("couldn't load the shows", couldntLoadTitle(Section.Series.label))
    }
}
