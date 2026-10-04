package cloud.nalet.chino.mobile.data.account

import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.forgetBearer
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Deleting an account against a fake chino-api (FakeServer: the app's own
 * client — its bearer, its check of every answer) answering as chino-api's
 * account.go does.
 */
class AccountDeletionTest {
    @Test
    fun a200MeansTheAccountAndItsDataAreDeleted() = runTest {
        val deleted = FakeServer(accessToken = { "tok-1" }) {
            respondJson("""{"account":"deleted","deleted":{"progress":12,"watched":30,"watchlists":2,"likes":4}}""")
        }
        assertEquals(AccountDeletion.Deleted, deleted.api.deleteAccount())
        // The account was gone already: now its data is too.
        val gone = FakeServer(accessToken = { "tok-1" }) { respondJson("""{"account":"gone","deleted":{}}""") }
        assertEquals(AccountDeletion.Deleted, gone.api.deleteAccount())
    }

    @Test
    fun theRequestIsADeleteOfMeWithTheBearerInTheHeaderNeverInTheUrl() = runTest {
        val server = FakeServer(accessToken = { "tok-secret" }) { respondJson("""{"account":"deleted"}""") }

        server.api.deleteAccount()

        val request = server.requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("https://media.example.com/api/v1/me", request.url.toString())
        assertEquals("Bearer tok-secret", request.headers[HttpHeaders.Authorization])
        assertTrue(request.url.parameters.isEmpty())
        assertFalse("tok-secret" in request.url.toString())
    }

    @Test
    fun theDeletionCarriesTheTokenOfTheAccountSignedInNow() = runTest {
        // The Auth plugin keeps the bearer it loaded first. Another account
        // signed in since (Switch account) must not have the first one
        // deleted.
        var active = "tok-first"
        val server = FakeServer(accessToken = { active }) { request ->
            if (request.method == HttpMethod.Delete) respondJson("""{"account":"deleted"}""")
            else respondJson("""{"items":[]}""")
        }
        server.api.listItems()
        active = "tok-now"

        server.api.deleteAccount()

        assertEquals(
            listOf("Bearer tok-first", "Bearer tok-now"),
            server.requests.map { it.headers[HttpHeaders.Authorization] },
        )
    }

    @Test
    fun afterTheDeletionTheNextRequestCarriesTheNextAccountsToken() = runTest {
        // What forgetDeletedAccount does once the account is gone: the
        // deleted account's bearer is not sent again, whoever comes next.
        var active = "tok-deleted"
        val server = FakeServer(accessToken = { active }) { request ->
            if (request.method == HttpMethod.Delete) respondJson("""{"account":"deleted"}""")
            else respondJson("""{"items":[]}""")
        }
        server.api.deleteAccount()
        active = "tok-next"
        server.http.forgetBearer()

        server.api.listItems()

        assertEquals("Bearer tok-next", server.requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun a409IsARefusalInTheServersWords() = runTest {
        val server = FakeServer {
            respondJson(
                """{"error":"refused","message":"You are the last admin of this server: make someone else an admin first."}""",
                HttpStatusCode.Conflict,
            )
        }
        val refused = server.api.deleteAccount()
        assertEquals(
            AccountDeletion.Refused("You are the last admin of this server: make someone else an admin first."),
            refused,
        )
        assertEquals("Not Deleted", refused.title)
        assertTrue(refused.isFinal)

        // No message to show: still a refusal, said plainly.
        val bare = FakeServer { respond("", HttpStatusCode.Conflict) }
        assertEquals(AccountDeletion.Refused("This server won't delete your account."), bare.api.deleteAccount())
    }

    @Test
    fun a501IsNotAvailableOnThisServerWhateverTheBodySays() = runTest {
        val server = FakeServer {
            respondJson(
                """{"error":"account_deletion_unavailable","message":"Accounts are not deleted from the apps on this server: ask whoever runs it."}""",
                HttpStatusCode.NotImplemented,
            )
        }
        val unavailable = server.api.deleteAccount()
        assertEquals(AccountDeletion.Unavailable, unavailable)
        assertEquals("Not Available", unavailable.title)
        assertEquals("Deleting your account isn't available on this server — ask its administrator.", unavailable.text)
        assertTrue(unavailable.isFinal)
    }

    @Test
    fun a502DeletesNothingTryAgainLaterWithTheServersMessage() = runTest {
        val server = FakeServer {
            respondJson(
                """{"error":"account_not_deleted","message":"Your account could not be deleted right now, so nothing was. Try again later."}""",
                HttpStatusCode.BadGateway,
            )
        }
        val failed = server.api.deleteAccount()
        assertEquals(
            AccountDeletion.Failed("Your account could not be deleted right now, so nothing was. Try again later."),
            failed,
        )
        assertEquals("Try Again Later", failed.title)
        assertFalse(failed.isFinal)
    }

    @Test
    fun anyOtherAnswerFailsTooAndOnlyAJsonMessageIsShown() = runTest {
        val dataNotDeleted = FakeServer {
            respondJson(
                """{"error":"data_not_deleted","message":"Your data could not be deleted, so nothing was. Try again later."}""",
                HttpStatusCode.InternalServerError,
            )
        }
        assertEquals(
            AccountDeletion.Failed("Your data could not be deleted, so nothing was. Try again later."),
            dataNotDeleted.api.deleteAccount(),
        )
        // A proxy's page, a plain-text error: no server words.
        val proxy = FakeServer {
            respond("<html><body>502 Bad Gateway</body></html>", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        assertEquals(AccountDeletion.Failed("Your account couldn't be deleted right now (HTTP 502)."), proxy.api.deleteAccount())
        val plain = FakeServer { respondText("upstream connect error\n", HttpStatusCode.ServiceUnavailable) }
        assertEquals(AccountDeletion.Failed("Your account couldn't be deleted right now (HTTP 503)."), plain.api.deleteAccount())
    }

    @Test
    fun aSignInTheServerNoLongerTakesEvenRenewedIsAFailure() = runTest {
        val server = FakeServer(accessToken = { "old" }, renewedToken = { null }) {
            respondText("unauthorized\n", HttpStatusCode.Unauthorized)
        }
        assertEquals(
            AccountDeletion.Failed("This server no longer accepts your sign-in. Sign in again, then try once more."),
            server.api.deleteAccount(),
        )
    }

    @Test
    fun aSignInTheAuthPluginRenewsStillDeletes() = runTest {
        val server = FakeServer(accessToken = { "old" }, renewedToken = { "new" }) { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer new") respondJson("""{"account":"deleted"}""")
            else respondText("token expired\n", HttpStatusCode.Unauthorized)
        }
        assertEquals(AccountDeletion.Deleted, server.api.deleteAccount())
        assertEquals(listOf("Bearer old", "Bearer new"), server.requests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun onlyA200SignsOutAnother2xxIsNoProof() = runTest {
        for (status in listOf(HttpStatusCode.Accepted, HttpStatusCode.NoContent)) {
            val server = FakeServer { respond("", status) }
            assertIs<AccountDeletion.Failed>(server.api.deleteAccount(), status.toString())
        }
    }

    @Test
    fun aRequestThatNeverReachesTheServerIsAFailureNotAThrow() = runTest {
        val offline = FakeServer { throw RuntimeException("connect timed out") }
        assertEquals(AccountDeletion.Failed("The server couldn't be reached."), offline.api.deleteAccount())
    }

    @Test
    fun theAccountIsSignedOutOnlyOnceTheServerSaysItIsGone() = runTest {
        suspend fun signOuts(server: FakeServer): Int {
            var count = 0
            server.api.deleteAccountThenSignOut { count++ }
            return count
        }
        assertEquals(1, signOuts(FakeServer { respondJson("""{"account":"deleted"}""") }))
        assertEquals(0, signOuts(FakeServer { respondJson("""{"error":"refused","message":"No."}""", HttpStatusCode.Conflict) }))
        assertEquals(0, signOuts(FakeServer { respond("", HttpStatusCode.NotImplemented) }))
        assertEquals(0, signOuts(FakeServer { respondJson("""{"error":"account_not_deleted"}""", HttpStatusCode.BadGateway) }))
        assertEquals(0, signOuts(FakeServer { respond("", HttpStatusCode.InternalServerError) }))
        assertEquals(0, signOuts(FakeServer { throw RuntimeException("offline") }))
    }

    @Test
    fun aLongServerMessageIsCutToAFewSentences() = runTest {
        val server = FakeServer { respondJson("""{"error":"refused","message":"${"x".repeat(1000)}"}""", HttpStatusCode.Conflict) }
        val refused = assertIs<AccountDeletion.Refused>(server.api.deleteAccount())
        assertEquals(300, refused.message.length)
    }
}
