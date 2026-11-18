package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.respondJson
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A press - a like, the watchlist flag, a title put in a list, a list made
 * or renamed or deleted - is the account's signed in as it is made: its write
 * goes with that account's own token, and is not sent once another account
 * is signed in, nor as that one; with nobody signed in, nothing is.
 */
class PressGoesForItsAccountTest {
    private val dispatcher = StandardTestDispatcher()

    /** The account signed in now. */
    private var account: String? = "first"

    /** The client holds the token of whoever is signed in. */
    private val server = FakeServer(accessToken = { account?.let { "token-of-$it" } }, dispatcher = dispatcher) { request ->
        if (request.method == HttpMethod.Post || request.method == HttpMethod.Patch) {
            respondJson("""{"id":"new","name":"Later","itemCount":0,"createdAt":"2026-11-18T10:00:00Z"}""", HttpStatusCode.Created)
        } else {
            respond("", HttpStatusCode.NoContent)
        }
    }

    /** An account's own token while it is the one signed in. */
    private val bearerOf: suspend (String) -> String? = { id -> "token-of-$id".takeIf { account == id } }

    private fun sent() = server.requests.map { "${it.method.value} ${it.url.encodedPath} ${it.headers[HttpHeaders.Authorization]}" }

    @Test
    fun aLikePressedForOneAccountIsNotSentForTheOneSignedInAfterIt() = runTest(dispatcher) {
        val flags = UserFlagsRepository(server.api, this, accountNow = { account }, bearerOf = bearerOf)

        flags.setLike("film", true)
        flags.setWatchlist("film", true)
        // Before the writes go, that account leaves and another is signed in.
        account = "second"
        advanceUntilIdle()
        assertTrue(server.requests.isEmpty(), "sent: ${sent()}")

        // Pressed for that one now: sent as it, with its own token.
        flags.setLike("film", true)
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/v1/me/likes/film Bearer token-of-second"), sent())
    }

    @Test
    fun withNobodySignedInAPressWritesNothing() = runTest(dispatcher) {
        val flags = UserFlagsRepository(server.api, this, accountNow = { account }, bearerOf = bearerOf)
        account = null

        flags.setLike("film", true)
        advanceUntilIdle()

        assertTrue(server.requests.isEmpty())
        assertTrue(flags.likes.value.isEmpty())
    }

    @Test
    fun aListsWritesGoWithTheTokenOfTheAccountTheyAreMadeFor() = runTest(dispatcher) {
        val lists = WatchlistsRepository(server.api, this, accountNow = { account }, bearerOf = bearerOf)

        lists.create("Later")
        lists.rename("new", "Soon")
        lists.setMembership("film", "new", true)
        advanceUntilIdle()
        // A title put in it for the first account, and another signed in
        // before the write goes: not sent.
        lists.setMembership("show", "new", true)
        account = "second"
        advanceUntilIdle()
        lists.delete("new")

        assertEquals(
            listOf(
                "POST /api/v1/me/watchlists Bearer token-of-first",
                "PATCH /api/v1/me/watchlists/new Bearer token-of-first",
                "PUT /api/v1/me/watchlists/new/items/film Bearer token-of-first",
                "DELETE /api/v1/me/watchlists/new Bearer token-of-second",
            ),
            sent(),
        )
        // Nobody signed in: a list made now is nobody's, and not sent.
        account = null
        assertFailsWith<NotSignedInNow> { lists.create("Again") }
        assertEquals(4, server.requests.size)
    }
}
