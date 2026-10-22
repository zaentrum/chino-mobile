package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A track's name, as every app names it: chino-web's src/lib/languages.test.ts
 * (subtitles-spec, 331aad4) as one table of input and expected name, case for
 * case, so the phone and the web cannot drift apart. The phone names in
 * English only, so the web's cases for another UI language are not here.
 */
class SubtitleNamesTest {
    private fun name(lang: String?, title: String? = null, forced: Boolean = false, sdh: Boolean = false) =
        subtitleLabel(SubtitleLabelInput(lang, title, forced, sdh))

    private fun assertNames(cases: List<Pair<SubtitleLabelInput, String>>) {
        for ((input, expected) in cases) assertEquals(expected, subtitleLabel(input), input.toString())
    }

    private fun t(lang: String?, title: String? = null, forced: Boolean = false, sdh: Boolean = false) = SubtitleLabelInput(lang, title, forced, sdh)

    @Test
    fun languagesByName() {
        val cases = listOf(
            "ger" to "German", "fre" to "French", "dut" to "Dutch", "vie" to "Vietnamese", "pt-BR" to "Portuguese (Brazil)",
            "und" to "Unknown", null to "Unknown",
            // A code there is no name for is shown as it came.
            "qaa" to "qaa",
            // "tl" is Tagalog, as ISO 639 calls it; "fil" is Filipino.
            "tl" to "Tagalog", "tgl" to "Tagalog", "tl-PH" to "Tagalog (Philippines)", "fil" to "Filipino",
            // A language the table lacks: the platform's English name, as Intl's.
            "br" to "Breton",
        )
        for ((code, expected) in cases) assertEquals(expected, languageName(code), code.toString())
        assertEquals("Tagalog", name("tgl", "Tagalog"))
    }

    @Test
    fun aScriptOrARegionInBracketsAfterTheLanguage() {
        val cases = listOf(
            "zh-Hans" to "Chinese (Simplified)",
            "zh-hant" to "Chinese (Traditional)",
            "zh-Hant-TW" to "Chinese (Traditional, Taiwan)",
            "pt_br" to "Portuguese (Brazil)",
            "spa-419" to "Spanish (Latin America)",
            "en-NZ" to "English (New Zealand)",
            "sr-Latn-RS" to "Serbian (Latin, Serbia)",
            // A variant or an extension names nothing.
            "de-1901" to "German",
            "en-US-x-custom" to "English (United States)",
        )
        for ((code, expected) in cases) assertEquals(expected, languageName(code), code)
        // Never the dialect's own name.
        assertNotEquals("Brazilian Portuguese", languageName("pt-BR"))
        assertNotEquals("Latin American Spanish", languageName("es-419"))
    }

    @Test
    fun zxxIsAFilmWithoutDialogue() {
        for (code in listOf("zxx", "ZXX", " zxx ", "zxx-Latn")) assertEquals("No dialogue", languageName(code), code)
    }

    @Test
    fun theDemosSintelEveryTrackNamedNoneBlank() {
        assertEquals(
            listOf("German", "Dutch", "English", "French", "Italian", "Spanish", "Polish", "Portuguese", "Russian", "Vietnamese"),
            subtitleLabels(listOf("ger", "dut", "eng", "fre", "ita", "spa", "pol", "por", "rus", "vie").map { t(it) }),
        )
    }

    @Test
    fun theKindInWordsAfterTheLanguageForcedFirst() {
        assertNames(
            listOf(
                t("eng", sdh = true) to "English · for deaf and hard of hearing",
                t("eng", forced = true) to "English · foreign-language parts only",
                t("eng", forced = true, sdh = true) to "English · foreign-language parts only · for deaf and hard of hearing",
                t("zh-Hans", forced = true) to "Chinese (Simplified) · foreign-language parts only",
            ),
        )
    }

    @Test
    fun bothTracksOfALanguageEachWithItsKind() {
        assertNames(
            listOf(
                t("eng", "English") to "English",
                t("eng", "English SDH") to "English · for deaf and hard of hearing",
                t("ger") to "German",
                t("ger", "German (forced)", forced = true) to "German · foreign-language parts only",
            ),
        )
    }

    @Test
    fun aTitleThatSaysTheKindItselfSaysItOnceInTheseWords() {
        val sdh = "English · for deaf and hard of hearing"
        val forced = "English · foreign-language parts only"
        val both = "English · foreign-language parts only · for deaf and hard of hearing"
        val cases = listOf(
            "English SDH" to sdh, "SDH" to sdh, "English [CC]" to sdh, "[CC]" to sdh,
            "English (Closed Captions)" to sdh, "English - Hearing Impaired" to sdh,
            "English (for the deaf and hard of hearing)" to sdh,
            // A packager's NAME, and this rule's own words again.
            "English · SDH" to sdh, sdh to sdh,
            "English (Forced)" to forced, "Forced" to forced, "English Forced Narrative" to forced, forced to forced,
            "English · SDH (forced)" to both, "Forced, SDH" to both, both to both,
        )
        for ((title, expected) in cases) assertEquals(expected, name("eng", title), title)
        // A title that says nothing of a kind is no kind.
        assertEquals("English", name("eng", "English"))
        assertEquals(true, isSdhSubtitle("English [CC]"))
        assertEquals(false, isSdhSubtitle("Commentary"))
        assertEquals(true, saysForced("German (Forced)"))
        assertEquals(false, saysForced("Reinforced"))
    }

    @Test
    fun wordsThatOnlyNameTheLanguageAgainGoInAnyLanguageWordsThatAddSomethingStay() {
        assertNames(
            listOf(
                // The word list's name, never "German · Deutsch".
                t("de", "Deutsch") to "German",
                t("de", "German · Deutsch") to "German",
                t("zh-hans", "中文") to "Chinese (Simplified)",
                t("zh-Hans", "Chinese (Simplified) · 中文") to "Chinese (Simplified)",
                t("zh-Hant", "繁體中文") to "Chinese (Traditional)",
                t("pt-br", "Português") to "Portuguese (Brazil)",
                t("spa", "Español") to "Spanish",
                t("ja", "日本語") to "Japanese",
                // Named in a third language: still only the language.
                t("ger", "Allemand") to "German",
                t("de", "Deutsch (Forced)") to "German · foreign-language parts only",
                t("de", "Deutsch · Kommentar") to "German · Kommentar",
                // Another language's own name is something else.
                t("de", "Español") to "German · Español",
                // Inside a part with other words, only the language's name
                // goes - not the label's own name, which says it once - and a
                // word that is a code elsewhere is a word ("und" is German
                // for "and").
                t("de", "Deutsch Kommentar") to "German · Kommentar",
                t("de", "Kommentar und Analyse") to "German · Kommentar und Analyse",
                t("eng", "English Commentary") to "English Commentary",
            ),
        )
    }

    @Test
    fun aChineseTracksTitleThatGivesItsScriptNamesItByItsScript() {
        for (title in listOf("简体中文", "簡體中文", "简体", "CHS", "Simplified", "Chinese (Simplified)", "Simplified Chinese")) {
            assertEquals("Chinese (Simplified)", name("chi", title), title)
            assertEquals("zh-Hans", subtitleLanguage("chi", title), title)
        }
        for (title in listOf("繁體中文", "繁体中文", "繁體", "CHT", "Traditional")) {
            assertEquals("Chinese (Traditional)", name("chi", title), title)
            assertEquals("zh-Hant", subtitleLanguage("zho", title), title)
        }
        assertNames(
            listOf(
                // Its region in brackets, the full-width ones too.
                t("chi", "繁體中文（台灣）") to "Chinese (Traditional, Taiwan)",
                // A tag that names the other script: the title's word stays.
                t("zh-Hans", "繁體中文") to "Chinese (Simplified) · 繁體中文",
                // Mandarin and Cantonese in the word list's words.
                t("chi", "國語") to "Chinese · Mandarin",
                t("chi", "普通话") to "Chinese · Mandarin",
                t("chi", "粵語") to "Chinese · Cantonese",
                t("chi", "中文") to "Chinese",
            ),
        )
        assertEquals("zh", subtitleLanguage("chi", "中文"))
    }

    @Test
    fun aLanguagesOwnNameBeforeARegionTheRegionInBracketsAfterTheLanguage() {
        val cases = listOf(
            Triple("spa", "Español (Latinoamérica)", "Spanish (Latin America)") to "es-419",
            Triple("spa", "Español (España)", "Spanish (Spain)") to "es-ES",
            Triple("spa", "Español Latino", "Spanish (Latin America)") to "es-419",
            Triple("spa", "Latinoamerica", "Spanish (Latin America)") to "es-419",
            Triple("fre", "Français (Canada)", "French (Canada)") to "fr-CA",
            Triple("fre", "Français canadien", "French (Canada)") to "fr-CA",
            Triple("pt", "Português (Brasil)", "Portuguese (Brazil)") to "pt-BR",
            Triple("por", "Português - Brasil", "Portuguese (Brazil)") to "pt-BR",
            Triple("spa", "Spanish (Latin America)", "Spanish (Latin America)") to "es-419",
            // The tag's region again says nothing more.
            Triple("es-419", "Español (Latinoamérica)", "Spanish (Latin America)") to "es-419",
        )
        for ((input, tag) in cases) {
            val (lang, title, label) = input
            assertEquals(label, name(lang, title), title)
            assertEquals(tag, subtitleLanguage(lang, title), title)
        }
        assertNames(
            listOf(
                // Another region than the tag's stays a word.
                t("es-ES", "Latinoamérica") to "Spanish (Spain) · Latinoamérica",
                // A region among other words is a word.
                t("fre", "Canada Commentaire") to "French · Canada Commentaire",
                // With the kind: once, after.
                t("spa", "Español (Latinoamérica) (Forced)") to "Spanish (Latin America) · foreign-language parts only",
            ),
        )
    }

    @Test
    fun whatElseATitleSaysStaysBeforeTheKind() {
        assertEquals(
            listOf(
                "English · Commentary",
                "English Commentary",
                "English · Commentary · for deaf and hard of hearing",
                "German · Signs & Songs · foreign-language parts only",
                "English (United Kingdom)",
                "Chinese (Simplified) · Converted",
            ),
            subtitleLabels(
                listOf(
                    t("eng", "Commentary"),
                    t("eng", "English Commentary"),
                    t("eng", "Commentary (SDH)"),
                    t("ger", "Signs & Songs", forced = true),
                    // "English" on a British English track: the name says it.
                    t("en-GB", "English"),
                    // A made track's label, its script in the name already.
                    t("zh-Hans", "Chinese (Simplified) · Converted"),
                ),
            ),
        )
        // The language again before what the title adds: said once.
        assertEquals("English · Commentary", name("eng", "English · Commentary"))
    }

    @Test
    fun aSidecarTheServerMadeSaysAiAfterTheLanguage() {
        assertEquals(
            listOf("German · AI", "English · AI", "Chinese (Simplified) · AI"),
            subtitleLabels(listOf(t("de", "AI"), t("en", "AI"), t("zh-Hans", "AI"))),
        )
    }

    @Test
    fun theSameLabelTwiceIsNumberedTheServersNumberNoMore() {
        // The labels as a list names them (the TV's tracks); a menu shows
        // the ones that read the same once instead (SubtitleMenuTest).
        assertEquals(
            listOf(
                "English",
                "English (2)",
                "English (3)",
                "English · for deaf and hard of hearing",
                "English · for deaf and hard of hearing (2)",
                "German · foreign-language parts only",
                "German · foreign-language parts only (2)",
                "Unknown",
            ),
            subtitleLabels(
                listOf(
                    t("eng", "eng"),
                    t("eng", "English"),
                    // chino-stream's unique NAME: numbered by the labels, once.
                    t("eng", "English (2)"),
                    t("eng", "SDH"),
                    t("eng", "English · SDH (2)"),
                    t("ger", forced = true),
                    t("ger", "Forced", forced = true),
                    t("und"),
                ),
            ),
        )
    }

    @Test
    fun withoutDialogueOrInNoKnownLanguageNoDialogueElseTheTitleElseUnknown() {
        assertEquals(
            listOf("No dialogue", "No dialogue (2)", "No dialogue · Signs", "Signs & Songs", "Unknown", "Unknown · foreign-language parts only", "Unknown (2)"),
            subtitleLabels(
                listOf(
                    t("zxx"),
                    t("zxx", "zxx"),
                    t("zxx", "Signs"),
                    t("und", "Signs & Songs"),
                    t("und", "und"),
                    t("", "Forced", forced = true),
                    t("und"),
                ),
            ),
        )
    }

    @Test
    fun mulIsMultipleLanguagesMisOtherLanguage() {
        assertEquals("Multiple languages", languageName("mul"))
        assertEquals("Multiple languages", languageName("MUL"))
        assertEquals("Other language", languageName("mis"))
        assertEquals(listOf("Multiple languages", "Other language · Signs"), subtitleLabels(listOf(t("mul"), t("mis", "Signs"))))
    }
}
