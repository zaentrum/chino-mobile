package cloud.nalet.chino.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.ui.components.EpisodeBadge
import cloud.nalet.chino.mobile.ui.components.HeroBanner
import cloud.nalet.chino.mobile.ui.components.MediaCard
import cloud.nalet.chino.mobile.ui.components.MediaRow

/**
 * Home tab content. Mirrors chino-web's HomeSection.tsx layout: hero banner
 * up top with a slow rotation, then Continue Watching, Next Up, Recently
 * Added (movies + series interleaved by year), Top Rated. Each row scrolls
 * horizontally; the whole page scrolls vertically.
 *
 * Lives inside MainShellScreen so the NavigationBar at the bottom doesn't
 * scroll with the content.
 */
@Composable
fun HomeSection(
    container: AppContainer,
    onItemSelected: (String) -> Unit = {},
    onPlay: (String) -> Unit = onItemSelected,
    // #150: jump to a full overview section ("movies" or "series") from a
    // rail's trailing "See all" tile. Wired by MainShellScreen to the
    // bottom-nav / side-rail section switch. Mirrors chino-web's
    // HomeSection onNavigate('movies'|'series').
    onNavigateToSection: ((String) -> Unit)? = null,
) {
    val model = remember { HomeSectionModel(container) }
    val state by model.state.collectAsState()

    when (val s = state) {
        HomeUiState.Loading -> Center { CircularProgressIndicator() }
        is HomeUiState.Error -> Center {
            Text(s.message, color = MaterialTheme.colorScheme.error)
        }
        is HomeUiState.Ready -> ReadyContent(
            s,
            onItemSelected,
            onPlay,
            onRemoveFromContinueWatching = model::removeFromContinueWatching,
            onToggleWatched = model::toggleWatched,
            onNavigateToSection = onNavigateToSection,
        )
    }
}

@Composable
private fun ReadyContent(
    s: HomeUiState.Ready,
    onItemSelected: (String) -> Unit,
    onPlay: (String) -> Unit,
    onRemoveFromContinueWatching: (String) -> Unit,
    onToggleWatched: (String) -> Unit,
    onNavigateToSection: ((String) -> Unit)? = null,
) {
    // CDP-verified: web has `space-y-8` (32px) between hero and shelves
    // and between shelves; `<main p-4>` puts the hero at y=80 (64
    // header + 16 top padding). Previous 20dp + 0dp top padding read
    // tighter than the reference.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        if (s.heroPool.isNotEmpty()) {
            HeroBanner(
                pool = s.heroPool,
                baseUrl = s.baseUrl,
                streamToken = s.streamToken,
                onMoreInfo = onItemSelected,
                onPlay = onPlay,
            )
        }
        if (s.continueWatching.isNotEmpty()) {
            ContinueWatchingShelf(
                items = s.continueWatching,
                baseUrl = s.baseUrl,
                streamToken = s.streamToken,
                showProgress = true,
                onItemClick = onItemSelected,
                // Only the in-progress CW rail surfaces the dismiss
                // affordance — web gates it the same way (HomeSection.tsx
                // L43-44: `onRemove && !it.up_next`).
                onRemoveFromContinueWatching = onRemoveFromContinueWatching,
                // #188: the watched toggle is ADDITIONAL to dismiss; both
                // live in the same overflow menu.
                onToggleWatched = onToggleWatched,
            )
        }
        if (s.nextUp.isNotEmpty()) {
            ContinueWatchingShelf(
                items = s.nextUp,
                baseUrl = s.baseUrl,
                streamToken = s.streamToken,
                title = "Next Up",
                // Next Up is server-substituted next episodes with
                // position=0 — web hides the progress bar on these
                // (HomeSection.tsx L18-19, "no progress bar").
                showProgress = false,
                onItemClick = onItemSelected,
                // No dismiss on Next Up cards (web parity).
                onRemoveFromContinueWatching = null,
                // #188: but the watched toggle is on every card surface.
                onToggleWatched = onToggleWatched,
            )
        }
        if (s.recentMovies.isNotEmpty()) {
            MediaRow(
                title = "Recently added — Movies",
                items = s.recentMovies,
                baseUrl = s.baseUrl,
                streamToken = s.streamToken,
                onItemClick = onItemSelected,
                onSeeAll = onNavigateToSection?.let { nav -> { nav("movies") } },
                onToggleWatched = onToggleWatched,
            )
        }
        if (s.recentSeries.isNotEmpty()) {
            MediaRow(
                title = "Recently added — Shows",
                items = s.recentSeries,
                baseUrl = s.baseUrl,
                streamToken = s.streamToken,
                onItemClick = onItemSelected,
                onSeeAll = onNavigateToSection?.let { nav -> { nav("series") } },
                onToggleWatched = onToggleWatched,
            )
        }
        if (s.topRated.isNotEmpty()) {
            MediaRow(
                title = "Top Rated",
                items = s.topRated,
                baseUrl = s.baseUrl,
                streamToken = s.streamToken,
                onItemClick = onItemSelected,
                onSeeAll = onNavigateToSection?.let { nav -> { nav("movies") } },
                onToggleWatched = onToggleWatched,
            )
        }
        Box(modifier = Modifier.padding(bottom = 16.dp))
    }
}

/**
 * Continue Watching / Next Up shelf. Mirrors chino-web's
 * HomeSection.tsx cwToCard mapping (L14-46):
 *   - Episodes show seriesTitle as the card title (not episode title),
 *     with `SxxExx · episodeTitle` as the subtitle in #58a6ff/#8b949e.
 *   - Movies show year • rating as the subtitle.
 *   - Continue Watching surfaces a blue progress bar overlay on the
 *     poster; Next Up hides it (server stamps position=0 there).
 *
 * Built ad-hoc rather than going through [MediaRow] because the per-
 * card extras (progress, episodeBadge, swapped title) don't fit through
 * the Item shape.
 */
@Composable
private fun ContinueWatchingShelf(
    items: List<ContinueWatchingItem>,
    baseUrl: String,
    streamToken: String,
    showProgress: Boolean,
    title: String = "Continue Watching",
    onItemClick: (String) -> Unit,
    onRemoveFromContinueWatching: ((String) -> Unit)? = null,
    onToggleWatched: ((String) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = title,
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // Web mobile poster is 128dp (3-across w/ peek); tablet/wide 208dp.
            val cardWidth = if (maxWidth < 600.dp) 128.dp else 208.dp
            LazyRow(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = { it.id }) { cw ->
                    val isEpisode = cw.type == "episode"
                    val displayTitle = if (isEpisode && cw.seriesTitle != null) cw.seriesTitle else cw.title
                    val episodeBadge = if (isEpisode && cw.seasonNumber != null && cw.episodeNumber != null) {
                        EpisodeBadge(
                            season = cw.seasonNumber,
                            episode = cw.episodeNumber,
                            episodeTitle = cw.title.takeIf { it.isNotBlank() },
                        )
                    } else null
                    val progress = if (showProgress && cw.durationSec > 0) {
                        (cw.positionSec.toFloat() / cw.durationSec.toFloat()) * 100f
                    } else null
                    MediaCard(
                        // year/rating ride through so MediaCard's existing
                        // "year • rating" meta line renders for movies (web
                        // parity); episodes take the episodeBadge path below
                        // and never reach the year/rating branch.
                        item = Item(
                            id = cw.id,
                            title = displayTitle,
                            kind = cw.type,
                            year = cw.year,
                            rating = cw.rating,
                        ),
                        posterUrl = "$baseUrl/v1/items/${cw.id}/poster?stream=$streamToken",
                        progress = progress,
                        episodeBadge = episodeBadge,
                        onClick = { onItemClick(cw.id) },
                        cardWidth = cardWidth,
                        onRemoveFromContinueWatching = onRemoveFromContinueWatching?.let { cb -> { cb(cw.id) } },
                        onToggleWatched = onToggleWatched?.let { cb -> { cb(cw.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
