package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.api.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The captions menu the iOS player shows: chino-api's sidecars, then the
 *  embedded text streams, named by language, picture formats listed but not
 *  selectable, and the one that comes on by itself. */
class SubtitleChoicesTest {
    private val apiBase = "https://media.example.org/api/"

    private val sidecars = listOf(
        SidecarSubtitle(id = "s-en", lang = "eng", url = "/api/v1/play/subs/s-en.vtt", format = "webvtt"),
        SidecarSubtitle(id = "s-fr", lang = "fre", url = "/api/v1/play/subs/s-fr.vtt", format = "srt"),
        SidecarSubtitle(id = "s-de", lang = "ger", label = "SDH", url = "/api/v1/play/subs/s-de.vtt", format = "pgs"),
        // An older server sends no format for WebVTT, and a blank url.
        SidecarSubtitle(id = "s-it", lang = "ita", url = ""),
    )

    private val embedded = listOf(
        TrackInfo(index = 3, codec = "subrip", language = "ger"),
        TrackInfo(index = 4, codec = "hdmv_pgs_subtitle", language = "fre", forced = true),
        // A packaged title's row: no stream index, nothing to address.
        TrackInfo(index = null, codec = "webvtt", language = "eng"),
    )

    private val choices = buildSubtitleChoices("m1", sidecars, embedded, apiBase, streamToken = "tok")

    @Test
    fun sidecarsThenEmbeddedTextStreamsNamedByLanguage() {
        assertEquals(
            listOf(
                "s-en" to "English",
                "s-fr" to "French",
                "s-de" to "German · SDH",
                "s-it" to "Italian",
                "emb-3" to "German",
                "emb-4" to "French (forced)",
            ),
            choices.map { it.id to it.label },
        )
    }

    @Test
    fun theCueFilesCarryTheStreamToken() {
        assertEquals("https://media.example.org/api/v1/play/subs/s-en.vtt?stream=tok", choices[0].url)
        assertEquals("https://media.example.org/api/v1/play/subs/s-it.vtt?stream=tok", choices[3].url)
        assertEquals("https://media.example.org/api/v1/items/m1/play/subtitles/3.vtt?stream=tok", choices[4].url)
    }

    @Test
    fun pictureSubtitlesAreListedButNotAvailableWhereNothingDrawsThem() {
        assertEquals(listOf("s-de", "emb-4"), choices.filter { !it.available }.map { it.id })
        assertEquals(SubtitleKind.Pgs, choices.single { it.id == "s-de" }.kind)
        assertEquals(SubtitleKind.Bitmap, subtitleKind("dvd_subtitle"))
        assertEquals(SubtitleKind.Text, subtitleKind(null))
        assertEquals(SubtitleKind.Text, subtitleKind("SRT"))
        // A player that draws PGS may offer a PGS sidecar, never an embedded
        // picture stream (the extractor cannot turn it into WebVTT).
        val withPgs = buildSubtitleChoices("m1", sidecars, embedded, apiBase, "tok", drawsPgs = true)
        assertEquals(listOf("emb-4"), withPgs.filter { !it.available }.map { it.id })
    }

    @Test
    fun offUnlessTheAudioIsInAnotherLanguageAndThenAFullTrackTheOverlayCanDraw() {
        // English audio, English subtitles preferred: off.
        assertNull(defaultSubtitleChoice(choices, audioLang = "eng", subtitlePref = "eng", audioPref = "eng"))
        // German audio, English subtitles preferred: the English sidecar.
        assertEquals("s-en", defaultSubtitleChoice(choices, "ger", "eng", "eng")?.id)
        // Japanese audio, German subtitles preferred: the PGS sidecar cannot be
        // drawn, so the embedded German text stream comes on.
        assertEquals("emb-3", defaultSubtitleChoice(choices, "jpn", "deu", "orig")?.id)
        // The mobile default (subtitles off) never switches any on.
        assertNull(defaultSubtitleChoice(choices, "jpn", "off", "eng"))
    }

    @Test
    fun theAudioThatPlaysFirstPreferenceThenTheFilesDefaultThenTheFirst() {
        val tracks = listOf(
            TrackInfo(index = 1, language = "jpn", default = true),
            TrackInfo(index = 2, language = "ger"),
            TrackInfo(index = 3, language = "eng"),
        )
        assertEquals(3, preferredAudioTrack(tracks, "eng")?.index)
        assertEquals(2, preferredAudioTrack(tracks, "deu")?.index)
        assertEquals(1, preferredAudioTrack(tracks, "orig")?.index)
        assertEquals(1, preferredAudioTrack(tracks, "ita")?.index)
        assertEquals(2, preferredAudioTrack(tracks.drop(1), "ita")?.index)
        assertNull(preferredAudioTrack(emptyList(), "eng"))
    }
}
