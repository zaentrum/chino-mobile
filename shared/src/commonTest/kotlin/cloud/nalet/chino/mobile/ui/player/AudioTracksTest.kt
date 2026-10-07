package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The audio menu's words for a track, and which /play/info track a player's
 *  rendition plays. */
class AudioTracksTest {
    @Test
    fun codecsByTheNameAViewerKnowsWhicheverSpellingTheyCameIn() {
        assertEquals(listOf("AAC", "AAC", "AAC"), listOf("mp4a", "mp4a.40.2", "aac").map { audioCodecName(it) })
        assertEquals(listOf("E-AC-3", "E-AC-3", "AC-3", "AC-3"), listOf("ec-3", "eac3", "ac-3", "ac3").map { audioCodecName(it) })
        assertEquals(listOf("MP3", "MP3", "Opus", "FLAC"), listOf("mp3", "mp4a.40.34", "opus", "flac").map { audioCodecName(it) })
        assertNull(audioCodecName(null))
        assertNull(audioCodecName(" "))
    }

    @Test
    fun theDetailIsTheCodecThenTheChannels() {
        assertEquals("E-AC-3 · 5.1", audioDetail("ec-3", 6))
        assertEquals("AAC · Stereo", audioDetail("mp4a.40.2", 2))
        assertEquals("Mono", audioDetail(null, 1))
        assertEquals("AAC", audioDetail("aac", 0))
        assertEquals("7.1", audioDetail(null, 8))
        assertEquals("3ch", audioDetail(null, 3))
        assertNull(audioDetail(null, null))
    }

    /** /play/info of a client served the 5.1 companions: the demo's Sintel. */
    private val union = listOf(
        TrackInfo(index = 0, codec = "ec-3", language = "eng", name = "English 5.1", title = "English 5.1", default = true, channels = 6, group = "audio-surround", rendition = "a1"),
        TrackInfo(index = 1, codec = "mp4a.40.2", language = "eng", name = "English", title = "English", channels = 2, group = "audio-surround", rendition = "a0"),
    )

    private fun byName(name: String?): (TrackInfo) -> Boolean = { it.displayName == name }

    @Test
    fun aRenditionPlaysTheTrackOfItsName() {
        // In whichever order the player lists them.
        assertEquals("a0", audioTrackFor(union, place = 0, count = 2, language = "en", named = byName("English"))?.rendition)
        assertEquals("a1", audioTrackFor(union, place = 1, count = 2, language = "en", named = byName("English 5.1"))?.rendition)
    }

    @Test
    fun aServerThatNamesThemOtherwiseByPlaceThenByLanguage() {
        val old = listOf(
            TrackInfo(index = 1, codec = "aac", language = "eng", title = "Main"),
            TrackInfo(index = 2, codec = "ac3", language = "ger", title = "Deutsch"),
        )
        // As many tracks as renditions: the one in its place.
        assertEquals(2, audioTrackFor(old, place = 1, count = 2, language = "de", named = byName("audio_ger"))?.index)
        // Counts that differ: the first in its language, else none.
        assertEquals(2, audioTrackFor(old, place = 0, count = 1, language = "de", named = byName("audio_ger"))?.index)
        assertNull(audioTrackFor(old, place = 0, count = 1, language = "fr", named = byName("audio_fre")))
        assertNull(audioTrackFor(emptyList(), place = 0, count = 1, language = "en", named = byName("English")))
    }

    @Test
    fun aTracksNameIsItsNameElseItsTitle() {
        assertEquals("English 5.1", union[0].displayName)
        assertEquals("Main", TrackInfo(title = " Main ").displayName)
        assertNull(TrackInfo(name = " ", title = "").displayName)
    }
}
