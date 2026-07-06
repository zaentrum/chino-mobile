package cloud.nalet.chino.mobile.ui.search

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import cloud.nalet.chino.mobile.data.api.Person
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.ui.person.InitialsAvatar
import cloud.nalet.chino.mobile.ui.person.creditLabel
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Inline search results — rendered inside the main shell when the user
 * has typed something in the TopBar search field. Mirrors chino-web's
 * SearchPage layout exactly:
 *   - Big header: `${N} results for "${query}"`
 *   - Responsive grid of poster cards (same look as Movies / Shows)
 *
 * Both movies and series are queried in parallel via /v1/items?q=… and
 * merged into a single grid. The server already returns each list in
 * relevance order (exact > prefix > FTS rank > alpha), so the client renders
 * the server order as-is — no client-side re-ranking. Queries are debounced
 * 250 ms so typing doesn't fire a request per keystroke.
 *
 * A "Cast & crew" people section (GET /v1/people?q=…, same debounce) renders
 * above the title grid: name + "· N titles" + an initials avatar. Tapping a
 * person opens the Person / Filmography surface.
 */
@Composable
fun SearchResultsSection(
    container: AppContainer,
    query: String,
    onItemSelected: (String) -> Unit,
    onPersonSelected: (Person) -> Unit = {},
) {
    val state = remember { MutableStateFlow<SearchState>(SearchState.Idle) }
    val ui by state.asStateFlow().collectAsState()
    val peopleState = remember { MutableStateFlow<List<Person>>(emptyList()) }
    val people by peopleState.asStateFlow().collectAsState()
    val streamToken by container.streamTokenManager.current.collectAsState()

    LaunchedEffect(query) {
        if (query.isBlank()) {
            state.value = SearchState.Idle
            peopleState.value = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        // People is a best-effort sidebar — a failure there must never blank
        // the title results, so it's fetched + caught independently.
        peopleState.value = runCatching {
            container.chinoApi.searchPeople(q = query, limit = 12).people
        }.getOrDefault(emptyList())
        state.value = SearchState.Loading
        state.value = runCatching {
            val movies = container.chinoApi.listItems(q = query, type = "movie", limit = 40).items
            val series = container.chinoApi.listItems(q = query, type = "series", limit = 40).items
            // Server already ranks each list; render in arrival order, no re-sort.
            SearchState.Ready(movies + series)
        }.getOrElse { SearchState.Error(it.message ?: it::class.simpleName.orEmpty()) }
    }

    val baseUrl = container.config.apiBaseUrl.trimEnd('/')
    val token = streamToken ?: ""

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Cast & crew — matching people above the title grid. Hidden when the
        // people search returned nothing.
        if (people.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PeopleSection(people = people, onPersonClick = onPersonSelected)
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            val count = (ui as? SearchState.Ready)?.items?.size ?: 0
            Text(
                text = when (ui) {
                    SearchState.Idle -> "Search the chino catalogue…"
                    SearchState.Loading -> "Searching for \"$query\"…"
                    is SearchState.Ready -> when (count) {
                        0 -> "No results for \"$query\""
                        1 -> "1 result for \"$query\""
                        else -> "$count results for \"$query\""
                    }
                    is SearchState.Error -> "Search failed"
                },
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        when (val s = ui) {
            SearchState.Idle -> {}
            SearchState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = ChinoCloudBlue) }
            }
            is SearchState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = s.message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                )
            }
            is SearchState.Ready -> {
                items(s.items, key = { it.id }) { item ->
                    SearchCard(
                        item = item,
                        baseUrl = baseUrl,
                        streamToken = token,
                        onClick = { onItemSelected(item.id) },
                    )
                }
            }
        }
    }
}

private sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Ready(val items: List<Item>) : SearchState
    data class Error(val message: String) : SearchState
}

/** "Cast & crew" — matching people, rendered above the title grid. Each row
 *  is an initials avatar + name + "· N titles", tappable to the Person
 *  surface. Server order is rendered as-is. */
@Composable
private fun PeopleSection(people: List<Person>, onPersonClick: (Person) -> Unit) {
    Column(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Cast & crew",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        people.forEach { person ->
            PersonRow(person = person, onClick = { onPersonClick(person) })
        }
    }
}

@Composable
private fun PersonRow(person: Person, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InitialsAvatar(name = person.name, size = 40.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = person.name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = creditLabel(person.credits),
                color = ChinoMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SearchCard(
    item: Item,
    baseUrl: String,
    streamToken: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RectangleShape)
            .background(ChinoSurface)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            AsyncImage(
                model = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(ChinoBg2),
            )
            // Watched badge — same emerald check overlay as Home/Browse.
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
                    Text(it.toString(), color = ChinoMuted, fontSize = 12.sp)
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
