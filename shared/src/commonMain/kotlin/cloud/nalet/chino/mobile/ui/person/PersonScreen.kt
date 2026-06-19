package cloud.nalet.chino.mobile.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.api.PersonDetail
import cloud.nalet.chino.mobile.ui.components.MediaCard
import cloud.nalet.chino.mobile.ui.detail.DetailScreen
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide

/**
 * Person / Filmography surface. Reached from the search "Cast & crew" section
 * and from tappable cast/crew names on the Detail page. Header = initials
 * avatar + name + "· N titles"; body = a grid of the person's titles
 * rendered with the existing [MediaCard] (watched/saved badges, tap → detail).
 *
 * Data: GET /v1/people/{id} (chino-api). The filmography items carry the
 * standard poster_url/watched_at so the catalogue card renders them with no
 * special-casing. Mirrors chino-web's usePerson/PersonPage.
 */
class PersonScreen(private val personId: String, private val initialName: String? = null) : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val nav = LocalNavigator.currentOrThrow
        val container = LocalAppContainer.current
        val streamToken by container.streamTokenManager.current.collectAsState()
        var state by remember(personId) { mutableStateOf<PersonUiState>(PersonUiState.Loading) }

        LaunchedEffect(personId) {
            state = PersonUiState.Loading
            state = runCatching {
                PersonUiState.Ready(container.chinoApi.getPerson(personId, limit = 100))
            }.getOrElse { PersonUiState.Error(it.message ?: it::class.simpleName.orEmpty()) }
        }

        val baseUrl = container.config.apiBaseUrl.trimEnd('/')
        val token = streamToken ?: ""

        Scaffold(containerColor = MaterialTheme.colorScheme.background) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    PersonUiState.Loading -> Center { CircularProgressIndicator(color = Color(0xFF58A6FF)) }
                    is PersonUiState.Error -> Center {
                        Text(s.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    }
                    is PersonUiState.Ready -> Filmography(
                        person = s.person,
                        baseUrl = baseUrl,
                        streamToken = token,
                        onItemClick = { id -> nav.push(DetailScreen(id)) },
                    )
                }
                // Back chip — same overlay treatment as DetailScreen.
                Box(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 16.dp, top = 16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x80000000))
                        .clickable { nav.pop() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.ArrowLeft,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun Filmography(
        person: PersonDetail,
        baseUrl: String,
        streamToken: String,
        onItemClick: (String) -> Unit,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 128.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PersonHeader(name = person.name, credits = person.items.size)
            }
            items(person.items, key = { it.id }) { item ->
                MediaCard(
                    item = item,
                    posterUrl = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                    onClick = { onItemClick(item.id) },
                    cardWidth = 128.dp,
                )
            }
            if (person.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "No titles found.",
                        color = Color(0xFF8B949E),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonHeader(name: String, credits: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Leave room for the overlaid back chip on the left.
            .padding(top = 56.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        InitialsAvatar(name = name, size = 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = creditLabel(credits),
                color = Color(0xFF8B949E),
                fontSize = 14.sp,
            )
        }
    }
}

/** "· N titles" — matches chino-web's PersonSummary credit label. */
internal fun creditLabel(credits: Int): String =
    "· $credits ${if (credits == 1) "title" else "titles"}"

/**
 * Photo-less initials avatar for a person. No person photos exist in the
 * catalogue, so this is the only avatar treatment — initials on a
 * deterministic coloured circle (same palette/initial logic as the account
 * [cloud.nalet.chino.mobile.ui.components.Avatar]).
 */
@Composable
internal fun InitialsAvatar(name: String, size: Dp) {
    val initials = name
        .split(Regex("[\\s._-]+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(personAvatarColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun personAvatarColor(seed: String): Color {
    val palette = listOf(
        Color(0xFF58A6FF), Color(0xFFFFB454), Color(0xFF2EA043), Color(0xFF9E86FF),
        Color(0xFFFF6B6B), Color(0xFF14B8A6), Color(0xFFE879F9), Color(0xFFEAB308),
        Color(0xFF38BDF8), Color(0xFFF472B6), Color(0xFF4ADE80), Color(0xFFFB923C),
    )
    val hash = seed.fold(0) { acc, c -> acc * 31 + c.code }
    val idx = ((hash % palette.size) + palette.size) % palette.size
    return palette[idx]
}

private sealed interface PersonUiState {
    data object Loading : PersonUiState
    data class Ready(val person: PersonDetail) : PersonUiState
    data class Error(val message: String) : PersonUiState
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
