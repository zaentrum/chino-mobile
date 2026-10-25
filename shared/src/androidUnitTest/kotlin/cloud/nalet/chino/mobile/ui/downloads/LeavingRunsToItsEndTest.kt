package cloud.nalet.chino.mobile.ui.downloads

import android.app.Application
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Signing out, removing an account and switching to another wait for the
 * TV's last position to be written while the account casts - a few seconds
 * on a slow server. The screen that started one can go meanwhile (Back,
 * another tab): the action still runs to its end - casting stopped, the
 * account gone or switched - never an account half gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeavingRunsToItsEndTest {
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

    /** A TV that takes its time to have the last position written: the
     *  leave starts, and finishes once [release] is completed. */
    private class SlowTv {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val left = mutableListOf<String>()
        val leave: suspend (String, Boolean) -> String? = { accountId, _ ->
            started.complete(Unit)
            release.await()
            left += accountId
            "Living Room TV"
        }
    }

    /** [action] started from a screen's scope, that scope cancelled while
     *  the TV's write is out - Back - and the write then done. */
    private fun startThenLeaveTheScreen(tv: SlowTv, action: suspend () -> Unit) = runBlocking {
        val screen = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = screen.launch { action() }
        withTimeout(10_000) { tv.started.await() }
        screen.cancel()
        tv.release.complete(Unit)
        withTimeout(10_000) { job.join() }
    }

    @Test
    fun aSignOutWhoseScreenGoesWhileTheTvsPositionIsWrittenStillSignsOut() {
        val c = container()
        runBlocking { c.accountStore.addOrUpdate(account("anna"), setActive = true) }
        val tv = SlowTv()
        c.leaveCasting = tv.leave
        startThenLeaveTheScreen(tv) { signOutOfThisPhone(c, "anna", castingSaid = true) }
        assertEquals(listOf("anna"), tv.left)
        assertTrue(c.accountStore.snapshotBlocking().accounts.none { it.id == "anna" }, "still signed in")
    }

    @Test
    fun aRemovalWhoseScreenGoesMeanwhileStillRemovesTheAccount() {
        val c = container()
        runBlocking {
            c.accountStore.addOrUpdate(account("anna"), setActive = false)
            c.accountStore.addOrUpdate(account("ben"), setActive = true)
        }
        val tv = SlowTv()
        c.leaveCasting = tv.leave
        startThenLeaveTheScreen(tv) { removeFromThisPhone(c, "ben", castingSaid = true) }
        assertEquals(listOf("ben"), tv.left)
        val left = c.accountStore.snapshotBlocking().accounts.map { it.id }
        assertFalse("ben" in left)
        assertTrue("anna" in left)
    }

    @Test
    fun aSwitchWhoseScreenGoesMeanwhileStillSwitches() {
        val c = container()
        runBlocking {
            c.accountStore.addOrUpdate(account("anna"), setActive = false)
            c.accountStore.addOrUpdate(account("ben"), setActive = true)
        }
        val tv = SlowTv()
        c.leaveCasting = tv.leave
        startThenLeaveTheScreen(tv) { switchAccount(c, from = "ben", to = "anna") }
        // The account before left the TV, and the other is signed in.
        assertEquals(listOf("ben"), tv.left)
        assertEquals("anna", c.accountStore.snapshotBlocking().activeAccount?.id)
    }

    @Test
    fun signInAgainStopsCastingBeforeTheAccountGoesAndSaysSo() {
        val c = container()
        runBlocking {
            c.accountStore.addOrUpdate(account("ben"), setActive = false)
            c.accountStore.addOrUpdate(account("anna"), setActive = true)
        }
        val tv = SlowTv()
        // Whether the account was still there as the TV's last position was
        // written: as it, never as whoever comes next.
        var annaThereAsItLeft: Boolean? = null
        c.leaveCasting = { id, writeLast ->
            annaThereAsItLeft = c.accountStore.snapshotBlocking().activeAccount?.id == id
            tv.leave(id, writeLast)
        }
        val said = java.util.Collections.synchronizedList(mutableListOf<String>())
        val listening = CoroutineScope(Dispatchers.Default).launch { c.toasts.collect { said += it.text } }
        runBlocking { withTimeout(10_000) { c.toasts.subscriptionCount.first { it > 0 } } }
        startThenLeaveTheScreen(tv) { signInAgain(c, "anna") }
        runBlocking { withTimeout(10_000) { while (said.isEmpty()) delay(10) } }
        listening.cancel()
        assertEquals(listOf("anna"), tv.left)
        assertEquals(true, annaThereAsItLeft)
        assertEquals(listOf("Stopped casting on Living Room TV."), said.toList())
        // Anna is gone; Ben, who comes next, is still on the phone.
        val left = c.accountStore.snapshotBlocking().accounts.map { it.id }
        assertFalse("anna" in left)
        assertTrue("ben" in left)
    }

    @Test
    fun aSwitchToTheAccountSignedInLeavesNothing() {
        val c = container()
        runBlocking { c.accountStore.addOrUpdate(account("anna"), setActive = true) }
        val tv = SlowTv()
        tv.release.complete(Unit)
        c.leaveCasting = tv.leave
        runBlocking { switchAccount(c, from = "anna", to = "anna") }
        assertTrue(tv.left.isEmpty())
        assertEquals("anna", c.accountStore.snapshotBlocking().activeAccount?.id)
    }
}
