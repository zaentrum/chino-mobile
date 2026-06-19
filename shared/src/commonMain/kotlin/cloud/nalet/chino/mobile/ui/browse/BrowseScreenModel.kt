package cloud.nalet.chino.mobile.ui.browse

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Browse-screen UI state: a paged grid of items for a given type
 *  ("movie" or "series") with chino-web-style filter chips. */
data class BrowseUiState(
    val items: List<Item> = emptyList(),
    val filter: BrowseQuery = BrowseQuery(),
    val genres: List<String> = emptyList(),
    val loading: Boolean = true,
    val nextPageToken: String? = null,
    val baseUrl: String = "",
    val streamToken: String = "",
    val error: String? = null,
)

/** Backs MoviesScreen / SeriesScreen. Loads paged items via
 *  /v1/items?type=$type with the active filters, refetches from page 1
 *  whenever the filters change, and exposes [loadMore] for the lazy
 *  grid's tail-sentinel. */
class BrowseScreenModel(
    private val container: AppContainer,
    private val type: String,
    private val pageSize: Int = 48,
) : ScreenModel {
    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        screenModelScope.launch {
            val genres = runCatching { container.chinoApi.listGenres().genres }.getOrDefault(emptyList())
            val baseUrl = container.config.apiBaseUrl.trimEnd('/')
            val streamToken = runCatching { container.streamTokenManager.valid() }.getOrDefault("")
            _state.value = _state.value.copy(
                genres = genres,
                baseUrl = baseUrl,
                streamToken = streamToken,
            )
        }
        reload(_state.value.filter)
    }

    fun setFilter(q: BrowseQuery) {
        if (q == _state.value.filter) return
        reload(q)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.nextPageToken == null) return
        screenModelScope.launch {
            _state.value = s.copy(loading = true)
            val page = runCatching {
                container.chinoApi.listItems(
                    pageToken = s.nextPageToken,
                    limit = pageSize,
                    type = type,
                    genre = s.filter.genre,
                    yearMin = s.filter.yearMin,
                    yearMax = s.filter.yearMax,
                    ratingMin = s.filter.ratingMin,
                    sort = s.filter.sort,
                )
            }.getOrNull()
            _state.value = if (page == null) {
                s.copy(loading = false)
            } else {
                s.copy(
                    items = s.items + page.items,
                    nextPageToken = page.nextPageToken,
                    loading = false,
                )
            }
        }
    }

    /**
     * #188: toggle the fully-watched flag for a grid item from its card
     * overflow menu. Reuses the SAME watched endpoints the detail-page eye
     * uses (POST to mark, DELETE to un-mark). Browse does NOT request
     * unwatched=true (watched titles stay findable for a rewatch), so the
     * card stays in the grid — we just flip the green watched ✓ badge
     * optimistically by stamping/clearing watchedAt on the local item, and
     * roll back if the write fails.
     */
    fun toggleWatched(id: String) {
        val s = _state.value
        val target = s.items.firstOrNull { it.id == id } ?: return
        val wasWatched = target.watchedAt != null
        val optimistic = if (wasWatched) null else "optimistic"
        _state.value = s.copy(
            items = s.items.map { if (it.id == id) it.copy(watchedAt = optimistic) else it },
        )
        screenModelScope.launch {
            val result = runCatching {
                if (wasWatched) container.chinoApi.deleteWatched(id)
                else container.chinoApi.postWatched(id)
            }
            if (result.isFailure) {
                // Roll the badge back so the card doesn't lie about server state.
                val latest = _state.value
                _state.value = latest.copy(
                    items = latest.items.map {
                        if (it.id == id) it.copy(watchedAt = target.watchedAt) else it
                    },
                )
            }
        }
    }

    private fun reload(q: BrowseQuery) {
        loadJob?.cancel()
        loadJob = screenModelScope.launch {
            _state.value = _state.value.copy(
                filter = q,
                items = emptyList(),
                nextPageToken = null,
                loading = true,
                error = null,
            )
            val page = runCatching {
                container.chinoApi.listItems(
                    limit = pageSize,
                    type = type,
                    genre = q.genre,
                    yearMin = q.yearMin,
                    yearMax = q.yearMax,
                    ratingMin = q.ratingMin,
                    sort = q.sort,
                )
            }
            _state.value = page.fold(
                onSuccess = { p ->
                    _state.value.copy(
                        items = p.items,
                        nextPageToken = p.nextPageToken,
                        loading = false,
                    )
                },
                onFailure = { e ->
                    _state.value.copy(
                        loading = false,
                        error = e.message ?: e::class.simpleName.orEmpty(),
                    )
                },
            )
        }
    }
}
