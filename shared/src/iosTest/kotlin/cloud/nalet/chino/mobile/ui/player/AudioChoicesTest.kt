package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.SurroundRendition
import cloud.nalet.chino.mobile.data.api.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** The audio menu's rows: AVFoundation's renditions named as the web names
 *  /play/info's tracks — the language first, else the title or NAME — with
 *  codec and channels underneath. */
class AudioChoicesTest {
    private val tracks = listOf(
        TrackInfo(index = 1, codec = "aac", language = "eng", title = "", default = true, channels = 2),
        TrackInfo(index = 2, codec = "eac3", language = "ger", title = "Deutsch", channels = 6),
    )

    @Test
    fun renditionsTakeTheLanguageNameFirst() {
        val choices = audioChoicesFor(
            listOf(
                AudioRendition(index = 0, name = "audio_eng", language = "en", selected = true),
                AudioRendition(index = 1, name = "audio_ger", language = "de", selected = false),
            ),
            tracks,
        )
        assertEquals(listOf("English", "German"), choices.map { it.label })
        assertEquals(listOf("AAC · Stereo", "E-AC-3 · 5.1"), choices.map { it.detail })
        assertEquals(listOf(true, false), choices.map { it.selected })
        assertEquals(listOf(0, 1), choices.map { it.index })
    }

    @Test
    fun countsThatDifferMatchByName() {
        val choices = audioChoicesFor(
            listOf(AudioRendition(index = 0, name = "Deutsch", language = "ger", selected = true)),
            tracks,
        )
        assertEquals("German", choices.single().label)
        assertEquals("E-AC-3 · 5.1", choices.single().detail)
    }

    @Test
    fun anOptionIsTheTrackOfItsNameInWhicheverOrder() {
        // chino-stream names each track as the master names its rendition.
        val named = listOf(
            TrackInfo(index = 0, codec = "mp4a", language = "eng", name = "English", channels = 2),
            TrackInfo(index = 1, codec = "mp4a", language = "ger", name = "German", channels = 1),
        )
        val choices = audioChoicesFor(
            listOf(
                AudioRendition(index = 0, name = "German", language = "de", selected = false),
                AudioRendition(index = 1, name = "English", language = "en", selected = true),
            ),
            named,
        )
        assertEquals(listOf("German", "English"), choices.map { it.label })
        assertEquals(listOf("AAC · Mono", "AAC · Stereo"), choices.map { it.detail })
    }

    @Test
    fun aNativePlayersTrackSaysTheCompanionThatMayPlayIt() {
        // `native` in the caps: AVPlayer lists the member once, whichever of
        // its groups plays it; /play/info marks the companion that may.
        val native = listOf(
            TrackInfo(
                index = 0, codec = "mp4a", language = "eng", name = "English", title = "English", default = true, channels = 2,
                surround = SurroundRendition(group = "audio-surround", rendition = "a1", codec = "ec-3", channels = 6),
            ),
            // A stereo source: no companion, the 5.1 group plays it stereo.
            TrackInfo(index = 1, codec = "mp4a", language = "ger", name = "German", channels = 2),
        )
        val choices = audioChoicesFor(
            listOf(
                AudioRendition(index = 0, name = "English", language = "en", selected = true),
                AudioRendition(index = 1, name = "German", language = "de", selected = false),
            ),
            native,
        )
        assertEquals(listOf("English", "German"), choices.map { it.label })
        assertEquals(listOf("AAC · Stereo or E-AC-3 · 5.1", "AAC · Stereo"), choices.map { it.detail })
    }

    @Test
    fun withoutTrackInfoTheRenditionSpeaksForItself() {
        val untagged = audioChoicesFor(listOf(AudioRendition(index = 0, name = "Main", language = null, selected = true)), emptyList())
        assertEquals("Main", untagged.single().label)
        assertEquals(null, untagged.single().detail)

        val tagged = audioChoicesFor(listOf(AudioRendition(index = 0, name = "Main", language = "fra", selected = true)), emptyList())
        assertEquals("French", tagged.single().label)
    }

    @Test
    fun aFilmWithoutDialogueAndATrackInNoLanguage() {
        val choices = audioChoicesFor(
            listOf(
                AudioRendition(index = 0, name = "zxx", language = "zxx", selected = true),
                AudioRendition(index = 1, name = "Track 1", language = "und", selected = false),
                AudioRendition(index = 2, name = "AC3 5.1 @ 640 Kbps", language = null, selected = false),
            ),
            emptyList(),
        )
        assertEquals(listOf("No dialogue", "Unknown", "Unknown (2)"), choices.map { it.label })
    }
}
