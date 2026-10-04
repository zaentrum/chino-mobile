package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** The audio menu's rows: AVFoundation's renditions named as the web names
 *  /play/info's tracks — the title, else the language — with codec and
 *  channels underneath. */
class AudioChoicesTest {
    private val tracks = listOf(
        TrackInfo(index = 1, codec = "aac", language = "eng", title = "", default = true, channels = 2),
        TrackInfo(index = 2, codec = "eac3", language = "ger", title = "Deutsch", channels = 6),
    )

    @Test
    fun renditionsTakeTheTrackTitleElseTheLanguageName() {
        val choices = audioChoicesFor(
            listOf(
                AudioRendition(index = 0, name = "audio_eng", language = "en", selected = true),
                AudioRendition(index = 1, name = "audio_ger", language = "de", selected = false),
            ),
            tracks,
        )
        assertEquals(listOf("English", "Deutsch"), choices.map { it.label })
        assertEquals(listOf("AAC · Stereo", "EAC3 · 5.1"), choices.map { it.detail })
        assertEquals(listOf(true, false), choices.map { it.selected })
        assertEquals(listOf(0, 1), choices.map { it.index })
    }

    @Test
    fun countsThatDifferMatchByLanguage() {
        val choices = audioChoicesFor(
            listOf(AudioRendition(index = 0, name = "Deutsch", language = "ger", selected = true)),
            tracks,
        )
        assertEquals("Deutsch", choices.single().label)
        assertEquals("EAC3 · 5.1", choices.single().detail)
    }

    @Test
    fun withoutTrackInfoTheRenditionSpeaksForItself() {
        val untagged = audioChoicesFor(listOf(AudioRendition(index = 0, name = "Main", language = null, selected = true)), emptyList())
        assertEquals("Main", untagged.single().label)
        assertEquals(null, untagged.single().detail)

        val tagged = audioChoicesFor(listOf(AudioRendition(index = 0, name = "Main", language = "fra", selected = true)), emptyList())
        assertEquals("French", tagged.single().label)
    }
}
