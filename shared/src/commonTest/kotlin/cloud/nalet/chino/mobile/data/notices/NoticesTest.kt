package cloud.nalet.chino.mobile.data.notices

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The portal bell's notices.test.ts on a phone: what chino-api answers for
 *  the signed-in person's notices read, counted and dated, where a link may
 *  lead, and the list after each change. */
class NoticesTest {
    private val api = "https://media.example.org/api/"
    private val now = parseInstant("2026-10-05T08:00:00Z")!!

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun notice(
        id: String = "00000001-0000-4000-8000-000000000001",
        addon: String = "example",
        addonTitle: String = "Example",
        link: String = "",
        itemId: String = "",
        readAt: String? = null,
    ) = Notice(
        id = id,
        addon = addon,
        addonTitle = addonTitle,
        addonIcon = "puzzle",
        title = "Your title is ready",
        body = "It is in your library now.",
        link = link,
        itemId = itemId,
        createdAt = "2026-10-05T07:58:00Z",
        readAt = readAt,
    )

    private val noticeJson =
        """{"id":"00000001-0000-4000-8000-000000000001","addon":"example","addonTitle":"Example","addonIcon":"puzzle",""" +
            """"title":"Your title is ready","body":"It is in your library now.","link":"","itemId":"",""" +
            """"createdAt":"2026-10-05T07:58:00Z","readAt":null}"""

    // What chino-api answers is read as it came: a notice without an id or a
    // title is none, a missing field is empty, and the unread count holds
    // the unread notices listed.
    @Test
    fun aListIsReadDefensively() {
        val got = noticeListOf(
            json(
                """{"notices":[$noticeJson,{"id":"x"},{"title":"no id"},null,"a string",
                   {"id":"y","title":"bare","readAt":"2026-10-05T07:59:00Z","link":7}],"unread":1}""",
            ),
        )
        assertEquals(notice(), got.notices[0])
        assertEquals(2, got.notices.size)
        assertEquals("", got.notices[1].link)
        assertEquals("2026-10-05T07:59:00Z", got.notices[1].readAt)
        assertEquals("", got.notices[1].addonTitle)
        assertEquals(1, got.unread)
        assertEquals(NoticeList(emptyList(), 0), noticeListOf(null))
        assertEquals(NoticeList(emptyList(), 0), noticeListOf(json("""{"notices":"no"}""")))
        // An unread count below what is listed unread is not believed.
        val two = """{"notices":[$noticeJson,${noticeJson.replace("00000001-", "00000002-")}],"unread":0}"""
        assertEquals(2, noticeListOf(json(two)).unread)
        assertEquals(1, noticeListOf(json("""{"notices":[$noticeJson],"unread":-3}""")).unread)
        assertEquals(1, noticeListOf(json("""{"notices":[$noticeJson],"unread":"9"}""")).unread)
        assertEquals(7, noticeListOf(json("""{"notices":[],"unread":7}""")).unread)
    }

    @Test
    fun aNoticeListedTwiceIsListedOnce() {
        val again = noticeJson.replace("Your title is ready", "Again")
        val got = noticeListOf(json("""{"notices":[$noticeJson,$again],"unread":2}"""))
        assertEquals(listOf("Your title is ready"), got.notices.map { it.title })
        assertEquals(2, got.unread, "the count is the server's")
    }

    @Test
    fun nothingIsShownUnlessPortalApiAnswered() {
        val list = noticesAnswer(json("""{"notices":[$noticeJson],"unread":1,"available":true}"""))
        assertEquals(NoticeList(listOf(notice()), 1), list)
        // No portal-api: the list is empty and says nothing of the person's.
        assertNull(noticesAnswer(json("""{"notices":[],"unread":0,"available":false}""")))
        assertNull(noticesAnswer(json("""{"notices":[$noticeJson],"unread":1}""")))
        assertNull(noticesAnswer(json("""{"notices":[$noticeJson],"unread":1,"available":"true"}""")))
        assertNull(noticesAnswer(json("""[]""")))
        assertNull(noticesAnswer(null))
    }

    @Test
    fun theBellSaysHowManyAreUnread() {
        assertEquals("", badgeText(0))
        assertEquals("", badgeText(-1))
        assertEquals("1", badgeText(1))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
        assertEquals("Notices", bellLabel(0))
        assertEquals("Notices, 3 unread", bellLabel(3))
    }

    @Test
    fun aNoticeSaysWhomItIsFromAndWhen() {
        assertEquals("Example", fromText(notice()))
        assertEquals("example", fromText(notice(addonTitle = "  ")))
        assertEquals("An addon", fromText(notice(addonTitle = "", addon = "")))
        assertEquals("now", ageText("2026-10-05T07:59:30Z", now))
        assertEquals("now", ageText("2026-10-05T08:05:00Z", now), "a clock ahead of the phone's")
        assertEquals("5m", ageText("2026-10-05T07:55:00Z", now))
        assertEquals("3h", ageText("2026-10-05T05:00:00Z", now))
        assertEquals("2d", ageText("2026-10-03T08:00:00Z", now))
        assertEquals("12 Sep", ageText("2026-09-12T08:00:00Z", now))
        assertEquals("", ageText("not a time", now))
        assertEquals("", ageText("", now))
    }

    @Test
    fun aWeekOldNoticeShowsTheDayItCameInThePhonesZone() {
        val lateUtc = "2026-09-12T23:30:00Z"
        assertEquals("12 Sep", ageText(lateUtc, now))
        assertEquals("13 Sep", ageText(lateUtc, now) { 2 * 3_600_000L })
        assertEquals("1 Jan", ageText("2025-12-31T23:30:00Z", now) { 3_600_000L })
    }

    @Test
    fun timesAreReadAsRfc3339() {
        assertEquals(0L, parseInstant("1970-01-01T00:00:00Z"))
        assertEquals(951_825_600_000L, parseInstant("2000-02-29T12:00:00Z"))
        assertEquals(parseInstant("2026-10-05T08:00:00.123Z"), parseInstant("2026-10-05T10:00:00.123456+02:00"))
        assertEquals(parseInstant("2026-10-05T08:00:00Z"), parseInstant("2026-10-05t03:30:00-04:30"))
        assertEquals(parseInstant("2026-10-05T08:00:00Z"), parseInstant("2026-10-05 08:00:00z"))
        for (text in listOf(
            "2026-02-29T00:00:00Z",
            "2026-13-01T00:00:00Z",
            "2026-10-32T00:00:00Z",
            "2026-10-05T24:00:00Z",
            "2026-10-05T08:60:00Z",
            "2026-10-05T08:00:60Z",
            "2026-10-05T08:00:00",
            "2026-10-05T08:00:00+24:00",
            "2026-10-05",
            "１９７０-01-01T00:00:00Z",
        )) {
            assertNull(parseInstant(text), text)
        }
        assertEquals("1970-01-01T00:00:00Z", formatInstant(0))
        assertEquals("2000-02-29T12:00:00Z", formatInstant(951_825_600_000L))
        assertEquals("2026-10-05T08:00:00Z", formatInstant(now + 999))
    }

    // A link is followed only to an http(s) page of this server: what the
    // server's rule takes, and nothing it refuses — checked again here, since
    // the phone is where it is opened.
    @Test
    fun aLinkLeadsOnlyToThisServer() {
        for ((link, want) in listOf(
            "/portal/app/example" to "https://media.example.org/portal/app/example",
            "/portal/app/example?q=x#/ready" to "https://media.example.org/portal/app/example?q=x#/ready",
            "https://media.example.org/portal/app/example" to "https://media.example.org/portal/app/example",
            "HTTPS://MEDIA.EXAMPLE.ORG/x" to "https://media.example.org/x",
            "  /x  " to "https://media.example.org/x",
        )) {
            assertEquals(want, noticeHref(link, api), link)
        }
        for (link in listOf(
            "",
            "javascript:alert(document.cookie)",
            "JavaScript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "https://elsewhere.example/x",
            "http://media.example.org/x", // another scheme is another origin
            "https://media.example.org.elsewhere.example/x",
            "//elsewhere.example/x",
            "//media.example.org/x",
            "/\\elsewhere.example/x",
            "https://user:pass@media.example.org/x",
            "portal/app/example",
            "?q=x",
            "#/x",
            "mailto:someone@example.org",
        )) {
            assertNull(noticeHref(link, api), link)
        }
    }

    @Test
    fun anItemOpensOnlyByAnIdTheServerTakes() {
        assertEquals("3f2b9c1e-0b7a-4c55-9d1e-2a4f6b8c0d1e", noticeItemId(notice(itemId = "3f2b9c1e-0b7a-4c55-9d1e-2a4f6b8c0d1e")))
        assertEquals("tt.0111_161:x", noticeItemId(notice(itemId = "tt.0111_161:x")))
        assertEquals("a".repeat(128), noticeItemId(notice(itemId = "a".repeat(128))))
        for (id in listOf("", ".", "..", "../me", "a/b", "a b", "a?b", "a#b", "a%2fb", "a".repeat(129))) {
            assertNull(noticeItemId(notice(itemId = id)), id)
        }
    }

    @Test
    fun aNoticesTextIsPlainItsLineBreaksKept() {
        assertEquals("Ready.\nIn your library.\nEnjoy.", noticeText("Ready.\r\nIn your library.\rEnjoy."))
        assertEquals("Two\n\nparagraphs", noticeText("Two\n\nparagraphs"))
        assertEquals("<b>not bold</b> &amp;", noticeText("<b>not bold</b> &amp;"))
        // What could hide or reorder the text is left out.
        assertEquals("abcdef", noticeText("abc‮def"))
        assertEquals("ab", noticeText("a\u0007\u0000\u007F\u0085b"))
        assertEquals("a b", noticeText("a\tb"))
    }

    @Test
    fun readingReadingAllAndDeletingKeepTheCount() {
        val list = NoticeList(
            listOf(notice(id = "a"), notice(id = "b"), notice(id = "c", readAt = "2026-10-05T07:00:00Z")),
            unread = 2,
        )
        val read = markRead(list, "a", "2026-10-05T08:00:00Z")
        assertEquals(1, read.unread)
        assertEquals("2026-10-05T08:00:00Z", read.notices[0].readAt)
        assertNull(list.notices[0].readAt, "the list it was given stays as it was")
        assertEquals(1, markRead(read, "a", "later").unread, "reading again changes nothing")
        assertEquals("2026-10-05T07:00:00Z", markRead(read, "c", "later").notices[2].readAt)
        assertEquals(2, markRead(list, "none", "later").unread)
        val all = markAllRead(list, "now")
        assertEquals(0, all.unread)
        assertTrue(all.notices.all { it.readAt != null })
        assertEquals("2026-10-05T07:00:00Z", all.notices[2].readAt)
        assertEquals(listOf("a", "c"), removeNotice(list, "b").notices.map { it.id })
        assertEquals(1, removeNotice(list, "b").unread)
        assertEquals(2, removeNotice(list, "c").unread, "a read one takes nothing off the count")
        assertEquals(0, removeNotice(NoticeList(listOf(notice(id = "a")), 0), "a").unread)
        assertEquals(list, removeNotice(list, "none"))
    }
}
