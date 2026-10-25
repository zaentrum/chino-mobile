package cloud.nalet.chino.mobile.data.downloads

import kotlinx.coroutines.test.runTest
import cloud.nalet.chino.mobile.data.api.chinoApiClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where the app opens: on the account's downloads when the server cannot
 *  be reached - after "Who's watching?" for several accounts - and nowhere
 *  without an account; as before otherwise. */
class StartScreenTest {
    private fun start(configured: Boolean = true, accounts: Int = 1, active: Boolean = true, reach: Reach, downloads: Boolean = true) =
        startScreen(configured, accounts, active, reach, downloads)

    @Test
    fun aServerThatCannotBeReachedOpensTheAccountsDownloads() {
        assertEquals(StartScreen.Downloads, start(reach = Reach.Unreachable))
        // Several accounts: each sees its own downloads - who is watching
        // first, which works without the server.
        assertEquals(StartScreen.AccountPickerThenDownloads, start(accounts = 3, reach = Reach.Unreachable))
        assertEquals(StartScreen.AccountPickerThenDownloads, start(accounts = 2, active = false, reach = Reach.Unreachable))
        // Nobody signed in has no downloads: the sign-in, which waits for the
        // server - no downloads screen of nobody's.
        assertEquals(StartScreen.SignIn, start(accounts = 0, active = false, reach = Reach.Unreachable))
    }

    @Test
    fun aServerThatAnswersOpensAsBefore() {
        for (reach in listOf(Reach.Reachable, Reach.Unknown)) {
            assertEquals(StartScreen.Home, start(reach = reach))
            assertEquals(StartScreen.AccountPicker, start(accounts = 2, reach = reach))
            assertEquals(StartScreen.SignIn, start(accounts = 0, active = false, reach = reach))
        }
    }

    @Test
    fun withoutAServerTheAddServerScreenWhateverTheReach() {
        assertEquals(StartScreen.AddServer, start(configured = false, accounts = 0, active = false, reach = Reach.Unreachable))
    }

    @Test
    fun aPlatformWithoutDownloadsOpensAsBeforeEvenWithoutTheServer() {
        assertEquals(StartScreen.Home, start(reach = Reach.Unreachable, downloads = false))
        assertEquals(StartScreen.SignIn, start(accounts = 0, active = false, reach = Reach.Unreachable, downloads = false))
    }
}

/** Whether the server answers, and what it says it offers. */
class ServerReachTest {
    @Test
    fun noConnectionIsUnreachableWithoutAskingTheServer() = runTest {
        var asked = 0
        val reach = ServerReach(fetch = { asked++; "{}" }, networkUp = { false })
        assertEquals(Reach.Unreachable, reach.check())
        assertEquals(0, asked)
        assertEquals(Reach.Unreachable, reach.state.value)
    }

    @Test
    fun noAnswerOrAnAnswerThatIsNoConfigIsUnreachable() = runTest {
        assertEquals(Reach.Unreachable, ServerReach(fetch = { throw IllegalStateException("timeout") }).check())
        assertEquals(Reach.Unreachable, ServerReach(fetch = { null }).check())
        // A captive portal's page.
        assertEquals(Reach.Unreachable, ServerReach(fetch = { "<html>sign in to the hotel wifi</html>" }).check())
    }

    @Test
    fun theServersFeaturesAreRead() = runTest {
        val off = ServerReach(fetch = { """{"product":"chino","features":{"feedback":true,"downloads":false}}""" })
        assertEquals(Reach.Reachable, off.check())
        assertEquals(ServerFeatures(downloads = false, feedback = true), off.features.value)
        // A server from before the switch: downloads on, as its default.
        val older = ServerReach(fetch = { """{"product":"chino","features":{"feedback":false}}""" })
        older.check()
        assertEquals(true, older.features.value?.downloads)
        val oldest = ServerReach(fetch = { """{"product":"chino"}""" })
        assertNull(oldest.features.value)
        oldest.check()
        assertEquals(true, oldest.features.value?.downloads)
    }

    @Test
    fun anyAnsweredRequestMakesTheServerReachableAtOnce() {
        val reach = ServerReach(fetch = { null })
        reach.report(false)
        // The first answer after a time out of reach: reachable, and says so.
        assertEquals(true, reach.requestAnswered())
        assertEquals(Reach.Reachable, reach.state.value)
        // Then nothing more to say.
        assertEquals(false, reach.requestAnswered())
        // Before the first look: reachable, but nothing waited for it.
        val fresh = ServerReach(fetch = { null })
        assertEquals(false, fresh.requestAnswered())
        assertEquals(Reach.Reachable, fresh.state.value)
    }

    @Test
    fun theApisClientTellsOfEveryAnswerA2xxOnly() = runTest {
        var answered = 0
        var status = io.ktor.http.HttpStatusCode.OK
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { respond("{}", status) }) {
            chinoApiClient("https://media.example.com/api/", { null }, { null }, onAnswered = { answered++ })
        }
        client.get("v1/me")
        assertEquals(1, answered)
        // A proxy's 502 or a refusal is no answer of the server's to count.
        status = io.ktor.http.HttpStatusCode.BadGateway
        runCatching { client.get("v1/me") }
        status = io.ktor.http.HttpStatusCode.NotFound
        runCatching { client.get("v1/me") }
        assertEquals(1, answered)
    }

    @Test
    fun anotherRequestsAnswerCounts() {
        val reach = ServerReach(fetch = { null })
        reach.report(false)
        assertEquals(Reach.Unreachable, reach.state.value)
        reach.report(true)
        assertEquals(Reach.Reachable, reach.state.value)
    }
}
