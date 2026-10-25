package cloud.nalet.chino.mobile.ui.settings

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.LocalSignInLauncher
import cloud.nalet.chino.mobile.data.auth.SignInLauncher
import cloud.nalet.chino.mobile.data.auth.SignInResult
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import cloud.nalet.chino.mobile.data.downloads.SIGN_OUT_TITLE
import cloud.nalet.chino.mobile.ui.profile.ProfileScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * A sign-out from Settings or from the account's page, while the account
 * casts: the TV's last position is written first, a few seconds on a slow
 * server. The question stays open meanwhile, its danger button busy, and
 * nothing starts a second sign-out - not the danger button, not the Sign out
 * row - until it is done and the screen goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignOutBusyTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication() as Application

    private fun container() = AppContainer(
        buildConfig = AppConfig(flavor = AppConfig.Flavor.PROD, apiBaseUrl = "", oidcIssuer = "", oidcClientId = "chino", displayName = "Chino"),
        accountStoreFactory = PlatformAccountStoreFactory(app),
        tokenStoreFactory = PlatformTokenStoreFactory(app),
        settingsStoreFactory = PlatformSettingsStoreFactory(app),
        serverConfigStoreFactory = PlatformServerConfigStoreFactory(app),
        deviceStaticContext = emptyMap(),
    )

    private fun account(id: String) =
        Account(id = id, displayName = id, email = "", accessToken = "t", refreshToken = null, expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0)

    /** A TV that takes its time to have the last position written: each
     *  leave is counted, and finishes once [release] is completed. */
    private class SlowTv {
        val release = CompletableDeferred<Unit>()
        var leaves = 0
        val leave: suspend (String, Boolean) -> String? = { _, _ ->
            leaves++
            release.await()
            "Living Room TV"
        }
    }

    private object NoSignIn : SignInLauncher {
        override suspend fun signIn(): SignInResult = SignInResult.Cancelled
    }

    private object SettingsHost : Screen {
        @Composable
        override fun Content() = SettingsSection()
    }

    /** Anna signs out from [screen] while she casts, the TV slow to take
     *  her last position: busy until it is done, then she is gone. */
    private fun signsOutOnceAndShowsItBusy(screen: Screen, signOutRow: String) {
        val c = container()
        runBlocking {
            c.accountStore.addOrUpdate(account("ben"), setActive = false)
            c.accountStore.addOrUpdate(account("anna"), setActive = true)
        }
        val tv = SlowTv()
        c.castingDeviceOf = { id -> "Living Room TV".takeIf { id == "anna" } }
        c.leaveCasting = tv.leave
        compose.setContent {
            // The account picker the screen goes to can add an account: it
            // is never asked to here.
            CompositionLocalProvider(LocalAppContainer provides c, LocalSignInLauncher provides NoSignIn) { Navigator(screen) }
        }
        // The account is on the screen before Sign out means it.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("anna").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(signOutRow).onFirst().performClick()

        // The question: casting stops. Its danger button signs out.
        val dangerButton = hasText(SIGN_OUT_TITLE) and hasClickAction()
        compose.waitUntil(10_000) { compose.onAllNodes(dangerButton).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(dangerButton).onFirst().performClick()
        compose.waitUntil(10_000) { tv.leaves == 1 }

        // Meanwhile: the question stays, busy; cancel and the Sign out row
        // take no tap; a second tap on the danger button starts nothing.
        val busy = dangerButton and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "busy")
        compose.onAllNodes(busy).assertCountEquals(1)
        compose.onAllNodes(hasText("cancel") and hasClickAction()).onFirst().assertIsNotEnabled()
        compose.onAllNodes(hasText(signOutRow) and hasClickAction()).onFirst().assertIsNotEnabled()
        compose.onAllNodes(busy).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(1, tv.leaves)

        // The TV has her position: she is signed out, and the screen goes.
        tv.release.complete(Unit)
        compose.waitUntil(10_000) { c.accountStore.snapshotBlocking().accounts.none { it.id == "anna" } }
        compose.waitUntil(10_000) { compose.onAllNodes(busy).fetchSemanticsNodes().isEmpty() }
        assertEquals(1, tv.leaves)
    }

    @Test
    fun aSignOutFromSettingsWhileItCastsIsBusyUntilDoneAndRunsOnce() =
        signsOutOnceAndShowsItBusy(SettingsHost, signOutRow = "Sign out")

    @Test
    fun aSignOutFromTheAccountsPageWhileItCastsIsBusyUntilDoneAndRunsOnce() =
        signsOutOnceAndShowsItBusy(ProfileScreen(), signOutRow = "Sign out")
}
