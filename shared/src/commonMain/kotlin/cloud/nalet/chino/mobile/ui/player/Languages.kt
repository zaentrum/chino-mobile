package cloud.nalet.chino.mobile.ui.player

import kotlin.concurrent.Volatile

/**
 * Languages as the catalog, the media files and the settings spell them —
 * ISO 639-2/B ("ger", "fre", "dut"), 639-2/T ("deu", "fra", "nld"), 639-1
 * ("de"), BCP 47 tags ("pt-BR") — and what to call them. A port of chino-web's
 * src/lib/languages.ts (and its tests' table, SubtitleNamesTest); the names
 * are English, from a table rather than the platform, so every target labels
 * a track the same way. The platform's names in other languages
 * (PlatformNames.kt) only tell what a track's own title says.
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

// ISO 639-1 -> 639-2/T: the table above the other way round.
private val ONE_TO_T = T_TO_1.entries.associate { (t, one) -> one to t }

// Withdrawn 639-1 codes still found in the wild.
private val OLD_1 = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

// "Undetermined", "no linguistic content", "multiple", "uncoded": no language
// one could read or listen to.
private val NO_LANGUAGE = setOf("und", "zxx", "mul", "mis")

/** What a track tagged "zxx" (no linguistic content) is called: the audio of a
 *  film without dialogue. */
const val NO_DIALOGUE = "No dialogue"

/** What a track is called whose language is not known ("und", none). */
const val UNKNOWN_LANGUAGE = "Unknown"

// The codes for no one language that still say what a track is in, and what
// such a track is called: no dialogue ("zxx"), several languages ("mul"), a
// language ISO 639 has no code for ("mis").
private val NOT_ONE_LANGUAGE = mapOf(
    "zxx" to NO_DIALOGUE,
    "mul" to "Multiple languages",
    "mis" to "Other language",
)

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

// The English names of the scripts a tag carries, as CLDR puts them in a
// language's name: "zh-Hans" is "Chinese (Simplified)", "sr-Latn" "Serbian
// (Latin)".
private val SCRIPT_NAMES = mapOf(
    "Hans" to "Simplified", "Hant" to "Traditional", "Latn" to "Latin", "Cyrl" to "Cyrillic",
    "Arab" to "Arabic", "Deva" to "Devanagari", "Beng" to "Bangla", "Guru" to "Gurmukhi",
    "Grek" to "Greek", "Hebr" to "Hebrew", "Jpan" to "Japanese", "Kore" to "Korean", "Thai" to "Thai",
    "Geor" to "Georgian", "Armn" to "Armenian", "Ethi" to "Ethiopic", "Mong" to "Mongolian",
    "Tibt" to "Tibetan", "Taml" to "Tamil", "Telu" to "Telugu", "Knda" to "Kannada", "Mlym" to "Malayalam",
    "Gujr" to "Gujarati", "Orya" to "Odia", "Sinh" to "Sinhala", "Khmr" to "Khmer", "Laoo" to "Lao",
    "Mymr" to "Myanmar",
)

// The English names of the regions a tag carries, as CLDR puts them in a
// language's name: "pt-BR" is "Portuguese (Brazil)", "es-419" "Spanish
// (Latin America)". A region not here is named by its code.
private val REGION_NAMES = mapOf(
    "US" to "United States", "GB" to "United Kingdom", "AU" to "Australia", "CA" to "Canada",
    "NZ" to "New Zealand", "IE" to "Ireland", "IN" to "India", "ZA" to "South Africa", "SG" to "Singapore",
    "PH" to "Philippines", "MY" to "Malaysia", "NG" to "Nigeria", "KE" to "Kenya",
    "BR" to "Brazil", "PT" to "Portugal", "AO" to "Angola", "MZ" to "Mozambique",
    "ES" to "Spain", "MX" to "Mexico", "AR" to "Argentina", "CO" to "Colombia", "CL" to "Chile",
    "PE" to "Peru", "VE" to "Venezuela", "UY" to "Uruguay", "CU" to "Cuba", "419" to "Latin America",
    "BO" to "Bolivia", "EC" to "Ecuador", "PY" to "Paraguay", "DO" to "Dominican Republic", "GT" to "Guatemala",
    "HN" to "Honduras", "NI" to "Nicaragua", "PA" to "Panama", "SV" to "El Salvador", "CR" to "Costa Rica",
    "PR" to "Puerto Rico",
    "FR" to "France", "BE" to "Belgium", "CH" to "Switzerland", "LU" to "Luxembourg", "MC" to "Monaco",
    "DE" to "Germany", "AT" to "Austria", "LI" to "Liechtenstein", "NL" to "Netherlands", "SR" to "Suriname",
    "IT" to "Italy", "SM" to "San Marino",
    "CN" to "China", "TW" to "Taiwan", "HK" to "Hong Kong SAR China", "MO" to "Macao SAR China",
    "JP" to "Japan", "KR" to "South Korea", "KP" to "North Korea", "MN" to "Mongolia",
    "RU" to "Russia", "UA" to "Ukraine", "BY" to "Belarus", "KZ" to "Kazakhstan", "UZ" to "Uzbekistan",
    "SE" to "Sweden", "FI" to "Finland", "NO" to "Norway", "DK" to "Denmark", "IS" to "Iceland",
    "EE" to "Estonia", "LV" to "Latvia", "LT" to "Lithuania",
    "PL" to "Poland", "CZ" to "Czechia", "SK" to "Slovakia", "HU" to "Hungary", "RO" to "Romania",
    "MD" to "Moldova", "BG" to "Bulgaria", "RS" to "Serbia", "ME" to "Montenegro",
    "BA" to "Bosnia & Herzegovina", "HR" to "Croatia", "SI" to "Slovenia", "MK" to "North Macedonia",
    "AL" to "Albania", "XK" to "Kosovo", "GR" to "Greece", "CY" to "Cyprus", "TR" to "Türkiye",
    "GE" to "Georgia", "AM" to "Armenia", "AZ" to "Azerbaijan", "IL" to "Israel",
    "SA" to "Saudi Arabia", "EG" to "Egypt", "AE" to "United Arab Emirates", "MA" to "Morocco",
    "DZ" to "Algeria", "TN" to "Tunisia", "LB" to "Lebanon", "JO" to "Jordan", "IQ" to "Iraq",
    "SY" to "Syria", "KW" to "Kuwait", "QA" to "Qatar", "BH" to "Bahrain", "OM" to "Oman", "YE" to "Yemen",
    "LY" to "Libya", "SD" to "Sudan", "IR" to "Iran", "AF" to "Afghanistan", "PK" to "Pakistan",
    "BD" to "Bangladesh", "LK" to "Sri Lanka", "NP" to "Nepal",
    "TH" to "Thailand", "VN" to "Vietnam", "ID" to "Indonesia", "KH" to "Cambodia", "LA" to "Laos",
    "MM" to "Myanmar", "001" to "world", "150" to "Europe",
)

// What a language calls itself where the platform does not say it so
// (chino-web's table): a title that is only one of these says nothing the
// language's name does not.
private val ENDONYMS: Map<String, Set<String>> = mapOf(
    "af" to setOf("afrikaans"), "ar" to setOf("العربية", "عربي"), "bg" to setOf("български"), "bn" to setOf("বাংলা"),
    "ca" to setOf("català"), "cs" to setOf("čeština", "česky"), "da" to setOf("dansk"), "de" to setOf("deutsch"),
    "el" to setOf("ελληνικά"), "en" to setOf("english"), "es" to setOf("español", "castellano"), "et" to setOf("eesti"),
    "eu" to setOf("euskara"), "fa" to setOf("فارسی"), "fi" to setOf("suomi"), "fr" to setOf("français"),
    "ga" to setOf("gaeilge"), "gl" to setOf("galego"), "he" to setOf("עברית"), "hi" to setOf("हिन्दी"),
    "hr" to setOf("hrvatski"), "hu" to setOf("magyar"), "id" to setOf("bahasa indonesia"), "is" to setOf("íslenska"),
    "it" to setOf("italiano"), "ja" to setOf("日本語"), "ko" to setOf("한국어"), "lt" to setOf("lietuvių"),
    "lv" to setOf("latviešu"), "mk" to setOf("македонски"), "ms" to setOf("bahasa melayu"), "nb" to setOf("norsk bokmål", "norsk"),
    "nl" to setOf("nederlands"), "nn" to setOf("norsk nynorsk", "nynorsk"), "no" to setOf("norsk"), "pl" to setOf("polski"),
    "pt" to setOf("português"), "ro" to setOf("română"), "ru" to setOf("русский"), "sk" to setOf("slovenčina"),
    "sl" to setOf("slovenščina"), "sq" to setOf("shqip"), "sr" to setOf("српски", "srpski"), "sv" to setOf("svenska"),
    "th" to setOf("ไทย"), "tr" to setOf("türkçe"), "uk" to setOf("українська"), "vi" to setOf("tiếng việt"),
    "zh" to setOf("中文", "汉语", "漢語"),
)

private val PRIMARY = Regex("^[a-z]{2,3}$")

private fun subtags(code: String): List<String> = code.trim().replace('_', '-').split('-')

/** The code's language subtag, lower-cased ("pt" of "PT_br"); "" for none. */
private fun primarySubtag(code: String?): String = code?.let { subtags(it).first().lowercase() }.orEmpty()

/** Whether the code is "zxx": no linguistic content, no dialogue. */
fun isNoDialogue(code: String?): Boolean = primarySubtag(code) == "zxx"

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

/** The language's English name, with the script and the region the code
 *  names in brackets after it ("ger" -> "German", "zh-Hans" -> "Chinese
 *  (Simplified)", "pt-BR" -> "Portuguese (Brazil)", "zh-Hant-TW" -> "Chinese
 *  (Traditional, Taiwan)"); the platform's English name for a language
 *  the table lacks, the code as it came when there is no name for it; "No dialogue" for "zxx", "Multiple languages" for "mul",
 *  "Other language" for "mis", "Unknown" for none. */
fun languageName(code: String?): String {
    NOT_ONE_LANGUAGE[primarySubtag(code)]?.let { return it }
    val tag = languageTag(code)
    if (tag.isEmpty()) return UNKNOWN_LANGUAGE
    // The table's English name; for a language it lacks, the platform's -
    // what chino-web's Intl names it ("br" is Breton).
    val base = normalizeLang(code)
    val name = NAMES[base] ?: platformLanguageName(base, "en") ?: return code.orEmpty().trim()
    val variant = variantNames(tag)
    return if (variant.isEmpty()) name else "$name (${variant.joinToString(", ")})"
}

/** The names of the script and the region of a canonical tag ([languageTag]),
 *  in that order; a variant, an extension or a private use names nothing. */
private fun variantNames(tag: String): List<String> {
    var script: String? = null
    var region: String? = null
    for (sub in tag.split('-').drop(1)) {
        if (sub.length == 1) break
        when {
            script == null && region == null && sub.length == 4 && sub.all { it.isLetter() } -> script = SCRIPT_NAMES[sub] ?: sub
            region == null && ((sub.length == 2 && sub.all { it.isLetter() }) || (sub.length == 3 && sub.all { it.isDigit() })) ->
                region = REGION_NAMES[sub] ?: sub
        }
    }
    return listOfNotNull(script, region)
}

/** A track's language by name ("German", "No dialogue" for zxx), else its own
 *  label for a track in no language; null for neither. What a title's
 *  subtitle languages are listed as. */
fun languageOrLabel(lang: String?, label: String?): String? =
    if (hasLanguage(lang)) languageName(lang) else label?.trim()?.takeIf { it.isNotEmpty() }

/** What a subtitle menu is told about one track. */
data class SubtitleLabelInput(
    /** The language code as the track is tagged. */
    val lang: String?,
    /** The track's own title, from the catalog or the file, when it has one. */
    val title: String? = null,
    /** Forced: only the lines in another language than the audio's. A title
     *  that says so counts too. */
    val forced: Boolean = false,
    /** For the deaf and hard of hearing, where the player knows it other than
     *  by the title (an HLS rendition's characteristics); a title that says
     *  so ([isSdhSubtitle]) counts too. */
    val sdh: Boolean = false,
)

/** What a forced subtitle is, after its language ("English · foreign-language
 *  parts only"): the lines a film shows in another language than its audio's,
 *  not the dialogue. */
const val FORCED_SUBTITLE_KIND = "foreign-language parts only"

/** What a subtitle for the deaf and hard of hearing is, after its language
 *  ("English · for deaf and hard of hearing"). */
const val SDH_SUBTITLE_KIND = "for deaf and hard of hearing"

// What a track's own title may say of a forced subtitle.
private val FORCED_WORDS = Regex(
    """(?<![A-Za-z])(foreign[ -]language parts( only)?|foreign parts( only)?|forced( narrative| subtitles?| subs)?)(?![A-Za-z])""",
    RegexOption.IGNORE_CASE,
)

// What a track's own title may say of a subtitle for the deaf and hard of
// hearing - the words isSdhSubtitle reads, and the phrases they are part of.
private val SDH_PHRASES = Regex(
    """(?<![A-Za-z])((for )?(the )?deaf( and | & | ?/ ?)hard[ -]of[ -]hearing|(for )?(the )?hard[ -]of[ -]hearing|(for )?(the )?hearing[ -]impaired|closed[ -]captions?|SDH|CC|HoH|deaf)(?![A-Za-z])""",
    RegexOption.IGNORE_CASE,
)

// A number a server gave a title to keep it unique in its group ("English
// (2)"); the menu numbers what still reads the same itself.
private val TRAILING_NUMBER = Regex("""\s*\(\d+\)$""")

private val SEPARATOR_RUN = Regex("""\s*([·\-–—:,|/])(\s*[·\-–—:,|/])+\s*""")
private val SEPARATOR_AFTER_OPEN = Regex("""([(\[])\s*[·\-–—:,|/]+\s*""")
private val SEPARATOR_BEFORE_CLOSE = Regex("""\s*[·\-–—:,|/]+\s*([)\]])""")

private val CODE_LIKE = Regex("^[a-z]{2,3}([-_][a-z0-9]+)*$", RegexOption.IGNORE_CASE)

/** Whether the code says what the track is in: a language, no dialogue,
 *  several languages or one with no code. */
private fun hasLanguage(code: String?): Boolean =
    normalizeLang(code).isNotEmpty() || primarySubtag(code) in NOT_ONE_LANGUAGE

/** A title that is only a language code: the track's own again ("eng" on
 *  English), or one that names no language ("und"). */
private fun sameLanguageCode(title: String, lang: String?): Boolean {
    if (!CODE_LIKE.matches(title)) return false
    val named = normalizeLang(title)
    return named.isEmpty() || named == normalizeLang(lang)
}

/** A track's own title without what it says of the track's kind - forced,
 *  for the deaf and hard of hearing - or a number that only kept it unique:
 *  "English SDH" is "English", "German (Forced)" "German", "SDH" nothing. */
private fun titleWithoutKind(title: String): String =
    title.replace(TRAILING_NUMBER, "")
        .replace(FORCED_WORDS, " ")
        .replace(SDH_PHRASES, " ")
        .replace(EMPTY_BRACKETS, " ")
        .replace(SEPARATOR_AFTER_OPEN, "$1")
        .replace(SEPARATOR_BEFORE_CLOSE, "$1")
        .replace(EMPTY_BRACKETS, " ")
        .replace(SEPARATOR_RUN, " $1 ")
        .replace(SPACES, " ")
        .replace(EDGE_SEPARATORS, "")

/** Whether a subtitle's title says it is forced ("German (Forced)",
 *  "foreign-language parts only"). */
fun saysForced(title: String?): Boolean = title != null && FORCED_WORDS.containsMatchIn(title)

/** The language, script and region of a canonical tag ([languageTag]) - what
 *  a name says; a variant, an extension or a private use names nothing
 *  ("de-1901" is "de", "en-US-x-custom" "en-US"). */
private fun namedTag(tag: String): String {
    val parts = tag.split('-')
    var script = ""
    var region = ""
    for (sub in parts.drop(1)) {
        if (sub.length == 1) break
        if (script.isEmpty() && region.isEmpty() && sub.length == 4 && sub.all { it.isLetter() }) {
            script = sub
        } else if (region.isEmpty() && ((sub.length == 2 && sub.all { it.isLetter() }) || (sub.length == 3 && sub.all { it.isDigit() }))) {
            region = sub
        }
    }
    return listOf(parts.first(), script, region).filter { it.isNotEmpty() }.joinToString("-")
}

/** The script of a canonical tag ("Hans" of "zh-Hans-CN"), or "". */
internal fun scriptOfTag(tag: String): String =
    tag.split('-').drop(1).firstOrNull { it.length == 4 && it.all(Char::isLetter) }.orEmpty()

/** The region of a canonical tag ("BR" of "pt-BR", "419"), or "". */
internal fun regionOfTag(tag: String): String =
    tag.split('-').drop(1).firstOrNull { (it.length == 2 && it.all(Char::isLetter)) || (it.length == 3 && it.all(Char::isDigit)) }.orEmpty()

// The words a Chinese track's title gives its script in. They say more than
// "Chinese": the track is named and listed by its script ("Chinese
// (Simplified)"), as a track tagged with it is.
private val CHINESE_SCRIPTS = mapOf(
    "简体中文" to "Hans", "簡體中文" to "Hans", "简体" to "Hans", "簡體" to "Hans", "简中" to "Hans", "簡中" to "Hans",
    "chs" to "Hans", "simplified" to "Hans",
    "繁體中文" to "Hant", "繁体中文" to "Hant", "繁體" to "Hant", "繁体" to "Hant", "繁中" to "Hant",
    "cht" to "Hant", "traditional" to "Hant",
)

// The spoken Chinese a title may name: it says something "Chinese" does
// not, in the word list's words ("Chinese · Cantonese").
private val CHINESE_VARIETIES = mapOf(
    "粵語" to "Cantonese", "粤语" to "Cantonese", "廣東話" to "Cantonese", "广东话" to "Cantonese",
    "國語" to "Mandarin", "国语" to "Mandarin", "普通話" to "Mandarin", "普通话" to "Mandarin",
)

// What a title may call a language's region other than by the region's
// name: "Español Latino" is Spanish (Latin America).
private val REGION_WORDS: Map<String, Map<String, String>> = mapOf(
    "es" to mapOf("latino" to "419", "latinoamericano" to "419", "latam" to "419"),
    "fr" to mapOf("canadien" to "CA", "québécois" to "CA", "québec" to "CA"),
    "pt" to mapOf("brasileiro" to "BR", "europeu" to "PT"),
)

// The regions a title may name: the region table's.
private val REGION_CODES = REGION_NAMES.keys.filter { it != "001" }

private val COMBINING_MARKS = Regex("[̀-ͯ]")

/** Lower-cased, without its accents: "Latinoamérica" is "latinoamerica". */
private fun folded(text: String): String = decomposed(text).replace(COMBINING_MARKS, "").lowercase().trim()

// Made once per locale and per language, when a title asks: immutable maps,
// replaced whole, so a reader on another thread sees one or the other.
@Volatile private var regionNamesCache: Map<String, Map<String, String>> = emptyMap()
@Volatile private var regionsCache: Map<String, Map<String, String>> = emptyMap()
@Volatile private var languageNamesCache: Map<String, Set<String>> = emptyMap()

/** The regions by their names in one locale, folded; English by the region
 *  table too. */
private fun regionNamesIn(locale: String): Map<String, String> {
    regionNamesCache[locale]?.let { return it }
    val regions = LinkedHashMap<String, String>()
    for (code in REGION_CODES) {
        val name = platformRegionName(code, locale) ?: continue
        regions.getOrPut(folded(name)) { code }
    }
    if (locale == "en") for (code in REGION_CODES) REGION_NAMES[code]?.let { regions.getOrPut(folded(it)) { code } }
    regionNamesCache = regionNamesCache + (locale to regions)
    return regions
}

/** The regions a title may name for the language [base] ([normalizeLang]),
 *  by their names in the language itself and in English, folded: "españa"
 *  -> "ES", "latinoamerica" -> "419", "brasil" -> "BR". */
private fun regionsOf(base: String): Map<String, String> {
    regionsCache[base]?.let { return it }
    val regions = LinkedHashMap<String, String>()
    for (locale in if (base == "zh") listOf("zh-Hans", "zh-Hant", "en") else listOf(base, "en")) {
        for ((name, code) in regionNamesIn(locale)) regions.getOrPut(name) { code }
    }
    for ((word, code) in REGION_WORDS[base].orEmpty()) regions[folded(word)] = code
    regionsCache = regionsCache + (base to regions)
    return regions
}

// The locales a language's name is looked up in: the languages a library is
// likely to carry, each naming every other.
private val NAME_LOCALES = (T_TO_1.values + listOf("fil", "zh-Hans", "zh-Hant")).distinct()

/** Every name of the code's language - in English, in itself, in the other
 *  languages a library carries, with and without its script and region -
 *  lower-cased: "Deutsch", "allemand" and "German" for "ger"; "中文" for
 *  "zh-Hans". */
private fun languageNames(code: String?): Set<String> {
    val tag = namedTag(languageTag(code))
    languageNamesCache[tag]?.let { return it }
    val names = HashSet<String>()
    val base = normalizeLang(code)
    if (base.isNotEmpty()) {
        val tags = listOf(tag, base).distinct()
        for (locale in (tags + NAME_LOCALES).distinct()) {
            for (t in tags) platformLanguageName(t, locale)?.let { names += it.lowercase() }
        }
        for (t in tags) names += languageName(t).lowercase()
        names += ENDONYMS[base].orEmpty()
    }
    languageNamesCache = languageNamesCache + (tag to names)
    return names
}

/** Whether a title only names the track's language again, in any language
 *  ("Deutsch", "中文", "English", "eng" on a German, Chinese, English
 *  track), or names no language as a code ("und"). */
private fun namesLanguage(title: String, lang: String?): Boolean {
    val t = title.trim()
    if (t.isEmpty()) return false
    return sameLanguageCode(t, lang) || t.lowercase() in languageNames(lang)
}

// A bracket in a title: round, square, or the full-width round ones of a
// Chinese or Japanese title.
private val BRACKETED = Regex("""\(([^()]*)\)|\[([^\[\]]*)]|（([^（）]*)）""")
private val MASKED_BRACKET = Regex("\u0001(\\d+)\u0002")

// Where a title's parts meet: "Chinese (Simplified) · 中文", "German - Deutsch".
private val PART_SEPARATOR = Regex("""\s*[·|/–—]\s*|\s+-\s+|\s*:\s+|\s*,\s+""")

/** What a piece of a title says of the track's language. */
private sealed interface Says {
    data object Language : Says
    data class Script(val script: String) : Says
    data class Region(val region: String) : Says
    data class Words(val words: String) : Says
}

/** What a piece of a title - a part, a bracket, words of one - says of the
 *  track's language: that it is the language ("Deutsch", "中文"), its
 *  script ("简体"), its region ("Latinoamérica"), a Chinese variety in the
 *  word list's words ("粵語": "Cantonese"); null for none of these. Words
 *  inside a longer piece name the language by a name, never by a code: "und"
 *  is a German word. */
private fun pieceSays(text: String, code: String?, whole: Boolean): Says? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val lower = t.lowercase()
    if (if (whole) namesLanguage(t, code) else lower in languageNames(code)) return Says.Language
    val base = normalizeLang(code)
    if (base == "zh") {
        CHINESE_SCRIPTS[lower]?.let { return Says.Script(it) }
        CHINESE_VARIETIES[t]?.let { return Says.Words(it) }
    }
    val region = if (base.isNotEmpty()) regionsOf(base)[folded(t)] else null
    return region?.let { Says.Region(it) }
}

/** A title read for the track tagged [code]: the title without what only
 *  names the language again, and the script and the region it names that the
 *  tag agrees with. */
private data class ReadTitle(val title: String, val script: String, val region: String)

/**
 * A title read for the track tagged [code] (chino-web's readParts): a part,
 * or a bracket, that is the language's name, script or region goes whole
 * ("Chinese (Simplified) · 中文", "Español (Latinoamérica)"); inside one with
 * other words only the language's names go ("Deutsch Kommentar" is
 * "Kommentar") - not the label's own name, which says it once ("English
 * Commentary") - and a region stays a word. Nothing read, the title as it
 * was.
 */
private fun readParts(title: String, code: String?): ReadTitle {
    val tag = namedTag(languageTag(code))
    val ownScript = scriptOfTag(tag)
    val ownRegion = regionOfTag(tag)
    var foundScript = ""
    var foundRegion = ""
    val display = languageName(code).lowercase()
    var changed = false
    fun agreesScript(v: String) = (ownScript.isEmpty() || ownScript == v) && (foundScript.isEmpty() || foundScript == v)
    fun agreesRegion(v: String) = (ownRegion.isEmpty() || ownRegion == v) && (foundRegion.isEmpty() || foundRegion == v)
    // What is left of a piece; null for nothing.
    fun readPiece(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        when (val whole = pieceSays(t, code, true)) {
            Says.Language -> {
                changed = true
                return null
            }
            is Says.Script -> if (agreesScript(whole.script)) {
                foundScript = whole.script
                changed = true
                return null
            }
            is Says.Region -> if (agreesRegion(whole.region)) {
                foundRegion = whole.region
                changed = true
                return null
            }
            is Says.Words -> {
                changed = true
                return whole.words
            }
            null -> Unit
        }
        val words = t.split(SPACES)
        if (words.size < 2) return t
        // The longest run of words from each place on that says something.
        val runs = ArrayList<Pair<String, Says?>>()
        var i = 0
        while (i < words.size) {
            var says: Says? = null
            var j = words.size
            while (j > i) {
                says = pieceSays(words.subList(i, j).joinToString(" "), code, false)
                if (says != null) break
                j--
            }
            if (says != null) {
                runs += words.subList(i, j).joinToString(" ") to says
                i = j
            } else {
                runs += words[i] to null
                i++
            }
        }
        val plain = runs.any { it.second == null }
        val kept = ArrayList<String>()
        for ((run, says) in runs) {
            when {
                says == null -> kept += run
                says == Says.Language -> if (plain && run.lowercase() == display) kept += run else changed = true
                says is Says.Script && agreesScript(says.script) -> {
                    foundScript = says.script
                    changed = true
                }
                says is Says.Region && !plain && agreesRegion(says.region) -> {
                    foundRegion = says.region
                    changed = true
                }
                says is Says.Words -> {
                    kept += says.words
                    changed = true
                }
                else -> kept += run
            }
        }
        return if (kept.isEmpty()) null else kept.joinToString(" ")
    }
    // The title's parts, split where they meet outside a bracket.
    val brackets = ArrayList<String>()
    val masked = BRACKETED.replace(title) { m ->
        brackets += m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
        "\u0001${brackets.size - 1}\u0002"
    }
    val kept = ArrayList<String>()
    for (part in masked.split(PART_SEPARATOR)) {
        val inner = ArrayList<String>()
        val main = MASKED_BRACKET.replace(part) { m ->
            inner += brackets[m.groupValues[1].toInt()]
            " "
        }
        val pieces = (listOf(readPiece(main.replace(SPACES, " "))) + inner.map(::readPiece)).filterNotNull()
        if (pieces.isNotEmpty()) kept += (listOf(pieces.first()) + pieces.drop(1).map { "($it)" }).joinToString(" ")
    }
    return ReadTitle(if (changed) kept.joinToString(" · ") else title, foundScript, foundRegion)
}

/** A track's language as its tag and its own title say it - the tag, with
 *  the script and the region the title names where the tag names none ("chi"
 *  titled "繁體中文" is "zh-Hant", "spa" titled "Español (Latinoamérica)"
 *  "es-419") - and the title without what only names that language again. A
 *  track in no language is read as it is. */
private fun readTitle(title: String, code: String?): Pair<String?, String> {
    val base = normalizeLang(code)
    if (base.isEmpty() || title.isEmpty()) return code to title
    val first = readParts(title, code)
    val tag = namedTag(languageTag(code))
    val script = scriptOfTag(tag).ifEmpty { first.script }
    val region = regionOfTag(tag).ifEmpty { first.region }
    if (script == scriptOfTag(tag) && region == regionOfTag(tag)) return code to first.title
    val refined = listOf(base, script, region).filter { it.isNotEmpty() }.joinToString("-")
    return refined to readParts(title, refined).title
}

/** The language a subtitle is in, as a BCP 47 tag: its code's, with the
 *  script and the region its own title names where the code names none
 *  ("chi" titled "繁體中文" is "zh-Hant"), as [subtitleLabel] names it; ""
 *  for none. What the menu lists the track by. */
fun subtitleLanguage(lang: String?, title: String?): String =
    languageTag(readTitle(titleWithoutKind(title?.trim().orEmpty()), lang).first)

/**
 * One subtitle's label: the language's name, its script or region in it
 * ("German", "Chinese (Simplified)", "No dialogue" for zxx) - a script or a
 * region the track's own title gives, where its tag names none, too
 * ("Chinese (Traditional)" for "繁體中文", "Spanish (Latin America)" for
 * "Español (Latinoamérica)"); the track's own title where it says more than
 * that ("English · Commentary", "German · AI", "Chinese · Cantonese") - not
 * the words of it that only name the language again, in any language
 * ("Deutsch", "中文") - or alone for a track tagged with no language, else
 * "Unknown"; then its kind in words - "foreign-language parts only" for a
 * forced one, "for deaf and hard of hearing" for one with sound described,
 * both in that order. A title that says the kind itself ("English SDH",
 * "[CC]", "German (forced)") says it once, in these words. chino-web's
 * subtitleLabel: the same track has the same name there.
 */
fun subtitleLabel(track: SubtitleLabelInput): String {
    val raw = track.title?.trim().orEmpty()
    val forced = track.forced || saysForced(raw)
    val sdh = track.sdh || isSdhSubtitle(raw)
    val (lang, title) = readTitle(titleWithoutKind(raw), track.lang)
    val name = languageName(lang)
    val lower = title.lowercase()
    val label = when {
        title.isEmpty() || lower == name.lowercase() || namesLanguage(title, lang) -> name
        !hasLanguage(lang) -> title
        lower.contains(name.lowercase()) -> title
        // "English" on a British English track: the name says it already.
        name.lowercase().contains(lower) -> name
        else -> "$name · $title"
    }
    val kinds = listOfNotNull(FORCED_SUBTITLE_KIND.takeIf { forced }, SDH_SUBTITLE_KIND.takeIf { sdh })
    return (listOf(label) + kinds).joinToString(" · ")
}

/** The subtitle menu's labels, one per track, in order ([subtitleLabel]); where
 *  two tracks would still read the same, a number for the second and later
 *  ones ("German (2)"). */
fun subtitleLabels(tracks: List<SubtitleLabelInput>): List<String> = numbered(tracks.map(::subtitleLabel))

/** The labels, the second and later of the ones that read the same numbered
 *  ("German (2)"). [key] says which read the same: by default the label. */
private fun numbered(labels: List<String>, key: (String, Int) -> String = { label, _ -> label }): List<String> {
    val seen = HashMap<String, Int>()
    return labels.mapIndexed { i, label ->
        val k = key(label, i)
        val n = (seen[k] ?: 0) + 1
        seen[k] = n
        if (n == 1) label else "$label ($n)"
    }
}

/** What an audio menu is told about one track. */
data class AudioLabelInput(
    /** The language code as the track is tagged. */
    val lang: String?,
    /** What the track is called: the file's title, or the master's NAME. */
    val name: String? = null,
    /** What the menu shows beside the label ("AAC · Stereo"). Two tracks that
     *  differ there are told apart there. */
    val detail: String? = null,
)

// A name that describes the source's audio format — a codec, a bitrate, a
// sample rate or depth ("AC3 5.1 @ 640 Kbps", "DTS-HD MA 5.1") — and so
// nothing of the track: the stream is AAC whatever the file had. chino-web's
// lib/languages.ts.
private val FORMAT_WORDS = Regex(
    """(^|[^A-Za-z0-9])(dts(-hd)?|truehd|atmos|dolby|e?-?ac-?3|ddp?\+?|aac|flac|l?pcm|opus|mp3|vorbis|lossless|master audio|\d+ ?k?hz|\d* ?[km]bps|kb/s|\d+[- ]?bit)(?![A-Za-z0-9])""",
    RegexOption.IGNORE_CASE,
)

// A channel layout, which goes from a name that names the track ("Commentary
// 5.1" is "Commentary"): the menu shows the channels beside it.
private val LAYOUT_WORDS = Regex(
    """(^|[^A-Za-z0-9.])(mono|stereo|surround|[1-9]\.[0-2]|\d{1,2} ?ch(annels?)?)(?![A-Za-z0-9.])""",
    RegexOption.IGNORE_CASE,
)

// A name that only numbers the track ("Track 2", "Audio Track 1", "2").
private val NUMBERED = Regex("""^(audio|sound|track|stream|[\s#])*\d*$""", RegexOption.IGNORE_CASE)

private val EMPTY_BRACKETS = Regex("""\(\s*\)|\[\s*]""")
private val SPACES = Regex("""\s+""")
private val EDGE_SEPARATORS = Regex("""^[\s\-–—:·,|/]+|[\s\-–—:·,|/]+$""")
private val LEADING_SEPARATORS = Regex("""^[\s\-–—:·,|]+""")
private val QUALIFIER_START = Regex("""^([\s\-–—:·,|(\[]|$)""")

/** What a track's name says about it, or "" when it says nothing: none, a
 *  format, a number, a language code. */
private fun trackName(name: String?, lang: String?): String {
    val raw = name?.trim().orEmpty()
    if (raw.isEmpty() || FORMAT_WORDS.containsMatchIn(raw)) return ""
    val t = raw.replace(LAYOUT_WORDS, "$1")
        .replace(EMPTY_BRACKETS, "")
        .replace(SPACES, " ")
        .replace(EDGE_SEPARATORS, "")
    if (t.isEmpty() || NUMBERED.matches(t) || sameLanguageCode(t, lang)) return ""
    return t
}

/** What a track's name adds to its label ("English Commentary" on an English
 *  track: "Commentary"); "" when nothing. */
private fun nameQualifier(name: String?, label: String, lang: String?): String {
    var t = trackName(name, lang)
    if (t.startsWith(label, ignoreCase = true) && QUALIFIER_START.containsMatchIn(t.substring(label.length))) {
        t = t.substring(label.length).replace(LEADING_SEPARATORS, "").trim()
        if ((t.startsWith("(") && t.endsWith(")")) || (t.startsWith("[") && t.endsWith("]"))) {
            t = t.substring(1, t.length - 1).trim()
        }
    }
    return if (t.isNotEmpty() && !NUMBERED.matches(t)) t else ""
}

/**
 * The audio menu's labels, one per track, in order: the language the track is
 * tagged with, by name ("German"; "No dialogue" for zxx). A track tagged with
 * none is called what its name says ("Commentary") — not a format ("AC3 5.1 @
 * 640 Kbps"), a number ("Track 1") or a code — else "Unknown". Two that would
 * read the same, with the same detail, are told apart by their names
 * ("English · Commentary"), else numbered ("English (2)"). chino-web's rule.
 */
fun audioLabels(tracks: List<AudioLabelInput>): List<String> {
    val bases = tracks.map { t ->
        if (hasLanguage(t.lang)) {
            val name = languageName(t.lang)
            // No name for the code: the track's own name before the code.
            if (name != t.lang.orEmpty().trim()) return@map name
        }
        trackName(t.name, t.lang).ifEmpty { if (hasLanguage(t.lang)) t.lang.orEmpty().trim() else UNKNOWN_LANGUAGE }
    }
    val key = { label: String, i: Int -> label + "\u0000" + tracks[i].detail.orEmpty() }
    val count = HashMap<String, Int>()
    bases.forEachIndexed { i, b -> count[key(b, i)] = (count[key(b, i)] ?: 0) + 1 }
    val labels = bases.mapIndexed { i, b ->
        if ((count[key(b, i)] ?: 0) < 2) return@mapIndexed b
        val q = nameQualifier(tracks[i].name, b, tracks[i].lang)
        if (q.isNotEmpty() && !q.equals(b, ignoreCase = true)) "$b · $q" else b
    }
    return numbered(labels, key)
}

/** The audio chip for the track playing: its language's ISO 639-2/T code
 *  ("ENG", "DEU", "JPN"; the code as tagged where the table has none), "MUL"
 *  for several languages and "MIS" for one with no code, "—" for no dialogue
 *  (zxx), "Audio" for a track tagged with no language. Never a name cut short:
 *  Japanese is not "JAP", and Malay, Malayalam and Maltese are three. As
 *  chino-web's chip reads. */
fun audioChipLabel(code: String?): String {
    val primary = primarySubtag(code)
    if (primary == "zxx") return "—"
    if (primary in NOT_ONE_LANGUAGE) return primary.uppercase()
    val lang = normalizeLang(code)
    if (lang.isEmpty()) return "Audio"
    return (if (lang.length == 2) ONE_TO_T[lang] ?: lang else lang).uppercase()
}

/**
 * The language subtitles come on in by themselves, or null for off — the
 * default. The preferred subtitle language comes on when the audio PLAYING
 * is in another language (docs/chino subtitles, "What switches on by
 * itself"); the audio language Settings prefer plays no part - an "en"
 * preference never keeps German subtitles off over an English film. Never
 * with subtitles set off, and never when the audio's language is not known.
 *
 * @param audioLang the language of the audio being played
 * @param subtitlePref Settings' subtitle language, or "off"
 */
fun defaultSubtitleLang(audioLang: String?, subtitlePref: String?): String? {
    if (subtitlePref.isNullOrBlank() || subtitlePref.trim().equals("off", ignoreCase = true)) return null
    val want = normalizeLang(subtitlePref)
    val audio = normalizeLang(audioLang)
    if (want.isEmpty() || audio.isEmpty() || audio == want) return null
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
): T? {
    val want = defaultSubtitleLang(audioLang, subtitlePref) ?: return null
    val inLang = tracks.filter { normalizeLang(lang(it)) == want }
    return inLang.firstOrNull { !forced(it) } ?: inLang.firstOrNull()
}

/** The forced track in the language of the audio playing, or null — the one
 *  a film carries for what is said or written in another language than its
 *  audio's (a sign, a letter, a call abroad) — a text one before a picture
 *  one (PGS). None when the audio's language is not known. chino-web's
 *  forcedSubtitleTrack. */
fun <T> forcedSubtitleTrack(
    tracks: List<T>,
    lang: (T) -> String?,
    forced: (T) -> Boolean,
    text: (T) -> Boolean,
    audioLang: String?,
): T? {
    val audio = normalizeLang(audioLang)
    if (audio.isEmpty()) return null
    val inLang = tracks.filter { forced(it) && normalizeLang(lang(it)) == audio }
    return inLang.firstOrNull(text) ?: inLang.firstOrNull()
}

/**
 * The subtitle that comes on by itself, or null: the one [defaultSubtitleTrack]
 * picks — a full track in the Settings language, for audio playing in another
 * language — else, where none would come on (the audio is in the subtitle
 * language, or subtitles are off in Settings), the forced
 * track in the audio's language ([forcedSubtitleTrack]), which is part of
 * the film rather than subtitles to read along. Asked again whenever the
 * audio's language changes, until the viewer picks ([SubtitleSession]).
 * chino-web's autoSubtitleTrack.
 */
fun <T> autoSubtitleTrack(
    tracks: List<T>,
    lang: (T) -> String?,
    forced: (T) -> Boolean,
    text: (T) -> Boolean,
    audioLang: String?,
    subtitlePref: String?,
): T? = defaultSubtitleTrack(tracks, lang, forced, audioLang, subtitlePref)
    ?: forcedSubtitleTrack(tracks, lang, forced, text, audioLang)
