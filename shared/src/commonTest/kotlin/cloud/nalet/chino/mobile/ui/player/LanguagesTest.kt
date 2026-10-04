package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** chino-web's languages.test.ts cases: codes as one language, the menu's
 *  labels, and when subtitles come on by themselves. */
class LanguagesTest {
    @Test
    fun codesInEverySpellingTheLibraryUsesAreOneLanguage() {
        val cases = mapOf(
            "de" to listOf("ger", "deu", "de", "DE", "de-CH", "de_AT"),
            "fr" to listOf("fre", "fra", "fr"),
            "nl" to listOf("dut", "nld", "nl"),
            "en" to listOf("eng", "en", "en-US"),
            "zh" to listOf("chi", "zho", "zh", "zh-Hant"),
            "cs" to listOf("cze", "ces", "cs"),
            "el" to listOf("gre", "ell", "el"),
            "ro" to listOf("rum", "ron", "ro"),
            "es" to listOf("spa", "es"),
            "he" to listOf("iw", "heb", "he"),
        )
        for ((lang, codes) in cases) for (code in codes) assertEquals(lang, normalizeLang(code), code)
        // A three-letter code with no two-letter one stays as it is.
        assertEquals("fil", normalizeLang("fil"))
        assertEquals("yue", normalizeLang("yue"))
    }

    @Test
    fun noLanguageUndeterminedNoneEmptyNotACode() {
        for (code in listOf("und", "zxx", "mul", "mis", "", "  ", "english", "e", "12", null)) {
            assertEquals("", normalizeLang(code), code.toString())
        }
    }

    @Test
    fun aTagKeepsItsRegionOrScriptCanonicallyCased() {
        assertEquals("pt-BR", languageTag("pt_br"))
        assertEquals("de", languageTag("ger"))
        assertEquals("zh-Hant", languageTag("zh-hant"))
        assertEquals("es-419", languageTag("spa-419"))
        assertEquals("", languageTag("und"))
    }

    @Test
    fun namesInEnglish() {
        assertEquals("German", languageName("ger"))
        assertEquals("French", languageName("fre"))
        assertEquals("Dutch", languageName("dut"))
        assertEquals("Vietnamese", languageName("vie"))
        assertEquals("Brazilian Portuguese", languageName("pt-BR"))
        assertEquals("Traditional Chinese", languageName("zh-Hant-TW"))
        // A region without a name of its own is its language.
        assertEquals("English", languageName("en-NZ"))
        assertEquals("Unknown language", languageName("und"))
        assertEquals("Unknown language", languageName(null))
        // A code there is no name for is shown as it came.
        assertEquals("qaa", languageName("qaa"))
    }

    @Test
    fun theMenuOfTheDemosSintelEveryTrackNamedNoneBlank() {
        val sidecars = listOf("ger", "dut", "eng", "fre", "ita", "spa", "pol", "por", "rus", "vie")
            .map { SubtitleLabelInput(lang = it) }
        assertEquals(
            listOf("German", "Dutch", "English", "French", "Italian", "Spanish", "Polish", "Portuguese", "Russian", "Vietnamese"),
            subtitleLabels(sidecars),
        )
    }

    @Test
    fun aTracksTitleWhenItSaysMoreForcedTracksTheSameLanguageTwice() {
        assertEquals(
            listOf(
                "English · SDH",
                "English [CC]",
                "English",
                "English (2)",
                "German (forced)",
                "German · Forced",
                "German",
                "German (2)",
                "Unknown language",
            ),
            subtitleLabels(
                listOf(
                    SubtitleLabelInput("eng", title = "SDH"),
                    SubtitleLabelInput("eng", title = "English [CC]"),
                    SubtitleLabelInput("eng", title = "eng"),
                    SubtitleLabelInput("eng", title = "English"),
                    SubtitleLabelInput("ger", forced = true),
                    SubtitleLabelInput("ger", title = "Forced", forced = true),
                    SubtitleLabelInput("ger"),
                    SubtitleLabelInput("ger"),
                    SubtitleLabelInput("und"),
                ),
            ),
        )
    }

    @Test
    fun subtitlesDefaultOffWhenTheAudioIsInTheViewersLanguage() {
        assertNull(defaultSubtitleLang(audioLang = "eng", subtitlePref = "eng", audioPref = "eng"))
        assertNull(defaultSubtitleLang(audioLang = "en", subtitlePref = "eng"))
        // Tagged the other way round: still the same language.
        assertNull(defaultSubtitleLang(audioLang = "ger", subtitlePref = "deu"))
    }

    @Test
    fun subtitlesComeOnInTheChosenLanguageWhenTheAudioIsInAnother() {
        assertEquals("en", defaultSubtitleLang(audioLang = "fre", subtitlePref = "eng", audioPref = "eng"))
        assertEquals("de", defaultSubtitleLang(audioLang = "jpn", subtitlePref = "deu", audioPref = "orig"))
    }

    @Test
    fun theAudioInTheLanguageTheViewerPrefersToListenToNeedsNoSubtitlesEither() {
        // German dubs preferred, English subtitles: a German track is followed.
        assertNull(defaultSubtitleLang(audioLang = "ger", subtitlePref = "eng", audioPref = "deu"))
        assertEquals("en", defaultSubtitleLang(audioLang = "jpn", subtitlePref = "eng", audioPref = "deu"))
    }

    @Test
    fun neverWithSubtitlesSetOffNorWhenTheAudiosLanguageIsUnknown() {
        assertNull(defaultSubtitleLang(audioLang = "fre", subtitlePref = "off"))
        assertNull(defaultSubtitleLang(audioLang = "fre", subtitlePref = "OFF"))
        assertNull(defaultSubtitleLang(audioLang = "fre", subtitlePref = ""))
        for (audio in listOf("und", "", null)) {
            assertNull(defaultSubtitleLang(audioLang = audio, subtitlePref = "eng"), audio.toString())
        }
    }

    private data class Track(val id: String, val lang: String, val forced: Boolean = false, val default: Boolean = false)

    private fun pick(tracks: List<Track>, audio: String, pref: String) =
        defaultSubtitleTrack(tracks, { it.lang }, { it.forced }, audioLang = audio, subtitlePref = pref)?.id

    @Test
    fun theTrackAFullOneBeforeAForcedOneTheFilesDefaultFlagCountsForNothing() {
        val tracks = listOf(
            Track("de", "ger", default = true),
            Track("en-forced", "eng", forced = true),
            Track("en", "en"),
        )
        assertEquals("en", pick(tracks, audio = "fre", pref = "eng"))
        assertEquals("en-forced", pick(tracks.take(2), audio = "fre", pref = "eng"))
        // English audio: off, although German is flagged default in the file.
        assertNull(pick(tracks, audio = "eng", pref = "eng"))
        // No track in the chosen language: off, not some other language.
        assertNull(pick(tracks, audio = "fre", pref = "ita"))
    }
}
