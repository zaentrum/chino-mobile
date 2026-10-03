package cloud.nalet.chino.mobile.data.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** GET /v1/people/{id} as people.go answers it: the biography follows the
 *  request's Accept-Language, an unknown id is a plain-text 404. */
class GetPersonTest {
    @Test
    fun theBiographyIsAskedForInTheDevicesLanguages() = runTest {
        val server = FakeServer {
            respondJson("""{"id":"p1","name":"Ada Example","has_profile":true,"profile_url":"/api/v1/people/p1/profile","biography":"Ada ist Schauspielerin.","biography_lang":"de","items":[]}""")
        }

        val person = server.api.getPerson("p1", limit = 100, acceptLanguage = "de-CH, de;q=0.9, en;q=0.8")

        val request = server.requests.single()
        assertEquals("/api/v1/people/p1", request.url.encodedPath)
        assertEquals("100", request.url.parameters["limit"])
        assertEquals("de-CH, de;q=0.9, en;q=0.8", request.headers[HttpHeaders.AcceptLanguage])
        assertEquals("de", person?.biographyLang)
        assertEquals(
            "https://media.example.com/api/v1/people/p1/profile?stream=tok",
            artworkUrl(FAKE_API_BASE, person?.profileUrl, "tok"),
        )
    }

    @Test
    fun noLanguagesNoHeader() = runTest {
        val server = FakeServer { respondJson("""{"id":"p1","name":"Ada Example","items":[]}""") }

        server.api.getPerson("p1", acceptLanguage = "")

        assertNull(server.requests.single().headers[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun anUnknownPersonIsNullNotAnError() = runTest {
        val server = FakeServer { respondText("person not found", HttpStatusCode.NotFound) }

        assertNull(server.api.getPerson("nobody"))
    }

    @Test
    fun aCatalogFailureThrows() = runTest {
        val server = FakeServer { respondText("katalog: timeout", HttpStatusCode.BadGateway) }

        val failure = assertFailsWith<ApiStatusException> { server.api.getPerson("p1") }

        assertEquals(502, failure.status)
    }
}
