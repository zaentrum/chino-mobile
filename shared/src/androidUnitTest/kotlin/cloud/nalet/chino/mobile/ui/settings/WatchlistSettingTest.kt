package cloud.nalet.chino.mobile.ui.settings

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
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
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings' watchlist group (sections 16.4.7 and 16.8): right after binge
 * watching, only where the server keeps Separate movies and shows and fills
 * a list by kind. While the account's choice is read, the switch is busy and
 * shows the choice last known on the phone; where that read failed, it
 * shows that choice and works. A save that failed says so in an error
 * banner under the switch. Settings drawn whole against a server stood up in
 * the test, signed in as one account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class WatchlistSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication() as Application

    /** A chino server: its /api/config says [features], once [configGoesOn]
     *  where [configHeld]; the settings read waits for [readGoesOn] where
     *  [readsHeld] and answers [readAnswer]; a save answers [saveAnswer].
     *  Every request is recorded, with its bearer. */
    private class Server(private val features: String) {
        val readGoesOn = CountDownLatch(1)
        @Volatile var readsHeld = true
        val configGoesOn = CountDownLatch(1)
        @Volatile var configHeld = false
        @Volatile var readAnswer: MockResponse = json("""{"watchlist_by_kind":false}""")
        @Volatile var saveAnswer: MockResponse = json("""{"watchlist_by_kind":false}""")
        val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

        data class Seen(val method: String, val path: String, val authorization: String?, val body: String)

        val web = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += Seen(request.method.orEmpty(), request.path.orEmpty(), request.getHeader("Authorization"), request.body.readUtf8())
                    return when {
                        request.path == "/api/config" -> {
                            if (configHeld) configGoesOn.await(20, TimeUnit.SECONDS)
                            json("""{"product":"chino","features":$features}""")
                        }
                        request.path == "/api/v1/me/settings" && request.method == "GET" -> {
                            if (readsHeld) readGoesOn.await(20, TimeUnit.SECONDS)
                            readAnswer
                        }
                        request.path == "/api/v1/me/settings" && request.method == "PATCH" -> saveAnswer
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }

        fun of(method: String, path: String) = seen.filter { it.method == method && it.path == path }

        companion object {
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        }
    }

    private var server: Server? = null

    @After
    fun tearDown() {
        server?.readGoesOn?.countDown()
        server?.configGoesOn?.countDown()
        server?.web?.shutdown()
    }

    private object NoSignIn : SignInLauncher {
        override suspend fun signIn(): SignInResult = SignInResult.Cancelled
    }

    private object SettingsHost : Screen {
        @Composable
        override fun Content() = SettingsSection()
    }

    /** Settings for Anna on [features]'s server, the phone knowing her
     *  choice as [known] (nothing where null); [shown]: settings opened at
     *  once. */
    private fun settingsFor(features: String, known: Boolean?, shown: Boolean = true): Pair<Server, AppContainer> {
        val s = Server(features).also { server = it }
        runBlocking {
            PlatformServerConfigStoreFactory(app).create().save(
                ServerConfig(baseUrl = s.web.url("/api/").toString(), issuer = s.web.url("/auth").toString(), clientId = "chino"),
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
            c.accountStore.addOrUpdate(
                Account(id = "anna", displayName = "Anna", email = "", accessToken = "tok-anna", refreshToken = null, expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0),
                setActive = true,
            )
            c.serverConfigStore.saveWatchlistByKind(c.serverBase, "anna", known)
        }
        if (shown) openSettings(c)
        return s to c
    }

    /** Settings opened on [c]. */
    private fun openSettings(c: AppContainer) {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides c, LocalSignInLauncher provides NoSignIn) { Navigator(SettingsHost) }
        }
    }

    /** Until [done], with nothing on the screen yet. */
    private fun waitOff(done: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not done in 20 s" }
            Thread.sleep(10)
        }
    }

    @Test
    fun afterHomesWarmTheGroupIsOnSettingsFirstFrameWithTheAccountsOwnChoice() {
        // The phone's copy says off; the server keeps on.
        val (s, c) = settingsFor("""{"watchlist_kinds":true}""", known = false, shown = false)
        s.readsHeld = false
        s.readAnswer = Server.json("""{"watchlist_by_kind":true}""")
        // Home opened: what the server offers, and Anna's choice.
        c.warmListsByKind()
        waitOff { c.serverReach.features.value != null && c.accountSettings.current()?.watchlistByKind == true }
        assertEquals(1, s.of("GET", "/api/config").size)
        assertEquals(1, s.of("GET", "/api/v1/me/settings").size)
        // From here nothing more is answered until the page has drawn.
        s.readsHeld = true
        s.configHeld = true
        openSettings(c)

        compose.onNode(switchRow).assertIsDisplayed()
        compose.onNodeWithText(WATCHLIST_GROUP).assertIsDisplayed()
        // Settings' own read answered: the switch showed on, so a press asks
        // for off.
        s.readGoesOn.countDown()
        waitFor { s.of("GET", "/api/v1/me/settings").size == 2 }
        waitFor { runCatching { compose.onNode(switchRow).assertIsEnabled() }.isSuccess }
        compose.onNode(switchRow).performClick()
        waitFor { s.of("PATCH", "/api/v1/me/settings").isNotEmpty() }
        assertEquals("""{"watchlist_by_kind":false}""", s.of("PATCH", "/api/v1/me/settings").single().body)
    }

    private val switchRow get() = hasText(SEPARATE_MOVIES_AND_SHOWS) and hasClickAction()

    private fun waitFor(what: () -> Boolean) = compose.waitUntil(20_000) { what() }

    private fun shows(text: String) = compose.onAllNodesWithTextCount(text) > 0

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun leftOutOn(features: String) {
        val (s, c) = settingsFor(features, known = true)
        waitFor { c.serverReach.features.value != null }
        compose.waitForIdle()

        compose.onNodeWithText("Auto-play next episode").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount(SEPARATE_MOVIES_AND_SHOWS))
        assertEquals(0, compose.onAllNodesWithTextCount(WATCHLIST_GROUP))
        // Nor is the choice asked for.
        assertTrue(s.of("GET", "/api/v1/me/settings").isEmpty())
    }

    @Test
    fun theGroupIsLeftOutWhereTheServerDoesntSayItFillsListsByKind() = leftOutOn("""{"feedback":false}""")

    @Test
    fun theGroupIsLeftOutWhereTheServerSaysItDoesntFillListsByKind() = leftOutOn("""{"watchlist_kinds":false}""")

    @Test
    fun theGroupComesRightAfterBingeWatchingWithItsLine() {
        val (s, _) = settingsFor("""{"watchlist_kinds":true}""", known = null)
        waitFor { shows(SEPARATE_MOVIES_AND_SHOWS) }
        s.readGoesOn.countDown()

        compose.onNodeWithText(SEPARATE_MOVIES_AND_SHOWS_HINT, useUnmergedTree = true).assertIsDisplayed()
        val autoPlay = compose.onNodeWithText("Auto-play next episode").getBoundsInRoot()
        val heading = compose.onNodeWithText(WATCHLIST_GROUP).getBoundsInRoot()
        val switch = compose.onNode(switchRow).getBoundsInRoot()
        val nextGroup = compose.onNodeWithText("Server").getBoundsInRoot()
        assertTrue(autoPlay.bottom <= heading.top, "$autoPlay, then $heading")
        assertTrue(heading.bottom <= switch.top, "$heading, then $switch")
        assertTrue(switch.bottom <= nextGroup.top, "$switch, then $nextGroup")
    }

    @Test
    fun whileTheChoiceIsReadTheSwitchIsBusyAndWhereTheReadFailedItShowsTheLastKnownAndWorks() {
        val (s, _) = settingsFor("""{"watchlist_kinds":true}""", known = true)
        // As settings open: the choice read again, and the switch busy.
        waitFor { s.of("GET", "/api/v1/me/settings").isNotEmpty() }
        waitFor { shows(SEPARATE_MOVIES_AND_SHOWS) }
        compose.onNode(switchRow).assertIsNotEnabled()
        compose.onNode(switchRow).performClick()
        compose.waitForIdle()
        assertTrue(s.of("PATCH", "/api/v1/me/settings").isEmpty())

        // The read fails: no word of it, and the switch takes a press. It
        // shows the choice last known, on, so the press switches it off.
        s.readAnswer = MockResponse().setResponseCode(503)
        s.readGoesOn.countDown()
        waitFor { runCatching { compose.onNode(switchRow).assertIsEnabled() }.isSuccess }
        assertEquals(0, compose.onAllNodesWithTextCount(SETTING_NOT_SAVED))

        // The save fails too: the error banner under the switch, by the
        // answer's status.
        s.saveAnswer = MockResponse().setResponseCode(503)
        compose.onNode(switchRow).performClick()
        waitFor { s.of("PATCH", "/api/v1/me/settings").isNotEmpty() }
        val patch = s.of("PATCH", "/api/v1/me/settings").single()
        assertEquals("Bearer tok-anna", patch.authorization)
        assertEquals("""{"watchlist_by_kind":false}""", patch.body)
        val banner = settingNotSavedText(LoadFailure.Busy)
        assertEquals("Couldn't save the setting. The server is busy.", banner)
        waitFor { shows(banner) }
        assertTrue(compose.onNode(switchRow).getBoundsInRoot().bottom <= compose.onNodeWithText(banner, useUnmergedTree = true).getBoundsInRoot().top)

        // Used again, it saves, and the banner goes.
        s.saveAnswer = Server.json("""{"watchlist_by_kind":false}""")
        compose.onNode(switchRow).performClick()
        waitFor { s.of("PATCH", "/api/v1/me/settings").size == 2 }
        waitFor { !shows(banner) }
    }

    @Test
    fun aBusyGroupShowsItsChoiceAndTakesNoPressAndNothingElse() {
        var pressed = 0
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                WatchlistGroup(state = WatchlistGroupState("anna", on = true, busy = true), onChange = { pressed++ })
            }
        }

        compose.onNode(switchRow).assertIsNotEnabled()
        compose.onNode(switchRow).performClick()
        compose.runOnIdle { assertEquals(0, pressed) }
        // No word for it: the switch's name and its line, and nothing more.
        assertEquals(0, compose.onAllNodes(hasText("…", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTextCount(SETTING_NOT_SAVED))
    }

    @Test
    fun aPressOnTheSwitchAsksForTheOtherChoice() {
        val pressed = mutableListOf<Boolean>()
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                WatchlistGroup(state = WatchlistGroupState("anna", on = false, busy = false), onChange = { pressed += it })
            }
        }

        compose.onNode(switchRow).assertIsEnabled()
        compose.onNode(switchRow).performClick()
        compose.runOnIdle { assertEquals(listOf(true), pressed) }
    }

    @Test
    fun aFailedSaveSaysWhyUnderTheSwitch() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                WatchlistGroup(state = WatchlistGroupState("anna", on = false, busy = false, failure = LoadFailure.NotAccepted), onChange = {})
            }
        }

        compose.onNodeWithText(
            "Couldn't save the setting. The server didn't accept the request. Check for an app update.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }
}
