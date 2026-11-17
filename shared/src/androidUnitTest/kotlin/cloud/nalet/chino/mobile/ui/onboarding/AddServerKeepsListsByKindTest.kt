package cloud.nalet.chino.mobile.ui.onboarding

import android.app.Application
import android.os.Looper
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
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
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Connecting to a server keeps what its /api/config said of lists by kind
 * with it (sections 16.4.7 and 8.8.1 of the specification): the app's next
 * start knows it before it asks the server anything, on every platform.
 * Change server takes every account's copy of its choice off the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddServerKeepsListsByKindTest {
    private val app = RuntimeEnvironment.getApplication() as Application

    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val web = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += "${request.method} ${request.path}"
                val issuer = url("/oidc").toString()
                val body = when (request.path) {
                    "/api/healthz" -> """{"status":"ok","product":"chino"}"""
                    "/api/config" ->
                        """{"product":"chino","oidcIssuer":"$issuer","oidcClientId":{"mobile":"chino"},"features":{"watchlist_kinds":true}}"""
                    "/oidc/.well-known/openid-configuration" ->
                        """{"issuer":"$issuer","authorization_endpoint":"$issuer/auth","token_endpoint":"$issuer/token"}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        start()
    }

    @After
    fun tearDown() = runCatching { web.shutdown() }.let { }

    private fun container() = AppContainer(
        buildConfig = AppConfig(flavor = AppConfig.Flavor.PROD, apiBaseUrl = "", oidcIssuer = "", oidcClientId = "chino", displayName = "Chino"),
        accountStoreFactory = PlatformAccountStoreFactory(app),
        tokenStoreFactory = PlatformTokenStoreFactory(app),
        settingsStoreFactory = PlatformSettingsStoreFactory(app),
        serverConfigStoreFactory = PlatformServerConfigStoreFactory(app),
        deviceStaticContext = emptyMap(),
    )

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
    fun aServerConnectedToIsKnownToFillListsByKindFromTheNextStart() {
        val model = AddServerScreenModel(container())
        model.connect(web.url("/").toString().trimEnd('/'))
        waitFor { model.state.value is AddServerState.Done }
        assertTrue("GET /api/config" in seen, seen.toString())

        // The server goes away; the next start knows what it said, asking nothing.
        web.shutdown()
        val next = container()
        val base = assertNotNull(next.serverConfigStore.currentBlocking()).baseUrl.trimEnd('/')
        assertEquals(true, next.serverConfigStore.watchlistKindsBlocking(base))
        assertEquals(true, next.serverReach.watchlistKinds.value)
    }

    @Test
    fun changeServerTakesEveryAccountsChoiceOffThePhone() {
        // Connected, Anna signed in, her choice kept on the phone.
        val first = AddServerScreenModel(container())
        first.connect(web.url("/").toString().trimEnd('/'))
        waitFor { first.state.value is AddServerState.Done }
        val c = container()
        val base = c.serverBase
        runBlocking {
            c.accountStore.addOrUpdate(
                Account(id = "anna", displayName = "Anna", email = "", accessToken = "tok-anna", refreshToken = null, expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0),
                setActive = true,
            )
            c.serverConfigStore.saveWatchlistByKind(base, "anna", true)
        }

        // Change server, to the same server: the accounts go, and their
        // copies with them - not only with another server's config.
        val change = AddServerScreenModel(c, clearAccountsOnConnect = true)
        change.connect(web.url("/").toString().trimEnd('/'))
        waitFor { change.state.value is AddServerState.Done }
        assertTrue(c.accountStore.snapshotBlocking().accounts.isEmpty())
        assertNull(c.serverConfigStore.watchlistByKindBlocking(base, "anna"))
        assertEquals(true, c.serverConfigStore.watchlistKindsBlocking(base))
    }
}
