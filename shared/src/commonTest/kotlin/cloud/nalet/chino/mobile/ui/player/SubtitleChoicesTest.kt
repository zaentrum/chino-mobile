package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.api.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        // English audio, English subtitles preferred, no forced English: off.
        assertNull(autoSubtitleChoice(choices, audioLang = "eng", subtitlePref = "eng", audioPref = "eng"))
        // German audio, English subtitles preferred: the English sidecar.
        assertEquals("s-en", autoSubtitleChoice(choices, "ger", "eng", "eng")?.id)
        // Japanese audio, German subtitles preferred: the PGS sidecar cannot be
        // drawn, so the embedded German text stream comes on.
        assertEquals("emb-3", autoSubtitleChoice(choices, "jpn", "deu", "orig")?.id)
        // The mobile default (subtitles off) switches none on but a forced
        // one; the forced French stream is a picture one, not drawn here.
        assertNull(autoSubtitleChoice(choices, "jpn", "off", "eng"))
        assertNull(autoSubtitleChoice(choices, "fre", "off", "eng"))
    }

    @Test
    fun aForcedSidecarSaysSoOrItsLabelDoes() {
        val forced = buildSubtitleChoices(
            "m1",
            listOf(
                SidecarSubtitle(id = "s-en", lang = "eng", url = "", format = "webvtt"),
                SidecarSubtitle(id = "s-en-f", lang = "eng", url = "", format = "webvtt", forced = true),
                // A server that does not pass the flag on: the label tells.
                SidecarSubtitle(id = "s-de-f", lang = "ger", label = "Forced", url = "", format = "webvtt"),
            ),
            emptyList(),
            apiBase,
            "tok",
        )
        assertEquals(listOf(false, true, true), forced.map { it.forced })
        assertEquals(listOf("English", "English (forced)", "German · Forced"), forced.map { it.label })
    }

    @Test
    fun theForcedTrackInTheAudiosLanguageWhereNoneWouldComeOnAndOneThePlayerCanDraw() {
        val choices = buildSubtitleChoices(
            "m1",
            listOf(
                SidecarSubtitle(id = "s-en", lang = "eng", url = "", format = "webvtt"),
                SidecarSubtitle(id = "s-en-pgs", lang = "eng", url = "", format = "pgs", forced = true),
                SidecarSubtitle(id = "s-en-f", lang = "eng", url = "", format = "webvtt", forced = true),
            ),
            listOf(TrackInfo(index = 5, codec = "subrip", language = "fre", forced = true)),
            apiBase,
            "tok",
        )
        // English audio, subtitles off in Settings: the forced English text
        // track; the PGS one cannot be drawn here, and comes after it anyway.
        assertEquals("s-en-f", autoSubtitleChoice(choices, audioLang = "eng", subtitlePref = "off", audioPref = "eng")?.id)
        assertEquals("s-en-f", autoSubtitleChoice(choices.filter { it.id != "s-en-pgs" }, "eng", "off", "eng")?.id)
        assertNull(autoSubtitleChoice(choices.filter { it.id != "s-en-f" }, "eng", "off", "eng"))
        // French audio: the embedded forced French stream.
        assertEquals("emb-5", autoSubtitleChoice(choices, audioLang = "fre", subtitlePref = "off", audioPref = "eng")?.id)
        // Japanese audio, English subtitles chosen: the full English track.
        assertEquals("s-en", autoSubtitleChoice(choices, audioLang = "jpn", subtitlePref = "eng", audioPref = "eng")?.id)
    }

    @Test
    fun thePlayerDecidesAtTheStartAndAsTheAudiosLanguageChangesUntilTheViewerPicks() {
        val session = SubtitleSession()
        assertTrue(session.playerDecides("eng"))
        // English 5.1 to English, "eng" to "en": the same language, no change.
        assertFalse(session.playerDecides("en"))
        assertFalse(session.playerDecides("eng"))
        // German audio: decided again, and English again after it.
        assertTrue(session.playerDecides("ger"))
        assertTrue(session.playerDecides("eng"))
        // The viewer's Off (or a track): theirs for the rest of the session.
        session.viewerPicks()
        assertTrue(session.viewerPicked)
        assertFalse(session.playerDecides("ger"))
        assertFalse(session.playerDecides("eng"))
    }

    @Test
    fun anAudioOfNoKnownLanguageIsOneLanguageToo() {
        val session = SubtitleSession()
        assertTrue(session.playerDecides(null))
        assertFalse(session.playerDecides("und"))
        assertTrue(session.playerDecides("fre"))
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

    /** A text track as Media3 lists it: the master's SUBTITLES renditions in
     *  their order, then the side-loaded sidecars. */
    private data class Track(val id: String, val lang: String?, val forced: Boolean = false, val sidecar: Boolean = false)

    private fun menu(tracks: List<Track>) =
        sidecarsThenOtherRenditions(tracks, { it.sidecar }, { it.lang }, { it.forced }).map { it.id }

    @Test
    fun aPackagesRenditionsDoNotDoubleItsSidecars() {
        // The packager's master with HLS_SUBTITLES: English, English (Forced),
        // German — the same files as the title's sidecars.
        val tracks = listOf(
            Track("hls-en", "en"),
            Track("hls-en-forced", "en", forced = true),
            Track("hls-de", "de"),
            Track("side-en", "eng", sidecar = true),
            Track("side-en-forced", "eng", forced = true, sidecar = true),
            Track("side-de", "ger", sidecar = true),
        )
        assertEquals(listOf("side-en", "side-en-forced", "side-de"), menu(tracks))
    }

    @Test
    fun aRenditionNoSidecarCarriesStays() {
        val tracks = listOf(
            Track("hls-en", "en"),
            // Forced English: no sidecar is forced, so this one is not a copy.
            Track("hls-en-forced", "en", forced = true),
            Track("hls-fr", "fr"),
            Track("side-en", "en", sidecar = true),
        )
        assertEquals(listOf("side-en", "hls-en-forced", "hls-fr"), menu(tracks))
        // No sidecars at all: the master's renditions, as they come.
        assertEquals(listOf("hls-en", "hls-fr"), menu(listOf(Track("hls-en", "en"), Track("hls-fr", "fr"))))
    }

    @Test
    fun theDefaultRuleReadsTheMenuNotTheCopies() {
        val tracks = sidecarsThenOtherRenditions(
            listOf(Track("hls-de", "de"), Track("side-de", "deu", sidecar = true)),
            { it.sidecar },
            { it.lang },
            { it.forced },
        )
        val on = defaultSubtitleTrack(tracks, { it.lang }, { it.forced }, audioLang = "jpn", subtitlePref = "ger")
        assertEquals("side-de", on?.id)
    }
}
