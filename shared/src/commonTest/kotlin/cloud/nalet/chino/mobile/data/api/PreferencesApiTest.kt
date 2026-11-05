package cloud.nalet.chino.mobile.data.api

import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The account's settings, and a list by kind, against JSON shaped as chino-api
 *  writes it. */
class PreferencesApiTest {
    private fun bodyOf(content: OutgoingContent) = (content as TextContent).text

    @Test
    fun preferencesReadAsTheServerWritesThemAMissingKeyItsDefault() = runTest {
        assertTrue(FakeServer { respondJson("""{"watchlist_by_kind":true,"later":1}""") }.api.getPreferences().watchlistByKind)
        assertFalse(FakeServer { respondJson("{}") }.api.getPreferences().watchlistByKind)
    }

    @Test
    fun turningTheSettingOffSendsFalseNotAnEmptyBody() = runTest {
        // The server changes only the fields in the body: {} would leave it on.
        val server = FakeServer { respondJson("""{"watchlist_by_kind":false}""") }
        server.api.patchPreferences(PreferencesPatch(watchlistByKind = false))
        val request = server.requests.single()
        assertEquals("PATCH", request.method.value)
        assertTrue(request.url.encodedPath.endsWith("/v1/me/preferences"))
        assertEquals("""{"watchlist_by_kind":false}""", bodyOf(request.body))
    }

    @Test
    fun aListSaysItsCountsOnlyWhenAsked() = runTest {
        val server = FakeServer {
            respondJson(
                """{"lists":[
                    {"id":"l1","name":"Watchlist","itemCount":7,"isDefault":true,"counts":{"movie":4,"series":2}},
                    {"id":"l2","name":"Weekend","itemCount":0}
                ]}""",
            )
        }
        val lists = server.api.listWatchlists(withCounts = true).lists
        assertEquals("counts", server.requests.last().url.parameters["include"])
        assertEquals(WatchlistCounts(movie = 4, series = 2), lists[0].counts)
        assertNull(lists[1].counts)
        server.api.listWatchlists()
        assertNull(server.requests.last().url.parameters["include"])
    }

    @Test
    fun aListIsAskedForByKindOnlyWhenAKindIsGiven() = runTest {
        val server = FakeServer { respondJson("""{"id":"l1","name":"L","items":["m1"]}""") }
        assertEquals(listOf("m1"), server.api.getWatchlist("l1", "movie").items)
        assertEquals("movie", server.requests.last().url.parameters["kind"])
        server.api.getWatchlist("l1")
        assertNull(server.requests.last().url.parameters["kind"])
    }
}
