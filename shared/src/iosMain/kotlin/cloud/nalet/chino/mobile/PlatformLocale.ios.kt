package cloud.nalet.chino.mobile

import cloud.nalet.chino.mobile.data.model.CatalogDate
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterLongStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.preferredLanguages

/** Settings → General → Language & Region, most preferred first. */
actual fun preferredLanguageTags(): List<String> =
    NSLocale.preferredLanguages.mapNotNull { it as? String }

private fun gregorian(): NSCalendar =
    NSCalendar.calendarWithIdentifier(NSCalendarIdentifierGregorian) ?: NSCalendar.currentCalendar

actual fun formatDeviceDate(date: CatalogDate): String {
    val components = NSDateComponents().apply {
        year = date.year.toLong()
        month = (date.month ?: 1).toLong()
        day = (date.day ?: 1).toLong()
        hour = 12
    }
    val instant = gregorian().dateFromComponents(components) ?: return ""
    val formatter = NSDateFormatter().apply {
        locale = NSLocale.currentLocale
        if (date.day != null) {
            dateStyle = NSDateFormatterLongStyle
            timeStyle = NSDateFormatterNoStyle
        } else {
            setLocalizedDateFormatFromTemplate("MMMMyyyy")
        }
    }
    return formatter.stringFromDate(instant)
}

actual fun todayCatalogDate(): String {
    val today = gregorian().components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay, fromDate = NSDate())
    val month = today.month.toString().padStart(2, '0')
    val day = today.day.toString().padStart(2, '0')
    return "${today.year}-$month-$day"
}
