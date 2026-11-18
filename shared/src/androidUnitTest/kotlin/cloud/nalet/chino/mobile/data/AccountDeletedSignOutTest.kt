package cloud.nalet.chino.mobile.data

import android.app.Application
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.account.ACCOUNT_DELETED_TOAST
import cloud.nalet.chino.mobile.data.account.AccountDeletion
import cloud.nalet.chino.mobile.data.account.AccountLeft
import cloud.nalet.chino.mobile.data.api.AccountSettingsChange
import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import cloud.nalet.chino.mobile.data.auth.Tokens
import cloud.nalet.chino.mobile.data.downloads.FlushResult
import cloud.nalet.chino.mobile.data.downloads.SendOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An account the server has deleted is signed out on this phone (section
 * 16.5.6 of the specification): chino-api answers what it sends 401
 * `account_deleted`, and the container's own client, against a chino-api and
 * a sign-in service of the test's, asks for no fresh token, and signs that
 * account out once, with the toast "Account deleted"; the other account
 * stays. Where it was the account signed in, nobody is signed in after it,
 * and the app is to start over. A write that waited out a deletion still
 * open, 503 `account busy`, signs nobody out. What the deleted account's
 * player writes after that goes to nobody, and what the phone kept of its
 * positions goes with it. Delete account on this phone sends the deletion
 * with the account's own token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountDeletedSignOutTest {
    private val app = RuntimeEnvironment.getApplication() as Application

    /** Every request, as "METHOD path bearer". */
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The bearers chino-api answers deleted, and those it answers busy. */
    private val deleted = Collections.synchronizedSet(mutableSetOf("tok-anna"))
    private val busy = Collections.synchronizedSet(mutableSetOf<String>())

    private val web = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bearer = request.getHeader("Authorization")?.removePrefix("Bearer ") ?: "-"
                seen += "${request.method} ${request.path} $bearer"
                return when {
                    request.path == "/token" -> MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"access_token":"tok-renewed","refresh_token":"refresh-renewed","expires_in":300}""")
                    // A request of chino-api's without a bearer, as chino-api
                    // answers it: a sign-in to renew.
                    bearer == "-" && request.path.orEmpty().startsWith("/api/v1/") ->
                        MockResponse().setResponseCode(401).setHeader("Content-Type", "text/plain").setBody("missing bearer")
                    bearer in deleted -> MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                        .setBody("""{"error":"account_deleted","detail":"the account's deletion has begun: nothing more is kept for it"}""")
                    bearer in busy -> MockResponse().setResponseCode(503).setHeader("Content-Type", "text/plain")
                        .setHeader("Retry-After", "5").setBody("account busy")
                    request.method == "DELETE" && request.path == "/api/v1/me" ->
                        MockResponse().setHeader("Content-Type", "application/json").setBody("""{"account":"deleted","deleted":{}}""")
                    request.path == "/api/v1/me/settings" -> MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"watchlist_by_kind":true}""")
                    else -> MockResponse().setResponseCode(204)
                }
            }
        }
        start()
    }

    private val listening = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The container's toasts, as they were said. */
    private val toasts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After
    fun tearDown() {
        listening.cancel()
        web.shutdown()
    }

    private fun account(id: String) = Account(
        id = id, displayName = id, email = "", accessToken = "tok-$id", refreshToken = "refresh-$id",
        expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0,
    )

    /** Anna and Ben on the phone, [active] signed in; listened to before anything is sent. */
    private fun phone(active: String): AppContainer {
        runBlocking {
            PlatformServerConfigStoreFactory(app).create().save(
                ServerConfig(
                    baseUrl = web.url("/api/").toString(), issuer = web.url("/auth").toString(), clientId = "chino",
                    tokenEndpoint = web.url("/token").toString(), userinfoEndpoint = web.url("/userinfo").toString(),
                ),
            )
        }
        val c = AppContainer(
            buildConfig = AppConfig(flavor = AppConfig.Flavor.PROD, apiBaseUrl = "", oidcIssuer = "", oidcClientId = "chino", displayName = "Chino"),
            accountStoreFactory = PlatformAccountStoreFactory(app),
            tokenStoreFactory = PlatformTokenStoreFactory(app),
            settingsStoreFactory = PlatformSettingsStoreFactory(app),
            serverConfigStoreFactory = PlatformServerConfigStoreFactory(app),
            deviceStaticContext = emptyMap(),
        )
        runBlocking {
            c.accountStore.snapshotBlocking().accounts.forEach { c.accountStore.remove(it.id) }
            c.accountStore.addOrUpdate(account("anna"), setActive = active == "anna")
            c.accountStore.addOrUpdate(account("ben"), setActive = active == "ben")
        }
        val listens = CompletableDeferred<Unit>()
        listening.launch { c.toasts.onSubscription { listens.complete(Unit) }.collect { toasts += it.text } }
        runBlocking { withTimeout(5_000) { listens.await() } }
        return c
    }

    private fun AppContainer.signedIn() = accountStore.snapshotBlocking().accounts.map { it.id }.sorted()

    private fun waitFor(what: String, done: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not $what in 10 s; asked $seen, said $toasts" }
            Thread.sleep(10)
        }
    }

    private val renewals get() = seen.filter { it.startsWith("POST /token") }

    /** A sign-out held as it stops casting, its first step, until [release]:
     *  [entered] once one got there, [times] how many did. */
    private class HeldSignOut {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val times = AtomicInteger()
    }

    private fun AppContainer.holdSignOuts(): HeldSignOut {
        val held = HeldSignOut()
        leaveCasting = { _, _ ->
            held.times.incrementAndGet()
            held.entered.complete(Unit)
            held.release.await()
            null
        }
        return held
    }

    private fun HeldSignOut.awaitEntered() = runBlocking { withTimeout(5_000) { entered.await() } }

    @Test
    fun theAccountSignedInAnsweredDeletedIsSignedOutWithTheToastAndTheOtherStays() {
        val c = phone(active = "anna")

        val e = assertFailsWith<ApiStatusException> { runBlocking { c.chinoApi.addLike("title-1") } }

        assertTrue(e.accountDeleted)
        waitFor("signed out") { c.signedIn() == listOf("ben") && c.startOver.value != null }
        assertEquals(AccountLeft("anna", wasActive = true, othersRemain = true), c.startOver.value)
        assertEquals(listOf(ACCOUNT_DELETED_TOAST), toasts.toList())
        // Nobody is signed in after her - not Ben: who's watching follows.
        assertNull(c.accountStore.snapshotBlocking().activeAccount)
        // No fresh token was asked for, and the write was sent once.
        assertEquals(listOf("PUT /api/v1/me/likes/title-1 tok-anna"), seen.filter { "/me/likes" in it })
        assertTrue(renewals.isEmpty())
    }

    @Test
    fun anotherAccountAnsweredDeletedGoesAloneAndTheOneSignedInStays() {
        deleted.clear()
        deleted += "tok-ben"
        val c = phone(active = "anna")

        assertFailsWith<ApiStatusException> {
            runBlocking { c.chinoApi.changeAccountSettings(AccountSettingsChange(watchlistByKind = true), bearer = "tok-ben") }
        }

        waitFor("signed out") { c.signedIn() == listOf("anna") && toasts.isNotEmpty() }
        assertEquals(listOf(ACCOUNT_DELETED_TOAST), toasts.toList())
        assertEquals("anna", c.accountStore.snapshotBlocking().activeAccount?.id)
        // Nothing to start over for: the account signed in is still there.
        assertNull(c.startOver.value)
        assertTrue(renewals.isEmpty())
    }

    @Test
    fun twoAnswersForTheSameAccountSignItOutOnce() {
        val c = phone(active = "anna")
        val held = c.holdSignOuts()

        // The first answer: her sign-out begins, and waits as it stops casting.
        assertFailsWith<ApiStatusException> { runBlocking { c.chinoApi.addLike("title-1") } }
        held.awaitEntered()
        // A second request meanwhile goes with her bearer, and is answered so
        // too, while she is still on the phone.
        val second = assertFailsWith<ApiStatusException> { runBlocking { c.chinoApi.removeLike("title-1") } }
        assertTrue(second.accountDeleted)
        assertEquals(
            listOf("PUT /api/v1/me/likes/title-1 tok-anna", "DELETE /api/v1/me/likes/title-1 tok-anna"),
            seen.filter { "/me/likes" in it },
        )
        held.release.complete(Unit)

        waitFor("signed out") { c.signedIn() == listOf("ben") && toasts.isNotEmpty() }
        Thread.sleep(500)
        // Signed out once: one sign-out stopped casting, one toast.
        assertEquals(1, held.times.get())
        assertEquals(listOf(ACCOUNT_DELETED_TOAST), toasts.toList())
    }

    @Test
    fun aRequestWhileTheDeletedAccountLeavesGoesWithItsOwnBearerAndRenewsNothing() {
        val c = phone(active = "anna")
        val held = c.holdSignOuts()

        assertFailsWith<ApiStatusException> { runBlocking { c.chinoApi.addLike("title-1") } }
        held.awaitEntered()
        // Her token within a minute of its end, as it may well be: one loaded
        // now would be renewed first.
        runBlocking {
            c.accountStore.updateTokensForBlocking("anna", Tokens("tok-anna", "refresh-anna", System.currentTimeMillis() + 30_000))
        }

        val e = assertFailsWith<ApiStatusException> { runBlocking { c.chinoApi.removeLike("title-1") } }

        // Sent with her own bearer, not without one, and answered deleted: no
        // fresh token asked for, now or as she leaves.
        assertTrue(e.accountDeleted)
        assertEquals("DELETE /api/v1/me/likes/title-1 tok-anna", seen.last { "/me/likes" in it })
        held.release.complete(Unit)
        waitFor("signed out") { c.signedIn() == listOf("ben") }
        assertTrue(renewals.isEmpty())
    }

    @Test
    fun theDeletedAccountsPlayerWritesNothingForTheAccountSignedInAfterIt() {
        val c = phone(active = "anna")

        // Her player's save every 10 s, answered deleted: she leaves.
        assertEquals(SendOutcome.Drop, runBlocking { c.progressSync.saveNow("title-1", 600, 5_400, openedFor = "anna") })
        waitFor("signed out") { c.signedIn() == listOf("ben") && c.startOver.value != null }

        // The start-over closes her player: its last position and its watched
        // mark go to nobody - with nobody signed in, and with Ben picked.
        assertNull(runBlocking { c.progressSync.saveNow("title-1", 610, 5_400, openedFor = "anna") })
        runBlocking { c.accountStore.setActive("ben") }
        assertNull(runBlocking { c.progressSync.saveNow("title-1", 610, 5_400, openedFor = "anna") })
        assertNull(runBlocking { c.progressSync.markWatchedNow("title-1", openedFor = "anna") })
        assertEquals(listOf("POST /api/v1/items/title-1/progress tok-anna"), seen.filter { "/items/" in it })
        assertFalse(runBlocking { c.progressSync.hasPending() })

        // His own player writes as him, with his own token.
        assertEquals(SendOutcome.Sent, runBlocking { c.progressSync.saveNow("title-2", 60, 5_400, openedFor = "ben") })
        assertEquals("POST /api/v1/items/title-2/progress tok-ben", seen.last { "/items/" in it })
    }

    @Test
    fun theDeletedAccountsQueuedPositionsGoAndNoneIsSentWhenItSignsInAgain() {
        val c = phone(active = "anna")
        runBlocking {
            // Played on a TV this phone cast to, and not sent yet: kept for
            // each account.
            c.progressSync.keepUnsent("anna", "title-1", 600, 5_400)
            c.progressSync.keepUnsent("ben", "title-2", 300, 5_400)
        }

        // A request of hers answered deleted - none of her positions.
        assertFailsWith<ApiStatusException> { runBlocking { c.chinoApi.addLike("title-1") } }
        waitFor("signed out") { c.signedIn() == listOf("ben") && toasts.isNotEmpty() }

        // His position still waits for him, once he is picked.
        assertFalse(runBlocking { c.progressSync.hasPending() })
        runBlocking { c.accountStore.setActive("ben") }
        assertTrue(runBlocking { c.progressSync.hasPending() })
        // The same account signs in here again: nothing kept of hers from
        // before is sent.
        deleted.clear()
        runBlocking { c.accountStore.addOrUpdate(account("anna").copy(accessToken = "tok-anna-again"), setActive = true) }
        assertFalse(runBlocking { c.progressSync.hasPending() })
        assertEquals(FlushResult(sent = 0, dropped = 0, left = 0), runBlocking { c.progressSync.flush() })
        assertTrue(seen.none { "/progress" in it }, "sent: $seen")
    }

    @Test
    fun anAnswerToATokenRenewedSinceSignsOutTheAccountItWasFor() {
        val c = phone(active = "anna")
        // Her token is renewed here; a request that went out with the one
        // before has its answer after that.
        assertEquals("tok-renewed", runBlocking { c.tokenManager.forceRefresh() })
        assertEquals("tok-renewed", c.accountStore.snapshotBlocking().accounts.first { it.id == "anna" }.accessToken)

        assertFailsWith<ApiStatusException> {
            runBlocking { c.chinoApi.changeAccountSettings(AccountSettingsChange(watchlistByKind = true), bearer = "tok-anna") }
        }

        waitFor("signed out") { c.signedIn() == listOf("ben") && c.startOver.value != null }
        assertEquals(listOf(ACCOUNT_DELETED_TOAST), toasts.toList())
    }

    @Test
    fun deleteAccountSendsTheDeletionWithItsAccountsOwnTokenWhoeverIsSignedInBy() {
        deleted.clear()
        val c = phone(active = "anna")
        // Ben signed in between Delete account's look at who is signed in and
        // the request: the deletion is still hers alone.
        runBlocking { c.accountStore.setActive("ben") }

        assertEquals(AccountDeletion.Deleted, runBlocking { c.deleteAccount("anna") })

        assertEquals(listOf("DELETE /api/v1/me tok-anna"), seen.filter { it.startsWith("DELETE /api/v1/me ") })
        assertEquals(listOf("ben"), c.signedIn())
        assertEquals("ben", c.accountStore.snapshotBlocking().activeAccount?.id)
    }

    @Test
    fun theOnlyAccountAnsweredDeletedLeavesNobodyAndTheAppGoesToSignIn() {
        val c = phone(active = "anna")
        runBlocking { c.accountStore.remove("ben") }

        runCatching { runBlocking { c.chinoApi.addLike("title-1") } }

        waitFor("signed out") { c.signedIn().isEmpty() && c.startOver.value != null }
        assertEquals(AccountLeft("anna", wasActive = true, othersRemain = false), c.startOver.value)
    }

    @Test
    fun aWriteThatWaitedOutAnOpenDeletionSignsNobodyOut() {
        deleted.clear()
        busy += "tok-anna"
        val c = phone(active = "anna")

        val e = assertFailsWith<ApiStatusException> { runBlocking { c.chinoApi.addLike("title-1") } }

        assertEquals(503, e.status)
        Thread.sleep(200)
        assertEquals(listOf("anna", "ben"), c.signedIn())
        assertNull(c.startOver.value)
        assertTrue(toasts.isEmpty())
        assertTrue(renewals.isEmpty())
    }
}
