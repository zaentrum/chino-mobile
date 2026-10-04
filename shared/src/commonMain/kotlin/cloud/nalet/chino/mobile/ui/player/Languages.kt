package cloud.nalet.chino.mobile.ui.player

/**
 * Languages as the catalog, the media files and the settings spell them —
 * ISO 639-2/B ("ger", "fre", "dut"), 639-2/T ("deu", "fra", "nld"), 639-1
 * ("de"), BCP 47 tags ("pt-BR") — and what to call them. A port of chino-web's
 * src/lib/languages.ts; the names are English, from a table rather than the
 * platform, so every target labels a track the same way.
 */

// ISO 639-2/B (bibliographic) codes and the /T (terminology) code each stands
// for; files tagged by older tools carry the /B ones.
private val B_TO_T = mapOf(
    "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
    "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
    "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk",
    "tib" to "bod", "wel" to "cym",
)

// ISO 639-2/T -> 639-1, for the languages a library is likely to carry.
// Anything else is compared as its three letters.
private val T_TO_1 = mapOf(
    "afr" to "af", "amh" to "am", "ara" to "ar", "aze" to "az", "bel" to "be", "ben" to "bn",
    "bod" to "bo", "bos" to "bs", "bul" to "bg", "cat" to "ca", "ces" to "cs", "cym" to "cy",
    "dan" to "da", "deu" to "de", "ell" to "el", "eng" to "en", "est" to "et", "eus" to "eu",
    "fas" to "fa", "fin" to "fi", "fra" to "fr", "gle" to "ga", "glg" to "gl", "guj" to "gu",
    "heb" to "he", "hin" to "hi", "hrv" to "hr", "hun" to "hu", "hye" to "hy", "ind" to "id",
    "isl" to "is", "ita" to "it", "jpn" to "ja", "kan" to "kn", "kat" to "ka", "kaz" to "kk",
    "khm" to "km", "kor" to "ko", "lao" to "lo", "lat" to "la", "lav" to "lv", "lit" to "lt",
    "ltz" to "lb", "mal" to "ml", "mar" to "mr", "mkd" to "mk", "mlt" to "mt", "mon" to "mn",
    "mri" to "mi", "msa" to "ms", "mya" to "my", "nep" to "ne", "nld" to "nl", "nob" to "nb",
    "nno" to "nn", "nor" to "no", "pan" to "pa", "pol" to "pl", "por" to "pt", "pus" to "ps",
    "ron" to "ro", "rus" to "ru", "sin" to "si", "slk" to "sk", "slv" to "sl", "som" to "so",
    "spa" to "es", "sqi" to "sq", "srp" to "sr", "swa" to "sw", "swe" to "sv", "tam" to "ta",
    "tel" to "te", "tgl" to "tl", "tha" to "th", "tur" to "tr", "ukr" to "uk", "urd" to "ur",
    "uzb" to "uz", "vie" to "vi", "yid" to "yi", "zho" to "zh", "zul" to "zu",
)

// Withdrawn 639-1 codes still found in the wild.
private val OLD_1 = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

// "Undetermined", "no linguistic content", "multiple", "uncoded": no language
// one could read or listen to.
private val NO_LANGUAGE = setOf("und", "zxx", "mul", "mis")

// The English name of each normalised language (639-1, else 639-2/T).
private val NAMES = mapOf(
    "af" to "Afrikaans", "am" to "Amharic", "ar" to "Arabic", "az" to "Azerbaijani",
    "be" to "Belarusian", "bg" to "Bulgarian", "bn" to "Bangla", "bo" to "Tibetan",
    "bs" to "Bosnian", "ca" to "Catalan", "cs" to "Czech", "cy" to "Welsh", "da" to "Danish",
    "de" to "German", "el" to "Greek", "en" to "English", "es" to "Spanish", "et" to "Estonian",
    "eu" to "Basque", "fa" to "Persian", "fi" to "Finnish", "fr" to "French", "ga" to "Irish",
    "gl" to "Galician", "gu" to "Gujarati", "he" to "Hebrew", "hi" to "Hindi", "hr" to "Croatian",
    "hu" to "Hungarian", "hy" to "Armenian", "id" to "Indonesian", "is" to "Icelandic",
    "it" to "Italian", "ja" to "Japanese", "ka" to "Georgian", "kk" to "Kazakh", "km" to "Khmer",
    "kn" to "Kannada", "ko" to "Korean", "la" to "Latin", "lb" to "Luxembourgish", "lo" to "Lao",
    "lt" to "Lithuanian", "lv" to "Latvian", "mi" to "Māori", "mk" to "Macedonian",
    "ml" to "Malayalam", "mn" to "Mongolian", "mr" to "Marathi", "ms" to "Malay", "mt" to "Maltese",
    "my" to "Burmese", "nb" to "Norwegian Bokmål", "ne" to "Nepali", "nl" to "Dutch",
    "nn" to "Norwegian Nynorsk", "no" to "Norwegian", "pa" to "Punjabi", "pl" to "Polish",
    "ps" to "Pashto", "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian", "si" to "Sinhala",
    "sk" to "Slovak", "sl" to "Slovenian", "so" to "Somali", "sq" to "Albanian", "sr" to "Serbian",
    "sv" to "Swedish", "sw" to "Swahili", "ta" to "Tamil", "te" to "Telugu", "th" to "Thai",
    "tl" to "Tagalog", "tr" to "Turkish", "uk" to "Ukrainian", "ur" to "Urdu", "uz" to "Uzbek",
    "vi" to "Vietnamese", "yi" to "Yiddish", "zh" to "Chinese", "zu" to "Zulu",
    "fil" to "Filipino", "gsw" to "Swiss German", "yue" to "Cantonese",
)

// Regional and script variants with a name of their own (CLDR's English
// "dialect" names, as chino-web's Intl.DisplayNames gives them). Any other
// region or script is named by its language.
private val VARIANT_NAMES = mapOf(
    "de-AT" to "Austrian German", "de-CH" to "Swiss High German",
    "en-AU" to "Australian English", "en-CA" to "Canadian English",
    "en-GB" to "British English", "en-US" to "American English",
    "es-419" to "Latin American Spanish", "es-ES" to "European Spanish", "es-MX" to "Mexican Spanish",
    "fr-CA" to "Canadian French", "fr-CH" to "Swiss French",
    "nl-BE" to "Flemish",
    "pt-BR" to "Brazilian Portuguese", "pt-PT" to "European Portuguese",
    "zh-Hans" to "Simplified Chinese", "zh-Hant" to "Traditional Chinese",
)

private val PRIMARY = Regex("^[a-z]{2,3}$")

private fun subtags(code: String): List<String> = code.trim().replace('_', '-').split('-')

/**
 * The language a code names, as one comparable key: the 639-1 code where there
 * is one ("ger", "deu", "de", "de-CH" are all "de"), else the 639-2/T code.
 * "" when the code names no language ("und", empty, not a code).
 */
fun normalizeLang(code: String?): String {
    if (code == null) return ""
    val primary = subtags(code).first().lowercase()
    if (!PRIMARY.matches(primary) || primary in NO_LANGUAGE) return ""
    if (primary.length == 2) return OLD_1[primary] ?: primary
    val t = B_TO_T[primary] ?: primary
    return T_TO_1[t] ?: t
}

/** The code as a BCP 47 tag: the normalised language, with the script and
 *  region it carried, canonically cased ("pt_br" -> "pt-BR", "zh-hant" ->
 *  "zh-Hant"). "" when the code names no language. */
fun languageTag(code: String?): String {
    val lang = normalizeLang(code)
    if (lang.isEmpty() || code == null) return lang
    val rest = subtags(code).drop(1).filter { it.isNotEmpty() }.map { tag ->
        when {
            tag.length == 4 && tag.all { it.isLetter() } -> tag.lowercase().replaceFirstChar { it.uppercase() }
            (tag.length == 2 && tag.all { it.isLetter() }) || (tag.length == 3 && tag.all { it.isDigit() }) -> tag.uppercase()
            else -> tag.lowercase()
        }
    }
    return (listOf(lang) + rest).joinToString("-")
}

/** The language's English name ("ger" -> "German", "pt-BR" -> "Brazilian
 *  Portuguese"); the code as it came when there is no name for it; "Unknown
 *  language" for none. */
fun languageName(code: String?): String {
    val tag = languageTag(code)
    if (tag.isEmpty()) return "Unknown language"
    VARIANT_NAMES[tag]?.let { return it }
    // A tag with a script and a region ("zh-Hant-TW"): the script variant's name.
    VARIANT_NAMES[tag.split('-').take(2).joinToString("-")]?.let { return it }
    return NAMES[normalizeLang(code)] ?: code.orEmpty().trim()
}

/** What a subtitle menu is told about one track. */
data class SubtitleLabelInput(
    /** The language code as the track is tagged. */
    val lang: String?,
    /** The track's own title, from the catalog or the file, when it has one. */
    val title: String? = null,
    val forced: Boolean = false,
)

private val CODE_LIKE = Regex("^[a-z]{2,3}([-_][a-z0-9]+)*$", RegexOption.IGNORE_CASE)

/** A title that is only the track's language code again ("eng" on English). */
private fun sameLanguageCode(title: String, lang: String?): Boolean =
    CODE_LIKE.matches(title) && normalizeLang(title).isNotEmpty() && normalizeLang(title) == normalizeLang(lang)

/**
 * The subtitle menu's labels, one per track, in order: the language's name
 * ("German"), the track's title when it says more than that ("English · SDH"),
 * "(forced)" for a forced track; and where two tracks would still read the
 * same, a number for the second and later ones ("German (2)").
 */
fun subtitleLabels(tracks: List<SubtitleLabelInput>): List<String> {
    val labels = tracks.map { t ->
        val name = languageName(t.lang)
        val title = t.title?.trim().orEmpty()
        var label = name
        if (title.isNotEmpty() && !title.equals(name, ignoreCase = true) && !sameLanguageCode(title, t.lang)) {
            label = if (title.contains(name, ignoreCase = true)) title else "$name · $title"
        }
        if (t.forced && !label.contains("forced", ignoreCase = true)) label += " (forced)"
        label
    }
    val seen = HashMap<String, Int>()
    return labels.map { label ->
        val n = (seen[label] ?: 0) + 1
        seen[label] = n
        if (n == 1) label else "$label ($n)"
    }
}

/**
 * The language subtitles come on in by themselves, or null for off — the
 * default. They come on only when the audio is in a language the viewer has
 * not said they follow: not the subtitle language they chose, not the audio
 * language they prefer. Never with subtitles set off, and never when the
 * audio's language is not known. chino-web's rule.
 *
 * @param audioLang the language of the audio being played
 * @param subtitlePref Settings' subtitle language, or "off"
 * @param audioPref Settings' audio language, or "orig" (the title's own)
 */
fun defaultSubtitleLang(audioLang: String?, subtitlePref: String?, audioPref: String? = null): String? {
    if (subtitlePref.isNullOrBlank() || subtitlePref.trim().equals("off", ignoreCase = true)) return null
    val want = normalizeLang(subtitlePref)
    val audio = normalizeLang(audioLang)
    if (want.isEmpty() || audio.isEmpty() || audio == want) return null
    if (!audioPref.isNullOrBlank() && !audioPref.equals("orig", ignoreCase = true) && normalizeLang(audioPref) == audio) {
        return null
    }
    return want
}

/** The track to switch on by default, or null: one in the language
 *  [defaultSubtitleLang] names, a full one rather than a forced one. A file's
 *  own default flag counts for nothing. */
fun <T> defaultSubtitleTrack(
    tracks: List<T>,
    lang: (T) -> String?,
    forced: (T) -> Boolean,
    audioLang: String?,
    subtitlePref: String?,
    audioPref: String? = null,
): T? {
    val want = defaultSubtitleLang(audioLang, subtitlePref, audioPref) ?: return null
    val inLang = tracks.filter { normalizeLang(lang(it)) == want }
    return inLang.firstOrNull { !forced(it) } ?: inLang.firstOrNull()
}
