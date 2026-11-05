package cloud.nalet.chino.mobile.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** The texts under Settings' audio and subtitle languages, word for word as
 *  docs/chino gives them for every app. */
class SettingsTextTest {
    @Test
    fun theAudioSettingsTextIsPlaybacksWordForWord() {
        assertEquals(
            "Titles play with audio in the language you pick here, where they have it. With Original, each " +
                "title plays in the language it was made in. A choice in the player applies only to what's playing now.",
            AUDIO_SETTING_TEXT,
        )
    }

    @Test
    fun theSubtitleSettingsTextIsSubtitlesWordForWord() {
        assertEquals(
            "Subtitles in the language you pick here come on when a title's audio is in another language. Otherwise, " +
                "only the foreign-language parts get subtitles, where a title has them. A choice in the player applies " +
                "only to what's playing now.",
            SUBTITLE_SETTING_TEXT,
        )
    }

    @Test
    fun theWatchlistSectionsWordsAreItsSpecsWordForWord() {
        assertEquals("Each list shows its movies in one row and its shows in another.", WATCHLIST_SETTING_TEXT)
        assertEquals("Couldn't save this. Check the connection and try again.", WATCHLIST_NOT_SAVED)
    }
}
