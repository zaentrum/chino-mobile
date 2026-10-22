package cloud.nalet.chino.mobile.downloads

import android.app.Application
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.offline.DownloadRequest
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.ServerConfig
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import cloud.nalet.chino.mobile.data.downloads.DownloadedTitle
import cloud.nalet.chino.mobile.data.downloads.OwnerClaim
import cloud.nalet.chino.mobile.data.downloads.STOP_NONE
import cloud.nalet.chino.mobile.data.downloads.STOP_OWNER_AWAY
import cloud.nalet.chino.mobile.data.downloads.downloadKey
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.isActive
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A change of server builds the app's graph anew: from then on only the new
 * graph's downloads say which download may run. The old graph's - its owner
 * the old server's account - would set "owner away" on the new server's
 * download while the new one sets "none", each change setting off the other;
 * its work stops with it (AppContainer.close), and nothing it still holds
 * sets anything once it is not the app's graph. Two servers, one phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TwoServersTest.Host::class)
class TwoServersTest {
    /** The app's host, as the Application is: the graph it has now. */
    class Host : Application(), AppContainerHost {
        override lateinit var container: AppContainer
    }

    private val app = RuntimeEnvironment.getApplication() as Host

    /** A server that answers nothing useful: the test is the phone's. */
    private fun server() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        start()
    }

    private val one = server()
    private val two = server()

    @After
    fun tearDown() {
        one.shutdown()
        two.shutdown()
    }

    private val platform = AndroidDownloadsPlatform(app)

    /** The graph for [server], as the Application builds it after the server
     *  was saved. */
    private fun graphFor(server: MockWebServer): AppContainer {
        runBlocking {
            PlatformServerConfigStoreFactory(app).create().save(
                ServerConfig(baseUrl = server.url("/api").toString(), issuer = server.url("/auth").toString(), clientId = "chino"),
            )
        }
        return AppContainer(
            buildConfig = AppConfig(flavor = AppConfig.Flavor.PROD, apiBaseUrl = "", oidcIssuer = "", oidcClientId = "chino", displayName = "Chino"),
            accountStoreFactory = PlatformAccountStoreFactory(app),
            tokenStoreFactory = PlatformTokenStoreFactory(app),
            settingsStoreFactory = PlatformSettingsStoreFactory(app),
            serverConfigStoreFactory = PlatformServerConfigStoreFactory(app),
            deviceStaticContext = emptyMap(),
            downloadsPlatform = platform,
        ).also { it.serverBase }
    }

    /** The main thread's work, and the manager's thread's, for a while. */
    private fun settle(ms: Long = 1_500) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun afterAChangeOfServerOnlyTheNewGraphSetsTheStopReasons() {
        // No connection: the download waits, and fetches nothing.
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        val engine = DownloadEngine.get(app)

        // Signed in on the first server: its graph is the app's.
        val first = graphFor(one)
        app.container = first
        runBlocking {
            first.accountStore.addOrUpdate(
                Account(id = "viewer", displayName = "Viewer", email = "", accessToken = "t", refreshToken = null, expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0),
                setActive = true,
            )
        }
        first.downloads
        // A download of the second server's, for the same person there.
        val serverTwo = two.url("/api").toString().trimEnd('/')
        val key = downloadKey(serverTwo, "film")
        runBlocking { engine.titles.awaitReady() }
        engine.titles.put(
            DownloadedTitle(
                itemId = "film",
                item = Item(id = "film", title = "A Film", kind = "movie"),
                quality = "720p",
                server = serverTwo,
                owners = listOf(OwnerClaim("viewer", addedAtMs = 1, checkedAtMs = 1)),
            ),
        )
        engine.addDirectly(
            DownloadRequest.Builder(key, Uri.parse("$serverTwo/v1/items/film/play/master.m3u8")).setMimeType(MimeTypes.APPLICATION_M3U8).build(),
            STOP_NONE,
        )
        settle()
        // The first server's graph: not its owner's - it waits, away.
        assertEquals(STOP_OWNER_AWAY, engine.manager.downloadIndex.getDownload(key)?.stopReason)

        // Change server: the second server's graph is the app's, the first's
        // work stops (the Application's rebuildContainer).
        val second = graphFor(two)
        app.container = second
        first.close()
        second.downloads
        settle()
        assertFalse(first.appScope.isActive)
        assertEquals(STOP_NONE, engine.manager.downloadIndex.getDownload(key)?.stopReason)

        // And it stays so as the downloads change: nothing of the first
        // graph's sets it back.
        repeat(3) { n ->
            engine.titles.update(key) { it.copy(estimatedBytes = 1_000L + n) }
            settle(500)
            assertEquals(STOP_NONE, engine.manager.downloadIndex.getDownload(key)?.stopReason, "after change ${n + 1}")
        }
    }
}
