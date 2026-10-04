package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The iOS stores share one NSUserDefaults suite: signing in must not cost the
 *  server config, and a relaunch must find both. */
class IosStoresTest {
    // A suite of its own per test run; the keys are removed afterwards.
    private val suite = "cloud.nalet.chino.mobile.ios-stores-test-" + Random.nextLong().toULong().toString(16)

    @AfterTest
    fun wipe() {
        val defaults = NSUserDefaults(suiteName = suite)
        for (key in listOf("blob_v1", "server_config_v1")) defaults.removeObjectForKey(key)
    }

    private val server = ServerConfig(baseUrl = "https://media.example.org/api/", issuer = "https://id.example.org/realms/m", clientId = "chino-mobile")

    private val account = Account(
        id = "viewer-1",
        displayName = "Test Viewer",
        email = "viewer@example.org",
        accessToken = "a",
        refreshToken = "r",
        expiresAtEpochMillis = 0,
        lastUsedAt = 0,
    )

    @Test
    fun theAccountsAndTheServerConfigKeepEachOther() = runTest {
        PlatformServerConfigStoreFactory(suite).create().save(server)
        PlatformAccountStoreFactory(suite).create().addOrUpdate(account, setActive = true)

        // A relaunch: fresh stores over the same suite.
        assertEquals(server, PlatformServerConfigStoreFactory(suite).create().current())
        assertEquals("viewer-1", PlatformAccountStoreFactory(suite).create().snapshotBlocking().activeAccount?.id)
    }

    @Test
    fun aServerConfigLeftUnderTheSharedKeyIsMovedAndAccountsThereAreNotTakenForOne() = runTest {
        val defaults = NSUserDefaults(suiteName = suite)
        defaults.setObject("""{"config":{"base_url":"https://media.example.org/api/","issuer":"https://id.example.org/realms/m","client_id":"chino-mobile"},"recents":["https://media.example.org"]}""", "blob_v1")
        val moved = PlatformServerConfigStoreFactory(suite).create()
        assertEquals("https://media.example.org/api/", moved.current()?.baseUrl)
        assertEquals(listOf("https://media.example.org"), moved.recents())

        wipe()
        NSUserDefaults(suiteName = suite).setObject("""{"accounts":[],"active_id":null}""", "blob_v1")
        assertNull(PlatformServerConfigStoreFactory(suite).create().current())
    }
}
