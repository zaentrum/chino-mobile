package cloud.nalet.chino.mobile

import cloud.nalet.chino.mobile.data.model.CatalogDate
import cloud.nalet.chino.mobile.data.model.acceptLanguage
import cloud.nalet.chino.mobile.data.model.parseCatalogDate
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The iOS actuals on the simulator, in whatever locale it runs: dates come
 *  out written (not empty, the catalog's year kept), today is a catalog date,
 *  and the language list makes an Accept-Language. The Android actuals need a
 *  device (JVM unit tests only see android.jar stubs). */
class PlatformLocaleIosTest {
    @Test
    fun aCatalogDateIsWrittenInTheDevicesLocale() {
        val full = formatDeviceDate(CatalogDate(1957, 3, 3))
        val month = formatDeviceDate(CatalogDate(1957, 3))

        assertTrue(full.contains("1957"), full)
        assertTrue(month.contains("1957"), month)
        assertTrue(full.length > month.length, "$full / $month")
    }

    @Test
    fun theFirstOfJanuaryStaysTheFirst() {
        // Noon in the Gregorian calendar: no time zone moves the day back.
        assertTrue(formatDeviceDate(CatalogDate(2000, 1, 1)).contains("2000"))
        assertTrue(formatDeviceDate(CatalogDate(2000, 1, 1)).contains("1"))
    }

    @Test
    fun todayIsACatalogDate() {
        assertNotNull(parseCatalogDate(todayCatalogDate()), todayCatalogDate())
    }

    @Test
    fun theDevicesLanguagesMakeAnAcceptLanguage() {
        val tags = preferredLanguageTags()

        assertTrue(tags.isNotEmpty())
        assertTrue(acceptLanguage(tags).isNotEmpty(), tags.toString())
    }
}
