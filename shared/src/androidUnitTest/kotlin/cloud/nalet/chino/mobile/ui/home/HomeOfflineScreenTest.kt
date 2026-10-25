package cloud.nalet.chino.mobile.ui.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.NO_ACCESS_TITLE
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import cloud.nalet.chino.mobile.data.downloads.UNREACHABLE_BANNER_NOTHING
import cloud.nalet.chino.mobile.data.model.Item
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Home with the server gone, as drawn: the banner on top as on the other
 * screens - fixed under the top bar as on a title's page - the rows the
 * phone knows under it with the hero still resuming, or Continue watching
 * saying it couldn't load - never gone without a word; and a pull-down that
 * asks the server at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HomeOfflineScreenTest {
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

    private val film = Item(id = "m1", title = "Harbour Lights", kind = "movie")
    private val row = ContinueWatchingItem(id = "m1", title = "Harbour Lights", positionSec = 103, durationSec = 5400, type = "movie")

    private fun ready(continueWatching: List<ContinueWatchingItem>, failed: ShelfFailure?) = HomeUiState.Ready(
        heroPool = listOf(film),
        continueWatching = continueWatching,
        nextUp = emptyList(),
        recentMovies = emptyList(),
        recentSeries = emptyList(),
        topRated = emptyList(),
        baseUrl = "http://localhost",
        streamToken = "t",
        heroPlays = heroPlay(film, continueWatching, null)?.let { mapOf("m1" to it) }.orEmpty(),
        offline = true,
        continueWatchingFailed = failed,
    )

    private fun home(
        state: HomeUiState.Ready,
        onRetry: () -> Unit = {},
        banner: String? = UNREACHABLE_BANNER_NOTHING,
        onPullDown: () -> Unit = {},
    ) {
        val c = container()
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides c) {
                ReadyContent(
                    state,
                    onItemSelected = {},
                    banner = banner,
                    onPullDown = onPullDown,
                    onRetry = onRetry,
                    onPlayCard = { _, _ -> },
                    onPlayHero = { _, _ -> },
                    onTrailerHero = { _, _ -> },
                    onRemoveFromContinueWatching = {},
                    onToggleWatched = {},
                )
            }
        }
    }

    @Test
    fun theRowsThePhoneKnowsStayUnderTheBannerAndTheHeroResumes() {
        home(ready(listOf(row), failed = null))
        compose.onNodeWithContentDescription("information: $UNREACHABLE_BANNER_NOTHING").assertExists()
        compose.onNodeWithText("Continue watching").assertExists()
        // The hero resumes where the phone knows it was stopped.
        compose.onNodeWithText("resume 1:43").assertExists()
        compose.onNodeWithText("start over").assertExists()
        compose.onNodeWithText(CONTINUE_WATCHING_COULDNT_LOAD).assertDoesNotExist()
    }

    @Test
    fun nothingKnownContinueWatchingSaysItCouldntLoadWithTryAgain() {
        var retried = 0
        home(ready(emptyList(), failed = ShelfFailure(sentence = null, details = "no answer")), onRetry = { retried++ })
        compose.onNodeWithContentDescription("information: $UNREACHABLE_BANNER_NOTHING").assertExists()
        compose.onNodeWithText("Continue watching").assertExists()
        compose.onNodeWithText(CONTINUE_WATCHING_COULDNT_LOAD).assertExists()
        // The banner says why: no second "Can't reach the server." under it.
        compose.onNodeWithText("Can't reach the server.").assertDoesNotExist()
        compose.onNodeWithText("try again").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test
    fun noAccessToContinueWatchingSaysSoWithNothingToTryAgainNeverCantReachTheServer() {
        val failure = ShelfFailure(sentence = null, details = "HTTP 403", title = NO_ACCESS_TITLE, tryAgain = false)
        home(ready(emptyList(), failed = failure).copy(offline = false), banner = null)
        compose.onNodeWithText("Continue watching").assertExists()
        compose.onNodeWithText(NO_ACCESS_TITLE).assertExists()
        compose.onNodeWithText(CONTINUE_WATCHING_COULDNT_LOAD).assertDoesNotExist()
        compose.onNodeWithText("try again").assertDoesNotExist()
        compose.onNodeWithText("Can't reach the server.").assertDoesNotExist()
    }

    /** Rows enough for the page to scroll well past a screen. */
    private fun longer(state: HomeUiState.Ready) = state.copy(
        recentMovies = (1..8).map { Item(id = "r$it", title = "Northern Coast $it", kind = "movie") },
        recentSeries = (1..8).map { Item(id = "s$it", title = "Quiet Water $it", kind = "series") },
        topRated = (1..8).map { Item(id = "t$it", title = "Harbour Lights $it", kind = "movie") },
    )

    @Test
    fun theBannerStaysUnderTheTopBarAsThePageScrollsAsOnATitlesPage() {
        home(longer(ready(listOf(row), failed = null)))
        val banner = compose.onNodeWithContentDescription("information: $UNREACHABLE_BANNER_NOTHING")
        banner.assertIsDisplayed()
        val top = banner.fetchSemanticsNode().boundsInRoot.top
        // Down to the last row: the hero has gone up out of sight, the
        // banner is where it was.
        compose.onNodeWithText("Top rated").performScrollTo()
        compose.onNodeWithText("resume 1:43").assertIsNotDisplayed()
        banner.assertIsDisplayed()
        assertEquals(top, banner.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test
    fun aPullDownAsksAtOnce() {
        var pulled = 0
        home(longer(ready(listOf(row), failed = null)).copy(offline = false), banner = null, onPullDown = { pulled++ })
        // The page's own scroll, not a row's.
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 400) }
        compose.runOnIdle { assertEquals(1, pulled) }
    }
}
