package cloud.nalet.chino.mobile.ui.browse

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
}
