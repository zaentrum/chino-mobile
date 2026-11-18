package cloud.nalet.chino.mobile.ui.settings

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.ServerConfig
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.LocalSignInLauncher
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import cloud.nalet.chino.mobile.data.auth.SignInLauncher
import cloud.nalet.chino.mobile.data.auth.SignInResult
import cloud.nalet.chino.mobile.data.downloads.Reach
import cloud.nalet.chino.mobile.ui.watchlist.WatchlistScreenModel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Settings and the watchlist know whether the server fills lists by kind
 * from their first frame after any start (sections 16.4.7 and 8.8.1): what
 * the server said the last time it answered is kept on the phone with the
 * server - right after a start on a slow server, after a read of its
 * /api/config that failed, in a process restored straight into settings. A
 * change of server forgets it. Against servers stood up in the test, signed
 * in as one account whose choice the phone knows as on. The titles are
 * invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class KindsKnownAtStartTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication() as Application

    /** How a server answers /api/config: not while the test looks, with an
     *  error, or saying what it offers. */
    private sealed interface ConfigAnswer {
        data object Held : ConfigAnswer
        data object Fails : ConfigAnswer
        data class Says(val watchlistKinds: Boolean) : ConfigAnswer
    }

    /** A chino server: /api/config as [config]; the account's settings read
     *  held until [readGoesOn], answering on; one list of one movie. Every
     *  request is recorded. */
    private class Server(@Volatile var config: ConfigAnswer) {
        val configGoesOn = CountDownLatch(1)
        val readGoesOn = CountDownLatch(1)
        val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val web = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += "${request.method} ${request.path}"
                    val body = when (request.path) {
                        "/api/config" -> when (val c = config) {
                            ConfigAnswer.Held -> {
                                configGoesOn.await(20, TimeUnit.SECONDS)
                                """{"product":"chino","features":{"watchlist_kinds":true}}"""
                            }
                            ConfigAnswer.Fails -> return MockResponse().setResponseCode(503)
                            is ConfigAnswer.Says -> """{"product":"chino","features":{"watchlist_kinds":${c.watchlistKinds}}}"""
                        }
                        "/api/v1/me/settings" -> {
                            if (request.method == "GET") readGoesOn.await(20, TimeUnit.SECONDS)
                            if (request.method == "GET") """{"watchlist_by_kind":true}""" else """{"watchlist_by_kind":false}"""
                        }
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

        fun asked(what: String) = seen.count { it == what }

        val serverConfig get() = ServerConfig(baseUrl = web.url("/api/").toString(), issuer = web.url("/auth").toString(), clientId = "chino")

        /** Its config with the sign-in's token renewed at [tokenEndpoint]. */
        fun signingInAt(tokenEndpoint: String) = serverConfig.copy(tokenEndpoint = tokenEndpoint, userinfoEndpoint = tokenEndpoint)
    }

    private val servers = mutableListOf<Server>()

    @After
    fun tearDown() = servers.forEach {
        it.configGoesOn.countDown()
        it.readGoesOn.countDown()
        it.web.shutdown()
    }

    private fun server(config: ConfigAnswer) = Server(config).also { servers += it }

    private object NoSignIn : SignInLauncher {
        override suspend fun signIn(): SignInResult = SignInResult.Cancelled
    }

    private object SettingsHost : Screen {
        @Composable
        override fun Content() = SettingsSection()
    }

    /** The app's graph as a start builds it, over what the phone keeps. */
    private fun graph() = AppContainer(
        buildConfig = AppConfig(flavor = AppConfig.Flavor.PROD, apiBaseUrl = "", oidcIssuer = "", oidcClientId = "chino", displayName = "Chino"),
        accountStoreFactory = PlatformAccountStoreFactory(app),
        tokenStoreFactory = PlatformTokenStoreFactory(app),
        settingsStoreFactory = PlatformSettingsStoreFactory(app),
        serverConfigStoreFactory = PlatformServerConfigStoreFactory(app),
        deviceStaticContext = emptyMap(),
    )

    /** A phone connected to [s], Anna signed in with her choice known as on,
     *  and [kept] what the phone keeps of the server's word on lists by kind
     *  (nothing where null). [renewedAt]: Anna's token has run out, and is
     *  renewed there. */
    private fun phoneOn(s: Server, kept: Boolean?, renewedAt: String? = null): AppContainer {
        runBlocking { PlatformServerConfigStoreFactory(app).create().save(renewedAt?.let(s::signingInAt) ?: s.serverConfig) }
        return graph().also { c ->
            runBlocking {
                c.accountStore.addOrUpdate(
                    Account(
                        id = "anna", displayName = "Anna", email = "", accessToken = "tok-anna",
                        refreshToken = renewedAt?.let { "refresh-anna" },
                        expiresAtEpochMillis = if (renewedAt != null) 0 else Long.MAX_VALUE, lastUsedAt = 0,
                    ),
                    setActive = true,
                )
                c.serverConfigStore.saveWatchlistByKind(c.serverBase, "anna", true)
                if (kept != null) c.serverConfigStore.saveWatchlistKinds(c.serverBase, kept)
            }
        }
    }

    private fun openSettings(c: AppContainer) {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides c, LocalSignInLauncher provides NoSignIn) { Navigator(SettingsHost) }
        }
    }

    private val switchRow get() = hasText(SEPARATE_MOVIES_AND_SHOWS) and hasClickAction()

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun waitFor(what: () -> Boolean) = compose.waitUntil(20_000) { what() }

    /** Runs the main thread until [done]: the watchlist's work comes back
     *  to it. */
    private fun mainUntil(done: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not done in 20 s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /** The group on settings' first frame, before the server has said
     *  anything in this process. */
    private fun groupOnFirstFrame(c: AppContainer) {
        openSettings(c)
        compose.onNodeWithText(WATCHLIST_GROUP).assertIsDisplayed()
        compose.onNode(switchRow).assertIsDisplayed()
        assertNull(c.serverReach.features.value)
    }

    @Test
    fun rightAfterAStartOnASlowServerSettingsAndTheWatchlistKnowItFromTheirFirstFrame() {
        val s = server(ConfigAnswer.Held)
        val c = phoneOn(s, kept = true)

        groupOnFirstFrame(c)
        // The account's choice read at once, not once the server has said.
        waitFor { s.asked("GET /api/v1/me/settings") == 1 }
        assertNull(c.serverReach.features.value)

        // The watchlist: drawn split from its first draw.
        assertEquals("anna", c.kindSplitNow()?.accountId)
        val model = WatchlistScreenModel(c)
        mainUntil { model.byKind.value != null }
        assertEquals("anna", assertNotNull(model.byKind.value).split.accountId)
        assertEquals(0, s.asked("GET /api/v1/me/watchlists/w"))
        assertNull(c.serverReach.features.value)
    }

    @Test
    fun afterAConfigReadThatFailedSettingsShowTheGroupAndItWorks() {
        val s = server(ConfigAnswer.Fails)
        val c = phoneOn(s, kept = true)
        // As at the start: the server's /api/config couldn't be read.
        assertEquals(Reach.Unreachable, runBlocking { c.serverReach.check() })

        groupOnFirstFrame(c)
        assertEquals("anna", c.kindSplitNow()?.accountId)

        // Its choice read: on. A press saves off.
        s.readGoesOn.countDown()
        waitFor { s.asked("GET /api/v1/me/settings") == 1 }
        waitFor { runCatching { compose.onNode(switchRow).assertIsEnabled() }.isSuccess }
        compose.onNode(switchRow).performClick()
        waitFor { s.asked("PATCH /api/v1/me/settings") == 1 }
        compose.onNodeWithText(WATCHLIST_GROUP).assertIsDisplayed()
    }

    @Test
    fun aProcessRestoredStraightIntoSettingsShowsTheGroupOnTheirFirstFrame() {
        val s = server(ConfigAnswer.Says(watchlistKinds = true))
        // The process before: the server answered, and what it said is kept.
        val before = phoneOn(s, kept = null)
        assertEquals(Reach.Reachable, runBlocking { before.serverReach.check() })
        assertEquals(true, before.serverConfigStore.watchlistKindsBlocking(before.serverBase))

        // Restored: a new graph over what the phone keeps, settings drawn
        // first - no home, no warm - while the server is slow.
        s.config = ConfigAnswer.Held
        val c = graph()
        groupOnFirstFrame(c)
    }

    @Test
    fun whereTheServerSaidItDoesntTheGroupIsLeftOutFromTheFirstFrameToo() {
        val s = server(ConfigAnswer.Says(watchlistKinds = false))
        val before = phoneOn(s, kept = true)
        runBlocking { before.serverReach.check() }
        assertEquals(false, before.serverConfigStore.watchlistKindsBlocking(before.serverBase))

        s.config = ConfigAnswer.Held
        val c = graph()
        openSettings(c)
        compose.onNodeWithText("Auto-play next episode").assertIsDisplayed()
        assertEquals(0, count(WATCHLIST_GROUP))
        assertNull(c.kindSplitNow())
    }

    @Test
    fun aChangeOfServerForgetsWhatTheOldOneSaid() {
        val old = server(ConfigAnswer.Says(watchlistKinds = true))
        val before = phoneOn(old, kept = null)
        runBlocking { before.serverReach.check() }
        assertEquals(true, before.serverConfigStore.watchlistKindsBlocking(before.serverBase))

        // Another server connected, as Add server saves it; the graph built
        // anew for it.
        val next = server(ConfigAnswer.Held)
        runBlocking { before.serverConfigStore.save(next.serverConfig) }
        assertNull(before.serverConfigStore.watchlistKindsBlocking(before.serverBase))
        val c = graph()
        assertEquals(next.serverConfig.baseUrl.trimEnd('/'), c.serverBase)
        assertNull(c.serverConfigStore.watchlistKindsBlocking(c.serverBase))

        openSettings(c)
        compose.onNodeWithText("Auto-play next episode").assertIsDisplayed()
        assertEquals(0, count(WATCHLIST_GROUP))
        assertNull(c.kindSplitNow())
    }

    /** Settings opened with Anna's token run out and its renewal failing as
     *  [renewedAt] answers: no crash - the group shows, the read fails, the
     *  switch works, and a press says why under it, sending nothing. */
    private fun renewalFails(renewedAt: (Server) -> String, says: LoadFailure) {
        val s = server(ConfigAnswer.Held)
        val c = phoneOn(s, kept = true, renewedAt = renewedAt(s))

        groupOnFirstFrame(c)
        waitFor { runCatching { compose.onNode(switchRow).assertIsEnabled() }.isSuccess }
        compose.onNode(switchRow).performClick()
        waitFor { count(settingNotSavedText(says)) > 0 }
        compose.onNode(switchRow).assertIsDisplayed()
        assertEquals(0, s.asked("GET /api/v1/me/settings"))
        assertEquals(0, s.asked("PATCH /api/v1/me/settings"))
        assertEquals(says, c.accountSettings.saveFailed.value["anna"])
    }

    @Test
    fun aRenewalThatGetsNoAnswerIsAReadThatFailedAndAPressSaysTheServerCantBeReached() =
        renewalFails({ "http://127.0.0.1:9/token" }, LoadFailure.NoAnswer)

    @Test
    fun aRenewalRefusedForTheAccountStillSignedInIsAPressThatFailed() =
        renewalFails({ it.web.url("/token").toString() }, LoadFailure.SignedOut)
}
