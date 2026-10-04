package cloud.nalet.chino.mobile.data.api

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Every non-2xx answer from chino-api is an [ApiStatusException] with the
 * server's message — for every call, from the client itself — not a body
 * that fails to decode (or decodes into defaults).
 */
class ApiErrorsTest {
    @Test
    fun aDuplicateListIsRefusedWithTheServersReasonNotADecodeError() = runTest {
        // watchlists.go: http.Error(w, "name exists", 409) — plain text.
        val server = FakeServer { respondText("name exists\n", HttpStatusCode.Conflict) }

        val failure = assertFailsWith<ApiStatusException> { server.api.createWatchlist("Weekend") }

        assertEquals(409, failure.status)
        assertEquals("name exists", failure.serverMessage)
        assertEquals("/api/v1/me/watchlists", failure.path)
        assertEquals("A list with that name already exists.", failure.watchlistRefusal())
    }

    @Test
    fun theOtherRefusalsOfAListInWords() = runTest {
        val tooMany = FakeServer { respondText("too many lists\n", HttpStatusCode.Conflict) }
        assertEquals(
            "You've reached the maximum number of lists.",
            assertFailsWith<ApiStatusException> { tooMany.api.createWatchlist("x") }.watchlistRefusal(),
        )
        val badName = FakeServer { respondText("name must be 1..60 chars\n", HttpStatusCode.BadRequest) }
        assertEquals(
            "Enter a name between 1 and 60 characters.",
            assertFailsWith<ApiStatusException> { badName.api.renameWatchlist("l1", " ") }.watchlistRefusal(),
        )
        // Not a refusal the server explains: the screen's own retry hint.
        assertNull(ApiStatusException(500, "/api/v1/me/watchlists", "db: timeout").watchlistRefusal())
        assertNull(RuntimeException("offline").watchlistRefusal())
    }

    @Test
    fun aRefusedDeleteFailsInsteadOfPassingForDone() = runTest {
        // Deleting the default list: 409 "cannot delete default". With no body
        // to decode it used to pass for done, and the list left the screen.
        val server = FakeServer { respondText("cannot delete default\n", HttpStatusCode.Conflict) }

        val failure = assertFailsWith<ApiStatusException> { server.api.deleteWatchlist("default") }

        assertEquals("cannot delete default", failure.serverMessage)
    }

    @Test
    fun writesThatFailSaySo() = runTest {
        val server = FakeServer { respondText("db: deadlock\n", HttpStatusCode.InternalServerError) }

        assertFailsWith<ApiStatusException> {
            server.api.postProgress("m1", ProgressBody(positionSec = 600, durationSec = 5400))
        }
        assertFailsWith<ApiStatusException> { server.api.postWatched("m1") }
        assertFailsWith<ApiStatusException> { server.api.addToWatchlist("m1") }
    }

    @Test
    fun aSavedPositionThatCouldNotBeReadIsNotZero() = runTest {
        // A JSON error decoded into ProgressResponse's defaults reads as
        // position 0, "never watched" — and a player would then overwrite
        // the viewer's place. It is an error.
        val server = FakeServer { respondJson("""{"error":"upstream unavailable"}""", HttpStatusCode.ServiceUnavailable) }

        val failure = assertFailsWith<ApiStatusException> { server.api.getProgress("m1") }

        assertEquals("upstream unavailable", failure.serverMessage)
    }

    @Test
    fun aJsonErrorSaysItsErrorField() = runTest {
        val server = FakeServer {
            respondJson(
                """{"product":"chino","error":"catalog unavailable","detail":"katalog request: timeout"}""",
                HttpStatusCode.BadGateway,
            )
        }

        val failure = assertFailsWith<ApiStatusException> { server.api.getItem("m1") }

        assertEquals("catalog unavailable", failure.serverMessage)
        assertEquals("/api/v1/items/m1: HTTP 502 — catalog unavailable", failure.message)
    }

    @Test
    fun aProxysPageOrAnEmptyBodySaysNothing() = runTest {
        val html = FakeServer {
            respond("<html><body>502 Bad Gateway</body></html>", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        assertNull(assertFailsWith<ApiStatusException> { html.api.getItem("m1") }.serverMessage)

        val empty = FakeServer { respond("", HttpStatusCode.NotFound) }
        val failure = assertFailsWith<ApiStatusException> { empty.api.getItem("m1") }
        assertNull(failure.serverMessage)
        assertEquals("/api/v1/items/m1: HTTP 404", failure.message)
    }

    @Test
    fun theMessageNeverCarriesTheStreamToken() = runTest {
        val server = FakeServer { respondText("no trickplay\n", HttpStatusCode.NotFound) }

        val failure = assertFailsWith<ApiStatusException> { server.api.trickplayVtt("m1", streamToken = "tok-secret") }

        assertEquals("/api/v1/items/m1/play/trickplay/thumbnails.vtt", failure.path)
        assertFalse("tok-secret" in failure.message.orEmpty())
    }

    @Test
    fun aSuccessWithoutABodyIsUntouched() = runTest {
        val server = FakeServer { respond("", HttpStatusCode.NoContent) }

        server.api.postWatched("m1")
        server.api.addItemToList("l1", "m1")

        assertEquals(2, server.requests.size)
    }

    @Test
    fun aSignInTheAuthPluginRenewsIsNoError() = runTest {
        // The check sees the answer after the Auth plugin's refresh-and-retry.
        val server = FakeServer(accessToken = { "old" }, renewedToken = { "new" }) { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer new") respondJson("""{"position_sec":1834}""")
            else respondText("no subject\n", HttpStatusCode.Unauthorized)
        }

        assertEquals(1834, server.api.getProgress("m1").positionSec)
        assertEquals(listOf("Bearer old", "Bearer new"), server.requests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun aSignInThatCannotBeRenewedIsA401() = runTest {
        val server = FakeServer(accessToken = { "old" }, renewedToken = { null }) {
            respondText("no subject\n", HttpStatusCode.Unauthorized)
        }

        val failure = assertFailsWith<ApiStatusException> { server.api.me() }

        assertEquals(401, failure.status)
        assertEquals("This server no longer accepts your sign-in. Sign in again.", failure.catalogueMessage())
    }

    @Test
    fun theBugReportDialogStillTellsARateLimitFromNoSetup() = runTest {
        val limited = FakeServer { respondText("rate limited\n", HttpStatusCode.TooManyRequests) }
        val failure = assertFailsWith<ApiStatusException> {
            limited.api.submitFeedback(FeedbackReport(source = "mobile", kind = "manual", description = "x"))
        }
        assertEquals(429, failure.status)
    }
}
