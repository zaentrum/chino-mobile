package cloud.nalet.chino.mobile.data.notices

import cloud.nalet.chino.mobile.data.ownServerHref
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Notices: what addons tell the signed-in person — "your title is ready" —
 * as chino-api forwards them from portal-api (GET /api/v1/notices) and
 * changes them (POST …/{id}/read, POST …/read-all, DELETE …/{id}). The
 * portal's bell (zaentrum-portal src/lib/notices.ts), ported, so the app
 * reads, counts, dates and changes them as the portal does. What a notice
 * says is the addon's: plain text, shown as text and never as markup, and its
 * link is followed only to this server — portal-api checks both when a notice
 * is posted, and the app checks again before it follows one.
 */

/** What an addon told the person (portal-api's model.Notice). */
data class Notice(
    val id: String,
    /** The key of the addon it is from. */
    val addon: String,
    /** The addon's app, which the notice is from: its title, and its lucide
     *  icon. */
    val addonTitle: String,
    val addonIcon: String,
    val title: String,
    val body: String,
    /** Where it leads, "" for nowhere. */
    val link: String,
    /** The catalog item it is about, "" for none. */
    val itemId: String,
    /** RFC 3339. */
    val createdAt: String,
    /** RFC 3339; null while unread. */
    val readAt: String?,
)

/** The person's notices, newest first, and how many of theirs are unread. */
data class NoticeList(val notices: List<Notice>, val unread: Int)

/** How often the list is asked for again while the app is shown: the
 *  portal's bell's minute. */
const val NOTICE_POLL_MS = 60_000L

/**
 * GET /v1/notices as it came, or null — nothing to show — unless chino-api
 * says portal-api answered (`available: true`): without it the list is empty
 * and says nothing about the person's notices.
 */
fun noticesAnswer(raw: JsonElement?): NoticeList? {
    val available = ((raw as? JsonObject)?.get("available") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    return if (available == true) noticeListOf(raw) else null
}

/** The list read as it came: what is no notice — no id, no title, an id
 *  listed already — is left out, a field missing is empty, and the unread
 *  count is never below the unread notices listed. */
fun noticeListOf(raw: JsonElement?): NoticeList {
    val doc = raw as? JsonObject
    val ids = mutableSetOf<String>()
    val notices = (doc?.get("notices") as? JsonArray).orEmpty().mapNotNull { element ->
        val n = element as? JsonObject ?: return@mapNotNull null
        val id = n.text("id")
        val title = n.text("title")
        // The list shows a notice by its id: one listed twice would be two
        // of the same row.
        if (id.isEmpty() || title.isEmpty() || !ids.add(id)) return@mapNotNull null
        Notice(
            id = id,
            addon = n.text("addon"),
            addonTitle = n.text("addonTitle"),
            addonIcon = n.text("addonIcon"),
            title = title,
            body = n.text("body"),
            link = n.text("link"),
            itemId = n.text("itemId"),
            createdAt = n.text("createdAt"),
            readAt = n.text("readAt").ifEmpty { null },
        )
    }
    val listed = notices.count { it.readAt == null }
    val unread = (doc?.get("unread") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
    return NoticeList(notices, maxOf(unread ?: listed, listed))
}

/** A field that is a JSON string, else "". */
private fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

// ─── reading ─────────────────────────────────────────────────────────────────

/** The unread count on the bell: nothing at none, 99+ past 99. */
fun badgeText(unread: Int): String = when {
    unread <= 0 -> ""
    unread > 99 -> "99+"
    else -> unread.toString()
}

/** What the bell is called to a screen reader. */
fun bellLabel(unread: Int): String = if (unread > 0) "Notices, $unread unread" else "Notices"

/** Whom a notice is from: its addon's title, else its key. */
fun fromText(notice: Notice): String = notice.addonTitle.trim().ifEmpty { notice.addon }.ifEmpty { "An addon" }

/**
 * When a notice came, short: now, 5m, 3h, 2d — and from a week on the day it
 * came, "12 Sep", in the zone [utcOffsetMillis] gives for that instant (the
 * device's; UTC when none is given). "" for a time that is none.
 */
fun ageText(createdAt: String, now: Long, utcOffsetMillis: (Long) -> Long = { 0L }): String {
    val t = parseInstant(createdAt) ?: return ""
    val ago = now - t
    return when {
        ago < MINUTE -> "now"
        ago < HOUR -> "${ago / MINUTE}m"
        ago < DAY -> "${ago / HOUR}h"
        ago < 7 * DAY -> "${ago / DAY}d"
        else -> civilDate((t + utcOffsetMillis(t)).floorDiv(DAY)).let { "${it.day} ${MONTHS[it.month - 1]}" }
    }
}

/**
 * A notice's title or body as the app shows it: plain text, its line breaks
 * kept — a "\r\n" or a lone "\r" is one. The control and bidirectional
 * formatting characters portal-api refuses are left out should one come
 * through (a tab is a space), so a text can neither hide part of itself nor
 * reorder it.
 */
fun noticeText(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').filter { c ->
    c == '\n' || !(c < ' ' || c in '\u007F'..'\u009F' || c in BIDI_FORMATTING)
}

private val BIDI_FORMATTING = setOf(
    '؜', '‎', '‏', '‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩',
)

/** Where a notice's link may lead: a page of this server ([ownServerHref]),
 *  as a path or absolute, and nothing else. Null when it leads nowhere it
 *  may — the notice is then its text alone. */
fun noticeHref(link: String, apiBaseUrl: String): String? = ownServerHref(link, apiBaseUrl)

/** The catalog item a notice opens, or null: an id as portal-api takes one —
 *  letters, digits and . _ : -, at most 128 — that is not dots alone, which
 *  would be a path of its own. */
fun noticeItemId(notice: Notice): String? =
    notice.itemId.takeIf { ITEM_ID.matches(it) && it.any { c -> c != '.' } }

private val ITEM_ID = Regex("^[A-Za-z0-9._:-]{1,128}$")

// ─── changing ────────────────────────────────────────────────────────────────

/** The list once a notice is read, before chino-api answers. */
fun markRead(list: NoticeList, id: String, at: String): NoticeList {
    var changed = 0
    val notices = list.notices.map { n ->
        if (n.id != id || n.readAt != null) {
            n
        } else {
            changed++
            n.copy(readAt = at)
        }
    }
    return NoticeList(notices, maxOf(0, list.unread - changed))
}

/** The list once every notice is read. */
fun markAllRead(list: NoticeList, at: String): NoticeList =
    NoticeList(list.notices.map { if (it.readAt == null) it.copy(readAt = at) else it }, 0)

/** The list without a notice. */
fun removeNotice(list: NoticeList, id: String): NoticeList {
    val gone = list.notices.firstOrNull { it.id == id }
    return NoticeList(
        notices = list.notices.filter { it.id != id },
        unread = maxOf(0, list.unread - if (gone != null && gone.readAt == null) 1 else 0),
    )
}

// ─── times ───────────────────────────────────────────────────────────────────
// RFC 3339 by hand, as the rest of the app reads its dates: no date library.

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private val RFC3339 = Regex(
    "^([0-9]{4})-([0-9]{2})-([0-9]{2})[Tt ]([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]+))?([Zz]|[+-][0-9]{2}:[0-9]{2})$",
)

/** An RFC 3339 time — "2026-10-05T07:58:00Z", "…07:58:00.123456+02:00", a
 *  space for the "T" as the RFC allows — as milliseconds since the epoch, or
 *  null when it is none. */
internal fun parseInstant(text: String): Long? {
    val g = RFC3339.matchEntire(text.trim())?.groupValues ?: return null
    val year = g[1].toInt()
    val month = g[2].toInt()
    val day = g[3].toInt()
    val hour = g[4].toInt()
    val minute = g[5].toInt()
    val second = g[6].toInt()
    if (month !in 1..12 || day !in 1..daysInMonth(year, month) || hour > 23 || minute > 59 || second > 59) return null
    val millis = g[7].take(3).padEnd(3, '0').toInt()
    val offset = when (val zone = g[8]) {
        "Z", "z" -> 0L
        else -> {
            val hours = zone.substring(1, 3).toInt()
            val minutes = zone.substring(4, 6).toInt()
            if (hours > 23 || minutes > 59) return null
            (if (zone[0] == '-') -1 else 1) * (hours * HOUR + minutes * MINUTE)
        }
    }
    return daysFromCivil(year, month, day) * DAY + hour * HOUR + minute * MINUTE + second * 1000L + millis - offset
}

/** [millis] as RFC 3339 in UTC, to the second: "2026-10-05T08:00:00Z". */
internal fun formatInstant(millis: Long): String {
    val days = millis.floorDiv(DAY)
    val date = civilDate(days)
    val seconds = (millis - days * DAY) / 1000
    fun Number.pad(width: Int) = toString().padStart(width, '0')
    return "${date.year.pad(4)}-${date.month.pad(2)}-${date.day.pad(2)}" +
        "T${(seconds / 3600).pad(2)}:${(seconds / 60 % 60).pad(2)}:${(seconds % 60).pad(2)}Z"
}

private class CivilDate(val year: Int, val month: Int, val day: Int)

private fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Days since 1970-01-01 of a Gregorian date (Howard Hinnant's
 *  days_from_civil). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * ((month + 9) % 12) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146097 + dayOfEra - 719468
}

/** The Gregorian date [days] after 1970-01-01 (civil_from_days). */
private fun civilDate(days: Long): CivilDate {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val dayOfEra = z - era * 146097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return CivilDate((yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt(), month, day)
}
