package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.ServerSubtitleLanguages
import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.TrackInfo
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The subtitles menu (docs/chino subtitles): the languages that matter here on
 * top, the rest behind "more languages" - never every track, a server that
 * names no languages too, where the person's own are on top - a language's
 * tracks together in one order, a track that reads like another once.
 * chino-web's src/lib/subtitles.test.ts (subtitles-spec, 65288be) case for
 * case - nothing numbered, the file's own before a sidecar, an untagged one
 * in its twin's language, a packaged title as any other; within a language
 * each region or script a run of its own, the bare name first, the others
 * alphabetically; "more languages" alphabetically by the languages' names.
 */
class SubtitleMenuTest {
    private data class S(
        val id: String,
        val lang: String,
        val title: String? = null,
        val forced: Boolean = false,
        val format: String? = null,
        val embedded: Boolean = false,
    )

    private val S.isForced get() = forced || saysForced(title)
    private val S.label get() = subtitleLabel(SubtitleLabelInput(lang, title, isForced, isSdhSubtitle(title)))
    private val S.picture get() = subtitleKind(format) != SubtitleKind.Text

    private val server = listOf("en", "de", "zh-Hans")

    // An original's tracks as a file and its sidecars bring them: the file's
    // own streams first, in no order, some twice; then the sidecars.
    private val original = listOf(
        S("emb-3", "ger", forced = true, embedded = true),
        S("emb-4", "eng", "English SDH", embedded = true),
        S("emb-5", "jpn", embedded = true),
        S("emb-6", "eng", embedded = true),
        S("emb-7", "zh-Hant", embedded = true),
        S("emb-8", "ger", "Deutsch", embedded = true),
        S("emb-9", "fre", embedded = true),
        S("emb-10", "und", "Signs", embedded = true),
        S("side-en", "en"),
        S("side-zh", "zh-Hans", "AI"),
        S("side-de-ai", "de", "AI"),
    )

    private fun menu(
        tracks: List<S>,
        preferred: String? = "eng",
        serverLanguages: List<String> = server,
        on: String? = null,
        audio: String? = null,
        device: String? = null,
    ) = subtitleMenu(
        tracks = tracks,
        tag = { subtitleLanguage(it.lang, it.title) },
        forced = { it.isForced },
        sdh = { isSdhSubtitle(it.title) },
        label = { it.label },
        preferred = preferred,
        serverLanguages = serverLanguages,
        audio = audio,
        device = device,
        on = { it.id == on },
        own = { it.embedded },
        ai = { isAiSubtitle(it.title) },
        picture = { it.picture },
        retag = { kept, twin -> kept.copy(lang = twin.lang) },
    )

    private fun labels(list: List<S>) = list.map { it.label }

    /** Every entry of the menu, in its order. */
    private fun entries(m: SubtitleMenu<S>) = m.top + m.more

    /** What comes on by itself among the menu's tracks, in its order: a
     *  setting's script or region matched as the menu lists a track, as the
     *  player asks it. */
    private fun auto(m: SubtitleMenu<S>, audioLang: String?, subtitlePref: String?) =
        autoSubtitleTrack(
            m.top + m.more,
            { it.lang },
            { it.isForced },
            { !it.picture },
            audioLang = audioLang,
            subtitlePref = subtitlePref,
            tag = { subtitleLanguage(it.lang, it.title) },
        )?.id

    @Test
    fun theViewersLanguageThenTheServersThenMoreLanguagesWithTheRest() {
        val m = menu(original)
        assertEquals(
            listOf(
                "English",
                "English · for deaf and hard of hearing",
                "German",
                "German · foreign-language parts only",
                "German · AI",
                "Chinese (Simplified) · AI",
            ),
            labels(m.top),
        )
        // The rest in alphabetical order of their languages' names, a track
        // in no language by its own (the web lists them in the file's
        // order); "zh-Hant" is not "zh-Hans".
        assertEquals(listOf("Chinese (Traditional)", "French", "Japanese", "Signs"), labels(m.more))
    }

    @Test
    fun withinALanguagePlainSdhForcedThenWhatThePlatformMade() {
        val m = menu(listOf(S("ai", "en", "AI"), S("f", "eng", "Forced"), S("sdh", "eng", "[CC]"), S("plain", "eng")))
        assertEquals(
            listOf("English", "English · for deaf and hard of hearing", "English · foreign-language parts only", "English · AI"),
            labels(m.top),
        )
        // "AI" as a word of its own, in capitals: not inside "MAIN", not "Plain".
        assertEquals(listOf(false, false, true), listOf("MAIN", "Plain", "AI (preview)").map(::isAiSubtitle))
    }

    @Test
    fun aViewerWithSubtitlesOffHasTheServersLanguagesOnTopTheirOwnFirstWhereItIsNotOneOfThem() {
        assertEquals(
            listOf("German", "German · foreign-language parts only", "German · AI"),
            labels(menu(original, preferred = "off", serverLanguages = listOf("de")).top),
        )
        assertEquals(
            listOf("French", "German", "German · foreign-language parts only", "German · AI"),
            labels(menu(original, preferred = "fre", serverLanguages = listOf("de")).top),
        )
    }

    @Test
    fun aLanguageNamedTwiceOnTopByTwoScriptsIsOneBlockWhereItFirstComesNeverSplitByTheLanguagesBetween() {
        val tracks = listOf(
            S("emb-2", "chi", embedded = true),
            S("emb-3", "chi", "繁體中文", embedded = true),
            S("emb-4", "eng", embedded = true),
            S("emb-5", "ger", embedded = true),
            S("side-zh", "zh-Hans", "AI"),
            S("side-en", "en", "AI"),
        )
        val chineseFirst = listOf("Chinese", "Chinese (Simplified) · AI", "Chinese (Traditional)", "English", "English · AI", "German")
        // The setting's Traditional Chinese, the server's Simplified after
        // English and German: Chinese together, first, its runs in their order.
        val m = menu(tracks, preferred = "zh-Hant")
        assertEquals(chineseFirst, labels(m.top))
        assertEquals(emptyList(), m.more)
        // The same where the setting is one of the server's languages.
        assertEquals(chineseFirst, labels(menu(tracks, preferred = "zh-Hant", serverLanguages = listOf("en", "de", "zh-Hans", "zh-Hant")).top))
        // The server's list naming Chinese twice, German between: Chinese
        // where it first comes.
        assertEquals(
            listOf("English", "English · AI", "Chinese", "Chinese (Simplified) · AI", "Chinese (Traditional)", "German"),
            labels(menu(tracks, preferred = "en", serverLanguages = listOf("en", "zh-Hans", "de", "zh-Hant")).top),
        )
        // An original's tracks, the setting's Traditional and the server's
        // Simplified: Chinese first, never split by English and German.
        assertEquals(
            listOf(
                "Chinese (Simplified) · AI",
                "Chinese (Traditional)",
                "English",
                "English · for deaf and hard of hearing",
                "German",
                "German · foreign-language parts only",
                "German · AI",
            ),
            labels(menu(original, preferred = "zh-Hant").top),
        )
        // The server's bare Chinese after the setting's Traditional: one
        // block, the bare name first, then the runs alphabetically.
        val chinese = listOf(
            S("emb-2", "zh-Hans", embedded = true),
            S("emb-3", "zh-Hant", embedded = true),
            S("emb-4", "chi", embedded = true),
            S("emb-5", "zh-CN", embedded = true),
            S("side-zh", "zh-Hant", "AI"),
            S("emb-6", "eng", embedded = true),
        )
        assertEquals(
            listOf("Chinese", "Chinese (China)", "Chinese (Simplified)", "Chinese (Traditional)", "Chinese (Traditional) · AI"),
            labels(menu(chinese, preferred = "zh-Hant", serverLanguages = listOf("zh")).top),
        )
        // A language the server names that the title doesn't have opens no
        // block of its own.
        assertEquals(listOf("English", "English · AI", "German"), labels(menu(tracks, preferred = "off", serverLanguages = listOf("en", "fr", "de")).top))
    }

    @Test
    fun aServerWithNoSubtitleLanguagesThePersonsOwnLanguagesOnTopTheRestUnderMoreLanguages() {
        // The subtitle setting's language, the audio setting's, the device's -
        // in that order, each the whole language.
        val m = menu(original, preferred = "eng", audio = "jpn", device = "de-CH", serverLanguages = emptyList())
        assertEquals(
            listOf(
                "English",
                "English · for deaf and hard of hearing",
                "Japanese",
                "German",
                "German · foreign-language parts only",
                "German · AI",
            ),
            labels(m.top),
        )
        // The rest in alphabetical order of their names, a language's scripts
        // together: Chinese (Simplified) · AI with the file's Chinese.
        assertEquals(listOf("Chinese (Simplified) · AI", "Chinese (Traditional)", "French", "Signs"), labels(m.more))
        // Never every track on top: with the subtitle setting's language
        // alone, the other languages wait under "more languages".
        val alone = menu(original, serverLanguages = emptyList())
        assertEquals(listOf("English", "English · for deaf and hard of hearing"), labels(alone.top))
        assertEquals(
            listOf(
                "Chinese (Simplified) · AI",
                "Chinese (Traditional)",
                "French",
                "German",
                "German · foreign-language parts only",
                "German · AI",
                "Japanese",
                "Signs",
            ),
            labels(alone.more),
        )
        // Tags that name no language count as none.
        assertEquals(alone, menu(original, serverLanguages = listOf("und", "")))
    }

    @Test
    fun aHundredTracksAndNoServerLanguagesGermanEnglishAndChineseOnTopTheOtherNinetyOddUnderMoreLanguages() {
        val codes = listOf(
            "ara", "bul", "cat", "ces", "dan", "ell", "est", "fin", "fra", "heb", "hin", "hrv", "hun", "ind", "isl", "ita", "jpn", "kor",
            "lav", "lit", "msa", "nld", "nor", "pol", "por", "ron", "rus", "slk", "slv", "spa", "srp", "swe", "tha", "tur", "ukr", "vie",
        )
        val tracks = ArrayList<S>()
        var n = 2
        for (lang in codes) {
            tracks += S("emb-${n++}", lang, embedded = true)
            tracks += S("emb-${n++}", lang, "SDH", embedded = true)
        }
        tracks += S("emb-${n++}", "ger", embedded = true)
        tracks += S("emb-${n++}", "eng", embedded = true)
        tracks += S("emb-${n++}", "chi", "简体中文", embedded = true)
        for (lang in codes) tracks += S("side-$lang", lang, "AI")
        tracks += S("side-zh-hant", "zh-Hant", "AI")
        assertTrue(tracks.size > 100)
        // German subtitles in Settings, English audio, a phone in Chinese.
        val m = menu(tracks, preferred = "ger", audio = "eng", device = "zh-CN", serverLanguages = emptyList())
        assertEquals(listOf("German", "English", "Chinese (Simplified)", "Chinese (Traditional) · AI"), labels(m.top))
        assertEquals(codes.size * 3, m.more.size)
        assertEquals("Arabic", m.more.first().label)
        // Every track is still there to pick, each once.
        assertEquals(tracks.size, entries(m).size)
        // A server naming Chinese twice, German between, subtitles off:
        // Chinese one block, where it first comes.
        assertEquals(
            listOf("English", "Chinese (Simplified)", "Chinese (Traditional) · AI", "German"),
            labels(menu(tracks, preferred = "off", serverLanguages = listOf("en", "zh-Hans", "de", "zh-Hant")).top),
        )
    }

    @Test
    fun thePersonsOwnLanguagesTheAudioSettingsUnlessOriginalTheDevicesEachLanguageOnce() {
        val tracks = listOf(
            S("emb-2", "fre", embedded = true),
            S("emb-3", "ita", embedded = true),
            S("emb-4", "eng", embedded = true),
            S("emb-5", "en-GB", embedded = true),
        )
        // Audio set to Original names no language: the device's alone.
        val original = menu(tracks, preferred = "off", audio = "orig", device = "it-IT", serverLanguages = emptyList())
        assertEquals(listOf("Italian"), labels(original.top))
        assertEquals(listOf("English", "English (United Kingdom)", "French"), labels(original.more))
        // The device's language with a region takes the whole language, the
        // bare name first.
        assertEquals(
            listOf("English", "English (United Kingdom)"),
            labels(menu(tracks, preferred = "off", device = "en-US", serverLanguages = emptyList()).top),
        )
        // The same language twice (the setting's "eng", the device's "en-GB")
        // is one block, where the first of them puts it.
        assertEquals(
            listOf("English", "English (United Kingdom)", "French"),
            labels(menu(tracks, preferred = "eng", audio = "fre", device = "en-GB", serverLanguages = emptyList()).top),
        )
        // None of them: "off" and "more languages" alone.
        val none = menu(tracks, preferred = "off", audio = "orig", device = "ja-JP", serverLanguages = emptyList())
        assertEquals(emptyList(), none.top)
        assertEquals(4, none.more.size)
        // On a server that names its languages, the audio setting's and the
        // device's count for nothing: the subtitle setting's, then the server's.
        assertEquals(
            listOf("English", "English (United Kingdom)"),
            labels(menu(tracks, preferred = "off", audio = "fre", device = "it-IT", serverLanguages = listOf("en")).top),
        )
        assertEquals(listOf("eng", "fre", "de-CH"), ownLanguages("eng", "fre", "de-CH"))
        assertEquals(listOf("de-CH"), ownLanguages("off", "orig", "de-CH"))
        assertEquals(listOf("deu"), ownLanguages("deu", "orig", "de-AT"))
        assertEquals(emptyList(), ownLanguages(null, null, "und"))
    }

    @Test
    fun noServerLanguagesThePreferredLanguageFirstTheWholeOfItItsRunsInOrderTheRestUnderMoreLanguages() {
        val tracks = listOf(
            S("emb-2", "chi", "中文", embedded = true),
            S("emb-3", "fre", embedded = true),
            S("emb-4", "und", "Signs", embedded = true),
            S("emb-5", "ger", embedded = true),
            S("emb-6", "fre", "SDH", embedded = true),
            S("emb-7", "eng", embedded = true),
            S("emb-8", "zh-Hant", embedded = true),
            S("side-zh", "zh-Hans", "AI"),
            S("side-de", "de", "AI"),
            S("side-en", "en", "AI"),
        )
        val eng = menu(tracks, preferred = "eng", serverLanguages = emptyList())
        assertEquals(listOf("English", "English · AI"), labels(eng.top))
        // The rest under "more languages", alphabetical, a language's tracks
        // together: the AI Chinese with the file's Chinese, not after French.
        assertEquals(
            listOf(
                "Chinese",
                "Chinese (Simplified) · AI",
                "Chinese (Traditional)",
                "French",
                "French · for deaf and hard of hearing",
                "German",
                "German · AI",
                "Signs",
            ),
            labels(eng.more),
        )
        // Subtitles off in Settings: nothing on top, a language's runs under
        // "more languages" the bare one first, then alphabetical.
        val off = menu(tracks, preferred = "off", serverLanguages = emptyList())
        assertEquals(emptyList(), off.top)
        assertEquals(listOf("Chinese", "Chinese (Simplified) · AI", "Chinese (Traditional)"), labels(off.more).take(3))
        // A preferred language with a script: the language on top, the whole
        // of it, its runs the bare one first, then alphabetical - the others
        // under "more languages".
        val hans = menu(tracks, preferred = "zh-Hans", serverLanguages = emptyList())
        assertEquals(listOf("Chinese", "Chinese (Simplified) · AI", "Chinese (Traditional)"), labels(hans.top))
        assertEquals("English", hans.more.first().label)
        // And with a region: the language's runs, the others under "more
        // languages".
        val portuguese = listOf(
            S("emb-2", "eng", embedded = true),
            S("emb-3", "pt-PT", embedded = true),
            S("emb-4", "por", "Português (Brasil)", embedded = true),
            S("side-pt", "pt-BR", "AI"),
        )
        val brazil = menu(portuguese, preferred = "pt-BR", serverLanguages = emptyList())
        assertEquals(listOf("Portuguese (Brazil)", "Portuguese (Brazil) · AI", "Portuguese (Portugal)"), labels(brazil.top))
        assertEquals(listOf("English"), labels(brazil.more))
        // A preferred run that doesn't sort first keeps the language's order:
        // the bare name, then the runs alphabetically - not its own run first,
        // nor the rest of the language spliced in after it.
        val chinese = listOf(
            S("emb-2", "zh-Hans", embedded = true),
            S("emb-3", "zh-Hant", embedded = true),
            S("emb-4", "chi", embedded = true),
            S("emb-5", "zh-CN", embedded = true),
            S("side-zh", "zh-Hant", "AI"),
            S("emb-6", "eng", embedded = true),
        )
        val hant = menu(chinese, preferred = "zh-Hant", serverLanguages = emptyList())
        assertEquals(
            listOf("Chinese", "Chinese (China)", "Chinese (Simplified)", "Chinese (Traditional)", "Chinese (Traditional) · AI"),
            labels(hant.top),
        )
        assertEquals(listOf("English"), labels(hant.more))
        // The same order for the device's language with a region.
        assertEquals(labels(hant.top), labels(menu(chinese, preferred = "off", device = "zh-TW", serverLanguages = emptyList()).top))
        val portugal = listOf(
            S("emb-2", "pt-BR", embedded = true),
            S("emb-3", "eng", embedded = true),
            S("emb-4", "pt-PT", embedded = true),
            S("emb-5", "por", embedded = true),
        )
        val pt = menu(portugal, preferred = "pt-PT", serverLanguages = emptyList())
        assertEquals(listOf("Portuguese", "Portuguese (Brazil)", "Portuguese (Portugal)"), labels(pt.top))
        assertEquals(listOf("English"), labels(pt.more))
        // What comes on by itself is as before: a setting with its script or
        // region takes a track with that one first (defaultSubtitleTrack).
        assertEquals("emb-3", auto(menu(chinese, preferred = "zh-Hant", serverLanguages = emptyList()), audioLang = "eng", subtitlePref = "zh-Hant"))
        assertEquals("emb-4", auto(menu(portugal, preferred = "pt-PT", serverLanguages = emptyList()), audioLang = "eng", subtitlePref = "pt-PT"))
    }

    @Test
    fun aPackagedTitleFollowsTheGeneralRule() {
        // A package's sidecars: the server's languages, and the tracks it kept
        // outside them.
        val packaged = listOf(S("de", "de"), S("fr", "fr"), S("en", "en"), S("ja", "ja"), S("zh", "zh-Hans"), S("de-ai", "de", "AI"))
        val m = menu(packaged)
        assertEquals(listOf("English", "German", "German · AI", "Chinese (Simplified)"), labels(m.top))
        assertEquals(listOf("French", "Japanese"), labels(m.more))
        // Only the server's languages: nothing more to open.
        assertEquals(emptyList(), menu(packaged.filter { it.lang != "fr" && it.lang != "ja" }).more)
        // A server with no subtitle languages: a package's tracks as an
        // original's - the person's own languages on top, the rest under
        // "more languages".
        val none = menu(packaged, audio = "deu", serverLanguages = emptyList())
        assertEquals(listOf("English", "German", "German · AI"), labels(none.top))
        assertEquals(listOf("Chinese (Simplified)", "French", "Japanese"), labels(none.more))
    }

    @Test
    fun duplicatesOfOneLanguageAndKindShowOnceTheFilesOwnBeforeASidecar() {
        val m = menu(original)
        val english = m.top.filter { it.label == "English" }
        assertEquals(listOf("emb-6"), english.map { it.id })
        // "German" and "Deutsch" on a German track read the same: once.
        assertEquals(1, m.top.count { it.label == "German" })
        // A number the server gave to keep a title unique is no difference.
        assertEquals(listOf("English"), labels(menu(listOf(S("a", "eng", "English"), S("b", "eng", "English (2)"))).top))
        // Three of the file's own, then two sidecars: the first of the file's.
        val many = menu(
            listOf(
                S("emb-2", "eng", embedded = true),
                S("emb-3", "eng", "English", embedded = true),
                S("emb-4", "en", embedded = true),
                S("side-1", "en"),
                S("side-2", "eng", "English"),
            ),
        )
        assertEquals(listOf("emb-2" to "English"), many.top.map { it.id to it.label })
        // Two sidecars alone: the first.
        assertEquals(listOf("side-1"), menu(listOf(S("side-1", "en"), S("side-2", "en"))).top.map { it.id })
        // A title that only cuts the region short reads as its untitled twin:
        // once, the file's own.
        val cutShort = listOf(
            S("emb-2", "es-419", embedded = true),
            S("emb-3", "es-419", "LAT", embedded = true),
            S("emb-4", "pt-BR", "BR", embedded = true),
            S("side-pt", "pt-BR"),
        )
        assertEquals(
            listOf("emb-4" to "Portuguese (Brazil)", "emb-2" to "Spanish (Latin America)"),
            entries(menu(cutShort, serverLanguages = emptyList())).map { it.id to it.label },
        )
    }

    @Test
    fun neverNumberedNoEntryOfAMenuReadsANumberInAnyLanguageOrKind() {
        val tracks = original + listOf(
            S("emb-20", "ger", embedded = true),
            S("emb-21", "jpn", "日本語", embedded = true),
            S("emb-22", "eng", "SDH", embedded = true),
            S("emb-23", "und", "Signs", embedded = true),
            S("emb-24", "und", "English", embedded = true),
            S("side-en-2", "eng", "English (2)"),
            S("side-zh-2", "zh-Hans", "AI (2)"),
        )
        for (serverLanguages in listOf(server, emptyList())) {
            val all = menu(tracks, serverLanguages = serverLanguages).let { it.top + it.more }
            for (e in all) assertFalse(Regex("""\(\d+\)""").containsMatchIn(e.label), e.label)
            // Each label once.
            assertEquals(all.size, all.map { it.label }.toSet().size)
        }
    }

    @Test
    fun duplicatesTheOneOnStaysWhicheverItIsATextOneBeforeAPictureOne() {
        val twice = listOf(S("emb", "eng", embedded = true), S("side", "en"), S("comm", "eng", "Commentary"))
        assertEquals(listOf("side", "comm"), menu(twice, on = "side").top.map { it.id })
        assertEquals(listOf("emb", "comm"), menu(twice).top.map { it.id })
        val pictures = listOf(S("pgs", "eng", format = "pgs"), S("vtt", "eng", format = "webvtt"))
        assertEquals(listOf("vtt"), menu(pictures).top.map { it.id })
    }

    @Test
    fun aTrackTaggedWithNoLanguageThatReadsAsOneTheFilesOwnElseTheFirstShowsInTheLanguageOfItsTwin() {
        // The file's untagged track titled "English", then a French one, then
        // an English sidecar: they read the same, and show once - the file's
        // own, timed to it, with the sidecar's language: on top, and on by
        // itself. No "English (2)".
        val tracks = listOf(S("emb-2", "und", "English", embedded = true), S("emb-3", "fre", embedded = true), S("s1", "en"))
        val m = menu(tracks)
        assertEquals(listOf(Triple("emb-2", "English", "en")), m.top.map { Triple(it.id, it.label, subtitleLanguage(it.lang, it.title)) })
        assertEquals(listOf("emb-3" to "French"), m.more.map { it.id to it.label })
        assertEquals("emb-2", auto(m, audioLang = "fre", subtitlePref = "eng"))
        // The same without the server's languages, and with no preferred language.
        val ownLanguages = menu(tracks, serverLanguages = emptyList())
        assertEquals(listOf("emb-2" to "en"), ownLanguages.top.map { it.id to subtitleLanguage(it.lang, it.title) })
        assertEquals(listOf("emb-3"), ownLanguages.more.map { it.id })
        assertEquals(listOf("emb-2", "emb-3"), menu(tracks, preferred = "off").let { it.top + it.more }.map { it.id })
        // The sidecar on: it stays, whichever it is.
        assertEquals(listOf("s1"), menu(tracks, on = "s1").top.map { it.id })
        // Alone, it is listed by its title, in its place in the file.
        assertEquals(listOf("emb-2" to "English", "emb-3" to "French"), menu(tracks.take(2)).more.map { it.id to it.label })
        // Two of the file's own, the untagged one first: the first, in English.
        val both = menu(listOf(S("emb-2", "und", "English", embedded = true), S("emb-3", "eng", embedded = true)))
        assertEquals(listOf("emb-2" to "en"), both.top.map { it.id to subtitleLanguage(it.lang, it.title) })
        assertEquals("emb-2", auto(both, audioLang = "fre", subtitlePref = "eng"))
        // The foreign-language parts only, too: English audio, the file's own forced track.
        val forced = menu(listOf(S("emb-4", "und", "English Forced", embedded = true), S("s2", "eng", forced = true)))
        assertEquals("emb-4", auto(forced, audioLang = "eng", subtitlePref = "off"))
        // Untagged tracks that read the same are one.
        assertEquals(1, menu(listOf(S("a", "und", "Signs"), S("b", "", "Signs"))).more.size)
    }

    @Test
    fun simplifiedAndTraditionalChineseToldApartByTheirTitlesOnlyAreTwo() {
        val tracks = listOf(S("emb-3", "chi", "简体中文", embedded = true), S("emb-4", "chi", "繁體中文", embedded = true), S("emb-5", "eng", embedded = true))
        val m = menu(tracks, preferred = "en")
        assertEquals(listOf("English", "Chinese (Simplified)"), labels(m.top))
        assertEquals(listOf("Chinese (Traditional)"), labels(m.more))
        assertEquals(listOf("zh-Hant"), m.more.map { subtitleLanguage(it.lang, it.title) })
        // A server with no languages: both, each by its script, under "more
        // languages"; on top for a phone in Chinese.
        assertEquals(listOf("Chinese (Simplified)", "Chinese (Traditional)"), labels(menu(tracks, preferred = "en", serverLanguages = emptyList()).more))
        assertEquals(
            listOf("English", "Chinese (Simplified)", "Chinese (Traditional)"),
            labels(menu(tracks, preferred = "en", device = "zh-HK", serverLanguages = emptyList()).top),
        )
        // A Traditional Chinese setting, English audio: the Traditional track
        // comes on, its script told by its title alone - with no server
        // languages, and with a server that names Chinese either way.
        for (serverLanguages in listOf(emptyList(), listOf("zh"), listOf("en", "de", "zh-Hans", "zh-Hant"))) {
            assertEquals("emb-4", auto(menu(tracks, preferred = "zh-Hant", serverLanguages = serverLanguages), audioLang = "eng", subtitlePref = "zh-Hant"), "$serverLanguages")
            assertEquals("emb-3", auto(menu(tracks, preferred = "zh-Hans", serverLanguages = serverLanguages), audioLang = "eng", subtitlePref = "zh-Hans"), "$serverLanguages")
        }
        // And a region a title tells: Portugal's Portuguese for a pt-PT setting.
        val portuguese = listOf(S("emb-2", "por", "Português (Brasil)", embedded = true), S("emb-3", "por", "Português (Portugal)", embedded = true))
        assertEquals("emb-3", auto(menu(portuguese, preferred = "pt-PT", serverLanguages = emptyList()), audioLang = "eng", subtitlePref = "pt-PT"))
        // Mandarin and Cantonese are two as well.
        assertEquals(
            listOf("Chinese · Mandarin", "Chinese · Cantonese"),
            labels(entries(menu(listOf(S("a", "chi", "國語"), S("b", "chi", "粵語")), serverLanguages = emptyList()))),
        )
    }

    @Test
    fun aLanguagesRegionInATitleListsTheTrackWithThatRegion() {
        val tracks = listOf(S("es", "spa", "Español (España)"), S("la", "spa", "Español (Latinoamérica)"), S("la2", "es-419"))
        val m = entries(menu(tracks, serverLanguages = emptyList()))
        // The same region twice is one; Spain and Latin America are two, each
        // a run of its own, alphabetically.
        assertEquals(listOf("Spanish (Latin America)", "Spanish (Spain)"), labels(m))
        assertEquals(listOf("es-419", "es-ES"), m.map { subtitleLanguage(it.lang, it.title) })
        // A region's adjective reads as the region: the file's Brazilian
        // Portuguese and the server's are one, the file's own.
        val brazil = listOf(S("emb-2", "por", "Portuguese (Brazilian)", embedded = true), S("side-pt", "pt-BR"))
        for (serverLanguages in listOf(emptyList(), listOf("en", "pt-BR"))) {
            assertEquals(
                listOf("emb-2" to "Portuguese (Brazil)"),
                menu(brazil, serverLanguages = serverLanguages).let { it.top + it.more }.map { it.id to it.label },
            )
        }
    }

    @Test
    fun titlesThatAreCodesWithARegionOrAScriptTwoRegionsTwoScriptsAreTwo() {
        val pairs = listOf(
            listOf("por", "PT-BR", "PT-PT") to listOf("Portuguese (Brazil)", "Portuguese (Portugal)", "emb-2", "emb-3"),
            listOf("chi", "zh-Hant", "zh-Hans") to listOf("Chinese (Simplified)", "Chinese (Traditional)", "emb-3", "emb-2"),
            listOf("spa", "es-419", "es-ES") to listOf("Spanish (Latin America)", "Spanish (Spain)", "emb-2", "emb-3"),
            listOf("por", "Português (BR)", "Português (PT)") to listOf("Portuguese (Brazil)", "Portuguese (Portugal)", "emb-2", "emb-3"),
        )
        for ((input, expected) in pairs) {
            val (lang, first, second) = input
            val tracks = listOf(S("emb-2", lang, first, embedded = true), S("emb-3", lang, second, embedded = true))
            // Without the server's languages, a run each, alphabetically...
            val all = entries(menu(tracks, serverLanguages = emptyList()))
            assertEquals(expected.take(2), labels(all), "$first, $second")
            assertEquals(expected.drop(2), all.map { it.id }, "$first, $second")
            // ...and with them, both still there to pick.
            assertEquals(listOf("emb-2", "emb-3"), menu(tracks).let { it.top + it.more }.map { it.id }.sorted())
        }
        // The same region twice, as a code and by its name, is one.
        val twice = menu(listOf(S("emb-2", "por", "PT-BR", embedded = true), S("side-pt", "pt-BR")))
        assertEquals(listOf("emb-2" to "Portuguese (Brazil)"), twice.let { it.top + it.more }.map { it.id to it.label })
    }

    @Test
    fun eachRegionOrScriptARunOfItsOwnTheBareNameFirstTheOthersAlphabeticalAiOnesInTheirRun() {
        // In the file's order: Traditional before the bare one, the AI one last.
        val tracks = listOf(
            S("emb-2", "zh-Hant", embedded = true),
            S("emb-3", "pt-PT", embedded = true),
            S("emb-4", "chi", embedded = true),
            S("emb-5", "kor", embedded = true),
            S("emb-6", "zh-Hans", embedded = true),
            S("emb-7", "pt-BR", "SDH", embedded = true),
            S("emb-8", "ara", embedded = true),
            S("side-zh", "zh-Hans", "AI"),
            S("side-pt", "pt-BR"),
        )
        val chinese = listOf("Chinese", "Chinese (Simplified)", "Chinese (Simplified) · AI", "Chinese (Traditional)")
        // On top, as a server's language.
        assertEquals(chinese, labels(menu(tracks, preferred = "off", serverLanguages = listOf("zh")).top))
        // Under "more languages": the languages by their names, each
        // language's runs as on top.
        assertEquals(
            listOf("Arabic") + chinese + listOf(
                "Korean",
                "Portuguese (Brazil)",
                "Portuguese (Brazil) · for deaf and hard of hearing",
                "Portuguese (Portugal)",
            ),
            labels(menu(tracks, preferred = "off", serverLanguages = listOf("en")).more),
        )
        // Without the server's languages, the device's language on top, the
        // rest under "more languages", each language's runs the same way.
        val own = menu(tracks, preferred = "off", device = "pt-BR", serverLanguages = emptyList())
        assertEquals(
            listOf("Portuguese (Brazil)", "Portuguese (Brazil) · for deaf and hard of hearing", "Portuguese (Portugal)"),
            labels(own.top),
        )
        assertEquals(listOf("Arabic") + chinese + listOf("Korean"), labels(own.more))
        // A track in no language is sorted by its own name, as a reader sorts
        // it - not by its letters' codes, which put "Spanish" before "signs".
        val signs = listOf(S("emb-2", "spa", embedded = true), S("emb-3", "und", "signs", embedded = true), S("emb-4", "fre", embedded = true))
        assertEquals(listOf("French", "signs", "Spanish"), labels(menu(signs, preferred = "off", serverLanguages = listOf("en")).more))
    }

    @Test
    fun aLanguageTakesItsRegionsAndScriptsOnlyWhereTheyAgree() {
        assertTrue(sameLanguage("en", "en-GB"))
        assertTrue(sameLanguage("eng", "en"))
        assertTrue(sameLanguage("zh-Hans", "chi"))
        assertFalse(sameLanguage("zh-Hans", "zh-Hant"))
        assertFalse(sameLanguage("pt-BR", "pt-PT"))
        assertTrue(sameLanguage("pt", "pt-BR"))
        assertFalse(sameLanguage("de", "und"))
    }

    @Test
    fun whatComesOnByItselfThePreferredLanguageWhereTheAudioIsInAnotherItsPlainTrackTheFilesOwn() {
        assertEquals("emb-8", auto(menu(original, preferred = "ger"), audioLang = "jpn", subtitlePref = "ger"))
        // English audio, English subtitles in Settings: not the full English
        // ones - nothing, as the file has no English forced track.
        assertEquals(null, auto(menu(original), audioLang = "eng", subtitlePref = "eng"))
        // Only for deaf and hard of hearing in that language: that one.
        assertEquals("sdh", auto(menu(listOf(S("sdh", "eng", "SDH"), S("de", "ger"))), audioLang = "fre", subtitlePref = "eng"))
        // The audio preference plays no part (docs/chino): preferred German
        // subtitles come on over an English film's English audio.
        assertEquals("emb-8", auto(menu(original, preferred = "ger"), audioLang = "eng", subtitlePref = "ger"))
    }

    @Test
    fun whatComesOnByItselfElseTheForeignLanguagePartsOnlyInTheAudiosLanguageNeverAnotherLanguage() {
        val m = menu(original)
        // German audio: the German forced track, whatever Settings say.
        assertEquals("emb-3", auto(m, audioLang = "ger", subtitlePref = "off"))
        assertEquals("emb-3", auto(m, audioLang = "ger", subtitlePref = "ger"))
        // The file flags a track default: it counts for nothing.
        assertEquals(null, auto(menu(listOf(S("de", "ger", "German"), S("en", "eng"))), audioLang = "eng", subtitlePref = "eng"))
        // No track in the chosen language: off, not some other language.
        assertEquals(null, auto(menu(original), audioLang = "jpn", subtitlePref = "ita"))
        // The audio's language not known: nothing.
        assertEquals(null, auto(m, audioLang = "und", subtitlePref = "eng"))
    }

    @Test
    fun theViewersLanguageComesOnOnlyOverAudioInAnotherOneAForcedOneInTheAudiosAlways() {
        data class T(val lang: String, val forced: Boolean = false)
        val tracks = listOf(T("eng"), T("ger"), T("ger", forced = true))
        fun auto(audio: String, pref: String = "eng") = autoSubtitleTrack(tracks, { it.lang }, { it.forced }, { true }, audioLang = audio, subtitlePref = pref)
        // English audio, English the viewer's: nothing on - they pick it themselves.
        assertEquals(null, auto("eng"))
        // German audio: English comes on.
        assertEquals(T("eng"), auto("ger"))
        // Subtitles off in Settings, German audio: the German forced track.
        assertEquals(T("ger", forced = true), auto("ger", pref = "off"))
        // French audio: the viewer's English, never a language they did not
        // choose - and with subtitles off, nothing (no French forced).
        assertEquals(T("eng"), auto("fre"))
        assertEquals(null, auto("fre", pref = "off"))
    }

    @Test
    fun theFilesOwnListedOnceBesideTheSidecarsAsTheIosMenuHasThem() {
        val choices = buildSubtitleChoices(
            "film",
            sidecars = listOf(
                cloud.nalet.chino.mobile.data.api.SidecarSubtitle(id = "s-en", lang = "en", url = "", format = "webvtt"),
                cloud.nalet.chino.mobile.data.api.SidecarSubtitle(id = "s-de", lang = "de", url = "", format = "webvtt"),
            ),
            embedded = listOf(TrackInfo(index = 3, codec = "subrip", language = "eng"), TrackInfo(index = 4, codec = "subrip", language = "und", title = "German")),
            apiBase = "https://media.example.com/api/",
            streamToken = "TOK",
        )
        // English twice: the file's own; the file's untagged "German" reads
        // as the German sidecar and shows for both, in German.
        assertEquals(listOf("emb-3" to "English", "emb-4" to "German"), choices.map { it.id to it.label }.sortedBy { it.second })
        assertEquals("de", choices.single { it.id == "emb-4" }.lang)
    }

    @Test
    fun anUnpackagedTitlesOwnTextTracksAreLoadedBesideTheStream() {
        val embedded = listOf(
            TrackInfo(index = 3, codec = "subrip", language = "eng", title = "English SDH"),
            TrackInfo(index = 4, codec = "hdmv_pgs_subtitle", language = "ger"),
            TrackInfo(index = 5, codec = "ass", language = "jpn", forced = true),
            TrackInfo(index = null, codec = "webvtt", language = "fre"),
        )
        val sidecars = embeddedSidecars("film", embedded, "https://media.example.com/api/", "TOK")
        // Text only, each by its stream index; a picture one is not extracted,
        // a row without an index is a packaged title's.
        assertEquals(listOf("emb-3", "emb-5"), sidecars.map { it.id })
        assertEquals("https://media.example.com/api/v1/items/film/play/subtitles/3.vtt?stream=TOK", sidecars[0].url)
        assertEquals("English SDH", sidecars[0].label)
        assertTrue(sidecars[1].forced)
    }
}

/** The server's subtitle languages: asked once a session, none from a server
 *  before the route, none - for now - without an answer. */
class ServerSubtitleLanguagesTest {
    private var reply: MockRequestHandleScope.() -> HttpResponseData = { respondJson("""{"languages":["en","de","zh-Hans"]}""") }
    private val asked = mutableListOf<String>()
    private val server = FakeServer { request ->
        asked += request.url.encodedPath
        reply()
    }

    @Test
    fun theServersLanguagesAreAskedOnce() = runTest {
        val languages = ServerSubtitleLanguages { server.api.subtitleLanguages().languages }
        assertEquals(listOf("en", "de", "zh-Hans"), languages.get())
        assertEquals(listOf("en", "de", "zh-Hans"), languages.get())
        assertEquals(listOf("en", "de", "zh-Hans"), languages.current)
        assertEquals(1, asked.size)
        assertTrue(asked.single().endsWith("/v1/subtitles/languages"))
    }

    @Test
    fun aServerFromBeforeTheRouteHasNoneAndIsNotAskedAgain() = runTest {
        reply = { respondText("404 page not found", HttpStatusCode.NotFound) }
        val languages = ServerSubtitleLanguages { server.api.subtitleLanguages().languages }
        assertEquals(emptyList(), languages.get())
        assertEquals(emptyList(), languages.get())
        assertEquals(1, asked.size)
    }

    @Test
    fun settingsTellsNoAnswerFromNone() = runTest {
        val languages = ServerSubtitleLanguages { server.api.subtitleLanguages().languages }
        // Offline, or a 5xx: not known - never "names none", which is every language.
        reply = { throw IOException("no connection") }
        assertEquals(null, languages.answered())
        reply = { respondText("upstream down", HttpStatusCode.ServiceUnavailable) }
        assertEquals(null, languages.answered())
        // A 200 with none, and a server before the route, name none.
        reply = { respondJson("""{"languages":[]}""") }
        assertEquals(emptyList(), languages.answered())
        val before = ServerSubtitleLanguages { server.api.subtitleLanguages().languages }
        reply = { respondText("404 page not found", HttpStatusCode.NotFound) }
        assertEquals(emptyList(), before.answered())
        // Settings' choices: "Off" and the current one while not known.
        assertEquals(listOf("off", "fr"), cloud.nalet.chino.mobile.ui.settings.subtitleSettingChoices(null, "fr").map { it.code })
    }

    @Test
    fun anAnswerIsKeptOnThePhoneAndAStartWithoutTheServerHasIt() = runTest {
        // A session that asked: the answer is kept.
        val keptOnThePhone = mutableListOf<List<String>>()
        val first = ServerSubtitleLanguages(fetch = { server.api.subtitleLanguages().languages }, kept = null, keep = { keptOnThePhone += it })
        assertEquals(listOf("en", "de", "zh-Hans"), first.get())
        assertEquals(listOf(listOf("en", "de", "zh-Hans")), keptOnThePhone)
        // The next start, the server out of reach: the menu has its
        // languages without asking, and nothing more is kept.
        reply = { throw IOException("no connection") }
        val next = ServerSubtitleLanguages(fetch = { server.api.subtitleLanguages().languages }, kept = keptOnThePhone.last(), keep = { keptOnThePhone += it })
        assertEquals(listOf("en", "de", "zh-Hans"), next.current)
        assertEquals(null, next.answered())
        assertEquals(listOf("en", "de", "zh-Hans"), next.current)
        assertEquals(1, keptOnThePhone.size)
        // Once it answers again, what it says now.
        reply = { respondJson("""{"languages":["fr"]}""") }
        assertEquals(listOf("fr"), next.get())
        assertEquals(listOf("fr"), next.current)
        assertEquals(listOf("fr"), keptOnThePhone.last())
        // Never asked and nothing kept: none, and the menu has the person's own.
        assertEquals(emptyList(), ServerSubtitleLanguages(fetch = { error("not asked") }).current)
    }

    @Test
    fun aDownloadsMenuKeepsTheKeptLanguagesWhereTheAskFailsOrTakesTooLong() = runTest {
        val kept = listOf("en", "de", "zh-Hans")
        // The server's answers as the client hands them on, at once (the
        // wait is the test's virtual time).
        fun languages(fetch: suspend () -> List<String>) = ServerSubtitleLanguages(fetch = fetch, kept = kept)
        val path = "/api/v1/subtitles/languages"
        // The server gone while the phone is online: a refused connection,
        // or a 503 at once - what the phone kept, never none.
        assertEquals(kept, languages { throw IOException("connection refused") }.answeredOrKnown(1_500))
        assertEquals(kept, languages { throw cloud.nalet.chino.mobile.data.api.ApiStatusException(503, path) }.answeredOrKnown(1_500))
        // An answer too slow to wait for: the same.
        assertEquals(kept, languages { kotlinx.coroutines.delay(10_000); listOf("fr") }.answeredOrKnown(1_500))
        // An answer in time: what it says now, a server that names none too.
        assertEquals(listOf("fr"), languages { kotlinx.coroutines.delay(1_000); listOf("fr") }.answeredOrKnown(1_500))
        assertEquals(emptyList(), languages { emptyList() }.answeredOrKnown(1_500))
        assertEquals(emptyList(), languages { throw cloud.nalet.chino.mobile.data.api.ApiStatusException(404, path) }.answeredOrKnown(1_500))
        // A streamed title's ask (get) has none for now there: the kept
        // ones are a download's.
        assertEquals(emptyList(), languages { throw IOException("connection refused") }.get())
    }

    @Test
    fun theServerConfigStoreKeepsOneServersLanguagesAndForgetsThemWithTheServer() = runTest {
        val store = object : cloud.nalet.chino.mobile.data.BaseServerConfigStore() {
            val state = kotlinx.coroutines.flow.MutableStateFlow(cloud.nalet.chino.mobile.data.ServerConfigBlob())
            override fun readBlobBlocking() = state.value
            override suspend fun writeBlob(blob: cloud.nalet.chino.mobile.data.ServerConfigBlob) { state.value = blob }
            override val blobFlow get() = state
        }
        val here = "https://media.example.com/api"
        store.save(cloud.nalet.chino.mobile.data.ServerConfig(baseUrl = here, issuer = "https://id.example.com", clientId = "app"))
        assertEquals(null, store.subtitleLanguagesBlocking(here))
        store.saveSubtitleLanguages(here, listOf("en", "de"))
        assertEquals(listOf("en", "de"), store.subtitleLanguagesBlocking(here))
        // A server that names none is kept as none, not as "never answered".
        store.saveSubtitleLanguages(here, emptyList())
        assertEquals(emptyList(), store.subtitleLanguagesBlocking(here))
        // Another server's are not this one's.
        assertEquals(null, store.subtitleLanguagesBlocking("https://other.example.com/api"))
        // Kept through a refreshed sign-in setting; gone with Change server.
        store.saveSubtitleLanguages(here, listOf("fr"))
        store.save(cloud.nalet.chino.mobile.data.ServerConfig(baseUrl = here, issuer = "https://id.example.com", clientId = "app-2"))
        assertEquals(listOf("fr"), store.subtitleLanguagesBlocking(here))
        store.clear()
        assertEquals(null, store.subtitleLanguagesBlocking(here))
    }

    @Test
    fun noAnswerIsNoneForNowAndTheNextMenuAsksAgain() = runTest {
        reply = { throw IOException("no connection") }
        val languages = ServerSubtitleLanguages { server.api.subtitleLanguages().languages }
        assertEquals(emptyList(), languages.get())
        reply = { respondJson("""{"languages":["de"]}""") }
        assertEquals(listOf("de"), languages.get())
        assertEquals(2, asked.size)
    }
}
