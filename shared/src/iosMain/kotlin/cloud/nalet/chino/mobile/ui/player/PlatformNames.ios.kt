package cloud.nalet.chino.mobile.ui.player

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.NSLocaleIdentifier
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.localizedStandardCompare

internal actual fun platformLanguageName(tag: String, inLocale: String): String? =
    NSLocale(localeIdentifier = inLocale).displayNameForKey(NSLocaleIdentifier, value = tag)
        ?.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }

internal actual fun platformRegionName(code: String, inLocale: String): String? =
    NSLocale(localeIdentifier = inLocale).displayNameForKey(NSLocaleCountryCode, value = code)
        ?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun decomposed(text: String): String = (text as NSString).decomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun compareNames(a: String, b: String): Int = (a as NSString).localizedStandardCompare(b).toInt()
