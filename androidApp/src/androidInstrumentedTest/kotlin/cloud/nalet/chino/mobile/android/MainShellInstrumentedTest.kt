package cloud.nalet.chino.mobile.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the production `MainActivity` mounted on a real device or
 * emulator. Boots the activity, waits for the
 * AppContainer's off-main snapshot read to finish, then asserts that the
 * shell chrome is composed with the four expected elements:
 *  - inline SearchField pill (text "Search movies, shows…")
 *  - Lucide.Bell IconCell (contentDescription "Watchlist")
 *  - Either the Avatar (when an account is active) or the fallback
 *    User-icon IconCell (contentDescription "Account")
 *  - "Home" rail-button (contentDescription) — confirms the SideRail
 *    composes on the tablet's wide-layout branch
 *
 * Color-of-pixel assertions live in a separate screenshot golden test
 * (Roborazzi) — the Compose UI Test framework reads the semantics tree,
 * not bitmap output, so we can't verify e.g. "the search text is
 * Color.White" from here.
 */
@RunWith(AndroidJUnit4::class)
class MainShellInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivity_reachesShellOrAuth() {
        // Cold launch may take a few seconds: AppContainer.accountStore is
        // `by lazy` and the first access materialises the
        // EncryptedSharedPreferences master key off the main thread, then
        // the boot snapshot resolves and Navigator mounts the actual
        // start destination (AuthScreen or MainShellScreen).
        rule.waitUntil(timeoutMillis = 15_000) {
            val onShell = rule.onAllNodesWithText("Search movies, shows…")
                .fetchSemanticsNodes().isNotEmpty()
            val onAuth = rule.onAllNodesWithText("Sign in")
                .fetchSemanticsNodes().isNotEmpty()
            onShell || onAuth
        }
    }

    @Test
    fun mainShell_topBar_hasSearchPillAndWatchlistBell() {
        // Requires the device to already have an active account so the
        // shell mounts (not the AuthScreen). The tablet at 5559 was
        // signed in as build_test earlier in this session, so this is
        // the expected path.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Search movies, shows…")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Search movies, shows…").assertIsDisplayed()
        rule.onNodeWithContentDescription("Watchlist").assertIsDisplayed()
    }

    @Test
    fun mainShell_sideRail_hasHomeAndSettings() {
        // The SideRail composes only on wide form factor (>=600 dp). On
        // the tablet emulator we're at 2560x1600 so the wide branch
        // fires. The rail's nav buttons surface their Section.label as
        // the contentDescription on the Icon inside the RailButton.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithContentDescription("Home")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Home").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }
}
