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
 * top, the rest behind "more languages", a language's tracks together in one
 * order, a track that reads like another once. chino-web's
 * src/lib/subtitles.test.ts (subtitles-spec, 893f594) case for case - nothing
 * numbered, the file's own before a sidecar, an untagged one in its twin's
 * language, a packaged title as any other - ordered by docs/chino's runs
 * where the spec is newer than the web (5da8ef6): within a language each
 * region or script a run of its own, the bare name first, the others
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

    private fun menu(tracks: List<S>, preferred: String? = "eng", serverLanguages: List<String> = server, on: String? = null) = subtitleMenu(
        tracks = tracks,
        tag = { subtitleLanguage(it.lang, it.title) },
        forced = { it.isForced },
        sdh = { isSdhSubtitle(it.title) },
        label = { it.label },
        preferred = preferred,
        serverLanguages = serverLanguages,
        on = { it.id == on },
        own = { it.embedded },
        ai = { isAiSubtitle(it.title) },
        picture = { it.picture },
        retag = { kept, twin -> kept.copy(lang = twin.lang) },
    )

    private fun labels(list: List<S>) = list.map { it.label }

    /** What comes on by itself among the menu's tracks, in its order. */
    private fun auto(m: SubtitleMenu<S>, audioLang: String?, subtitlePref: String?) =
        autoSubtitleTrack(m.top + m.more, { it.lang }, { it.isForced }, { !it.picture }, audioLang = audioLang, subtitlePref = subtitlePref)?.id

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
    fun aServerWithNoSubtitleLanguagesListsEveryTrackTheViewersFirstThenTheFilesOrder() {
        val m = menu(original, serverLanguages = emptyList())
        assertEquals(emptyList(), m.more)
        // A language's tracks together, each script a run of its own,
        // alphabetically: "Chinese (Simplified) · AI" before "Chinese
        // (Traditional)", both where Chinese first comes in the file.
        assertEquals(
            listOf(
                "English",
                "English · for deaf and hard of hearing",
                "German",
                "German · foreign-language parts only",
                "German · AI",
                "Japanese",
                "Chinese (Simplified) · AI",
                "Chinese (Traditional)",
                "French",
                "Signs",
            ),
            labels(m.top),
        )
        // Tags that name no language count as none.
        assertEquals(emptyList(), menu(original, serverLanguages = listOf("und", "")).more)
    }

    @Test
    fun noServerLanguagesThePreferredLanguageFirstThenTheFilesOrderALanguagesTracksTogether() {
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
        assertEquals(
            listOf(
                "English",
                "English · AI",
                // The bare name, then each script alphabetically, with its AI one.
                "Chinese",
                "Chinese (Simplified) · AI",
                "Chinese (Traditional)",
                "French",
                "French · for deaf and hard of hearing",
                // A track in no language: in its place in the file.
                "Signs",
                "German",
                "German · AI",
            ),
            labels(menu(tracks, preferred = "eng", serverLanguages = emptyList()).top),
        )
        // Subtitles off in Settings: the file's order alone.
        assertEquals(
            listOf("Chinese", "Chinese (Simplified) · AI", "Chinese (Traditional)"),
            labels(menu(tracks, preferred = "off", serverLanguages = emptyList()).top).take(3),
        )
        // A preferred language with a script: its own first, then the rest of
        // the language right after it, before the others.
        assertEquals(
            listOf("Chinese", "Chinese (Simplified) · AI", "Chinese (Traditional)", "French"),
            labels(menu(tracks, preferred = "zh-Hans", serverLanguages = emptyList()).top).take(4),
        )
        // And with a region: Brazil's, then Portugal's, then the others.
        val portuguese = listOf(
            S("emb-2", "eng", embedded = true),
            S("emb-3", "pt-PT", embedded = true),
            S("emb-4", "por", "Português (Brasil)", embedded = true),
            S("side-pt", "pt-BR", "AI"),
        )
        assertEquals(
            listOf("Portuguese (Brazil)", "Portuguese (Brazil) · AI", "Portuguese (Portugal)", "English"),
            labels(menu(portuguese, preferred = "pt-BR", serverLanguages = emptyList()).top),
        )
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
        // A server with no subtitle languages lists a package's tracks all on
        // top, as an original's: the viewer's language first, then their order.
        val none = menu(packaged, serverLanguages = emptyList())
        assertEquals(emptyList(), none.more)
        assertEquals(listOf("English", "German", "German · AI", "French", "Japanese", "Chinese (Simplified)"), labels(none.top))
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
        assertEquals(listOf("emb-2", "emb-3"), menu(tracks, serverLanguages = emptyList()).top.map { it.id })
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
        // A server with no languages: both, each by its script.
        assertEquals(listOf("English", "Chinese (Simplified)", "Chinese (Traditional)"), labels(menu(tracks, preferred = "en", serverLanguages = emptyList()).top))
        // Mandarin and Cantonese are two as well.
        assertEquals(
            listOf("Chinese · Mandarin", "Chinese · Cantonese"),
            labels(menu(listOf(S("a", "chi", "國語"), S("b", "chi", "粵語")), serverLanguages = emptyList()).top),
        )
    }

    @Test
    fun aLanguagesRegionInATitleListsTheTrackWithThatRegion() {
        val tracks = listOf(S("es", "spa", "Español (España)"), S("la", "spa", "Español (Latinoamérica)"), S("la2", "es-419"))
        val m = menu(tracks, serverLanguages = emptyList())
        // The same region twice is one; Spain and Latin America are two, each
        // a run of its own, alphabetically (the web keeps the file's order).
        assertEquals(listOf("Spanish (Latin America)", "Spanish (Spain)"), labels(m.top))
        assertEquals(listOf("es-419", "es-ES"), m.top.map { subtitleLanguage(it.lang, it.title) })
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
            listOf("por", "PT-BR", "PT-PT") to listOf("Portuguese (Brazil)", "Portuguese (Portugal)"),
            listOf("chi", "zh-Hant", "zh-Hans") to listOf("Chinese (Simplified)", "Chinese (Traditional)"),
            listOf("spa", "es-419", "es-ES") to listOf("Spanish (Latin America)", "Spanish (Spain)"),
            listOf("por", "Português (BR)", "Português (PT)") to listOf("Portuguese (Brazil)", "Portuguese (Portugal)"),
        )
        for ((input, expected) in pairs) {
            val (lang, first, second) = input
            val tracks = listOf(S("emb-2", lang, first, embedded = true), S("emb-3", lang, second, embedded = true))
            // Without the server's languages, a run each, alphabetically...
            val all = menu(tracks, serverLanguages = emptyList()).let { it.top + it.more }
            assertEquals(expected, labels(all), "$first, $second")
            // ...and with them, both still there to pick.
            assertEquals(listOf("emb-2", "emb-3"), menu(tracks).let { it.top + it.more }.map { it.id }.sorted())
        }
        // The same region twice, as a code and by its name, is one.
        val twice = menu(listOf(S("emb-2", "por", "PT-BR", embedded = true), S("side-pt", "pt-BR")))
        assertEquals(listOf("emb-2" to "Portuguese (Brazil)"), twice.let { it.top + it.more }.map { it.id to it.label })
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
    fun noAnswerIsNoneForNowAndTheNextMenuAsksAgain() = runTest {
        reply = { throw IOException("no connection") }
        val languages = ServerSubtitleLanguages { server.api.subtitleLanguages().languages }
        assertEquals(emptyList(), languages.get())
        reply = { respondJson("""{"languages":["de"]}""") }
        assertEquals(listOf("de"), languages.get())
        assertEquals(2, asked.size)
    }
}
