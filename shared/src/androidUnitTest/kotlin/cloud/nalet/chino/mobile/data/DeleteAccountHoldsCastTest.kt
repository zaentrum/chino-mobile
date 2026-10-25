package cloud.nalet.chino.mobile.data

import android.app.Application
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.account.AccountDeletion
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
import kotlin.test.assertTrue

/**
 * Deleting the account signed in while it casts: the TV's positions are
 * held from before DELETE /v1/me goes out - none arrives as the server
 * deletes the account's rows - and go on where the account was not deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeleteAccountHoldsCastTest {
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

    @Test
    fun theWritesAreHeldWhileTheServerIsAskedAndGoOnWhereNothingWasDeleted() {
        val c = container()
        runBlocking { c.accountStore.addOrUpdate(account("anna"), setActive = true) }
        val holds = mutableListOf<Pair<String, Boolean>>()
        c.holdCastWrites = { id, hold -> holds += id to hold }
        var left = 0
        c.leaveCasting = { _, _ ->
            left++
            "Living Room TV"
        }
        // No server here: the request gets no answer, and nothing is deleted.
        val answer = runBlocking { c.deleteAccount("anna") }
        assertTrue(answer is AccountDeletion.Failed, "$answer")
        // Held first, let go after - the account is still here, casting on.
        assertEquals(listOf("anna" to true, "anna" to false), holds)
        assertEquals(0, left)
        assertEquals("anna", c.accountStore.snapshotBlocking().activeAccount?.id)
    }
}
