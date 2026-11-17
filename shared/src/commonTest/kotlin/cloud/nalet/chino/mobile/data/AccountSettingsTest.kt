package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import cloud.nalet.chino.mobile.ui.settings.WatchlistGroupState
import cloud.nalet.chino.mobile.ui.settings.WatchlistSwitch
import cloud.nalet.chino.mobile.ui.settings.settingNotSavedText
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Separate movies and shows (changes 16.C25 and 8.C18 of the specification):
 * the account's, read and saved through /api/v1/me/settings with the bearer
 * of the account it is of, against a fake chino-api with the app's own
 * client. The shared client's bearer is another account's throughout, so a
 * request that went out with it would show.
 */
class AccountSettingsTest {
    /** Two accounts on one phone, the one signed in in [active]; a bearer
     *  goes out for an account only while it is the active one. */
    private class Phone {
        val active = MutableStateFlow<String?>("anna")
        val tokens = mapOf("anna" to "tok-anna", "ben" to "tok-ben")
        val device = MemoryKnownChoices()
        val bearerOf: suspend (String) -> String? = { id -> tokens[id].takeIf { active.value == id } }
    }

    private fun TestScope.settings(phone: Phone, server: FakeServer, scope: CoroutineScope = backgroundScope) =
        AccountSettings(server.api, phone.active, { phone.active.value }, phone.bearerOf, scope, phone.device)

    /** The same, with [bearerOf] in the phone's place: a sign-in whose
     *  renewal fails, or is refused. */
    private fun TestScope.settingsWith(phone: Phone, server: FakeServer, bearerOf: suspend (String) -> String?) =
        AccountSettings(server.api, phone.active, { phone.active.value }, bearerOf, backgroundScope, phone.device)

    /** chino-api's settings route: GET answers [kept], PATCH sets it from the
     *  body and answers it - or [patch] where given. */
    private class SettingsRoute(var kept: Boolean = false) {
        var get: (suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData)? = null
        var patch: (suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData)? = null

        suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
            if (request.url.encodedPath != "/api/v1/me/settings") return respondText("not found", HttpStatusCode.NotFound)
            return when (request.method) {
                HttpMethod.Get -> get?.invoke(this, request) ?: respondJson("""{"watchlist_by_kind":$kept}""")
                HttpMethod.Patch -> patch?.invoke(this, request) ?: run {
                    kept = bodyOf(request).contains("true")
                    respondJson("""{"watchlist_by_kind":$kept}""")
                }
                else -> respondText("method not allowed", HttpStatusCode.MethodNotAllowed)
            }
        }
    }

    private fun TestScope.server(route: SettingsRoute) =
        FakeServer(accessToken = { "tok-shared" }, dispatcher = StandardTestDispatcher(testScheduler)) { request -> with(route) { answer(request) } }

    private val FakeServer.patches get() = requests.filter { it.method == HttpMethod.Patch }

    @Test
    fun theChoiceIsReadAndSavedThroughTheAccountsSettings() = runTest {
        val phone = Phone()
        val route = SettingsRoute(kept = true)
        val server = server(route)
        val settings = settings(phone, server)

        assertEquals(AccountSetting("anna", true), settings.read())
        val get = server.requests.single()
        assertEquals(HttpMethod.Get, get.method)
        assertEquals("https://media.example.com/api/v1/me/settings", get.url.toString())
        assertEquals("Bearer tok-anna", get.headers[HttpHeaders.Authorization])
        runCurrent()
        assertEquals(AccountSetting("anna", true), settings.setting.value)

        assertEquals(SettingSave.Saved, settings.save(on = false, under = "anna"))
        val patch = server.patches.single()
        assertEquals("https://media.example.com/api/v1/me/settings", patch.url.toString())
        assertEquals("""{"watchlist_by_kind":false}""", bodyOf(patch))
        assertEquals("Bearer tok-anna", patch.headers[HttpHeaders.Authorization])
        runCurrent()
        assertEquals(AccountSetting("anna", false), settings.setting.value)
        // The phone keeps a copy of the account's last answer.
        assertEquals(false, phone.device.watchlistByKind("anna"))
    }

    @Test
    fun whileItIsReadTheSwitchIsBusyAndShowsTheChoiceLastKnownOnThePhone() = runTest {
        val phone = Phone()
        phone.device.keepWatchlistByKind("anna", true)
        val answer = CompletableDeferred<Unit>()
        val route = SettingsRoute(kept = false).apply {
            get = {
                answer.await()
                respondJson("""{"watchlist_by_kind":false}""")
            }
        }
        val settings = settings(phone, server(route))
        val switch = WatchlistSwitch(settings, backgroundScope)

        val read = async { settings.read() }
        runCurrent()
        assertEquals(AccountSetting("anna", true, reading = true), settings.setting.value)
        assertEquals(WatchlistGroupState("anna", on = true, busy = true), switch.state.value)

        answer.complete(Unit)
        assertEquals(AccountSetting("anna", false), read.await())
        runCurrent()
        assertEquals(WatchlistGroupState("anna", on = false, busy = false), switch.state.value)
    }

    @Test
    fun withNothingKnownOnThePhoneTheSwitchShowsOffWhileItIsRead() = runTest {
        val phone = Phone()
        val answer = CompletableDeferred<Unit>()
        val route = SettingsRoute(kept = true).apply {
            get = {
                answer.await()
                respondJson("""{"watchlist_by_kind":true}""")
            }
        }
        val settings = settings(phone, server(route))

        val read = async { settings.read() }
        runCurrent()
        assertEquals(AccountSetting("anna", false, reading = true), settings.setting.value)
        answer.complete(Unit)
        read.await()
        runCurrent()
        assertEquals(AccountSetting("anna", true), settings.setting.value)
    }

    @Test
    fun whereTheReadFailedTheSwitchShowsTheChoiceLastKnownAndWorksAndTheListsFollowIt() = runTest {
        val phone = Phone()
        phone.device.keepWatchlistByKind("anna", true)
        val route = SettingsRoute().apply { get = { respondText("catalog unavailable", HttpStatusCode.BadGateway) } }
        val server = server(route)
        val settings = settings(phone, server)
        val switch = WatchlistSwitch(settings, backgroundScope)

        assertEquals(AccountSetting("anna", true), settings.read())
        runCurrent()
        // No banner for a read that failed, and the switch takes a press.
        assertEquals(WatchlistGroupState("anna", on = true, busy = false), switch.state.value)
        // The lists follow that choice until it is read again.
        assertEquals("anna", settings.split(offered = true, fresh = true)?.accountId)

        switch.press(false)
        runCurrent()
        assertEquals("""{"watchlist_by_kind":false}""", bodyOf(server.patches.single()))
        assertEquals(WatchlistGroupState("anna", on = false, busy = false), switch.state.value)
    }

    @Test
    fun anotherAccountChosenShowsItsOwnChoiceAtOnceNeverTheOneBefores() = runTest {
        val phone = Phone()
        val route = SettingsRoute(kept = true)
        val settings = settings(phone, server(route))
        settings.read()
        runCurrent()
        assertEquals(AccountSetting("anna", true), settings.setting.value)

        phone.active.value = "ben"
        runCurrent()
        // Ben's own: nothing known of his, so off - not Anna's.
        assertEquals(AccountSetting("ben", false), settings.setting.value)
        assertEquals(AccountSetting("ben", false), settings.current())
        assertNull(settings.split(offered = true, fresh = false))

        // Back to Anna: hers again.
        phone.active.value = "anna"
        runCurrent()
        assertEquals(AccountSetting("anna", true), settings.setting.value)
    }

    @Test
    fun aReadAnsweredAfterAnotherAccountWasChosenIsNotShown() = runTest {
        val phone = Phone()
        val answer = CompletableDeferred<Unit>()
        val route = SettingsRoute().apply {
            get = {
                answer.await()
                respondJson("""{"watchlist_by_kind":true}""")
            }
        }
        val server = server(route)
        val settings = settings(phone, server)

        val read = async { settings.read() }
        runCurrent()
        phone.active.value = "ben"
        answer.complete(Unit)

        // Followed by no one, and shown to no one but the account it is of.
        assertNull(read.await())
        runCurrent()
        assertEquals(AccountSetting("ben", false), settings.setting.value)
        assertEquals("Bearer tok-anna", server.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun aSaveGoesWithTheBearerOfTheAccountWhoseChoiceTheSwitchShowed() = runTest {
        val phone = Phone()
        val route = SettingsRoute(kept = false)
        val server = server(route)
        val settings = settings(phone, server)
        val switch = WatchlistSwitch(settings, backgroundScope)
        runCurrent()

        switch.press(true)
        runCurrent()

        val patch = server.patches.single()
        assertEquals("Bearer tok-anna", patch.headers[HttpHeaders.Authorization])
        assertEquals("""{"watchlist_by_kind":true}""", bodyOf(patch))
        assertEquals(WatchlistGroupState("anna", on = true, busy = false), switch.state.value)
    }

    @Test
    fun aPressAfterAnotherAccountWasChosenSendsNothing() = runTest {
        val phone = Phone()
        val server = server(SettingsRoute(kept = false))
        val settings = settings(phone, server)
        val switch = WatchlistSwitch(settings, backgroundScope)
        runCurrent()
        assertEquals("anna", switch.state.value?.accountId)

        // Ben chosen, and the press lands before the screen has heard it.
        phone.active.value = "ben"
        switch.press(true)
        runCurrent()

        assertTrue(server.patches.isEmpty())
        assertEquals(SettingSave.NotSent, settings.save(on = true, under = "anna"))
        assertTrue(server.patches.isEmpty())
        assertEquals(WatchlistGroupState("ben", on = false, busy = false), switch.state.value)
    }

    @Test
    fun aSaveAnsweredAfterAnotherAccountWasChosenIsNotShownForIt() = runTest {
        val phone = Phone()
        val answer = CompletableDeferred<Unit>()
        val route = SettingsRoute().apply {
            patch = {
                answer.await()
                respondJson("""{"watchlist_by_kind":true}""")
            }
        }
        val server = server(route)
        val settings = settings(phone, server)
        val switch = WatchlistSwitch(settings, backgroundScope)
        runCurrent()

        switch.press(true)
        runCurrent()
        phone.active.value = "ben"
        answer.complete(Unit)
        runCurrent()

        assertEquals("Bearer tok-anna", server.patches.single().headers[HttpHeaders.Authorization])
        assertEquals(WatchlistGroupState("ben", on = false, busy = false), switch.state.value)
        // Anna's answer is Anna's: hers when she is chosen again.
        phone.active.value = "anna"
        runCurrent()
        assertEquals(WatchlistGroupState("anna", on = true, busy = false), switch.state.value)
    }

    @Test
    fun aFailedSavePutsTheSwitchBackAndSaysWhyByItsStatusUntilTheSwitchIsUsedAgain() = runTest {
        val phone = Phone()
        val route = SettingsRoute(kept = false).apply {
            patch = { respondJson("""{"error":"watchlist_by_kind_invalid","detail":"watchlist_by_kind takes true, false or null"}""", HttpStatusCode.BadRequest) }
        }
        val server = server(route)
        val settings = settings(phone, server)
        val switch = WatchlistSwitch(settings, backgroundScope)
        settings.read()
        runCurrent()

        switch.press(true)
        runCurrent()

        val shown = assertNotNull(switch.state.value)
        assertFalse(shown.on)
        assertFalse(shown.busy)
        assertEquals(LoadFailure.NotAccepted, shown.failure)
        val text = settingNotSavedText(shown.failure!!)
        assertEquals("Couldn't save the setting. The server didn't accept the request. Check for an app update.", text)
        assertFalse(text.contains("watchlist_by_kind"))

        // Used again: the banner goes.
        route.patch = null
        switch.press(true)
        runCurrent()
        assertEquals(WatchlistGroupState("anna", on = true, busy = false), switch.state.value)
    }

    @Test
    fun aSaveThatGotNoAnswerSaysTheServerCantBeReached() = runTest {
        val phone = Phone()
        val route = SettingsRoute().apply { patch = { throw kotlinx.io.IOException("connection reset") } }
        val settings = settings(phone, server(route))

        val result = settings.save(on = true, under = "anna")

        assertEquals(SettingSave.Failed(LoadFailure.NoAnswer), result)
        assertEquals("Couldn't save the setting. Can't reach the server.", settingNotSavedText(LoadFailure.NoAnswer))
    }

    @Test
    fun aBusySwitchShowsItsChoiceAndTakesNoPress() = runTest {
        val phone = Phone()
        val answer = CompletableDeferred<Unit>()
        val route = SettingsRoute().apply {
            get = {
                answer.await()
                respondJson("""{"watchlist_by_kind":false}""")
            }
            patch = {
                answer.await()
                respondJson("""{"watchlist_by_kind":true}""")
            }
        }
        val server = server(route)
        val settings = settings(phone, server)
        val switch = WatchlistSwitch(settings, backgroundScope)

        // Being read: a press sends nothing.
        val read = async { settings.read() }
        runCurrent()
        switch.press(true)
        runCurrent()
        assertTrue(server.patches.isEmpty())
        answer.complete(Unit)
        read.await()
        runCurrent()

        // Being saved: shows the choice, and a second press sends nothing.
        val held = CompletableDeferred<Unit>()
        route.patch = {
            held.await()
            respondJson("""{"watchlist_by_kind":true}""")
        }
        switch.press(true)
        runCurrent()
        assertEquals(WatchlistGroupState("anna", on = true, busy = true), switch.state.value)
        switch.press(false)
        runCurrent()
        assertEquals(1, server.patches.size)
        held.complete(Unit)
        runCurrent()
        assertEquals(WatchlistGroupState("anna", on = true, busy = false), switch.state.value)
    }

    @Test
    fun theListsAreSplitOnlyWhereTheServerFillsThemByKindAndTheChoiceIsOn() = runTest {
        val phone = Phone()
        val route = SettingsRoute(kept = true)
        val server = server(route)
        val settings = settings(phone, server)

        // A server without lists by kind: as with the setting off, and the
        // choice isn't even asked for.
        assertNull(settings.split(offered = false, fresh = true))
        assertTrue(server.requests.isEmpty())

        val split = assertNotNull(settings.split(offered = true, fresh = true))
        assertEquals("anna", split.accountId)
        assertEquals("tok-anna", split.bearer())
        assertTrue(split.isActive())
        // Another account chosen: the split's bearer goes nowhere.
        phone.active.value = "ben"
        assertNull(split.bearer())
        assertFalse(split.isActive())

        phone.active.value = "anna"
        route.kept = false
        assertNull(settings.split(offered = true, fresh = true))
    }

    @Test
    fun aReadAnsweredWithTheChoiceBeforeASaveLeavesTheSavesChoiceShownAndKept() = runTest {
        val phone = Phone()
        val readGoesOn = CompletableDeferred<Unit>()
        val route = SettingsRoute(kept = true).apply {
            get = {
                // Answered with the choice as it was before the save.
                val before = kept
                readGoesOn.await()
                respondJson("""{"watchlist_by_kind":$before}""")
            }
        }
        val settings = settings(phone, server(route))

        val read = async { settings.read() }
        runCurrent()
        assertEquals(SettingSave.Saved, settings.save(on = false, under = "anna"))
        readGoesOn.complete(Unit)
        assertEquals(AccountSetting("anna", false), read.await())
        runCurrent()

        assertEquals(false, phone.device.watchlistByKind("anna"))
        assertEquals(AccountSetting("anna", false), settings.current())
    }

    @Test
    fun aPressThenSettingsLeftWhileItIsSavedTheSaveStillLands() = runTest {
        val phone = Phone()
        val held = CompletableDeferred<Unit>()
        val route = SettingsRoute(kept = true).apply {
            patch = {
                held.await()
                kept = false
                respondJson("""{"watchlist_by_kind":false}""")
            }
        }
        val server = server(route)
        // The account settings' scope is the app's; the switch's, the screen's.
        val settings = settings(phone, server)
        val screen = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val switch = WatchlistSwitch(settings, screen)
        runCurrent()

        switch.press(false)
        runCurrent()
        assertEquals(1, server.patches.size)
        // Settings left while it is saved.
        screen.cancel()
        held.complete(Unit)
        runCurrent()

        assertEquals(false, phone.device.watchlistByKind("anna"))
        assertEquals(AccountSetting("anna", false), settings.current())
    }

    @Test
    fun homesWarmReadsTheChoiceOncePerAccountAndSettingsOpenOnIt() = runTest {
        val phone = Phone()
        val server = server(SettingsRoute(kept = true))
        val settings = settings(phone, server)

        settings.warm()
        assertEquals(1, server.requests.size)
        runCurrent()
        // Settings opened now: the group's first state is the account's own.
        assertEquals(WatchlistGroupState("anna", on = true, busy = false), WatchlistSwitch(settings, backgroundScope).state.value)
        // Asked once in the process.
        settings.warm()
        assertEquals(1, server.requests.size)
    }

    @Test
    fun asKnownNowTheListsFollowTheChoiceHeldAndNothingIsAsked() = runTest {
        val phone = Phone()
        phone.device.keepWatchlistByKind("anna", true)
        val server = server(SettingsRoute(kept = false))
        val settings = settings(phone, server)

        // What the server offers not said yet: every list whole.
        assertNull(settings.splitNow(offered = false))
        // Said: the choice last known on the phone, until it is read.
        assertEquals("anna", settings.splitNow(offered = true)?.accountId)
        assertTrue(server.requests.isEmpty())
        settings.read()
        assertNull(settings.splitNow(offered = true))
    }

    @Test
    fun aReadOrASaveStillOutWhenTheAccountLeftThePhoneKeepsNothingForIt() = runTest {
        val phone = Phone()
        val goOn = CompletableDeferred<Unit>()
        val route = SettingsRoute(kept = true).apply {
            get = {
                goOn.await()
                respondJson("""{"watchlist_by_kind":true}""")
            }
            patch = {
                goOn.await()
                respondJson("""{"watchlist_by_kind":true}""")
            }
        }
        val settings = settings(phone, server(route))

        val read = async { settings.read() }
        val save = async { settings.save(on = true, under = "anna") }
        runCurrent()
        settings.forget("anna")
        goOn.complete(Unit)
        read.await()
        save.await()

        assertNull(phone.device.watchlistByKind("anna"))
        assertEquals(AccountSetting("anna", false), settings.current())
    }

    @Test
    fun anAccountThatLeftThePhoneTakesItsChoiceWithIt() = runTest {
        val phone = Phone()
        phone.device.keepWatchlistByKind("ben", true)
        val settings = settings(phone, server(SettingsRoute(kept = true)))

        assertEquals(AccountSetting("anna", true), settings.read())
        settings.forget("anna")
        // Nothing of it stays: neither the answer read here nor the copy.
        assertNull(phone.device.watchlistByKind("anna"))
        assertEquals(AccountSetting("anna", false), settings.current())
        assertEquals(true, phone.device.watchlistByKind("ben"))
    }

    @Test
    fun thePhonesCopyIsKeptByServerAndAccountAndGoesWithTheServer() = runTest {
        val store = MemoryServerConfigStore()
        val one = "https://media.example.com/api"
        val two = "https://other.example.org/api"

        store.saveWatchlistByKind(one, "anna", true)
        store.saveWatchlistByKind(one, "ben", false)
        assertEquals(true, store.watchlistByKindBlocking(one, "anna"))
        assertEquals(false, store.watchlistByKindBlocking(one, "ben"))
        assertNull(store.watchlistByKindBlocking(one, "cleo"))
        assertNull(store.watchlistByKindBlocking(two, "anna"))

        // A deleted account's copy goes.
        store.saveWatchlistByKind(one, "ben", null)
        assertNull(store.watchlistByKindBlocking(one, "ben"))

        // Another server's answers replace the first one's.
        store.saveWatchlistByKind(two, "anna", false)
        assertNull(store.watchlistByKindBlocking(one, "anna"))
        assertEquals(false, store.watchlistByKindBlocking(two, "anna"))

        // Change server: they go with it.
        store.clear()
        assertNull(store.watchlistByKindBlocking(two, "anna"))
    }

    /** The phone's server store, in memory, as the platforms' share it. */
    private class MemoryServerConfigStore : BaseServerConfigStore() {
        private val state = MutableStateFlow(ServerConfigBlob())
        override fun readBlobBlocking(): ServerConfigBlob = state.value
        override suspend fun writeBlob(blob: ServerConfigBlob) {
            state.value = json.decodeFromString(ServerConfigBlob.serializer(), json.encodeToString(ServerConfigBlob.serializer(), blob))
        }
        override val blobFlow = state
    }

    @Test
    fun anAnswerWithFieldsOfOtherChangesIsReadForItsOwn() = runTest {
        val phone = Phone()
        val route = SettingsRoute().apply { get = { respondJson("""{"language":null,"watchlist_by_kind":true}""") } }
        val settings = settings(phone, server(route))

        assertEquals(AccountSetting("anna", true), settings.read())
    }

    @Test
    fun aRenewalThatGotNoAnswerIsAReadThatFailedAndASaveThatSaysWhy() = runTest {
        val phone = Phone()
        phone.device.keepWatchlistByKind("anna", true)
        val server = server(SettingsRoute(kept = false))
        val settings = settingsWith(phone, server) { throw kotlinx.io.IOException("the sign-in service can't be reached") }
        val switch = WatchlistSwitch(settings, backgroundScope)

        // Settings opened: a read that failed - the choice last known, and
        // the switch works.
        switch.opened()
        runCurrent()
        assertEquals(WatchlistGroupState("anna", on = true, busy = false), switch.state.value)
        assertEquals(AccountSetting("anna", true), settings.read())

        // Pressed: a save that failed, with its cause, under the switch.
        switch.press(false)
        runCurrent()
        assertEquals(WatchlistGroupState("anna", on = true, busy = false, failure = LoadFailure.NoAnswer), switch.state.value)
        assertEquals(SettingSave.Failed(LoadFailure.NoAnswer), settings.save(on = false, under = "anna"))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun aSignInThatCantBeRenewedForTheAccountStillSignedInFailsAndOnlyAnotherAccountSendsNothing() = runTest {
        val phone = Phone()
        phone.device.keepWatchlistByKind("anna", true)
        val server = server(SettingsRoute(kept = false))
        // No bearer, Anna still signed in: her renewal was refused.
        val settings = settingsWith(phone, server) { null }
        val switch = WatchlistSwitch(settings, backgroundScope)

        assertEquals(AccountSetting("anna", true), settings.read())
        switch.press(false)
        runCurrent()
        assertEquals(WatchlistGroupState("anna", on = true, busy = false, failure = LoadFailure.SignedOut), switch.state.value)
        assertEquals("Couldn't save the setting.", settingNotSavedText(LoadFailure.SignedOut))

        // Ben chosen: nothing is sent for Anna, and nothing is said.
        phone.active.value = "ben"
        assertEquals(SettingSave.NotSent, settings.save(on = false, under = "anna"))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun aSaveWhoseBearerIsStillBeingHadWhenTheAccountLeftKeepsNothingForIt() = runTest {
        val phone = Phone()
        val bearer = CompletableDeferred<String?>()
        val settings = settingsWith(phone, server(SettingsRoute(kept = false))) { bearer.await() }

        val save = async { settings.save(on = true, under = "anna") }
        runCurrent()
        settings.forget("anna")
        bearer.complete("tok-anna")
        save.await()

        assertNull(phone.device.watchlistByKind("anna"))
    }

    @Test
    fun aSaveThatFailsAfterSettingsWereLeftIsSaidWhenTheyOpenAgain() = runTest {
        val phone = Phone()
        val held = CompletableDeferred<Unit>()
        val route = SettingsRoute(kept = false).apply {
            patch = {
                held.await()
                respondText("busy", HttpStatusCode.ServiceUnavailable)
            }
        }
        val settings = settings(phone, server(route))
        val screen = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        WatchlistSwitch(settings, screen).press(true)
        runCurrent()
        // Settings left while it is saved, then opened again: the switch
        // shows the save, busy.
        screen.cancel()
        val again = WatchlistSwitch(settings, backgroundScope)
        runCurrent()
        assertEquals(WatchlistGroupState("anna", on = true, busy = true), again.state.value)

        held.complete(Unit)
        runCurrent()
        assertEquals(WatchlistGroupState("anna", on = false, busy = false, failure = LoadFailure.Busy), again.state.value)
        // Opened once more: still said, until the switch is used again.
        val later = WatchlistSwitch(settings, backgroundScope)
        runCurrent()
        assertEquals(LoadFailure.Busy, later.state.value?.failure)
        // The account leaves the phone: its banner with it.
        settings.forget("anna")
        assertNull(settings.saveFailed.value)
    }
}

/** A request's body as the server receives it. */
private fun bodyOf(request: HttpRequestData): String = (request.body as? TextContent)?.text.orEmpty()
