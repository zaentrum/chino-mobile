package cloud.nalet.chino.mobile.ui.browse

import android.app.Application
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import cloud.nalet.chino.mobile.data.downloads.UNREACHABLE_BANNER_NOTHING
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.paging.Paged
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Movies with the server gone mid-session, as drawn: the list the phone has
 * and its genre chips under the information banner, which stays under the
 * top bar as the grid scrolls - never "couldn't load the movies" over it;
 * a filter the phone never had couldn't load, and says no reason the banner
 * already says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BrowseOfflineScreenTest {
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

    private val films = Paged(
        items = (1..30).map { Item(id = "m$it", title = "Harbour Lights $it", kind = "movie", year = 1990 + it) },
        nextOffset = 30,
        endReached = true,
    )

    private fun grid(state: BrowseUiState, banner: String?, onRetry: () -> Unit = {}) {
        val c = container()
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides c) {
                BrowseContent(
                    state = state,
                    pageTitle = "Movies",
                    banner = banner,
                    gridState = rememberLazyGridState(),
                    onFilter = {},
                    onRetry = onRetry,
                    onRetryLoadMore = {},
                    onItemSelected = {},
                    onToggleWatched = {},
                    onPlay = null,
                )
            }
        }
    }

    private val banner = "information: $UNREACHABLE_BANNER_NOTHING"

    @Test
    fun theListThePhoneHasStaysUnderTheBannerWithItsGenreChips() {
        grid(
            BrowseUiState(paged = films, genres = listOf("Action", "Comedy"), loading = false, baseUrl = "http://localhost", streamToken = "t", offline = true),
            banner = UNREACHABLE_BANNER_NOTHING,
        )
        compose.onNodeWithContentDescription(banner).assertIsDisplayed()
        compose.onNodeWithText("movies").assertIsDisplayed()
        compose.onNodeWithText("Action").assertExists()
        compose.onNodeWithText("Comedy").assertExists()
        compose.onNodeWithText("Harbour Lights 1").assertIsDisplayed()
        compose.onNodeWithText(couldntLoadTitle("Movies")).assertDoesNotExist()
    }

    @Test
    fun theBannerStaysUnderTheTopBarAsTheGridScrolls() {
        grid(BrowseUiState(paged = films, loading = false, offline = true), banner = UNREACHABLE_BANNER_NOTHING)
        val top = compose.onNodeWithContentDescription(banner).fetchSemanticsNode().boundsInRoot.top
        // Down to the end of the list: the heading has gone, the banner not.
        // The grid, not a row of chips.
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(films.items.size + 3)
        compose.onNodeWithText("30 movies in all.").assertIsDisplayed()
        compose.onNodeWithText("movies").assertIsNotDisplayed()
        compose.onNodeWithContentDescription(banner).assertIsDisplayed()
        assertEquals(top, compose.onNodeWithContentDescription(banner).fetchSemanticsNode().boundsInRoot.top)
    }

    @Test
    fun aFilterThePhoneNeverHadCouldntLoadUnderTheBannerWithoutASecondReason() {
        var retried = 0
        grid(
            BrowseUiState(loading = false, error = LoadFailure.NoAnswer, errorDetails = "no answer", offline = true),
            banner = UNREACHABLE_BANNER_NOTHING,
            onRetry = { retried++ },
        )
        compose.onNodeWithContentDescription(banner).assertIsDisplayed()
        compose.onNodeWithText(couldntLoadTitle("Movies")).assertIsDisplayed()
        // "Can't reach the server." once, in the banner.
        compose.onNodeWithText("Can't reach the server.").assertDoesNotExist()
        compose.onNodeWithText("try again").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test
    fun withoutTheBannerItCouldntLoadSaysWhy() {
        grid(BrowseUiState(loading = false, error = LoadFailure.NoAnswer, errorDetails = "no answer"), banner = null)
        compose.onNodeWithContentDescription(banner).assertDoesNotExist()
        compose.onNodeWithText(couldntLoadTitle("Movies")).assertIsDisplayed()
        compose.onNodeWithText("Can't reach the server.").assertIsDisplayed()
    }
}
