package cloud.nalet.chino.mobile

import cloud.nalet.chino.mobile.data.model.CatalogDate

/** The device's preferred languages as BCP 47 tags, most wanted first
 *  ("de-CH", "en"). Feeds the person page's Accept-Language
 *  ([cloud.nalet.chino.mobile.data.model.acceptLanguage]). */
expect fun preferredLanguageTags(): List<String>

/** [date] — a month at least — written in the device's locale: a full date
 *  in the long style ("March 3, 1957", "3. März 1957"), a year-month as month
 *  and year ("March 1957"). Gregorian, and at noon, so no calendar or time
 *  zone moves the day the catalog names. */
expect fun formatDeviceDate(date: CatalogDate): String

/** Today in the device's calendar day, as a catalog date (YYYY-MM-DD). */
expect fun todayCatalogDate(): String

/** How far the device's time zone is ahead of UTC at [epochMillis], in
 *  milliseconds, summer time included: what puts an instant on the device's
 *  calendar day. */
expect fun utcOffsetMillis(epochMillis: Long): Long
