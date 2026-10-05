package cloud.nalet.chino.mobile.data.slots

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.FAKE_API_BASE
import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.respondJson
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A slot read from a fake chino-api, and an action sent — with the app's
 *  own client: its bearer, its base URL, its check of every answer. */
class SlotApiTest {
    @Test
    fun aSlotIsReadWithTheBearerAndItsRowsComeBackAsTheyCame() = runTest {
        val server = FakeServer(accessToken = { "tok-1" }) {
            respondJson("""[{"key":"example.find","kind":"link","label":"Find It","url":"/portal/app/example?q={q}","enabled":true}]""")
        }

        val rows = server.api.extensions(SLOT_SEARCH_EMPTY)

        val call = server.requests.single()
        assertEquals(HttpMethod.Get, call.method)
        assertEquals("https://media.example.com/api/v1/extensions?slot=search.empty", call.url.toString())
        assertEquals("Bearer tok-1", call.headers[HttpHeaders.Authorization])
        assertEquals(
            listOf("https://media.example.com/portal/app/example?q=night%20train"),
            slotButtons(rows, FAKE_API_BASE, mapOf("q" to "night train")).map { it.href },
        )
    }

    @Test
    fun anActionIsAPostWithTheBearerToExactlyWhereTheRowLeads() = runTest {
        val server = FakeServer(accessToken = { "tok-1" }) { respond("", HttpStatusCode.Accepted) }
        val href = slotActionUrl("/api/portal/apps/example/collect?q={q}", "POST", FAKE_API_BASE, mapOf("q" to "a b/c"))!!

        server.api.sendSlotAction(href)

        val call = server.requests.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("https://media.example.com/api/portal/apps/example/collect?q=a%20b%2Fc", call.url.toString())
        assertEquals("Bearer tok-1", call.headers[HttpHeaders.Authorization])
        assertEquals(0L, call.body.contentLength ?: 0L)
    }

    @Test
    fun aRedirectIsNotFollowedSoTheBearerGoesNowhereElse() = runTest {
        val server = FakeServer(accessToken = { "tok-1" }) {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/collect"))
        }

        val refused = assertFailsWith<ApiStatusException> {
            server.api.sendSlotAction("https://media.example.com/api/portal/apps/example/collect")
        }

        assertEquals(302, refused.status)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun anActionTheAddonRefusesFails() = runTest {
        val server = FakeServer { respondJson("""{"error":"nope"}""", HttpStatusCode.InternalServerError) }
        assertFailsWith<ApiStatusException> {
            server.api.sendSlotAction("https://media.example.com/api/portal/apps/example/collect")
        }
    }
}
