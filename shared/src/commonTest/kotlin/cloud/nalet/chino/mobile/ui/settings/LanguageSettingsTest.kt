package cloud.nalet.chino.mobile.ui.settings

import cloud.nalet.chino.mobile.ui.player.autoSubtitleTrack
import cloud.nalet.chino.mobile.ui.player.languageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings offers the server's subtitle languages, never a list fixed in
 *  the app, named as the player's menus name them (docs/chino subtitles,
 *  "The setting"). */
class LanguageSettingsTest {
    private fun names(choices: List<LanguageChoice>) = choices.map { it.name }

    @Test
    fun theSubtitleSettingOffersOffThenTheServersLanguagesInItsOrder() {
        val choices = subtitleSettingChoices(listOf("en", "de", "zh-Hans"), current = "off")
        // "off", as the player's subtitles menu says it.
        assertEquals(listOf("off", "English", "German", "Chinese (Simplified)"), names(choices))
        assertEquals(listOf("off", "en", "de", "zh-Hans"), choices.map { it.code })
        // Each name as the player's menus give it.
        for (c in choices.drop(1)) assertEquals(languageName(c.code), c.name)
    }

    @Test
    fun eachLanguageOnceHoweverTheServerOrTheSettingSpellsIt() {
        // "eng" set before, "en" the server's: one, and it is the one on.
        val choices = subtitleSettingChoices(listOf("en", "eng", "EN", "de", "und", ""), current = "eng")
        assertEquals(listOf("off", "English", "German"), names(choices))
        assertTrue(isChosen("eng", "en"))
        assertTrue(isChosen("deu", "de"))
        assertFalse(isChosen("zh-Hans", "zh-Hant"))
        assertFalse(isChosen("en-US", "en"))
        assertFalse(isChosen("off", "en"))
    }

    @Test
    fun aSettingNoneOfTheServersLanguagesIsShownAfterThem() {
        // French set before, on a server that names English and German.
        assertEquals(listOf("off", "English", "German", "French"), names(subtitleSettingChoices(listOf("en", "de"), current = "fra")))
        // Until the server answers: off and the setting alone.
        assertEquals(listOf("off", "French"), names(subtitleSettingChoices(null, current = "fra")))
        assertEquals(listOf("off"), names(subtitleSettingChoices(null, current = "off")))
    }

    @Test
    fun aServerThatNamesNoneOffersEveryLanguageByName() {
        val choices = subtitleSettingChoices(emptyList(), current = "off")
        assertEquals("off", choices.first().name)
        val languages = names(choices.drop(1))
        // Every ISO 639-1 language, by its name, once each, alphabetically.
        assertTrue(languages.size > 150, "${languages.size}")
        assertEquals(languages.distinct(), languages)
        for (n in listOf("Breton", "Chinese", "English", "German", "Japanese", "Welsh")) assertTrue(n in languages, n)
        assertTrue(languages.none { Regex("^[a-z]{2,3}$").matches(it) }, "a code shown as a name")
        assertEquals(languages.sortedBy { it.lowercase() }.take(5), languages.take(5))
        assertTrue(languages.indexOf("Breton") < languages.indexOf("Catalan"))
    }

    @Test
    fun whereEveryLanguageIsOfferedThePersonsOwnComeFirstThenTheOthersAlphabetically() {
        // Nothing set and a phone in German (Switzerland): German first,
        // never Abkhazian.
        val choices = subtitleSettingChoices(emptyList(), current = "off", device = "de-CH")
        assertEquals(listOf("off", "de"), choices.take(2).map { it.code })
        assertEquals("German", choices[1].name)
        val rest = names(choices.drop(2))
        assertEquals(rest.sortedBy { it.lowercase() }.take(5), rest.take(5))
        assertFalse("German" in rest)
        assertEquals(names(choices).distinct(), names(choices))
        // The one set, then the phone's; each once.
        assertEquals(listOf("off", "fr", "de"), subtitleSettingChoices(emptyList(), current = "fra", device = "de-DE").take(3).map { it.code })
        assertEquals("German", subtitleSettingChoices(emptyList(), current = "ger", device = "de")[1].name)
        assertFalse(subtitleSettingChoices(emptyList(), current = "ger", device = "de")[2].name == "German")
        // A phone in Chinese with its script: that script.
        assertEquals("Chinese (Simplified)", subtitleSettingChoices(emptyList(), current = "off", device = "zh-Hans-CN")[1].name)
        // A setting with its region stays as it is set.
        assertEquals("Portuguese (Brazil)", subtitleSettingChoices(emptyList(), current = "pt-BR")[1].name)
        // No phone language known: every language alphabetically, after off.
        assertEquals(everyLanguageNames(), names(subtitleSettingChoices(emptyList(), current = "off").drop(1)))
        // A server that names its languages keeps its order: no own first.
        assertEquals(listOf("off", "English", "German"), names(subtitleSettingChoices(listOf("en", "de"), current = "off", device = "de")))
    }

    private fun everyLanguageNames() = cloud.nalet.chino.mobile.ui.player.everyLanguage().map(::languageName)

    @Test
    fun theAudioSettingOffersOriginalThenTheAppsLanguagesNamedAsThePlayersAudioMenuNamesThem() {
        val choices = audioSettingChoices(current = "eng")
        assertEquals("Original", choices.first().name)
        assertEquals(listOf("English", "German", "French", "Spanish", "Italian", "Japanese", "Portuguese", "Dutch"), names(choices.drop(1)))
        // One set elsewhere stays shown.
        assertEquals("Chinese", audioSettingChoices(current = "zho").last().name)
        assertEquals(choices, audioSettingChoices(current = "orig"))
    }

    private data class Track(val lang: String, val forced: Boolean = false)

    @Test
    fun aSettingWithItsScriptTakesThatScriptsTrackFirstElseAnyOfTheLanguage() {
        val tracks = listOf(Track("zh-Hant"), Track("zh-Hans"), Track("en"))
        fun auto(pref: String, list: List<Track> = tracks) =
            autoSubtitleTrack(list, { it.lang }, { it.forced }, { true }, audioLang = "ja", subtitlePref = pref)?.lang
        assertEquals("zh-Hans", auto("zh-Hans"))
        assertEquals("zh-Hant", auto("zh-Hant"))
        // The bare language: the first of it, as before.
        assertEquals("zh-Hant", auto("zh"))
        // No track in that script: any of the language.
        assertEquals("zh-Hant", auto("zh-Hans", listOf(Track("zh-Hant"), Track("en"))))
    }
}
