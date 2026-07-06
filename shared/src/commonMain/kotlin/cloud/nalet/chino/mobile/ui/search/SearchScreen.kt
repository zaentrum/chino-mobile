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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.ui.detail.DetailScreen
import cloud.nalet.chino.mobile.ui.theme.PosterImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Search results. Mirrors chino-web's SearchPage: live query, split into
 * Movies and Series sections, posters with title + year. Debounced 250 ms
 * so the user typing doesn't fire a request per keystroke.
 */
class SearchScreen : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val nav = LocalNavigator.currentOrThrow
        val container = LocalAppContainer.current
        var query by remember { mutableStateOf("") }
        val resultsFlow = remember { MutableStateFlow<SearchUiState>(SearchUiState.Idle) }
        val state by resultsFlow.asStateFlow().collectAsState()
        val streamToken by container.streamTokenManager.current.collectAsState()

        // Debounced fetch. Cancels in-flight queries when the user keeps
        // typing — Compose's LaunchedEffect(key) does the cancellation for
        // us when `query` changes.
        LaunchedEffect(query) {
            if (query.isBlank()) {
                resultsFlow.value = SearchUiState.Idle
                return@LaunchedEffect
            }
            delay(250)
            resultsFlow.value = SearchUiState.Loading
            resultsFlow.value = runCatching {
                val movies = container.chinoApi.listItems(q = query, type = "movie", limit = 40).items
                val series = container.chinoApi.listItems(q = query, type = "series", limit = 40).items
                SearchUiState.Ready(movies, series)
            }.getOrElse { SearchUiState.Error(it.message ?: it::class.simpleName.orEmpty()) }
        }

        val baseUrl = container.config.apiBaseUrl.trimEnd('/')
        val token = streamToken ?: ""

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            SearchInput(query = query, onChange = { query = it })
            when (val s = state) {
                SearchUiState.Idle -> EmptyHint("Search the chino catalogue…")
                SearchUiState.Loading -> Center { CircularProgressIndicator() }
                is SearchUiState.Error -> EmptyHint(s.message, isError = true)
                is SearchUiState.Ready -> ResultsList(
                    s = s,
                    baseUrl = baseUrl,
                    streamToken = token,
                    onTap = { id -> nav.push(DetailScreen(id)) },
                )
            }
        }
    }
}

private sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Ready(val movies: List<Item>, val series: List<Item>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

@Composable
private fun SearchInput(query: String, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(48.dp)
            .clip(RectangleShape)
            .background(ChinoSurface)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Lucide.Search,
            contentDescription = null,
            tint = ChinoMuted,
            modifier = Modifier.size(20.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(ChinoCloudBlue),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { /* search fires reactively */ }),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(text = "Search movies, shows…", color = ChinoMuted, fontSize = 16.sp)
                }
                inner()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ResultsList(s: SearchUiState.Ready, baseUrl: String, streamToken: String, onTap: (String) -> Unit) {
    if (s.movies.isEmpty() && s.series.isEmpty()) {
        EmptyHint("No matches.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (s.movies.isNotEmpty()) {
            item { SectionHeading("Movies (${s.movies.size})") }
            items(s.movies, baseUrl, streamToken, onTap)
        }
        if (s.series.isNotEmpty()) {
            item { SectionHeading("Series (${s.series.size})") }
            items(s.series, baseUrl, streamToken, onTap)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.items(
    list: List<Item>,
    baseUrl: String,
    streamToken: String,
    onTap: (String) -> Unit,
) {
    items(list.size, key = { list[it].id }) { i ->
        ResultRow(item = list[i], baseUrl = baseUrl, streamToken = streamToken, onTap = onTap)
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ResultRow(item: Item, baseUrl: String, streamToken: String, onTap: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .clickable { onTap(item.id) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 60.dp, height = 90.dp)
                .clip(RectangleShape)
                .background(ChinoSurface),
        ) {
            PosterImage(
                model = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                title = item.title,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            Text(text = item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            val sub = listOfNotNull(
                item.year?.toString(),
                item.rating?.let { ((it * 10).toInt() / 10.0).toString() },
            ).joinToString(" • ")
            if (sub.isNotEmpty()) {
                Text(text = sub, color = ChinoMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error else ChinoMuted,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
