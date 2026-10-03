package cloud.nalet.chino.mobile.data.model

import cloud.nalet.chino.mobile.data.api.PersonDetail

/*
 * What the person page says about a person: their dates, their age, the
 * labelled facts under the name, and the languages their biography is asked
 * for in. A port of chino-web's lib/people.ts and PersonPage's personFacts.
 * Pure — the device's date formatting and today's date come in as arguments
 * (PlatformLocale supplies them) — so PersonFactsTest runs it anywhere.
 */

/** A catalog date: YYYY-MM-DD as katalog-api sends them, or YYYY-MM / YYYY. */
data class CatalogDate(val year: Int, val month: Int? = null, val day: Int? = null)

private val CATALOG_DATE = Regex("""^(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?$""")

/** [iso] as a [CatalogDate]; null for anything else, an impossible day included. */
fun parseCatalogDate(iso: String?): CatalogDate? {
    val m = CATALOG_DATE.matchEntire(iso.orEmpty().trim()) ?: return null
    val year = m.groupValues[1].toInt()
    val month = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
    val day = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
    if (month != null && month !in 1..12) return null
    if (day != null && (month == null || day !in 1..daysInMonth(year, month))) return null
    return CatalogDate(year, month, day)
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Whole years from [born] to [on], both full catalog dates; null when either
 *  is not one, or [on] comes first. */
fun ageInYears(born: String?, on: String?): Int? {
    val b = parseCatalogDate(born) ?: return null
    val o = parseCatalogDate(on) ?: return null
    val bm = b.month ?: return null
    val bd = b.day ?: return null
    val om = o.month ?: return null
    val od = o.day ?: return null
    var age = o.year - b.year
    if (om < bm || (om == bm && od < bd)) age -= 1
    return age.takeIf { it >= 0 }
}

/**
 * [iso] in words, as [formatDate] writes a date in the device's locale: "March
 * 3, 1957" (en-US), "3. März 1957" (de). A year alone is the year; a
 * year-month goes to [formatDate] with no day. Anything that is not a date
 * comes back as it was rather than as nothing; no date, "".
 */
fun formatCatalogDate(iso: String?, formatDate: (CatalogDate) -> String): String {
    val raw = iso.orEmpty().trim()
    val date = parseCatalogDate(raw) ?: return raw
    if (date.month == null) return date.year.toString()
    return runCatching { formatDate(date) }.getOrNull()?.takeIf { it.isNotBlank() } ?: raw
}

private val LANGUAGE_TAG = Regex("""^[A-Za-z]{1,8}(?:-[A-Za-z0-9]{1,8})*$""")

/**
 * An Accept-Language header for the device's languages, most wanted first:
 * "de-CH, de;q=0.9, en;q=0.8". katalog-api picks a person's biography from it
 * (chino-api passes the header on), falling back to English. Tags that are
 * not language tags are left out, each tag once, at most ten. Empty when
 * there are none.
 */
fun acceptLanguage(languages: List<String>): String {
    val tags = mutableListOf<String>()
    for (raw in languages) {
        val tag = raw.trim()
        if (!LANGUAGE_TAG.matches(tag)) continue
        if (tags.any { it.equals(tag, ignoreCase = true) }) continue
        tags += tag
        if (tags.size == 10) break
    }
    // q falls by a tenth per place: 0.9 for the second, 0.1 for the tenth.
    return tags.mapIndexed { i, tag -> if (i == 0) tag else "$tag;q=0.${10 - i}" }.joinToString(", ")
}

/** One line of a fact: [text], and a [note] the page sets in a muted tone
 *  beside it ("(age 76)"). */
data class FactLine(val text: String, val note: String? = null)

/** A labelled fact under the person's name ("Born", "Died", "Known for"). */
data class PersonFact(val label: String, val lines: List<FactLine>)

/**
 * The facts under the name, as chino-web's PersonPage lists them: what they
 * are known for, when and where they were born, when they died. Dates are
 * written by [formatDate] (the device's locale), with the age beside the
 * birth date — or the age they reached beside the death date. [today] is a
 * catalog date (YYYY-MM-DD) in the device's calendar.
 */
fun personFacts(person: PersonDetail, today: String, formatDate: (CatalogDate) -> String): List<PersonFact> {
    val facts = mutableListOf<PersonFact>()
    person.knownForDepartment?.trim()?.takeIf { it.isNotEmpty() }?.let {
        facts += PersonFact("Known for", listOf(FactLine(it)))
    }
    val born = formatCatalogDate(person.birthDate, formatDate)
    val died = formatCatalogDate(person.deathDate, formatDate)
    val age = ageInYears(person.birthDate, person.deathDate?.takeIf { it.isNotBlank() } ?: today)
    val birthplace = person.birthplace?.trim().orEmpty()
    if (born.isNotEmpty() || birthplace.isNotEmpty()) {
        facts += PersonFact(
            "Born",
            buildList {
                if (born.isNotEmpty()) add(FactLine(born, note = if (died.isEmpty() && age != null) "(age $age)" else null))
                if (birthplace.isNotEmpty()) add(FactLine(birthplace))
            },
        )
    }
    if (died.isNotEmpty()) {
        facts += PersonFact("Died", listOf(FactLine(died, note = age?.let { "(aged $it)" })))
    }
    return facts
}
