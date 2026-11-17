package cloud.nalet.chino.mobile.ui.watchlist

import android.app.Application
import android.os.Looper
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.ServerConfig
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The watchlist as the app builds it from its container, against a server
 * stood up in the test: home's warm asks what the server offers and the
 * account's choice; the watchlist's model, made from the container, splits
 * every list by the account's choice with the account's bearer; the choice
 * goes when the account leaves the phone. The titles are invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListsByKindWiringTest {
    private val app = RuntimeEnvironment.getApplication() as Application

    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val web = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += "${request.method} ${request.path} ${request.getHeader("Authorization")}"
                val body = when (request.path) {
                    "/api/config" -> """{"product":"chino","features":{"watchlist_kinds":true}}"""
                    "/api/v1/me/settings" -> """{"watchlist_by_kind":true}"""
                    "/api/v1/me/watchlists" -> """{"lists":[{"id":"w","name":"Watchlist","itemCount":1,"isDefault":true}]}"""
                    "/api/v1/me/watchlists/w?kind=movie" -> """{"id":"w","name":"Watchlist","items":["m1"]}"""
                    "/api/v1/me/watchlists/w?kind=series" -> """{"id":"w","name":"Watchlist","items":[]}"""
                    "/api/v1/items/m1" -> """{"id":"m1","title":"Lantern Field","type":"movie"}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        start()
    }

    @After
    fun tearDown() = web.shutdown()

    private val container: AppContainer by lazy {
        runBlocking {
            PlatformServerConfigStoreFactory(app).create().save(
                ServerConfig(baseUrl = web.url("/api/").toString(), issuer = web.url("/auth").toString(), clientId = "chino"),
            )
        }
        AppContainer(
            buildConfig = AppConfig(flavor = AppConfig.Flavor.PROD, apiBaseUrl = "", oidcIssuer = "", oidcClientId = "chino", displayName = "Chino"),
            accountStoreFactory = PlatformAccountStoreFactory(app),
            tokenStoreFactory = PlatformTokenStoreFactory(app),
            settingsStoreFactory = PlatformSettingsStoreFactory(app),
            serverConfigStoreFactory = PlatformServerConfigStoreFactory(app),
            deviceStaticContext = emptyMap(),
        ).also { c ->
            runBlocking {
                c.accountStore.addOrUpdate(
                    Account(id = "anna", displayName = "Anna", email = "", accessToken = "tok-anna", refreshToken = null, expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0),
                    setActive = true,
                )
                c.serverConfigStore.saveWatchlistByKind(c.serverBase, "anna", null)
            }
        }
    }

    /** Runs the main thread until [done]: the model's work comes back to it. */
    private fun waitFor(done: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not done in 20 s; asked $seen" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun homesWarmThenTheWatchlistFromTheContainerIsSplitByTheAccountsChoice() {
        val c = container
        c.warmListsByKind()
        waitFor { c.kindSplitNow() != null }
        assertTrue(seen.any { it.startsWith("GET /api/config ") }, seen.toString())
        assertTrue("GET /api/v1/me/settings Bearer tok-anna" in seen, seen.toString())
        // Kept on the phone as Anna's, for her server: the copy is written
        // after the answer is held, so it is waited for.
        waitFor { c.serverConfigStore.watchlistByKindBlocking(c.serverBase, "anna") != null }
        assertEquals(true, c.serverConfigStore.watchlistByKindBlocking(c.serverBase, "anna"))

        val model = WatchlistScreenModel(c)
        waitFor { model.byKind.value != null && !model.loadingShelves.value }
        val hub = assertNotNull(model.byKind.value)
        assertEquals("anna", hub.split.accountId)
        assertEquals(listOf("Lantern Field"), (hub.rows.getValue("w").movies as KindRow.Loaded).items.map { it.title })
        assertTrue("GET /api/v1/me/watchlists/w?kind=movie Bearer tok-anna" in seen, seen.toString())

        // Anna leaves the phone: her choice goes with her.
        runBlocking { c.accountSettings.forget("anna") }
        waitFor { c.serverConfigStore.watchlistByKindBlocking(c.serverBase, "anna") == null }
        assertNull(c.serverConfigStore.watchlistByKindBlocking(c.serverBase, "anna"))
    }
}
