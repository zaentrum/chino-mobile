package cloud.nalet.chino.mobile.ui.detail

import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.Subtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The detail page's Subtitles: each of the title's subtitles once, named as
 *  the player's menu names it - the language, then the kind. */
class SubtitleSummaryTest {
    private fun item(vararg subs: Subtitle) = Item(id = "m1", title = "Sintel", kind = "movie", subtitles = subs.toList())

    @Test
    fun bothTracksOfALanguageEachWithItsKindEachOnce() {
        val summary = subtitleSummary(
            item(
                Subtitle(id = "s1", lang = "eng", label = "English"),
                Subtitle(id = "s2", lang = "eng", label = "English SDH"),
                Subtitle(id = "s3", lang = "ger"),
                Subtitle(id = "s4", lang = "ger", forced = true),
                Subtitle(id = "s5", lang = "zh-Hans"),
                // The same again: listed once.
                Subtitle(id = "s6", lang = "ger"),
            ),
        )
        assertEquals(
            "English, English · for deaf and hard of hearing, German, German · foreign-language parts only, Chinese (Simplified)",
            summary,
        )
    }

    @Test
    fun noSubtitlesNoLine() {
        assertNull(subtitleSummary(item()))
    }
}
