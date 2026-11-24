package cloud.nalet.chino.mobile.ui.search

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.ServerConfig
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
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
import kotlin.test.assertTrue

/**
 * The search's results as drawn (change 9.C16 of the specification, its
 * figure 9.7): the titles first and cast & crew under them; where only
 * people match, they stand at the top. The results are drawn whole against a
 * server stood up in the test, which answers one search as chino-api does.
 * The titles are open films; the people are invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class SearchResultsOrderTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication() as Application

    private val titlesAndPeople = """
        {"product":"chino","items":[
          {"id":"m1","type":"movie","title":"Sintel","year":2010,"match":"prefix"},
          {"id":"m2","type":"movie","title":"Tears of Steel","year":2012,"match":"words"}],
         "people":[
          {"id":"p1","name":"Ada Night","credits":1,"has_profile":false,"match":"words"},
          {"id":"p2","name":"Bo Knight","credits":4,"has_profile":false,"match":"similar"}],
         "source":"katalog"}
    """

    private val peopleOnly = """
        {"product":"chino","items":[],
         "people":[{"id":"p1","name":"Ada Night","credits":1,"has_profile":false,"match":"words"}],
         "source":"katalog"}
    """

    @Volatile
    private var answer = titlesAndPeople

    private val web = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().startsWith("/api/v1/search")) {
                    MockResponse().setHeader("Content-Type", "application/json").setBody(answer)
                } else {
                    // The posters: none, the cards draw without them.
                    MockResponse().setResponseCode(404)
                }
        }
        start()
    }

    @After
    fun tearDown() {
        web.shutdown()
    }

    /** The results for "s" on the test's server. */
    private fun search() {
        runBlocking {
            PlatformServerConfigStoreFactory(app).create().save(
                ServerConfig(baseUrl = web.url("/api/").toString(), issuer = web.url("/auth").toString(), clientId = "chino"),
            )
        }
        val container = AppContainer(
            buildConfig = AppConfig(flavor = AppConfig.Flavor.PROD, apiBaseUrl = "", oidcIssuer = "", oidcClientId = "chino", displayName = "Chino"),
            accountStoreFactory = PlatformAccountStoreFactory(app),
            tokenStoreFactory = PlatformTokenStoreFactory(app),
            settingsStoreFactory = PlatformSettingsStoreFactory(app),
            serverConfigStoreFactory = PlatformServerConfigStoreFactory(app),
            deviceStaticContext = emptyMap(),
        )
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                SearchResultsSection(container = container, query = "s", onItemSelected = {})
            }
        }
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun top(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot().top

    @Test
    fun change9_C16_theTitlesStandBeforeCastAndCrew() {
        search()
        compose.waitUntil(20_000) { shows("Tears of Steel") && shows("Cast & crew") }

        // The heading and the titles, then cast & crew under the last title.
        assertTrue(top("2 results for \"s\"") < top("Sintel"))
        assertTrue(top("Sintel") < top("Cast & crew"), "the titles stand first")
        assertTrue(top("Tears of Steel") < top("Cast & crew"), "cast & crew follows the last title")
        assertTrue(top("Cast & crew") < top("Ada Night"))
        assertTrue(top("Ada Night") < top("Bo Knight"))
    }

    @Test
    fun change9_C16_whereOnlyPeopleMatchTheyStandAtTheTop() {
        answer = peopleOnly
        search()
        compose.waitUntil(20_000) { shows("Ada Night") && shows("No results for \"s\"") }

        assertTrue(top("Cast & crew") < top("Ada Night"))
        assertTrue(top("Ada Night") < top("No results for \"s\""), "the people stand at the top")
    }
}
