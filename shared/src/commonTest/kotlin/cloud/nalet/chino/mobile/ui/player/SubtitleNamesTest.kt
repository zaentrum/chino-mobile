package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A track's name, as every app names it: chino-web's src/lib/languages.test.ts
 * (subtitles-spec, 893f594) as one table of input and expected name, case for
 * case, so the phone and the web cannot drift apart - its region and script
 * rules too (cdfdea8, 00dd469, 2cda806): a dialect's name or its adjective, a
 * code, a region's code in brackets all name the region, in the word list's
 * form. The phone names in English only, so the web's cases for another UI
 * language are not here.
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
            // Every ISO 639-1 language by its English name, however tagged:
            // "br" and "bre" are Breton, never the code.
            "br" to "Breton", "bre" to "Breton", "gla" to "Scottish Gaelic", "epo" to "Esperanto",
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
            // A region the table lacks: the platform's name, as Intl's.
            "en-GU" to "English (Guam)",
            "en-GB" to "English (United Kingdom)", "en-US" to "English (United States)", "pt-PT" to "Portuguese (Portugal)",
            "fr-CA" to "French (Canada)", "nl-BE" to "Dutch (Belgium)", "de-CH" to "German (Switzerland)", "es-ES" to "Spanish (Spain)",
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

    /** One row of chino-web's tables: the track's tag and title, the name,
     *  and the language the menu lists it by. */
    private data class Row(val lang: String, val title: String, val label: String, val tag: String)

    private fun assertRows(rows: List<Row>) {
        for (r in rows) {
            assertEquals(r.label, name(r.lang, r.title), "${r.lang} ${r.title}")
            assertEquals(r.tag, subtitleLanguage(r.lang, r.title), "${r.lang} ${r.title}")
        }
    }

    @Test
    fun aTitleInTheDialectFormOrWithTheRegionsAdjectiveNamesTheRegion() {
        assertRows(
            listOf(
                // The dialect form names the region: said in the standard
                // form, the language and its region in brackets.
                Row("por", "Brazilian Portuguese", "Portuguese (Brazil)", "pt-BR"),
                Row("por", "European Portuguese", "Portuguese (Portugal)", "pt-PT"),
                Row("spa", "Latin American Spanish", "Spanish (Latin America)", "es-419"),
                Row("fre", "Canadian French", "French (Canada)", "fr-CA"),
                Row("eng", "British English", "English (United Kingdom)", "en-GB"),
                Row("dut", "Flemish", "Dutch (Belgium)", "nl-BE"),
                Row("pt-BR", "Brazilian Portuguese", "Portuguese (Brazil)", "pt-BR"),
                // The dialect's adjective in brackets after the language, or alone.
                Row("por", "Portuguese (Brazilian)", "Portuguese (Brazil)", "pt-BR"),
                Row("por", "Portuguese (European)", "Portuguese (Portugal)", "pt-PT"),
                Row("spa", "Spanish (Latin American)", "Spanish (Latin America)", "es-419"),
                Row("spa", "Spanish (Mexican)", "Spanish (Mexico)", "es-MX"),
                Row("fre", "French (Canadian)", "French (Canada)", "fr-CA"),
                Row("eng", "English (British)", "English (United Kingdom)", "en-GB"),
                Row("eng", "English [American]", "English (United States)", "en-US"),
                Row("ger", "German (Austrian)", "German (Austria)", "de-AT"),
                Row("por", "Brazilian", "Portuguese (Brazil)", "pt-BR"),
                Row("por", "Portuguese - Brazilian", "Portuguese (Brazil)", "pt-BR"),
                Row("spa", "Spanish (Latin American) (Forced)", "Spanish (Latin America) · foreign-language parts only", "es-419"),
                // chino-stream's own label for such a track.
                Row("por", "Portuguese · Brazilian", "Portuguese (Brazil)", "pt-BR"),
            ),
        )
        assertNames(
            listOf(
                // Another region than the tag's stays a word.
                t("es-ES", "Latin American Spanish") to "Spanish (Spain) · Latin American Spanish",
                t("pt-PT", "Portuguese (Brazilian)") to "Portuguese (Portugal) · Brazilian",
                // A region among other words is a word, its adjective too.
                t("por", "Brazilian Commentary") to "Portuguese · Brazilian Commentary",
            ),
        )
    }

    @Test
    fun aTitleThatIsACodeOfTheTracksLanguageKeepsItsRegionOrScriptInTheWordListsForm() {
        assertRows(
            listOf(
                Row("por", "PT-BR", "Portuguese (Brazil)", "pt-BR"),
                Row("por", "PT-PT", "Portuguese (Portugal)", "pt-PT"),
                Row("por", "pt_BR", "Portuguese (Brazil)", "pt-BR"),
                Row("chi", "zh-Hant", "Chinese (Traditional)", "zh-Hant"),
                Row("chi", "zh-Hans", "Chinese (Simplified)", "zh-Hans"),
                Row("chi", "zh-Hant-TW", "Chinese (Traditional, Taiwan)", "zh-Hant-TW"),
                Row("spa", "es-419", "Spanish (Latin America)", "es-419"),
                Row("spa", "es-ES", "Spanish (Spain)", "es-ES"),
                Row("eng", "en-US", "English (United States)", "en-US"),
                Row("eng", "en-GB", "English (United Kingdom)", "en-GB"),
                // In brackets after the language's name, and with the kind.
                Row("por", "Português (PT-BR)", "Portuguese (Brazil)", "pt-BR"),
                Row("por", "PT-BR (Forced)", "Portuguese (Brazil) · foreign-language parts only", "pt-BR"),
                // The tag's own again says nothing more; a code alone is the language.
                Row("pt-BR", "PT-BR", "Portuguese (Brazil)", "pt-BR"),
                Row("por", "pt", "Portuguese", "pt"),
                Row("eng", "eng", "English", "en"),
                Row("eng", "en-US-x-custom", "English (United States)", "en-US"),
            ),
        )
        assertNames(
            listOf(
                // Another region or script than the tag's stays, as the title says it.
                t("pt-PT", "PT-BR") to "Portuguese (Portugal) · PT-BR",
                t("zh-Hant", "zh-Hans") to "Chinese (Traditional) · zh-Hans",
                // Another language's code is something else.
                t("eng", "de") to "English · de",
            ),
        )
    }

    @Test
    fun aRegionsCodeInBracketsAfterTheLanguageIsTheRegionWhereItIsOneOfTheLanguages() {
        assertRows(
            listOf(
                Row("por", "Português (BR)", "Portuguese (Brazil)", "pt-BR"),
                // The language's own code, and a region of it: the region.
                Row("por", "Português (PT)", "Portuguese (Portugal)", "pt-PT"),
                Row("spa", "Español (419)", "Spanish (Latin America)", "es-419"),
                Row("spa", "Español [MX]", "Spanish (Mexico)", "es-MX"),
                Row("fre", "Français (CA)", "French (Canada)", "fr-CA"),
                Row("ger", "Deutsch (CH)", "German (Switzerland)", "de-CH"),
                Row("chi", "中文（TW）", "Chinese (Taiwan)", "zh-TW"),
                Row("spa", "Español (419) (Forced)", "Spanish (Latin America) · foreign-language parts only", "es-419"),
            ),
        )
        assertNames(
            listOf(
                // The language's own code where it is none of its regions: the
                // language (El Salvador, Argentina and Canada speak other
                // languages most).
                t("swe", "Svenska (SV)") to "Swedish",
                t("ara", "Arabic (AR)") to "Arabic",
                t("cat", "Català (CA)") to "Catalan",
                // A code in lower case is a language's; other capitals are a word.
                t("por", "Português (pt)") to "Portuguese",
                t("eng", "English (BD)") to "English · BD",
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
    fun theSameLabelTwiceReadsTheSameNeverNumberedTheServersNumberGoes() {
        // Never numbered: a menu shows the ones that read the same once
        // (SubtitleMenuTest).
        assertEquals(
            listOf(
                "English",
                "English",
                "English",
                "English · for deaf and hard of hearing",
                "English · for deaf and hard of hearing",
                "German · foreign-language parts only",
                "German · foreign-language parts only",
                "Unknown",
            ),
            subtitleLabels(
                listOf(
                    t("eng", "eng"),
                    t("eng", "English"),
                    // chino-stream's unique NAME: its number says nothing of the track.
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
            listOf("No dialogue", "No dialogue", "No dialogue · Signs", "Signs & Songs", "Unknown", "Unknown · foreign-language parts only", "Unknown"),
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
