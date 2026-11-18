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
import kotlin.test.assertTrue

/**
 * chino-api's 401 `{"error":"account_deleted",…}` (section 16.5.6 of the
 * specification), through the app's own client: no fresh token is asked for
 * and nothing is sent again, the bearer the request carried is said
 * ([chinoApiClient]'s onAccountDeleted), and the screens read it as an
 * account deleted, not as a sign-in to renew. A 503 `account busy` is none of
 * that.
 */
class AccountDeletedAnswerTest {
    private val deletedBody =
        """{"error":"account_deleted","detail":"the account's deletion has begun: nothing more is kept for it"}"""

    private class Heard {
        var renewals = 0
        val deleted = mutableListOf<String>()
    }

    private fun server(heard: Heard, answer: (bearer: String?) -> Pair<HttpStatusCode, String>) = FakeServer(
        accessToken = { "tok-anna" },
        renewedToken = {
            heard.renewals++
            "tok-anna-renewed"
        },
        onAccountDeleted = { heard.deleted += it },
    ) { request ->
        val (status, body) = answer(request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer "))
        if (body.startsWith("{")) respondJson(body, status) else respondText(body, status)
    }

    @Test
    fun aWriteAnsweredAccountDeletedAsksForNoFreshTokenAndIsNotSentAgain() = runTest {
        val heard = Heard()
        val server = server(heard) { HttpStatusCode.Unauthorized to deletedBody }

        val e = assertFailsWith<ApiStatusException> { server.api.addLike("title-1") }

        assertEquals(401, e.status)
        assertTrue(e.accountDeleted)
        assertEquals(1, server.requests.size)
        assertEquals(0, heard.renewals)
        assertEquals(listOf("tok-anna"), heard.deleted)
        // Read as an account deleted: not a sign-in to renew or to ask for.
        assertEquals(LoadFailure.AccountDeleted, e.loadFailure(singleThing = false))
        assertFalse(e.signInRefused)
    }

    @Test
    fun aRequestWithAnAccountsOwnBearerSaysThatBearer() = runTest {
        val heard = Heard()
        val server = server(heard) { bearer ->
            if (bearer == "tok-ben") HttpStatusCode.Unauthorized to deletedBody else HttpStatusCode.OK to """{"watchlist_by_kind":true}"""
        }

        assertFailsWith<ApiStatusException> {
            server.api.changeAccountSettings(AccountSettingsChange(watchlistByKind = true), bearer = "tok-ben")
        }

        assertEquals(1, server.requests.size)
        assertEquals(0, heard.renewals)
        assertEquals(listOf("tok-ben"), heard.deleted)
    }

    @Test
    fun aPlainTextUnauthorizedIsRenewedOnceAndSentAgainAsBefore() = runTest {
        val heard = Heard()
        val server = server(heard) { bearer ->
            if (bearer == "tok-anna") HttpStatusCode.Unauthorized to "invalid token" else HttpStatusCode.NoContent to ""
        }

        server.api.addLike("title-1")

        assertEquals(1, heard.renewals)
        assertEquals(
            listOf("Bearer tok-anna", "Bearer tok-anna-renewed"),
            server.requests.map { it.headers[HttpHeaders.Authorization] },
        )
        assertTrue(heard.deleted.isEmpty())
    }

    @Test
    fun anUnauthorizedWithAnotherCodeIsNoAccountDeleted() = runTest {
        val heard = Heard()
        val server = server(heard) { HttpStatusCode.Unauthorized to """{"error":"token_audience"}""" }

        val e = assertFailsWith<ApiStatusException> { server.api.addLike("title-1") }

        assertFalse(e.accountDeleted)
        assertEquals(LoadFailure.SignedOut, e.loadFailure(singleThing = false))
        assertTrue(heard.deleted.isEmpty())
    }

    @Test
    fun aWriteThatWaitedOutAnOpenDeletionIsBusyAndSignsNobodyOut() = runTest {
        val heard = Heard()
        val server = FakeServer(
            accessToken = { "tok-anna" },
            renewedToken = {
                heard.renewals++
                null
            },
            onAccountDeleted = { heard.deleted += it },
        ) {
            respond(
                content = "account busy",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType to listOf("text/plain"), HttpHeaders.RetryAfter to listOf("5")),
            )
        }

        val e = assertFailsWith<ApiStatusException> { server.api.addLike("title-1") }

        assertEquals(503, e.status)
        assertFalse(e.accountDeleted)
        assertEquals(LoadFailure.Busy, e.loadFailure(singleThing = false))
        assertEquals(1, server.requests.size)
        assertEquals(0, heard.renewals)
        assertTrue(heard.deleted.isEmpty())
    }
}
