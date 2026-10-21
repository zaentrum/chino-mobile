package cloud.nalet.chino.mobile.ui.search

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.catalogueMessage
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The search against a fake chino-api — with the app's own client: one GET
 * /v1/search as search.go answers it, and the search as before against a
 * server without it, which answers 404 as its router does for a path it does
 * not know.
 */
class CatalogueSearchTest {
    /** search.go's answer to "night": films and series ranked together, a
     *  series first — not the films first, then the series. */
    private val ranked = """
        {"product":"chino","items":[
          {"id":"s1","type":"series","title":"Night","poster_url":"/api/v1/items/s1/poster","backdrop_url":"/api/v1/items/s1/backdrop","match":"exact"},
          {"id":"m1","type":"movie","title":"Night Shift","year":2021,"rating":7.1,"poster_url":"/api/v1/items/m1/poster",
           "backdrop_url":"/api/v1/items/m1/backdrop","watched_at":"2026-09-30T20:00:00Z","match":"prefix"},
          {"id":"s2","type":"series","title":"The Long Night","poster_url":"/api/v1/items/s2/poster","backdrop_url":"/api/v1/items/s2/backdrop","match":"words"},
          {"id":"m2","type":"movie","title":"A Night Out","poster_url":"/api/v1/items/m2/poster","backdrop_url":"/api/v1/items/m2/backdrop","match":"words"}],
         "people":[
          {"id":"p1","name":"Ada Night","credits":2,"has_profile":true,"profile_url":"/api/v1/people/p1/profile","match":"words"},
          {"id":"p2","name":"Bo Knight","credits":1,"has_profile":false,"match":"similar"}],
         "source":"katalog"}
    """

    private val notFound = "404 page not found"

    @Test
    fun oneSearchAsksForFilmsAndSeriesAndKeepsTheServersOrder() = runTest {
        val server = FakeServer { respondJson(ranked) }

        val found = searchCatalogue(server.api, "night")

        val request = server.requests.single()
        assertEquals("/api/v1/search", request.url.encodedPath)
        assertEquals("night", request.url.parameters["q"])
        assertEquals("movie,series", request.url.parameters["types"])
        assertEquals("40", request.url.parameters["limit"])
        assertEquals("12", request.url.parameters["people"])
        // Best first, as they came: not grouped by type.
        val titles = found.titles.getOrThrow()
        assertEquals(listOf("s1", "m1", "s2", "m2"), titles.map { it.id })
        assertEquals(listOf("exact", "prefix", "words", "words"), titles.map { it.match })
        assertEquals("2026-09-30T20:00:00Z", titles[1].watchedAt)
        // The people of the same answer, in theirs.
        assertEquals(listOf("p1", "p2"), found.people.map { it.id })
        assertEquals("/api/v1/people/p1/profile", found.people[0].profileUrl)
    }

    @Test
    fun aServerOlderThanTheSearchIsSearchedAsBefore() = runTest {
        val server = FakeServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/search" -> respondText(notFound, HttpStatusCode.NotFound)
                "/api/v1/people" -> respondJson(
                    """{"people":[{"id":"p1","name":"Ada Night","credits":2,"has_profile":false}],"total":1}""",
                )
                else -> when (request.url.parameters["type"]) {
                    "movie" -> respondJson(
                        """{"product":"chino","items":[{"id":"m1","type":"movie","title":"Night Shift"},""" +
                            """{"id":"m2","type":"movie","title":"A Night Out"}],"source":"katalog"}""",
                    )
                    else -> respondJson("""{"product":"chino","items":[{"id":"s1","type":"series","title":"Night"}],"source":"katalog"}""")
                }
            }
        }

        val found = searchCatalogue(server.api, "night")

        // The people, then the films, then the series — the calls as before.
        assertEquals(
            listOf("/api/v1/search", "/api/v1/people", "/api/v1/items", "/api/v1/items"),
            server.requests.map { it.url.encodedPath },
        )
        val (people, movies, series) = server.requests.drop(1).map { it.url.parameters }
        assertEquals(listOf("night", "12"), listOf(people["q"], people["limit"]))
        assertEquals(listOf("night", "movie", "40"), listOf(movies["q"], movies["type"], movies["limit"]))
        assertEquals(listOf("night", "series", "40"), listOf(series["q"], series["type"], series["limit"]))
        // The films, then the series, each in the server's order.
        assertEquals(listOf("m1", "m2", "s1"), found.titles.getOrThrow().map { it.id })
        assertEquals(listOf("p1"), found.people.map { it.id })
    }

    @Test
    fun asBeforeAPeopleSearchThatFailsNeverBlanksTheTitles() = runTest {
        val server = FakeServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/search" -> respondText(notFound, HttpStatusCode.NotFound)
                "/api/v1/people" -> respondText("katalog: timeout", HttpStatusCode.BadGateway)
                else -> respondJson("""{"items":[{"id":"${request.url.parameters["type"]}-1","title":"Night"}]}""")
            }
        }

        val found = searchCatalogue(server.api, "night")

        assertEquals(listOf("movie-1", "series-1"), found.titles.getOrThrow().map { it.id })
        assertTrue(found.people.isEmpty())
    }

    @Test
    fun asBeforeTitlesThatFailAreAFailedSearchAndThePeopleStay() = runTest {
        val server = FakeServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/search" -> respondText(notFound, HttpStatusCode.NotFound)
                "/api/v1/people" -> respondJson("""{"people":[{"id":"p1","name":"Ada Night"}],"total":1}""")
                else -> respondJson("""{"product":"chino","error":"catalog unavailable"}""", HttpStatusCode.BadGateway)
            }
        }

        val found = searchCatalogue(server.api, "night")

        assertEquals(502, (found.titles.exceptionOrNull() as ApiStatusException).status)
        assertEquals(listOf("p1"), found.people.map { it.id })
    }

    @Test
    fun anyOtherFailureIsAFailedSearchNotTheSearchAsBefore() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.InternalServerError)) {
            val server = FakeServer { respondText("refused", status) }

            val found = searchCatalogue(server.api, "night")

            assertTrue(server.requests.all { it.url.encodedPath == "/api/v1/search" }, "searched as before after $status")
            assertEquals(status.value, (found.titles.exceptionOrNull() as ApiStatusException).status)
            assertTrue(found.people.isEmpty())
        }

        // search.go: the catalog failed — said as a failed search says it.
        val server = FakeServer {
            respondJson(
                """{"product":"chino","error":"catalog unavailable","detail":"katalog search: timeout"}""",
                HttpStatusCode.BadGateway,
            )
        }

        val found = searchCatalogue(server.api, "night")

        assertEquals(1, server.requests.size)
        assertEquals("The catalogue is unavailable right now (HTTP 502).", found.titles.exceptionOrNull()?.catalogueMessage())
        assertTrue(found.people.isEmpty())
    }

    @Test
    fun aSearchTheNextQueryCutsShortEndsQuietlyNotAsAFailedOne() = runTest {
        val asked = CompletableDeferred<Unit>()
        val server = FakeServer {
            asked.complete(Unit)
            awaitCancellation()
        }
        var found: SearchFound? = null

        val search = launch { found = searchCatalogue(server.api, "nig") }
        asked.await()
        search.cancelAndJoin()

        // Nothing came back: no failure to show over the next query's search.
        assertNull(found)
    }
}
