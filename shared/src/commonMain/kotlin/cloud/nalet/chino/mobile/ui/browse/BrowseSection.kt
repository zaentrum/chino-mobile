package cloud.nalet.chino.mobile.ui.browse

import cloud.nalet.chino.mobile.ui.components.CardMenu
import kotlinx.coroutines.launch
import cloud.nalet.chino.mobile.ui.components.CardStatusMarks
import androidx.compose.ui.graphics.RectangleShape

import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoOnFill
import cloud.nalet.chino.mobile.ui.theme.ChinoBg2
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder2
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoDim
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoHeading
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.downloads.Reach
import cloud.nalet.chino.mobile.ui.downloads.InformationBanner
import cloud.nalet.chino.mobile.ui.downloads.unreachableBanner
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.ui.theme.PosterImage
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.flow.first

/**
 * Browse grid for a single content type. Mirrors chino-web's
 * MoviesSection / SeriesSection layout:
 *   - Big page title at the top
 *   - BrowseFilters chip strip (Genre / Decade / Rating / Sort)
 *   - Responsive poster grid that lazy-loads more pages on scroll
 *
 * Reusable for both "movie" and "series" — the only difference is the
 * page heading and the type filter passed to chino-api.
 */
@Composable
fun BrowseSection(
    container: AppContainer,
    /** The screen the grid is on, the shell: its model lives as long as it. */
    screen: Screen,
    type: String,
    pageTitle: String,
    onItemSelected: (String) -> Unit,
    /** Plays a movie or an episode, where the player resumes it; a card's
     *  menu "play". Null: no such item. */
    onPlay: ((itemId: String) -> Unit)? = null,
) {
    // The filters, saveable as the shell's tab is: back from a title opened
    // from the grid, the grid has them still, as after a recreation or the
    // process being killed. The model starts from them; a change goes to
    // both.
    var filter by rememberSaveable(type, stateSaver = BrowseQuerySaver) { mutableStateOf(BrowseQuery()) }
    // How many rows the grid has loaded, saveable as the filters are: after
    // the process was killed, the model asks for as many again before it
    // shows any, so the titles are there to scroll back to.
    var loadedRows by rememberSaveable(type) { mutableIntStateOf(0) }
    // The shell's own, one per tab: a title opened from the grid covers the
    // shell, and back from it the grid has its pages and scrolls back to
    // where it was, nothing asked again; it goes, its requests with it, when
    // the shell leaves the stack (Home starts a new one).
    // A series card's "play" finds its episode first.
    val playScope = androidx.compose.runtime.rememberCoroutineScope()
    val model = screen.rememberScreenModel(tag = type) {
        BrowseScreenModel(container, type, initialFilter = filter, initialRows = loadedRows)
    }
    val state by model.state.collectAsState()
    LaunchedEffect(state.items.size) { if (state.items.isNotEmpty()) loadedRows = state.items.size }
    // Saveable: the shell keeps it, as the filters, while another tab or
    // the search results show (MainShellScreen).
    val gridState = rememberLazyGridState()
    // After the process was killed the grid has its scroll back but not yet
    // its titles. Laid out over the header rows alone it would lose the
    // position to them, so it waits for the model's first load - the pages
    // it had - and its first layout is where it was.
    var awaitingTitles by remember {
        mutableStateOf(
            state.items.isEmpty() &&
                (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0),
        )
    }
    if (awaitingTitles) {
        LaunchedEffect(model) {
            model.state.first { !it.loading }
            awaitingTitles = false
        }
    }

    // Tail-sentinel: when the last visible item is within ~6 rows of
    // the end, fetch the next page. Mirrors chino-web's
    // IntersectionObserver(rootMargin: 600px) behaviour.
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) false else {
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= total - 12 && state.canLoadMore
            }
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) model.loadMore() }

    // The server gone - found by this grid's own ask or by any other - the
    // banner over the list the phone has; back, the list asked again where
    // it is the one the phone knew.
    val reach by container.serverReach.state.collectAsState()
    val offline = state.offline || reach == Reach.Unreachable
    LaunchedEffect(reach, state.offline) { if (state.offline && reach == Reach.Reachable) model.refresh() }

    BrowseContent(
        state = state,
        pageTitle = pageTitle,
        banner = unreachableBanner(container, offline),
        gridState = gridState,
        awaitingTitles = awaitingTitles,
        onFilter = { q ->
            filter = q
            model.setFilter(q)
        },
        onRetry = model::retry,
        onRetryLoadMore = model::retryLoadMore,
        onItemSelected = onItemSelected,
        onToggleWatched = model::toggleWatched,
        // A movie plays itself; a series its episode, else its page opens
        // (chino-web's usePlayTitle).
        onPlay = onPlay?.let { play ->
            { id ->
                if (type == "series") {
                    playScope.launch {
                        cloud.nalet.chino.mobile.ui.home.seriesEpisodeToPlay(container.chinoApi, id)
                            ?.let(play) ?: onItemSelected(id)
                    }
                } else {
                    play(id)
                }
            }
        },
    )
}

/**
 * The grid of [state]: its heading, the filters, the titles; where the
 * server can't be reached, the information [banner] above it all, fixed
 * under the top bar as on a title's page - the grid scrolls under it, the
 * banner stays - and a couldn't-load under it says no reason of its own.
 */
@Composable
internal fun BrowseContent(
    state: BrowseUiState,
    pageTitle: String,
    banner: String?,
    gridState: LazyGridState,
    awaitingTitles: Boolean = false,
    onFilter: (BrowseQuery) -> Unit,
    onRetry: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onItemSelected: (String) -> Unit,
    onToggleWatched: (String) -> Unit,
    onPlay: ((itemId: String) -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
    banner?.let { InformationBanner(it, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
    // Column count tuned so the grid posters track the Home shelf card size
    // (small, ~3-across on a phone, scaling up on wider screens) instead of
    // 2 oversized columns. 16dp padding/gap matches the Home rows.
    val cols = when {
        maxWidth < 600.dp -> 3
        maxWidth < 900.dp -> 4
        maxWidth < 1200.dp -> 5
        else -> 6
    }
    if (awaitingTitles) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = ChinoCloudBlue)
        }
        return@BoxWithConstraints
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(cols),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Page title spans the full grid width as a header row: the web's
        // h1, in the heading face - mono, so lowercase, as "downloads" is.
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = pageHeading(pageTitle),
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                style = ChinoHeading,
                modifier = Modifier.padding(bottom = 16.dp).semantics { heading() },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            BrowseFilters(
                value = state.filter,
                genres = state.genres,
                onChange = onFilter,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(modifier = Modifier.height(8.dp))
        }

        val failure = state.error
        when {
            // The design system's "couldn't load", as Home says it: what,
            // in the screen's own word ("couldn't load the movies"), why -
            // the reason only - and "try again"; the status under details.
            // A refused sign-in is the sign-in, no access its own heading.
            failure != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                cloud.nalet.chino.mobile.ui.components.ListCouldntLoad(
                    title = couldntLoadTitle(pageTitle),
                    failure = failure,
                    details = state.errorDetails,
                    onTryAgain = onRetry,
                    modifier = Modifier.padding(vertical = 32.dp),
                    // "Can't reach the server." once: the banner says it.
                    saysWhy = browseSaysWhy(failure, offline = banner != null),
                )
            }
            state.items.isEmpty() && state.loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = ChinoCloudBlue)
                }
            }
            state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "No items match the current filters.",
                    color = ChinoMuted,
                    fontSize = 14.sp,
                )
            }
            else -> {
                items(state.items, key = { it.id }) { item ->
                    BrowseCard(
                        item = item,
                        baseUrl = state.baseUrl,
                        streamToken = state.streamToken,
                        onClick = { onItemSelected(item.id) },
                        // #188: mark-watched/unwatched from the card overflow
                        // menu — flips the green ✓ badge optimistically.
                        onToggleWatched = { onToggleWatched(item.id) },
                        onPlay = onPlay?.let { play -> { play(item.id) } },
                    )
                }
                // Loading sentinel, else the count at the end of the list in
                // the screen's own word ([endOfListLine]).
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.loadMoreFailed) {
                            // A later page failed, the ones above still there:
                            // say so, with "try again", rather than asking
                            // again on every scroll.
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Couldn't load more ${pageTitle.lowercase()}.",
                                    color = ChinoMuted,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    text = "try again",
                                    color = ChinoCloudBlue,
                                    fontSize = 13.sp,
                                    style = cloud.nalet.chino.mobile.ui.theme.ChinoMono,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onRetryLoadMore)
                                        .padding(horizontal = 8.dp, vertical = 14.dp),
                                )
                            }
                        } else if (state.hasMore) {
                            CircularProgressIndicator(color = ChinoCloudBlue)
                        } else {
                            Text(
                                text = endOfListLine(state.items.size, pageTitle),
                                color = ChinoMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
    }
    }
}

/**
 * The line at the end of a grid with nothing more to load: how many there
 * are, in the screen's own word ([pageTitle]: "Movies", "Shows") and its
 * right number - "12 shows in all.", "1 show in all." Never "catalogue",
 * never "1 shows".
 */
internal fun endOfListLine(count: Int, pageTitle: String): String {
    val plural = pageTitle.trim().lowercase()
    val word = if (count == 1) plural.removeSuffix("s") else plural
    return "$count $word in all."
}

@Composable
private fun BrowseCard(
    item: Item,
    baseUrl: String,
    streamToken: String,
    onClick: () -> Unit,
    // #188: when set, the card surfaces the watched toggle in its menu, on
    // a long-press, the same idiom and wording as the Home rail cards
    // (MediaCard, CardMenu). null leaves it off.
    onToggleWatched: (() -> Unit)? = null,
    /** The menu's "play"; null: none. */
    onPlay: (() -> Unit)? = null,
) {
    // Drives the card's menu, opened by a long-press.
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .clip(RectangleShape)
            .background(ChinoSurface)
            .let { base ->
                if (onToggleWatched != null || onPlay != null) {
                    base.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }, onLongClickLabel = "More options")
                } else {
                    base.clickable(onClick = onClick)
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            PosterImage(
                model = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                title = item.title,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(ChinoBg2),
            )
            // Its status marks: in the Watchlist, watched.
            CardStatusMarks(itemId = item.id, watched = item.watchedAt != null)
            // The card's menu - mark watched - on a long-press: nothing is
            // drawn over the artwork but its status marks (the design
            // system's title actions).
            if (onToggleWatched != null || onPlay != null) {
                // The poster is the menu's anchor.
                Box(modifier = Modifier.matchParentSize()) {
                    CardMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        watched = item.watchedAt != null,
                        onPlay = onPlay,
                        onToggleWatched = onToggleWatched,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // MediaCard's h3 on the web: the heading face.
            Text(
                text = item.title,
                color = ChinoFg2,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ChinoHeading,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.year?.let {
                    Text(
                        it.toString(),
                        color = ChinoMuted,
                        fontSize = 12.sp,
                    )
                }
                item.rating?.let { r ->
                    if (item.year != null) {
                        Text("•", color = ChinoMuted, fontSize = 12.sp)
                    }
                    Text(
                        text = ((r * 10).toInt() / 10.0).toString(),
                        color = ChinoCloudBlue,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** A grid screen's heading: its tab's word ([pageTitle]: "Movies",
 *  "Shows") in the mono face's lowercase - "movies", "shows" - as every
 *  heading in that face is (the design system's voice). */
internal fun pageHeading(pageTitle: String): String = pageTitle.trim().lowercase()

/** What a browse page that couldn't load is headed: what couldn't load, in
 *  the screen's own word - its heading's ("Movies": "couldn't load the
 *  movies"). */
internal fun couldntLoadTitle(pageTitle: String): String = "couldn't load the ${pageTitle.trim().lowercase()}"
