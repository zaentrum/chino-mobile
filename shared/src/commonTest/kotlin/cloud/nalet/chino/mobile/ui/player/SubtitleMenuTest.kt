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
 * src/lib/subtitles.test.ts (subtitles-spec, 331aad4) case for case, with the
 * UX answers that came after it (docs/chino 0f631b4): nothing numbered - two
 * that read the same show once, the one in a language before an untagged
 * one; a packaged title follows the general rule; a language's scripts and
 * regions stay together in the file's order.
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
        // The rest by language in the order they come, a track in no
        // language last; "zh-Hant" is not "zh-Hans".
        assertEquals(listOf("Japanese", "Chinese (Traditional)", "French", "Signs"), labels(m.more))
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
        // A language's tracks together, its scripts too: "Chinese (Simplified)
        // · AI" right after "Chinese (Traditional)" (UX answers, 7).
        assertEquals(
            listOf(
                "English",
                "English · for deaf and hard of hearing",
                "German",
                "German · foreign-language parts only",
                "German · AI",
                "Japanese",
                "Chinese (Traditional)",
                "Chinese (Simplified) · AI",
                "French",
                "Signs",
            ),
            labels(m.top),
        )
        // Tags that name no language count as none.
        assertEquals(emptyList(), menu(original, serverLanguages = listOf("und", "")).more)
    }

    @Test
    fun aPackagedTitleFollowsTheGeneralRule() {
        val packaged = listOf(S("de", "de"), S("en", "en"), S("zh", "zh-Hans"))
        val m = menu(packaged)
        assertEquals(listOf("English", "German", "Chinese (Simplified)"), labels(m.top))
        assertEquals(emptyList(), m.more)
        // A package keeps tracks outside the server's languages too: behind
        // "more languages", as any title's.
        val more = menu(packaged + S("fr", "fr"))
        assertEquals(listOf("English", "German", "Chinese (Simplified)"), labels(more.top))
        assertEquals(listOf("French"), labels(more.more))
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
    fun aTrackTaggedWithNoLanguageNeverHidesOneInALanguage() {
        // The file's untagged track titled "English", then a French one, then
        // an English sidecar: the English one is on top and comes on by
        // itself; the untagged one reads the same and shows no more - never
        // numbered (the web lists it as "English (2)").
        val tracks = listOf(S("emb-2", "und", "English", embedded = true), S("emb-3", "fre", embedded = true), S("s1", "en"))
        val m = menu(tracks)
        assertEquals(listOf("s1"), m.top.map { it.id })
        assertEquals(listOf("emb-3" to "French"), m.more.map { it.id to it.label })
        assertEquals("s1", auto(m, audioLang = "fre", subtitlePref = "eng"))
        // Two of the file's own, the untagged one first: the tagged one still.
        val both = menu(listOf(S("emb-2", "und", "English", embedded = true), S("emb-3", "eng", embedded = true)))
        assertEquals(listOf("emb-3"), both.top.map { it.id })
        assertEquals("emb-3", auto(both, audioLang = "fre", subtitlePref = "eng"))
        // The foreign-language parts only, too: English audio, the tagged forced track.
        val forced = menu(listOf(S("emb-4", "und", "English Forced", embedded = true), S("s2", "eng", forced = true)))
        assertEquals("s2", auto(forced, audioLang = "eng", subtitlePref = "off"))
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
        // The same region twice is one; Spain and Latin America are two.
        assertEquals(listOf("Spanish (Spain)", "Spanish (Latin America)"), labels(m.top))
        assertEquals(listOf("es-ES", "es-419"), m.top.map { subtitleLanguage(it.lang, it.title) })
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
        // English twice: the file's own; the untagged "German" reads as the
        // German sidecar and shows no more.
        assertEquals(listOf("emb-3" to "English", "s-de" to "German"), choices.map { it.id to it.label }.sortedBy { it.second })
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
    fun noAnswerIsNoneForNowAndTheNextMenuAsksAgain() = runTest {
        reply = { throw IOException("no connection") }
        val languages = ServerSubtitleLanguages { server.api.subtitleLanguages().languages }
        assertEquals(emptyList(), languages.get())
        reply = { respondJson("""{"languages":["de"]}""") }
        assertEquals(listOf("de"), languages.get())
        assertEquals(2, asked.size)
    }
}
