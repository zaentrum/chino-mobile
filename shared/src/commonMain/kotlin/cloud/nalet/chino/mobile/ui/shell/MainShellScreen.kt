package cloud.nalet.chino.mobile.ui.shell

import androidx.compose.ui.graphics.RectangleShape

import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoBg2
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder2
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoDim
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Tv
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Zap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.ui.components.Avatar
import cloud.nalet.chino.mobile.ui.detail.DetailScreen
import cloud.nalet.chino.mobile.ui.home.HomeSection
import cloud.nalet.chino.mobile.ui.player.PlayerScreen
import cloud.nalet.chino.mobile.ui.search.SearchScreen
import cloud.nalet.chino.mobile.ui.settings.SettingsSection
import cloud.nalet.chino.mobile.ui.watchlist.WatchlistScreen
import cloud.nalet.chino.mobile.ui.zap.InstallZapPrefetcher
import cloud.nalet.chino.mobile.ui.zap.ZapAppStartWarm

/**
 * Main signed-in shell. Responsive split mirrors chino-web's tablet-vs-
 * phone breakpoint (Tailwind `md` ≈ 768px / ~600dp):
 *
 *   width >= 600dp  → vertical NavigationRail on the left + Header up top
 *                     (matches the web tablet/desktop layout the user
 *                     showed on the 5559 emulator).
 *   width <  600dp  → bottom NavigationBar + Header up top (matches the
 *                     web mobile layout the user showed on the 5555
 *                     emulator). Hero collapses to image-then-text
 *                     stack — driven by HeroBanner's own breakpoint.
 *
 * Icon family is the lucide-extended subset of Material outlined icons
 * (Home / Film / Tv / Settings / Bell / User) so the glyphs match
 * chino-web's lucide-react set as closely as the Material set allows.
 */
class MainShellScreen : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val container = LocalAppContainer.current

        // Install the Zap client-side segment prefetcher at app-shell scope so
        // its on-disk cache + bridge handler exist BEFORE the user opens Zap
        // (Android = Media3 SimpleCache + CacheWriter; iOS = no-op). The Zap
        // ScreenModel drives it via ZapPrefetchBridge on feed-fill / settle; the
        // app-start warm below uses it too.
        InstallZapPrefetcher()
        // App-start warm: kick a lightweight prefetch of just the FIRST upcoming
        // Zap card once per process, so opening Zap is instant.
        androidx.compose.runtime.LaunchedEffect(Unit) { ZapAppStartWarm.warmOnce(container) }
        // Drain crash reports a previous process wrote on its way down. Fired
        // here (not Application.onCreate) because the shell only composes once
        // signed in — POST /v1/feedback needs the bearer. Once per process;
        // files that fail to submit survive for the next launch.
        androidx.compose.runtime.LaunchedEffect(Unit) { container.bugReporter.flushPending() }

        var section by remember { mutableStateOf(Section.Home) }
        // Lifted search state — drives both the TopBar SearchField and
        // the SearchResultsSection. Non-empty query hides the active
        // section and shows results inline (mirroring chino-web, where
        // the search bar in the header overlays the page with results).
        var searchQuery by remember { mutableStateOf("") }
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val isWide = maxWidth >= 600.dp
            // #150: lets a Home rail's "See all" tile switch the active
            // section (same path the bottom-nav / side-rail use).
            val onNavigateToSection: (String) -> Unit = { target ->
                when (target) {
                    "movies" -> section = Section.Movies
                    "series" -> section = Section.Series
                    else -> section = Section.Home
                }
            }
            if (isWide) {
                WideLayout(
                    section = section,
                    onChange = { section = it },
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                ) {
                    SectionContent(section, container, searchQuery, onNavigateToSection)
                }
            } else {
                NarrowLayout(
                    section = section,
                    onChange = { section = it },
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                ) {
                    SectionContent(section, container, searchQuery, onNavigateToSection)
                }
            }
        }
    }
}

@Composable
private fun SectionContent(
    section: Section,
    container: cloud.nalet.chino.mobile.data.AppContainer,
    searchQuery: String,
    onNavigateToSection: (String) -> Unit,
) {
    val nav = LocalNavigator.currentOrThrow
    val onItemSelected: (String) -> Unit = { id -> nav.push(DetailScreen(id)) }
    val onPlay: (String) -> Unit = { id -> nav.push(PlayerScreen(itemId = id, fromStart = false)) }
    // When the search field has text, results take over the content
    // area. Switching tabs while searching keeps the search visible —
    // user clears the query to return to the active section.
    if (searchQuery.isNotBlank()) {
        cloud.nalet.chino.mobile.ui.search.SearchResultsSection(
            container = container,
            query = searchQuery,
            onItemSelected = onItemSelected,
            onPersonSelected = { person ->
                nav.push(cloud.nalet.chino.mobile.ui.person.PersonScreen(person.id, person.name))
            },
        )
        return
    }
    when (section) {
        Section.Home -> HomeSection(
            container,
            onItemSelected = onItemSelected,
            onPlay = onPlay,
            onNavigateToSection = onNavigateToSection,
        )
        Section.Zap -> cloud.nalet.chino.mobile.ui.zap.ZapScreen().Content()
        Section.Movies -> cloud.nalet.chino.mobile.ui.browse.BrowseSection(
            container = container,
            type = "movie",
            pageTitle = "Movies",
            onItemSelected = onItemSelected,
        )
        Section.Series -> cloud.nalet.chino.mobile.ui.browse.BrowseSection(
            container = container,
            type = "series",
            pageTitle = "Shows",
            onItemSelected = onItemSelected,
        )
        // Same composable the bell-pushed WatchlistScreen renders — the
        // section path just embeds it under the shell TopBar.
        Section.Watchlist -> cloud.nalet.chino.mobile.ui.watchlist.WatchlistSection()
        Section.Settings -> SettingsSection()
    }
}

enum class Section(val label: String, val icon: ImageVector) {
    // Order MUST match chino-web's nav (ChinoSidebar / ChinoMobileNav) and the
    // TV side-rail: Home, Movies, Series, Watchlist, Zap, Settings. The nav
    // iterates Section.entries, so this declaration order IS the on-screen
    // order.
    Home("Home", Lucide.House),
    Movies("Movies", Lucide.Film),
    Series("Series", Lucide.Tv),
    // Watchlist — the cross-platform hub of per-list poster shelves. The
    // TopBar bell keeps working as a secondary entry point and lands on the
    // same WatchlistSection surface.
    Watchlist("Watchlist", Lucide.Bookmark),
    // Zap — the vertical reels discovery mode (web flavour). Lucide Zap glyph
    // matches chino-web's ZapSection icon. Renders its own full-bleed pager
    // inside the content area, the same way the web Zap section bleeds out of
    // the page padding.
    Zap("Zap", Lucide.Zap),
    Settings("Settings", Lucide.Settings),
}

/* ────────────────────────────  Wide layout  ──────────────────────────── */

@Composable
private fun WideLayout(
    section: Section,
    onChange: (Section) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        SideRail(active = section, onChange = onChange)
        Column(modifier = Modifier.fillMaxHeight().fillMaxWidth()) {
            TopBar(searchQuery = searchQuery, onSearchChange = onSearchChange)
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}

@Composable
private fun SideRail(active: Section, onChange: (Section) -> Unit) {
    // Flat canvas chrome with a 1dp #30363D *right-edge-only* divider —
    // matches chino-web exactly (`aside.border-r border-[#30363d]`).
    // The divider is a structural 1dp Box pinned to the right edge of an
    // outer wrapper Row, mirroring the TopBar's structural bottom
    // divider so the corner where rail + topbar meet aligns
    // pixel-perfectly via the layout system (drawBehind on adjacent
    // chrome would land at slightly different float positions).
    val dividerColor = ChinoBorder
    Row(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(ChinoBg2),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // Top inset = statusBars ONLY (matches the sibling TopBar
                // which also uses statusBars). Bottom inset = NONE —
                // chino-web pins the Settings cell at the very bottom
                // of the rail (y=720 in an 800-tall rail = 80dp from
                // bottom = 16 padding + 48 button + 16 padding). Adding
                // navigationBars bottom-padding here pushed Settings up
                // by the gesture-pill height (~24dp), so the gear icon
                // ended up at a higher Y than web. Settings can sit
                // behind the gesture pill — it's still visible and
                // tappable above the system pill.
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
        // Logo cell: 64dp tall, matches header height. CDP-verified web
        // structure: `<div class="h-16 flex items-center justify-center
        // border-b border-[#30363d]">` — bottom-edge-only divider.
        // Implemented as a Column with a 63dp content box + a 1dp Box
        // divider rather than drawBehind/border: empirically the
        // drawBehind approach landed the line ~11dp above where the
        // sibling TopBar's drawBehind put its line, even though the
        // modifier math says they should be equal. Structural 1dp Box
        // = layout-system-guaranteed alignment, no float rounding or
        // anti-aliasing drift at the rail/topbar corner.
        Column(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                // chino-web's logo button is `w-12 h-12` (48px) but the
                // SVG inside renders the `>c` glyph at effective fontSize
                // ~27px (viewBox 64, text fontSize 36 → 36 * 48/64). What
                // the eye reads is the GLYPH size, not the button box.
                // LogoMark's multiplier is 0.72, so to land at a ~26sp
                // glyph (matching web's 27px effective height) we want
                // sizeDp = 36 → fontSize = 25.92sp. Previous sizeDp=48
                // produced ~34.5sp — visibly heavier than web. Previous
                // sizeDp=24 was ~17sp — about half.
                LogoMark(sizeDp = 36)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ChinoBorder),
            )
        }
        Column(
            // CDP-verified: web nav has `px-4 pt-4 space-y-2` — top + sides
            // padding, NO bottom padding (Settings cell handles bottom).
            // Previous `vertical = 16.dp` added a 16dp gap that doesn't
            // exist on web.
            modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 16.dp, end = 16.dp, top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Same order as web/TV + the bottom nav: Home, Movies, Series,
            // Watchlist, Zap (Settings is pinned separately below).
            listOf(Section.Home, Section.Movies, Section.Series, Section.Watchlist, Section.Zap).forEach { s ->
                RailButton(section = s, isActive = s == active, onClick = { onChange(s) })
            }
        }
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                RailButton(
                    section = Section.Settings,
                    isActive = active == Section.Settings,
                    onClick = { onChange(Section.Settings) },
                )
            }
        }
        // Structural right-edge divider — fillMaxHeight so it spans the
        // full rail (background to background to gesture-pill), 1dp wide.
        // Lays out at the rail Row's right edge automatically; the
        // bottom of TopBar's divider Box terminates exactly where this
        // vertical divider crosses it, forming a clean L corner.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(dividerColor),
        )
    }
}

@Composable
private fun RailButton(section: Section, isActive: Boolean, onClick: () -> Unit) {
    // Active slot uses #161B22 — stands out against the canvas-color rail
    // (#0D1117). The accent strip in App.kt's Content() root uses the same
    // colour, so the whole chrome reads as a single elevation family.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RectangleShape)
            .background(if (isActive) ChinoSurface else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = section.icon,
            contentDescription = section.label,
            tint = if (isActive) ChinoCloudBlue else ChinoMuted,
            modifier = Modifier.size(24.dp),
        )
    }
}

/* ────────────────────────────  Narrow layout  ──────────────────────────── */

@Composable
private fun NarrowLayout(
    section: Section,
    onChange: (Section) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(searchQuery = searchQuery, onSearchChange = onSearchChange)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
        BottomNav(active = section, onChange = onChange)
    }
}

@Composable
private fun BottomNav(active: Section, onChange: (Section) -> Unit) {
    // Top-edge-only divider — structural 1dp Box (same pattern as the
    // TopBar bottom divider) so layout pins it pixel-perfectly. The
    // outer Column carries the canvas bg + edge-to-edge background; the
    // inner Row holds the icon cluster + navigationBars inset.
    val dividerColor = ChinoBorder
    Column(modifier = Modifier.fillMaxWidth().background(ChinoBg2)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerColor),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(63.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Section.entries.forEach { s ->
                BottomNavItem(section = s, isActive = s == active, onClick = { onChange(s) })
            }
        }
    }
}

@Composable
private fun BottomNavItem(section: Section, isActive: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RectangleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = section.icon,
            contentDescription = section.label,
            tint = if (isActive) ChinoCloudBlue else ChinoMuted,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = section.label,
            color = if (isActive) ChinoCloudBlue else ChinoMuted,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/* ───────────────────────────────  Top bar  ─────────────────────────────── */

@Composable
private fun TopBar(searchQuery: String, onSearchChange: (String) -> Unit) {
    val nav = LocalNavigator.currentOrThrow
    // Read the active account hot-flow so the top-right avatar reflects the
    // current user (initial set by an off-main snapshot read at App-root
    // mount time — see App.kt's produceState). collectAsState here is safe
    // because by the time this composable is composed the AppContainer's
    // lazy graph has already materialised on Dispatchers.Default during
    // boot, so the first subscriber doesn't pay a keystore round-trip.
    val container = LocalAppContainer.current
    val activeAccount by container.accountStore.activeAccount.collectAsState(initial = null)
    // Edge-to-edge: dark canvas reaches the very top of the screen
    // (background painted on the outer Column so it covers the
    // status-bar inset area too), the visible 64dp content row sits
    // below the inset, and a structural 1dp Box at the column's bottom
    // is the divider. Switched to a structural divider Box (instead of
    // drawBehind on the Row) because the drawBehind approach landed the
    // line ~11dp above the sibling SideRail's logo-cell divider —
    // structural Box-as-divider lets the layout system pin both to the
    // exact same Y so the rail/topbar corner reads as one clean L.
    val dividerColor = ChinoBorder
    Column(modifier = Modifier.fillMaxWidth().background(ChinoBg2)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(63.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            // CDP-verified: web TopBar has the SearchField filling the
            // remaining width, then a 24dp gap to the right cluster,
            // then 16dp between bell and avatar inside that cluster
            // (web: `ml-6` + `gap-4`). Previous uniform 12dp gap put bell
            // too close to search and avatar too close to bell.
        ) {
            // Web caps the search pill at max-w-xl (36rem = 576dp). The pill
            // lives in a weight(1f) Box so it (a) shrinks on a narrow phone to
            // leave room for the bell + avatar — a bare fillMaxWidth() here ate
            // the whole row and pushed the icons off the right edge in portrait
            // — and (b) on wide layouts caps at 576dp left-aligned, the Box's
            // leftover space acting as the spacer so the icon cluster hugs the
            // right edge (matches web's ml-auto search + right-aligned actions).
            Box(modifier = Modifier.weight(1f)) {
                SearchField(
                    modifier = Modifier.widthIn(max = 576.dp).fillMaxWidth(),
                    query = searchQuery,
                    onChange = onSearchChange,
                )
            }
            Row(
                modifier = Modifier.padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconCell(
                    icon = Lucide.Bell,
                    contentDescription = "Watchlist",
                    onClick = { nav.push(WatchlistScreen()) },
                )
                val acct = activeAccount
                if (acct != null) {
                    Avatar(
                        displayName = acct.displayName,
                        email = acct.email,
                        size = 36.dp,
                        onClick = { nav.push(cloud.nalet.chino.mobile.ui.profile.ProfileScreen()) },
                    )
                } else {
                    IconCell(icon = Lucide.User, contentDescription = "Account")
                }
            }
        }
        // Structural divider Box — lays out at exactly the bottom of the
        // 63dp content row, no float drift. Aligns pixel-perfectly with
        // the SideRail's logo-cell divider because both are 1dp Boxes at
        // the bottom of identical-height (64dp total) content columns.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerColor),
        )
    }
}

@Composable
private fun SearchField(modifier: Modifier = Modifier, query: String, onChange: (String) -> Unit) {
    // Real text input — drives SearchResultsSection inline (chino-web's
    // inline-search behaviour). Clearing the query reverts the main
    // content area to the active section (Home/Movies/etc).
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RectangleShape)
            .background(ChinoSurface)
            .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Lucide.Search,
            contentDescription = null,
            tint = ChinoFg2,
            modifier = Modifier.size(20.dp),
        )
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = ChinoFg2,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(ChinoCloudBlue),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = "Search movies, shows…",
                        color = ChinoMuted,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                    )
                }
                inner()
            },
            modifier = Modifier.weight(1f),
        )
        // Clear-X — only when the field has text. Tap clears the query
        // and reverts the content area to the active section.
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RectangleShape)
                    .clickable { onChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = "Clear",
                    tint = ChinoMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun IconCell(icon: ImageVector, contentDescription: String, onClick: () -> Unit = {}) {
    // CDP-verified: chino-web's header icon button is
    //   `<button class="p-2 text-[#c9d1d9] hover:bg-[#161b22] rounded-lg">`
    // size 36×36 (8px padding around a 20×20 svg), bg transparent
    // (hover-only #161B22), 8px corner radius — NOT a filled circle.
    // Foreground stays white per user direction.
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RectangleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
