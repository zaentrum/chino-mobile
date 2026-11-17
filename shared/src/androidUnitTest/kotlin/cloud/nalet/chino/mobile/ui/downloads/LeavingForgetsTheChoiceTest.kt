package cloud.nalet.chino.mobile.ui.downloads

import android.app.Application
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * An account that leaves the phone takes the phone's copy of its Separate
 * movies and shows with it (section 16.4.7 of the specification): at a
 * sign-out, where it is removed in the account picker, and where it was
 * deleted. Another account's copy stays, and so does the account's own where
 * a refused sign-in asks it to sign in again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeavingForgetsTheChoiceTest {
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

    /** Two accounts on the phone, each with its choice kept on it. */
    private fun twoAccounts(): AppContainer = container().also { c ->
        runBlocking {
            c.accountStore.addOrUpdate(account("anna"), setActive = true)
            c.accountStore.addOrUpdate(account("ben"), setActive = false)
            c.serverConfigStore.saveWatchlistByKind(c.serverBase, "anna", true)
            c.serverConfigStore.saveWatchlistByKind(c.serverBase, "ben", false)
        }
    }

    /** The store's copy, waited for until it is [expected]: within 10 s. */
    private fun eventually(c: AppContainer, account: String, expected: Boolean?) {
        val end = System.nanoTime() + 10_000_000_000
        while (c.serverConfigStore.watchlistByKindBlocking(c.serverBase, account) != expected && System.nanoTime() < end) {
            Thread.sleep(10)
        }
        assertEquals(expected, c.serverConfigStore.watchlistByKindBlocking(c.serverBase, account))
    }

    @Test
    fun aSignOutTakesTheAccountsChoiceOffThePhone() {
        val c = twoAccounts()
        runBlocking { signOutOfThisPhone(c, "anna", castingSaid = true) }
        eventually(c, "anna", null)
        eventually(c, "ben", false)
    }

    @Test
    fun anAccountRemovedInThePickerTakesItsChoiceOffThePhone() {
        val c = twoAccounts()
        runBlocking { removeFromThisPhone(c, "ben", castingSaid = true) }
        eventually(c, "ben", null)
        eventually(c, "anna", true)
    }

    @Test
    fun aDeletedAccountTakesItsChoiceOffThePhone() {
        val c = twoAccounts()
        // What the deletion does once the server says the account is gone.
        runBlocking { c.forgetDeletedAccount("ben") }
        eventually(c, "ben", null)
        eventually(c, "anna", true)
    }

    @Test
    fun signInAgainKeepsTheAccountsChoiceOnThePhone() {
        val c = twoAccounts()
        runBlocking { signInAgain(c, "anna") }
        eventually(c, "anna", true)
        eventually(c, "ben", false)
    }
}
