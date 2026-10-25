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
