package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import cloud.nalet.chino.mobile.data.model.Item
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val A = "https://media.example.com/api"
private const val B = "https://other.example.com/api"

private val parent = Owner(A, "parent")
private val child = Owner(A, "child")

private fun title(id: String, server: String = A) =
    DownloadedTitle(itemId = id, item = Item(id = id, title = id, kind = "movie"), quality = "720p", server = server)

/** Whose the downloads on a phone are: each account sees its own, one title
 *  is fetched once for every account that downloads it and goes with the
 *  last of them, and the server's word on what an account may watch is the
 *  account's alone. */
class DownloadBookTest {
    @Test
    fun aTitleOfOneServerIsOneDownloadWhoeverDownloadsIt() {
        assertEquals(downloadKey(A, "film"), title("film").key)
        assertNotEquals(downloadKey(A, "film"), downloadKey(B, "film"))
        // The server as it is written, or with a trailing slash: the same.
        assertEquals(downloadKey(A, "film"), downloadKey("$A/", "film"))
    }

    @Test
    fun twoAccountsShareOneDownloadAndItGoesWithTheLast() {
        var book = claim(emptyMap(), title("film"), parent, now = 1, savedPositionSec = 600).titles
        // The child downloads the same title: no second copy, a second claim.
        book = claim(book, title("film"), child, now = 2, savedPositionSec = null).titles
        assertEquals(1, book.size)
        assertEquals(listOf("parent", "child"), book.getValue(title("film").key).owners.map { it.subject })
        assertEquals(listOf("film"), visibleTo(book, parent).map { it.itemId })
        assertEquals(listOf("film"), visibleTo(book, child).map { it.itemId })
        // Each keeps its own: where the server had the parent, and when.
        assertEquals(600, book.getValue(title("film").key).claimOf("parent")?.savedPositionSec)
        assertNull(book.getValue(title("film").key).claimOf("child")?.savedPositionSec)

        // The child deletes it: still the parent's, its media stays.
        val first = unclaim(book, title("film").key, "child")
        assertTrue(first.removed.isEmpty())
        assertEquals(emptyList(), visibleTo(first.titles, child))
        assertEquals(listOf("film"), visibleTo(first.titles, parent).map { it.itemId })
        // The parent deletes it too: the media goes with the last claim.
        val last = unclaim(first.titles, title("film").key, "parent")
        assertEquals(listOf(title("film").key), last.removed)
        assertTrue(last.titles.isEmpty())
    }

    @Test
    fun eachAccountSeesOnlyItsOwnAndNobodySeesNone() {
        var book = claim(emptyMap(), title("kids-film"), child, 1, null).titles
        book = claim(book, title("adult-film"), parent, 2, null).titles
        assertEquals(listOf("kids-film"), visibleTo(book, child).map { it.itemId })
        assertEquals(listOf("adult-film"), visibleTo(book, parent).map { it.itemId })
        assertTrue(visibleTo(book, null).isEmpty())
        // The same account on another server is another owner.
        assertTrue(visibleTo(book, Owner(B, "parent")).isEmpty())
        assertNull(titleOf(book, child, "adult-film"))
        assertEquals("kids-film", titleOf(book, child, "kids-film")?.itemId)
    }

    @Test
    fun aSignOutTakesTheAccountsClaimsAndOnlyTheMediaNobodyElseClaims() {
        var book = claim(emptyMap(), title("shared"), parent, 1, null).titles
        book = claim(book, title("shared"), child, 2, null).titles
        book = claim(book, title("mine"), parent, 3, null).titles
        val out = unclaimAll(book, parent)
        assertEquals(listOf(title("mine").key), out.removed)
        assertEquals(listOf("shared"), visibleTo(out.titles, child).map { it.itemId })
        assertTrue(visibleTo(out.titles, parent).isEmpty())
    }

    @Test
    fun aServerChangeTakesThatServersDownloads() {
        var book = claim(emptyMap(), title("a1"), parent, 1, null).titles
        book = claim(book, title("b1", server = B), Owner(B, "parent"), 2, null).titles
        val out = dropServer(book, A)
        assertEquals(listOf(title("a1").key), out.removed)
        assertEquals(listOf("b1"), out.titles.values.map { it.itemId })
    }

    @Test
    fun theServersWordIsTheAccountsAlone() {
        var book = claim(emptyMap(), title("shared"), parent, now = 1, savedPositionSec = null).titles
        book = claim(book, title("shared"), child, now = 1, savedPositionSec = null).titles
        book = claim(book, title("kids"), child, now = 1, savedPositionSec = null).titles
        // The child's cap was lowered: "shared" is no longer the child's -
        // said twice, an hour apart.
        val first = applyAvailability(book, child, asked = listOf("shared", "kids"), available = setOf("kids"), now = 50)
        val change = applyAvailability(first.change.titles, child, listOf("shared", "kids"), setOf("kids"), now = 50 + GONE_CONFIRM_MS)
        assertEquals(listOf("shared"), change.deleted.map { it.itemId })
        // The parent keeps it, media and all; the child's other title is
        // checked as of now.
        assertTrue(change.change.removed.isEmpty())
        assertEquals(listOf("shared"), visibleTo(change.change.titles, parent).map { it.itemId })
        assertEquals(listOf("kids"), visibleTo(change.change.titles, child).map { it.itemId })
        assertEquals(50 + GONE_CONFIRM_MS, change.change.titles.getValue(title("kids").key).claimOf("child")?.checkedAtMs)
        // A title not asked about is not touched.
        val none = applyAvailability(book, child, asked = listOf("kids", "shared"), available = setOf("shared"), now = 60)
        assertEquals(listOf("kids"), none.marked.map { it.itemId })
        val only = applyAvailability(book, child, asked = listOf("kids"), available = setOf("kids"), now = 60)
        assertNull(only.change.titles.getValue(title("shared").key).claimOf("child")?.goneAtMs)
    }

    @Test
    fun anAnswerThatLeavesOutEveryTitleAskedNeedsADayUnlessTheServerSaysItsLibraryHoldsTitles() {
        val hour = GONE_CONFIRM_MS
        val day = EMPTY_ANSWER_CONFIRM_MS
        var book = claim(emptyMap(), title("film"), child, now = 1, savedPositionSec = null).titles
        book = claim(book, title("kids"), child, now = 1, savedPositionSec = null).titles
        val asked = listOf("film", "kids")
        // A library imported anew, or reset: the server knows none of them
        // for a while, and says nothing of its library. One answer marks
        // them, nothing more; a second an hour later takes nothing either.
        val first = applyAvailability(book, child, asked, emptySet(), now = 10 * hour)
        assertTrue(first.deleted.isEmpty())
        assertEquals(setOf("film", "kids"), first.marked.map { it.itemId }.toSet())
        assertEquals(1, first.change.titles.getValue(title("film").key).claimOf("child")?.checkedAtMs)
        val second = applyAvailability(first.change.titles, child, asked, emptySet(), now = 12 * hour)
        assertTrue(second.deleted.isEmpty())
        assertTrue(second.change.removed.isEmpty())
        // The library back meanwhile: the marks go, as for any answer.
        val back = applyAvailability(second.change.titles, child, asked, setOf("film", "kids"), now = 13 * hour)
        assertTrue(back.marked.isEmpty())
        assertNull(back.change.titles.getValue(title("film").key).claimOf("child")?.goneAtMs)
        // Still leaving them out a day after the first: they go - all of
        // them, even where they are all the account has.
        val later = applyAvailability(second.change.titles, child, asked, emptySet(), now = 10 * hour + day)
        assertEquals(setOf("film", "kids"), later.deleted.map { it.itemId }.toSet())
        assertTrue(visibleTo(later.change.titles, child).isEmpty())
    }

    @Test
    fun anAccountsOnlyDownloadAboveItsLoweredCapGoes() {
        val hour = GONE_CONFIRM_MS
        val one = claim(emptyMap(), title("film"), child, now = 1, savedPositionSec = null).titles
        val key = title("film").key
        // The server says its library holds titles for the child (POST
        // /me/available's "titles"): two answers an hour apart take it.
        val first = applyAvailability(one, child, listOf("film"), emptySet(), now = hour, libraryTitles = 3)
        assertEquals(listOf("film"), first.marked.map { it.itemId })
        assertTrue(first.deleted.isEmpty())
        val soon = applyAvailability(first.change.titles, child, listOf("film"), emptySet(), now = 2 * hour - 1, libraryTitles = 3)
        assertTrue(soon.deleted.isEmpty())
        val second = applyAvailability(soon.change.titles, child, listOf("film"), emptySet(), now = 2 * hour, libraryTitles = 3)
        assertEquals(listOf("film"), second.deleted.map { it.itemId })
        assertEquals(listOf(key), second.change.removed)
        // A library that shows the child nothing, or a server that does not
        // say: an hour is not enough, a day is.
        for (said in listOf(0, null)) {
            val f = applyAvailability(one, child, listOf("film"), emptySet(), now = hour, libraryTitles = said)
            assertTrue(applyAvailability(f.change.titles, child, listOf("film"), emptySet(), now = 2 * hour, libraryTitles = said).deleted.isEmpty())
            assertEquals(
                listOf("film"),
                applyAvailability(f.change.titles, child, listOf("film"), emptySet(), now = hour + EMPTY_ANSWER_CONFIRM_MS, libraryTitles = said)
                    .deleted.map { it.itemId },
                "titles $said",
            )
        }
    }

    @Test
    fun aTitleGoesOnlyAfterTwoAnswersAnHourApart() {
        val hour = GONE_CONFIRM_MS
        // Another of the account's titles the server still knows: its
        // answers say something of the film.
        var book = claim(emptyMap(), title("film"), child, now = 1, savedPositionSec = 300).titles
        book = claim(book, title("kids"), child, now = 1, savedPositionSec = null).titles
        val asked = listOf("film", "kids")
        val kids = setOf("kids")
        val key = title("film").key
        // The first answer that it is not the account's: marked, nothing
        // else - on the phone, its last good check as it was.
        val first = applyAvailability(book, child, asked, kids, now = 10 * hour)
        assertTrue(first.deleted.isEmpty())
        assertEquals(listOf("film"), first.marked.map { it.itemId })
        assertTrue(first.change.removed.isEmpty())
        val marked = first.change.titles.getValue(key).claimOf("child")!!
        assertEquals(10 * hour, marked.goneAtMs)
        assertEquals(1, marked.checkedAtMs)
        assertEquals(300, marked.savedPositionSec)
        // Said again within the hour: still marked, since the first answer.
        val soon = applyAvailability(first.change.titles, child, asked, kids, now = 11 * hour - 1)
        assertTrue(soon.deleted.isEmpty())
        assertEquals(10 * hour, soon.change.titles.getValue(key).claimOf("child")?.goneAtMs)
        // An hour after the first: deleted, the media with its last claim.
        val second = applyAvailability(soon.change.titles, child, asked, kids, now = 11 * hour)
        assertEquals(listOf("film"), second.deleted.map { it.itemId })
        assertEquals(listOf(key), second.change.removed)
        assertEquals(listOf("kids"), visibleTo(second.change.titles, child).map { it.itemId })
    }

    @Test
    fun anAnswerThatTheTitleIsTheAccountsClearsTheMark() {
        val hour = GONE_CONFIRM_MS
        var book = claim(emptyMap(), title("film"), child, now = 1, savedPositionSec = null).titles
        book = claim(book, title("kids"), child, now = 1, savedPositionSec = null).titles
        val asked = listOf("film", "kids")
        val key = title("film").key
        // A server having a bad moment, then itself again.
        val glitch = applyAvailability(book, child, asked, setOf("kids"), now = hour)
        val fine = applyAvailability(glitch.change.titles, child, asked, setOf("film", "kids"), now = hour + 5)
        assertNull(fine.change.titles.getValue(key).claimOf("child")?.goneAtMs)
        assertEquals(hour + 5, fine.change.titles.getValue(key).claimOf("child")?.checkedAtMs)
        // A later "not the account's" is a first answer again, not a second.
        val later = applyAvailability(fine.change.titles, child, asked, setOf("kids"), now = 3 * hour)
        assertTrue(later.deleted.isEmpty())
        assertEquals(3 * hour, later.change.titles.getValue(key).claimOf("child")?.goneAtMs)
    }

    @Test
    fun aDownloadNotCheckedFor30DaysWaitsForTheServer() {
        val claim = OwnerClaim("child", addedAtMs = 0, checkedAtMs = 0)
        assertFalse(checkDue(claim, CHECK_EVERY_MS - 1))
        assertTrue(checkDue(claim, CHECK_EVERY_MS))
        assertEquals(30L * 24 * 60 * 60 * 1000, CHECK_EVERY_MS)
    }

    @Test
    fun onlyTheSignedInAccountsDownloadsRun() {
        val book = claim(emptyMap(), title("film"), parent, 1, null).titles
        val t = book.getValue(title("film").key)
        assertEquals(STOP_NONE, stopReasonFor(t, parent, serverOffersDownloads = true))
        assertEquals(STOP_OWNER_AWAY, stopReasonFor(t, child, serverOffersDownloads = true))
        assertEquals(STOP_OWNER_AWAY, stopReasonFor(t, null, serverOffersDownloads = true))
        assertEquals(STOP_OWNER_AWAY, stopReasonFor(null, parent, serverOffersDownloads = true))
        assertEquals(STOP_SERVER_OFF, stopReasonFor(t, parent, serverOffersDownloads = false))
        assertEquals(STOP_NO_SPACE, stopReasonFor(t.copy(failure = DownloadFailure.NoSpace), parent, serverOffersDownloads = true))
    }
}

/** POST /me/available against a server that holds a capped child to its
 *  cap, as chino-api does: what the server answers decides, and nothing but
 *  an answer does. */
class AskAvailableTest {
    private val ages = mapOf("kid" to 6, "teen" to 12, "adult" to 16)
    private var down = false
    private val bearers = mutableListOf<String?>()
    private val server = FakeServer(accessToken = { "token-of-whoever-is-signed-in" }) { request ->
        if (down) throw IOException("no connection")
        val path = request.url.encodedPath
        when {
            path.endsWith("/v1/me/available") -> {
                bearers += request.headers[HttpHeaders.Authorization]
                val body = (request.body as io.ktor.http.content.TextContent).text
                val asked = Json.parseToJsonElement(body).jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content }
                // The child's cap is 12; the parent has none.
                val cap = if (request.headers[HttpHeaders.Authorization] == "Bearer child") 12 else Int.MAX_VALUE
                val allowed = asked.filter { (ages[it] ?: return@filter false) <= cap }
                // An answer that leaves out every id asked says how many
                // titles of the library the account may watch.
                val titles = if (allowed.isEmpty() && asked.isNotEmpty()) ""","titles":${ages.values.count { it <= cap }}""" else ""
                respondJson("""{"ids":[${allowed.joinToString(",") { "\"$it\"" }}]$titles}""")
            }
            else -> respondText("not found", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun aCappedAccountsAnswerLeavesOutWhatItsCapDoesNotAllow() = runTest {
        assertEquals(setOf("kid", "teen"), askAvailable(server.api, listOf("kid", "teen", "adult", "gone"), "child")?.ids)
        assertEquals(setOf("kid", "teen", "adult"), askAvailable(server.api, listOf("kid", "teen", "adult", "gone"), "parent")?.ids)
        // With the account's own bearer, not the one signed in now's.
        assertEquals(listOf<String?>("Bearer child", "Bearer parent"), bearers)

        // The whole rule: the child's above-cap download goes, the parent's
        // copy of it stays.
        var book = claim(emptyMap(), title("adult"), parent, 1, null).titles
        book = claim(book, title("adult"), child, 1, null).titles
        book = claim(book, title("kid"), child, 1, null).titles
        val ids = visibleTo(book, child).map { it.itemId }
        // Two answers an hour apart: the first marks it, the second deletes it.
        val first = applyAvailability(book, child, ids, askAvailable(server.api, ids, "child")!!.ids, now = 9)
        assertEquals(listOf("adult"), first.marked.map { it.itemId })
        assertTrue(first.deleted.isEmpty())
        val change = applyAvailability(first.change.titles, child, ids, askAvailable(server.api, ids, "child")!!.ids, now = 9 + GONE_CONFIRM_MS)
        assertEquals(listOf("adult"), change.deleted.map { it.itemId })
        assertEquals(listOf("adult"), visibleTo(change.change.titles, parent).map { it.itemId })
    }

    @Test
    fun aChildsOnlyDownloadAboveItsCapGoesOnTheServersWord() = runTest {
        // The child's cap is 12 now; its one download is the 16 one.
        val answer = askAvailable(server.api, listOf("adult"), "child")
        assertEquals(AvailableAnswer(emptySet(), libraryTitles = 2), answer)
        val book = claim(emptyMap(), title("adult"), child, 1, null).titles
        val first = applyAvailability(book, child, listOf("adult"), answer!!.ids, now = 9, libraryTitles = answer.libraryTitles)
        val again = askAvailable(server.api, listOf("adult"), "child")!!
        val change = applyAvailability(first.change.titles, child, listOf("adult"), again.ids, now = 9 + GONE_CONFIRM_MS, libraryTitles = again.libraryTitles)
        assertEquals(listOf("adult"), change.deleted.map { it.itemId })
        // An answer with ids in it carries no count.
        assertEquals(AvailableAnswer(setOf("kid")), askAvailable(server.api, listOf("kid", "adult"), "child"))
    }

    @Test
    fun aServerFromBeforeTheRouteIsAskedTitleByTitle() = runTest {
        // No POST /me/available: its title route decides, as for every
        // other request of one title - 404 is not the account's.
        val asked = mutableListOf<String>()
        val old = FakeServer { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/v1/me/available") -> respondText("404 page not found", HttpStatusCode.NotFound)
                path.endsWith("/v1/items/kid") -> {
                    asked += request.headers[HttpHeaders.Authorization].orEmpty()
                    respondJson("""{"id":"kid","title":"kid","type":"movie"}""")
                }
                else -> respondText("not found", HttpStatusCode.NotFound)
            }
        }
        assertEquals(AvailableAnswer(setOf("kid")), askAvailable(old.api, listOf("kid", "adult"), "child"))
        assertEquals(listOf("Bearer child"), asked)
        // Every title answered 404: an answer that leaves out all of them,
        // which such a server never vouches for - its titles go only after
        // two such answers a day apart.
        assertEquals(AvailableAnswer(emptySet(), libraryTitles = null), askAvailable(old.api, listOf("adult", "gone"), "child"))
        // Its title route failing: no answer.
        val failing = FakeServer { request ->
            if (request.url.encodedPath.endsWith("/v1/me/available")) {
                respondText("404 page not found", HttpStatusCode.NotFound)
            } else {
                respondText("catalog unavailable", HttpStatusCode.BadGateway)
            }
        }
        assertNull(askAvailable(failing.api, listOf("kid"), "child"))
    }

    @Test
    fun noAnswerOfAnyKindDecidesAnything() = runTest {
        // The catalog failing behind the server.
        val failing = FakeServer { respondText("catalog unavailable", HttpStatusCode.BadGateway) }
        assertNull(askAvailable(failing.api, listOf("kid"), "child"))
        // A sign-in it does not take.
        val refusing = FakeServer { respondText("unauthorized", HttpStatusCode.Unauthorized) }
        assertNull(askAvailable(refusing.api, listOf("kid"), "child"))
        // No connection.
        down = true
        assertNull(askAvailable(server.api, listOf("kid"), "child"))
    }

    @Test
    fun manyTitlesAreAskedAboutInChunks() = runTest {
        val ids = (1..1_200).map { "t$it" }
        val chunks = mutableListOf<Int>()
        val counting = FakeServer { request ->
            val body = (request.body as io.ktor.http.content.TextContent).text
            val asked = Json.parseToJsonElement(body).jsonObject.getValue("ids").jsonArray
            chunks += asked.size
            respondJson("""{"ids":${asked}}""")
        }
        assertEquals(ids.toSet(), askAvailable(counting.api, ids, "parent")?.ids)
        assertEquals(listOf(500, 500, 200), chunks)
    }
}
