package cloud.nalet.chino.mobile

import android.os.LocaleList
import cloud.nalet.chino.mobile.data.model.CatalogDate
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** The system language list (Settings → Languages), extensions such as
 *  "-u-mu-celsius" stripped. */
actual fun preferredLanguageTags(): List<String> {
    val locales = LocaleList.getDefault()
    return (0 until locales.size()).mapNotNull { i ->
        withoutExtensions(locales[i].toLanguageTag()).takeIf { it.isNotBlank() && it != "und" }
    }
}

/** A BCP 47 tag up to its first singleton subtag, where the extensions and the
 *  private use start: what Locale.stripExtensions() gives, which needs API 26
 *  (minSdk is 24). */
internal fun withoutExtensions(tag: String): String =
    tag.split('-').takeWhile { it.length > 1 }.joinToString("-")

actual fun formatDeviceDate(date: CatalogDate): String {
    val locale = Locale.getDefault()
    val zone = TimeZone.getDefault()
    // GregorianCalendar, not Calendar.getInstance(locale): a Thai or Japanese
    // locale would otherwise read 1957 as a year of its own era.
    val calendar = GregorianCalendar(zone, locale).apply {
        clear()
        set(date.year, (date.month ?: 1) - 1, date.day ?: 1, 12, 0, 0)
    }
    val format = if (date.day != null) {
        DateFormat.getDateInstance(DateFormat.LONG, locale)
    } else {
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), locale)
    }
    format.timeZone = zone
    return format.format(calendar.time)
}

actual fun todayCatalogDate(): String {
    val now = GregorianCalendar()
    val month = (now.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
    val day = now.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
    return "${now.get(Calendar.YEAR)}-$month-$day"
}

actual fun utcOffsetMillis(epochMillis: Long): Long = TimeZone.getDefault().getOffset(epochMillis).toLong()
