package cloud.nalet.chino.mobile.ui.watchlist

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import cloud.nalet.chino.mobile.data.api.Watchlist
import cloud.nalet.chino.mobile.data.model.Item
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watchlist with Separate movies and shows on (section 8.8.1), as drawn:
 * each list's heading with its own count, its movies row and its shows row
 * under it, each from its own answer; a row with nothing in it left out, one
 * that couldn't load named without a count with try again for itself; an
 * opened list split the same way. Off, as today. The titles are invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp")
class WatchlistByKindScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication() as Application

    /** The cards read the app's graph for their marks. */
    private val container by lazy {
        AppContainer(
            buildConfig = AppConfig(flavor = AppConfig.Flavor.PROD, apiBaseUrl = "", oidcIssuer = "", oidcClientId = "chino", displayName = "Chino"),
            accountStoreFactory = PlatformAccountStoreFactory(app),
            tokenStoreFactory = PlatformTokenStoreFactory(app),
            settingsStoreFactory = PlatformSettingsStoreFactory(app),
            serverConfigStoreFactory = PlatformServerConfigStoreFactory(app),
            deviceStaticContext = emptyMap(),
        )
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalAppContainer provides container) { content() } }
    }

    private val watchlist = Watchlist(id = "w", name = "Watchlist", itemCount = 3, isDefault = true)
    private val sunday = Watchlist(id = "sn", name = "Sunday nights", itemCount = 0)

    private val film = Item(id = "m1", title = "Lantern Field", kind = "movie")
    private val bothFilm = Item(id = "m2", title = "Glass Meadow", kind = "movie")
    private val show = Item(id = "s1", title = "Orchard Hours", kind = "series")

    private val opened = mutableListOf<String>()
    private val retried = mutableListOf<Pair<String, TitleKind>>()

    private fun hub(byKind: Map<String, KindRows>?, shelves: Map<String, List<Item>> = emptyMap(), lists: List<Watchlist> = listOf(watchlist)) {
        show {
            WatchlistHub(
                lists = lists,
                shelves = shelves,
                byKind = byKind,
                loading = false,
                baseUrl = "https://media.example.com/api",
                token = "t",
                onItemClick = {},
                onOpenList = { opened += it.id },
                onNewList = {},
                onRetryRow = { list, kind -> retried += list.id to kind },
            )
        }
    }

    private fun top(text: String) = compose.onNodeWithText(text).getBoundsInRoot().top

    @Test
    fun aListsMoviesRowThenItsShowsRowUnderItsHeadingWithItsOwnCount() {
        hub(mapOf("w" to KindRows(KindRow.Loaded(1, listOf(film)), KindRow.Loaded(2, listOf(show)))))

        compose.onNodeWithText("Watchlist · 3").assertIsDisplayed()
        compose.onNodeWithText("movies · 1").assertIsDisplayed()
        compose.onNodeWithText("shows · 2").assertIsDisplayed()
        compose.onNodeWithText("Lantern Field", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Orchard Hours", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(top("Watchlist · 3") < top("movies · 1"))
        assertTrue(top("movies · 1") < top("shows · 2"))
        // Each row ends with see all, which opens the list; so does the
        // heading.
        compose.onAllNodesWithText("See all", useUnmergedTree = true).assertCountEquals(2)
        compose.onNode(hasText("Watchlist · 3") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("w"), opened) }
    }

    @Test
    fun aTitleBothAnswersHoldIsInBothRows() {
        hub(mapOf("w" to KindRows(KindRow.Loaded(2, listOf(film, bothFilm)), KindRow.Loaded(2, listOf(bothFilm, show)))))

        compose.onAllNodesWithText("Glass Meadow", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun aRowWithNothingInItIsLeftOut() {
        hub(mapOf("w" to KindRows(KindRow.Loaded(0, emptyList()), KindRow.Loaded(1, listOf(show)))))

        compose.onAllNodesWithText("movies", substring = true).assertCountEquals(0)
        compose.onNodeWithText("shows · 1").assertIsDisplayed()
    }

    @Test
    fun aListWithNothingInItShowsItsEmptyLineAsWithTheSettingOff() {
        hub(mapOf("w" to KindRows.Empty, "sn" to KindRows.Empty), lists = listOf(watchlist, sunday))

        compose.onNodeWithText("Sunday nights · 0").assertIsDisplayed()
        compose.onAllNodesWithText("Save titles with the + button on any movie or show.").assertCountEquals(2)
        compose.onAllNodesWithText("movies", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("shows", substring = true).assertCountEquals(0)
    }

    @Test
    fun aRowThatCouldntLoadKeepsItsNameWithoutACountAndIsTriedAgainByItself() {
        hub(mapOf("w" to KindRows(KindRow.Loaded(1, listOf(film)), KindRow.Failed(LoadFailure.ServerProblem))))

        compose.onNodeWithText("movies · 1").assertIsDisplayed()
        compose.onNodeWithText("shows").assertIsDisplayed()
        compose.onAllNodesWithText("shows ·", substring = true).assertCountEquals(0)
        compose.onNodeWithText(ROW_COULDNT_LOAD).assertIsDisplayed()
        compose.onNodeWithText("The server ran into a problem.").assertIsDisplayed()
        assertTrue(top("shows") < top(ROW_COULDNT_LOAD))

        compose.onNode(hasText("try again") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("w" to TitleKind.Shows), retried) }
    }

    @Test
    fun aRowAskedAgainKeepsItsName() {
        hub(mapOf("w" to KindRows(KindRow.Loaded(1, listOf(film)), KindRow.Loading)))

        compose.onNodeWithText("shows").assertIsDisplayed()
        compose.onAllNodesWithText(ROW_COULDNT_LOAD).assertCountEquals(0)
    }

    @Test
    fun withTheSettingOffEveryListIsOneRowAsToday() {
        hub(byKind = null, shelves = mapOf("w" to listOf(film, show)))

        compose.onNodeWithText("Watchlist · 3").assertIsDisplayed()
        compose.onNodeWithText("Lantern Field", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Orchard Hours", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("movies", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("shows", substring = true).assertCountEquals(0)
    }

    private fun openedList(kinds: KindRows?, items: List<Item> = emptyList()) {
        show {
            ListMoreView(
                list = watchlist,
                items = items,
                kinds = kinds,
                loading = false,
                baseUrl = "https://media.example.com/api",
                token = "t",
                onBack = {},
                onItemClick = {},
                onRename = {},
                onDelete = {},
                onRetryPart = { kind -> retried += "w" to kind },
            )
        }
    }

    @Test
    fun anOpenedListIsSplitTheSameWay() {
        openedList(KindRows(KindRow.Loaded(2, listOf(film, bothFilm)), KindRow.Loaded(2, listOf(bothFilm, show))))

        // Its count stays where it is.
        compose.onNodeWithText("· 3").assertIsDisplayed()
        compose.onNodeWithText("movies · 2").assertIsDisplayed()
        compose.onNodeWithText("shows · 2").assertIsDisplayed()
        assertTrue(top("movies · 2") < top("shows · 2"))
        assertTrue(compose.onNodeWithText("Lantern Field", useUnmergedTree = true).getBoundsInRoot().top < top("shows · 2"))
        assertTrue(compose.onNodeWithText("Orchard Hours", useUnmergedTree = true).getBoundsInRoot().top > top("shows · 2"))
        // A title in both answers stands in both parts.
        compose.onAllNodesWithText("Glass Meadow", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun anOpenedListsPartThatCouldntLoadIsTriedAgainByItself() {
        openedList(KindRows(KindRow.Failed(LoadFailure.NoAnswer), KindRow.Loaded(1, listOf(show))))

        compose.onNodeWithText("movies").assertIsDisplayed()
        compose.onNodeWithText(ROW_COULDNT_LOAD).assertIsDisplayed()
        compose.onNodeWithText("Can't reach the server.").assertIsDisplayed()
        compose.onNodeWithText("shows · 1").assertIsDisplayed()
        compose.onNode(hasText("try again") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("w" to TitleKind.Movies), retried) }
    }

    @Test
    fun anOpenedListWithNothingInItShowsItsEmptyLine() {
        openedList(KindRows.Empty)

        compose.onNodeWithText("Add titles with the bookmark on a movie's or a show's page.").assertIsDisplayed()
        compose.onAllNodesWithText("movies", substring = true).assertCountEquals(0)
    }
}
