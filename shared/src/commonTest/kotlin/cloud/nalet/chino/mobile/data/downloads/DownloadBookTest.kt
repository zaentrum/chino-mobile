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
        // The child's cap was lowered: "shared" is no longer the child's.
        val change = applyAvailability(book, child, asked = listOf("shared", "kids"), available = setOf("kids"), now = 50)
        assertEquals(listOf("shared"), change.deleted.map { it.itemId })
        // The parent keeps it, media and all; the child's other title is
        // checked as of now.
        assertTrue(change.change.removed.isEmpty())
        assertEquals(listOf("shared"), visibleTo(change.change.titles, parent).map { it.itemId })
        assertEquals(listOf("kids"), visibleTo(change.change.titles, child).map { it.itemId })
        assertEquals(50, change.change.titles.getValue(title("kids").key).claimOf("child")?.checkedAtMs)
        // A title not asked about is not touched.
        val none = applyAvailability(book, child, asked = listOf("kids"), available = emptySet(), now = 60)
        assertEquals(listOf("kids"), none.deleted.map { it.itemId })
        assertEquals(listOf("shared"), visibleTo(none.change.titles, child).map { it.itemId })
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
                respondJson("""{"ids":[${allowed.joinToString(",") { "\"$it\"" }}]}""")
            }
            else -> respondText("not found", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun aCappedAccountsAnswerLeavesOutWhatItsCapDoesNotAllow() = runTest {
        assertEquals(setOf("kid", "teen"), askAvailable(server.api, listOf("kid", "teen", "adult", "gone"), "child"))
        assertEquals(setOf("kid", "teen", "adult"), askAvailable(server.api, listOf("kid", "teen", "adult", "gone"), "parent"))
        // With the account's own bearer, not the one signed in now's.
        assertEquals(listOf<String?>("Bearer child", "Bearer parent"), bearers)

        // The whole rule: the child's above-cap download goes, the parent's
        // copy of it stays.
        var book = claim(emptyMap(), title("adult"), parent, 1, null).titles
        book = claim(book, title("adult"), child, 1, null).titles
        book = claim(book, title("kid"), child, 1, null).titles
        val answer = askAvailable(server.api, visibleTo(book, child).map { it.itemId }, "child")!!
        val change = applyAvailability(book, child, visibleTo(book, child).map { it.itemId }, answer, now = 9)
        assertEquals(listOf("adult"), change.deleted.map { it.itemId })
        assertEquals(listOf("adult"), visibleTo(change.change.titles, parent).map { it.itemId })
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
        assertEquals(setOf("kid"), askAvailable(old.api, listOf("kid", "adult"), "child"))
        assertEquals(listOf("Bearer child"), asked)
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
        assertEquals(ids.toSet(), askAvailable(counting.api, ids, "parent"))
        assertEquals(listOf(500, 500, 200), chunks)
    }
}
