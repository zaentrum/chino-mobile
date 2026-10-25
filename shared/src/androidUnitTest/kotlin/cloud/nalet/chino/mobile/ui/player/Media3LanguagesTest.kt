package cloud.nalet.chino.mobile.ui.player

import androidx.media3.common.Format
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The menus name a track by the language Media3 gives it, and Media3 rewrites
 * some (Util.normalizeLanguageCode): a sidecar the server tags "nb" reaches
 * the menu as "no-nob". Named as the server tagged it all the same.
 */
class Media3LanguagesTest {
    /** The language of a track the player was given [code] for. */
    private fun asMedia3Gives(code: String): String? = Format.Builder().setLanguage(code).build().language

    @Test
    fun aBokmalSidecarTitledNbIsNorwegianBokmalNotNorwegianNb() {
        val lang = asMedia3Gives("nb")
        assertEquals("no-nob", lang)
        assertEquals("Norwegian Bokmål", subtitleLabel(SubtitleLabelInput(lang, "NB")))
        assertEquals("nb", subtitleLanguage(lang, "NB"))
        // "NO" only repeats the language too, and "BR" the region.
        assertEquals("Norwegian Bokmål", subtitleLabel(SubtitleLabelInput(lang, "NO")))
        assertEquals("Portuguese (Brazil)", subtitleLabel(SubtitleLabelInput(asMedia3Gives("pt-BR"), "BR")))
    }

    @Test
    fun aScriptOnlyTheTitleTellsComesOnForASettingWithThatScript() {
        // The file's own tracks as Media3 has them: one code for both
        // Chinese tracks, the script in the title alone.
        data class Track(val id: String, val language: String?, val title: String?) {
            val tag = subtitleLanguage(language, title)
        }
        val tracks = listOf(
            Track("emb-2", asMedia3Gives("chi"), "简体中文"),
            Track("emb-3", asMedia3Gives("chi"), "繁體中文"),
            Track("emb-4", asMedia3Gives("eng"), null),
        )
        assertEquals(listOf("zh-Hans", "zh-Hant", "en"), tracks.map { it.tag })
        for (serverLanguages in listOf(emptyList(), listOf("en", "de", "zh-Hans", "zh-Hant"))) {
            val menu = subtitleMenu(
                tracks = tracks,
                tag = { it.tag },
                forced = { false },
                sdh = { false },
                label = { subtitleLabel(SubtitleLabelInput(it.language, it.title)) },
                preferred = "zh-Hant",
                serverLanguages = serverLanguages,
            )
            val on = autoSubtitleTrack(
                tracks = menu.top + menu.more,
                lang = { it.language },
                forced = { false },
                text = { true },
                audioLang = asMedia3Gives("eng"),
                subtitlePref = "zh-Hant",
                tag = { it.tag.ifEmpty { it.language.orEmpty() } },
            )
            assertEquals("emb-3", on?.id, "$serverLanguages")
        }
    }

    @Test
    fun everyLanguageIsNamedAsTheServerTaggedIt() {
        val cases = listOf(
            "nb" to "Norwegian Bokmål",
            "nn" to "Norwegian Nynorsk",
            "no" to "Norwegian",
            "id" to "Indonesian",
            "ms" to "Malay",
            "hr" to "Croatian",
            "bs" to "Bosnian",
            "sr" to "Serbian",
            "de" to "German",
            "ger" to "German",
            "zh-Hant" to "Chinese (Traditional)",
            "pt-BR" to "Portuguese (Brazil)",
        )
        for ((code, name) in cases) {
            val lang = asMedia3Gives(code)
            assertEquals(name, languageName(lang), "$code, as $lang")
            assertEquals(normalizeLang(code), normalizeLang(lang), "$code, as $lang")
        }
    }
}
