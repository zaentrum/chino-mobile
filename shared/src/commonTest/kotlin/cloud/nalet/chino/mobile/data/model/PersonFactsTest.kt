package cloud.nalet.chino.mobile.data.model

import cloud.nalet.chino.mobile.data.api.PersonDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** chino-web's lib/people.test.ts (dates, age, Accept-Language) and its
 *  PersonPage facts. The device's date formatting is a stand-in here: the
 *  platform formatters (PlatformLocale) need a device to check. */
class PersonFactsTest {
    /** Writes a date the way a test can read: "3/3/1957", "3/1957". */
    private val numeric: (CatalogDate) -> String = { d -> listOfNotNull(d.day, d.month, d.year).joinToString("/") }

    @Test
    fun parseCatalogDateTakesFullDatesYearMonthsAndYears() {
        assertEquals(CatalogDate(1957, 3, 3), parseCatalogDate("1957-03-03"))
        assertEquals(CatalogDate(2024, 2, 29), parseCatalogDate("2024-02-29"))
        assertEquals(CatalogDate(1957, 3), parseCatalogDate("1957-03"))
        assertEquals(CatalogDate(1957), parseCatalogDate(" 1957 "))
        assertNull(parseCatalogDate("2023-02-29"))
        assertNull(parseCatalogDate("1957-13-01"))
        assertNull(parseCatalogDate("1957-04-31"))
        assertNull(parseCatalogDate("03.03.1957"))
        assertNull(parseCatalogDate(null))
    }

    @Test
    fun aDateIsWrittenAsFarAsItGoesAndAnythingElseAsItWas() {
        assertEquals("3/3/1957", formatCatalogDate("1957-03-03", numeric))
        assertEquals("3/1957", formatCatalogDate("1957-03", numeric))
        assertEquals("1957", formatCatalogDate("1957", numeric))
        assertEquals("circa 1957", formatCatalogDate("circa 1957", numeric))
        assertEquals("1957-02-30", formatCatalogDate("1957-02-30", numeric))
        assertEquals("", formatCatalogDate(null, numeric))
        // A platform formatter that fails leaves the date as the catalog has it.
        assertEquals("1957-03-03", formatCatalogDate("1957-03-03") { error("no formatter") })
    }

    @Test
    fun theAgeTurnsOnTheBirthdayNotBefore() {
        assertEquals(68, ageInYears("1957-03-03", "2026-03-02"))
        assertEquals(69, ageInYears("1957-03-03", "2026-03-03"))
        assertEquals(69, ageInYears("1957-03-03", "2026-10-03"))
        assertEquals(43, ageInYears("1980-11-20", "2024-02-10"))
        assertEquals(0, ageInYears("2000-02-29", "2001-02-28"))
        assertEquals(1, ageInYears("2000-02-29", "2001-03-01"))
    }

    @Test
    fun noAgeWithoutTwoFullDatesNorBeforeTheBirth() {
        assertNull(ageInYears("1957", "2026-10-03"))
        assertNull(ageInYears("1957-03-03", null))
        assertNull(ageInYears(null, "2026-10-03"))
        assertNull(ageInYears("1957-03-03", "1956-01-01"))
    }

    @Test
    fun acceptLanguageListsTheDevicesLanguagesMostWantedFirst() {
        assertEquals("en-US", acceptLanguage(listOf("en-US")))
        assertEquals("de-CH, de;q=0.9, en;q=0.8", acceptLanguage(listOf("de-CH", "de", "en")))
        assertEquals("fr, it;q=0.9", acceptLanguage(listOf("fr", "FR", " it ")))
        assertEquals("de", acceptLanguage(listOf("de", "x y", "en;q=1", "")))
        assertEquals("", acceptLanguage(emptyList()))
        val many = acceptLanguage(List(12) { i -> "l" + ('a' + i) })
        assertEquals(10, many.split(", ").size)
        assertTrue(many.endsWith("lj;q=0.1"))
    }

    private val ada = PersonDetail(
        id = "p1",
        name = "Ada Example",
        birthDate = "1950-03-01",
        deathDate = "2020-11-30",
        birthplace = "Bern, Switzerland",
        knownForDepartment = "Acting",
    )

    @Test
    fun someoneWhoDiedHasTheAgeTheyReachedBesideTheDeathDate() {
        val facts = personFacts(ada, today = "2026-10-04", formatDate = numeric)

        assertEquals(
            listOf(
                PersonFact("Known for", listOf(FactLine("Acting"))),
                PersonFact("Born", listOf(FactLine("1/3/1950"), FactLine("Bern, Switzerland"))),
                PersonFact("Died", listOf(FactLine("30/11/2020", note = "(aged 70)"))),
            ),
            facts,
        )
    }

    @Test
    fun someoneLivingHasTheirAgeBesideTheBirthDate() {
        val facts = personFacts(ada.copy(deathDate = null), today = "2026-10-04", formatDate = numeric)

        assertEquals(
            PersonFact("Born", listOf(FactLine("1/3/1950", note = "(age 76)"), FactLine("Bern, Switzerland"))),
            facts.single { it.label == "Born" },
        )
        assertTrue(facts.none { it.label == "Died" })
    }

    @Test
    fun aBirthplaceAloneStillSaysBornAndNothingKnownSaysNothing() {
        val placeOnly = personFacts(
            PersonDetail(id = "p5", name = "Di Place", birthplace = "Basel"),
            today = "2026-10-04",
            formatDate = numeric,
        )
        assertEquals(listOf(PersonFact("Born", listOf(FactLine("Basel")))), placeOnly)

        assertTrue(personFacts(PersonDetail(id = "p2", name = "Bo Writer"), "2026-10-04", numeric).isEmpty())
    }

    @Test
    fun aYearOfBirthHasNoAge() {
        val facts = personFacts(PersonDetail(id = "p6", name = "Ed Year", birthDate = "1957"), "2026-10-04", numeric)

        assertEquals(listOf(PersonFact("Born", listOf(FactLine("1957")))), facts)
    }
}
