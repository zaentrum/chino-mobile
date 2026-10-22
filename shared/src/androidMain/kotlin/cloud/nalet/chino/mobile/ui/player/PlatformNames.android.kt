package cloud.nalet.chino.mobile.ui.player

import java.text.Normalizer
import java.util.Locale

internal actual fun platformLanguageName(tag: String, inLocale: String): String? = runCatching {
    val locale = Locale.forLanguageTag(tag)
    if (locale.language.isEmpty()) return null
    // A code the platform has no name for comes back as the code itself.
    locale.getDisplayName(Locale.forLanguageTag(inLocale))
        .takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) && !it.equals(locale.language, ignoreCase = true) }
}.getOrNull()

internal actual fun platformRegionName(code: String, inLocale: String): String? = runCatching {
    Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.forLanguageTag(inLocale))
        .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
}.getOrNull()

internal actual fun decomposed(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)

private val ENGLISH_ORDER: java.text.Collator = java.text.Collator.getInstance(Locale.ENGLISH)

internal actual fun compareNames(a: String, b: String): Int = synchronized(ENGLISH_ORDER) { ENGLISH_ORDER.compare(a, b) }
