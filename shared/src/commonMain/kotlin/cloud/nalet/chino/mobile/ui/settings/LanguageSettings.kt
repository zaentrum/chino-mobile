package cloud.nalet.chino.mobile.ui.settings

import cloud.nalet.chino.mobile.ui.player.everyLanguage
import cloud.nalet.chino.mobile.ui.player.languageName
import cloud.nalet.chino.mobile.ui.player.languageTag
import cloud.nalet.chino.mobile.ui.player.normalizeLang
import cloud.nalet.chino.mobile.ui.player.scriptFor

/*
 * The languages Settings offers, and their names: the names the player's
 * menus give them (languageName), so a language reads the same in Settings
 * and in the player - "German" in both, "Chinese (Simplified)" in both.
 */

/** One choice of a language setting: the [code] it stores, the [name] it
 *  shows. */
data class LanguageChoice(val code: String, val name: String)

/** The subtitle setting's "Off". */
const val SUBTITLES_OFF = "off"

/** The audio setting's "Original": each title in the language it was made in. */
const val AUDIO_ORIGINAL = "orig"

/** Whether the stored [value] is the choice [code]: one language however it
 *  is spelled ("eng", "en"), with the same script and region ("zh-Hans" is
 *  not "zh-Hant", "en-US" not "en"); "off" and "orig" as they are. */
fun isChosen(value: String, code: String): Boolean {
    if (value.trim().equals(code.trim(), ignoreCase = true)) return true
    val tag = languageTag(value)
    return tag.isNotEmpty() && tag == languageTag(code)
}

/**
 * The subtitle setting's choices (docs/chino subtitles, "The setting"):
 * "off", as the player's subtitles menu says it, then the server's subtitle
 * languages ([serverLanguages], GET /subtitles/languages, in its order) -
 * never a list fixed in the app; a server that names none offers every
 * language by name ([everyLanguage]), Chinese in either script too, the
 * person's own first - the one set, the phone's ([device]) - then the
 * others in alphabetical order, never a
 * scroll from Abkhazian (the TV's, 1c27a33). Each language once, named as
 * the player's menus name it. The [current] setting, where it is none of
 * the server's - set before, or on another server - is offered after them,
 * so the setting shows what it is. Until the server has answered (null) -
 * and while it doesn't: offline, a 5xx, which say nothing of its languages
 * - "off" and the current setting alone.
 */
fun subtitleSettingChoices(serverLanguages: List<String>?, current: String, device: String? = null): List<LanguageChoice> {
    val off = listOf(LanguageChoice(SUBTITLES_OFF, SUBTITLES_OFF))
    if (serverLanguages == null) return withCurrent(off, current)
    val named = serverLanguages.map(::languageTag).filter { it.isNotEmpty() }.distinct()
    if (named.isNotEmpty()) return withCurrent(off + named.map { LanguageChoice(it, languageName(it)) }, current)
    // Every language: the one set and the phone's first, each once, then the
    // rest alphabetically without them.
    val every = everyLanguage(scripts = true).map { LanguageChoice(it, languageName(it)) }
    val own = listOf(current.takeUnless { it.trim().equals(SUBTITLES_OFF, ignoreCase = true) }.orEmpty(), phoneLanguage(device))
        .filter { languageTag(it).isNotEmpty() }
        .map { code -> every.firstOrNull { isChosen(code, it.code) } ?: LanguageChoice(code, languageName(code)) }
        .distinctBy { it.code }
    return off + own + every.filter { e -> own.none { it.code == e.code } }
}

/** The phone's language as Settings offers it: the language with its script
 *  ("zh-Hans"), not its region - "de-CH" is German. A phone in Chinese that
 *  names its region alone has the script that region writes: "zh-TW" and
 *  "zh-HK" are Traditional, "zh-CN" and "zh-SG" Simplified. */
private fun phoneLanguage(device: String?): String =
    listOf(normalizeLang(device), scriptFor(device)).filter { it.isNotEmpty() }.joinToString("-")

/** The languages the audio setting offers besides "Original": the server
 *  names no audio languages, so these are the app's, as before. */
private val AUDIO_LANGUAGES = listOf("eng", "deu", "fra", "spa", "ita", "jpn", "por", "nld")

/**
 * The audio setting's choices (docs/chino playback, "The audio setting"):
 * "Original", then the app's languages, named as the player's audio menu
 * names them; the [current] setting where it is none of them, after them.
 */
fun audioSettingChoices(current: String): List<LanguageChoice> =
    withCurrent(listOf(LanguageChoice(AUDIO_ORIGINAL, "Original")) + AUDIO_LANGUAGES.map { LanguageChoice(it, languageName(it)) }, current)

/** [choices] with the [current] value after them, named, where it is none
 *  of them and names a language. */
private fun withCurrent(choices: List<LanguageChoice>, current: String): List<LanguageChoice> {
    if (current.isBlank() || choices.any { isChosen(current, it.code) } || languageTag(current).isEmpty()) return choices
    return choices + LanguageChoice(current, languageName(current))
}
