package cloud.nalet.chino.mobile.ui.browse

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.ui.theme.PosterImage
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide

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
    type: String,
    pageTitle: String,
    onItemSelected: (String) -> Unit,
) {
    val model = remember(type) { BrowseScreenModel(container, type) }
    val state by model.state.collectAsState()
    val gridState = rememberLazyGridState()

    // Tail-sentinel: when the last visible item is within ~6 rows of
    // the end, fetch the next page. Mirrors chino-web's
    // IntersectionObserver(rootMargin: 600px) behaviour.
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) false else {
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= total - 12 && state.nextPageToken != null && !state.loading
            }
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) model.loadMore() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    // Column count tuned so the grid posters track the Home shelf card size
    // (small, ~3-across on a phone, scaling up on wider screens) instead of
    // 2 oversized columns. 16dp padding/gap matches the Home rows.
    val cols = when {
        maxWidth < 600.dp -> 3
        maxWidth < 900.dp -> 4
        maxWidth < 1200.dp -> 5
        else -> 6
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(cols),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Page title spans the full grid width as a header row.
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = pageTitle,
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            BrowseFilters(
                value = state.filter,
                genres = state.genres,
                onChange = model::setFilter,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(modifier = Modifier.height(8.dp))
        }

        when {
            state.error != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Failed to load: ${state.error}",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
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
                        onToggleWatched = { model.toggleWatched(item.id) },
                    )
                }
                // Loading sentinel + count footer matching chino-web's
                // "You've reached the end of the catalogue — N movies."
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.nextPageToken != null) {
                            CircularProgressIndicator(color = ChinoCloudBlue)
                        } else {
                            Text(
                                text = "You've reached the end of the catalogue — " +
                                    "${state.items.size} ${if (type == "movie") "movies" else "shows"}.",
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

@Composable
private fun BrowseCard(
    item: Item,
    baseUrl: String,
    streamToken: String,
    onClick: () -> Unit,
    // #188: when set, the card surfaces a Mark-watched/Mark-unwatched toggle
    // via a ⋮ overflow button + long-press, same overflow idiom + wording as
    // the Home rail cards (MediaCard). null leaves the menu off.
    onToggleWatched: (() -> Unit)? = null,
) {
    // Drives the overflow dropdown — opened by the ⋮ button or a long-press.
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .clip(RectangleShape)
            .background(ChinoSurface)
            .let { base ->
                if (onToggleWatched != null) {
                    base.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
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
            // Watched badge — same emerald check overlay as Home/Search.
            if (item.watchedAt != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(RectangleShape)
                        .background(ChinoGreen),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.Check,
                        contentDescription = "Watched",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            // ⋮ overflow + watched-toggle dropdown. Offset below the watched
            // badge when both want the top-right corner.
            if (onToggleWatched != null) {
                val menuTopInset = if (item.watchedAt != null) 32.dp else 6.dp
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = menuTopInset, end = 6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RectangleShape)
                            .background(Color(0x99000000))
                            .clickable { menuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.EllipsisVertical,
                            contentDescription = "More options",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        val isWatched = item.watchedAt != null
                        DropdownMenuItem(
                            text = { Text(if (isWatched) "Mark as unwatched" else "Mark as watched") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isWatched) Lucide.EyeOff else Lucide.Eye,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggleWatched()
                            },
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                color = ChinoFg2,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
