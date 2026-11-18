package cloud.nalet.chino.mobile.ui.auth

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.account.ACCOUNT_DELETED_TOAST
import cloud.nalet.chino.mobile.data.account.AccountLeft
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.LocalSignInLauncher
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import cloud.nalet.chino.mobile.data.auth.SignInLauncher
import cloud.nalet.chino.mobile.data.auth.SignInResult
import cloud.nalet.chino.mobile.ui.components.TOAST_MS
import cloud.nalet.chino.mobile.ui.components.ToastHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An account the server deleted, signed out on this phone: where it was the
 * account signed in, the app starts over as after Delete account - who's
 * watching where another account is left, sign in where none is - with the
 * toast "Account deleted" (section 16.5.6 of the specification), whichever
 * screen was open, or the next one drawn where none was; another account
 * deleted leaves the screen as it is. The
 * navigator and the toasts are drawn as App.kt draws them; the clock is the
 * test's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartOverWhenAccountDeletedTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication() as Application

    private object NoSignIn : SignInLauncher {
        override suspend fun signIn(): SignInResult = SignInResult.Cancelled
    }

    /** A screen of the account signed in: a title's page, say. */
    private object Somewhere : Screen {
        @Composable
        override fun Content() = Text("a title's page")
    }

    private lateinit var navigator: Navigator

    private fun account(id: String) =
        Account(id = id, displayName = id, email = "", accessToken = "tok-$id", refreshToken = null, expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0)

    /** [ids] on the phone, the first signed in, the app on [Somewhere]. */
    private fun phone(vararg ids: String): AppContainer = container(*ids).also { show(it) }

    /** [ids] on the phone, the first signed in; no screen drawn yet. */
    private fun container(vararg ids: String): AppContainer {
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
            ids.reversed().forEach { c.accountStore.addOrUpdate(account(it)) }
            c.accountStore.setActive(ids.first())
        }
        return c
    }

    /** The app drawn on [Somewhere], as App.kt draws it. */
    private fun show(c: AppContainer) {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides c, LocalSignInLauncher provides NoSignIn) {
                Box {
                    Navigator(Somewhere) { nav ->
                        navigator = nav
                        StartOverWhenAccountDeleted(nav)
                        CurrentScreen()
                    }
                    ToastHost(c.toasts)
                }
            }
        }
        compose.waitForIdle()
        // From here the test moves the clock, frame by frame.
        compose.mainClock.autoAdvance = false
    }

    /** [accountId] answered deleted, off the main thread as the client does. */
    private fun AppContainer.answeredDeleted(accountId: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { signOutDeleted(accountId, startOver = true) }
    }

    private fun toastShown() = compose.onAllNodesWithText(ACCOUNT_DELETED_TOAST).fetchSemanticsNodes().isNotEmpty()

    /** Moves the clock a frame at a time until [done], in under a second of it. */
    private fun framesUntil(what: String, done: () -> Boolean) {
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (done()) return
            Thread.sleep(20)
        }
        throw AssertionError("not $what: on ${navigator.lastItem}")
    }

    @Test
    fun theAccountSignedInDeletedOpensWhosWatchingWithTheToastWhichGoesAfterFiveSeconds() {
        val c = phone("anna", "ben")
        c.answeredDeleted("anna")

        framesUntil("on who's watching with the toast") { navigator.lastItem is AccountPickerScreen && toastShown() }
        assertEquals(1, navigator.items.size)
        assertEquals(listOf("ben"), c.accountStore.snapshotBlocking().accounts.map { it.id })
        // Nobody signed in until someone is picked there.
        assertNull(c.accountStore.snapshotBlocking().activeAccount)

        compose.mainClock.advanceTimeBy(TOAST_MS)
        framesUntil("the toast gone") { !toastShown() }
    }

    @Test
    fun theOnlyAccountDeletedOpensSignIn() {
        val c = phone("anna")
        c.answeredDeleted("anna")

        framesUntil("on sign in with the toast") { navigator.lastItem is AuthScreen && toastShown() }
        assertTrue(c.accountStore.snapshotBlocking().accounts.isEmpty())
    }

    @Test
    fun signedOutWithNoScreenDrawnTheNextScreenDrawnStartsOverAndTakesIt() {
        val c = container("anna", "ben")
        // Signed out while no screen is drawn - the app in the background,
        // its activity gone.
        runBlocking { c.signOutDeleted("anna", startOver = true) }
        assertEquals(AccountLeft("anna", wasActive = true, othersRemain = true), c.startOver.value)

        // The activity back, on the stack it had: who's watching, and the
        // start-over taken.
        show(c)
        framesUntil("on who's watching") { navigator.lastItem is AccountPickerScreen }
        assertEquals(1, navigator.items.size)
        assertNull(c.startOver.value)
    }

    @Test
    fun anotherAccountDeletedLeavesTheScreenAsItIsWithTheToast() {
        val c = phone("anna", "ben")
        c.answeredDeleted("ben")

        framesUntil("the toast") { toastShown() }
        assertIs<Somewhere>(navigator.lastItem)
        assertEquals("anna", c.accountStore.snapshotBlocking().activeAccount?.id)
    }
}
