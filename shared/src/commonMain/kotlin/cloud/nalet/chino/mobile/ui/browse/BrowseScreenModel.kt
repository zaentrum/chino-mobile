package cloud.nalet.chino.mobile.ui.browse

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.catalogueMessage
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.paging.Paged
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Browse-screen UI state: a paged grid of items for a given type
 *  ("movie" or "series") with chino-web-style filter chips. */
data class BrowseUiState(
    /** The pages loaded so far, read by offset as chino-api pages. */
    val paged: Paged<Item> = Paged(),
    val filter: BrowseQuery = BrowseQuery(),
    val genres: List<String> = emptyList(),
    val loading: Boolean = true,
    /** A later page failed. The grid stops asking until the user retries,
     *  so a server that keeps failing isn't asked again on every frame. */
    val loadMoreFailed: Boolean = false,
    val baseUrl: String = "",
    val streamToken: String = "",
    val error: String? = null,
) {
    val items: List<Item> get() = paged.items

    /** The catalogue has rows past the ones loaded. */
    val hasMore: Boolean get() = !paged.endReached

    /** The tail sentinel may ask for the next page now. */
    val canLoadMore: Boolean
        get() = hasMore && !loading && !loadMoreFailed && error == null
}

/** Backs MoviesScreen / SeriesScreen. Loads /v1/items?type=$type with the
 *  active filters page by page — `limit` + `offset`, the way chino-api pages
 *  ([Paged]) — refetches from the first page whenever the filters change, and
 *  exposes [loadMore] for the lazy grid's tail sentinel. */
class BrowseScreenModel(
    private val container: AppContainer,
    private val type: String,
    private val pageSize: Int = 48,
) : ScreenModel {
    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    /** The request in flight — the first page or a later one. A filter
     *  change cancels it, so a page of the old filter never lands. */
    private var loadJob: Job? = null

    init {
        screenModelScope.launch {
            val genres = runCatching { container.chinoApi.listGenres().genres }.getOrDefault(emptyList())
            val baseUrl = container.config.apiBaseUrl.trimEnd('/')
            val streamToken = runCatching { container.streamTokenManager.valid() }.getOrDefault("")
            _state.update { it.copy(genres = genres, baseUrl = baseUrl, streamToken = streamToken) }
        }
        reload(_state.value.filter)
    }

    fun setFilter(q: BrowseQuery) {
        if (q == _state.value.filter) return
        reload(q)
    }

    /** The next page, from the offset the loaded rows end at. Does nothing
     *  while a page is loading, once a short page ended the catalogue, or
     *  after a failed page until [retryLoadMore]. */
    fun loadMore() {
        val s = _state.value
        if (!s.canLoadMore) return
        val filter = s.filter
        val offset = s.paged.nextOffset
        _state.update { it.copy(loading = true) }
        loadJob = screenModelScope.launch {
            val page = runCatching { fetchPage(filter, offset) }
            _state.update { current ->
                // Only a page of the filter on screen may land (reload()
                // cancels this job, but never trust a late resume).
                if (current.filter != filter || current.paged.nextOffset != offset) {
                    return@update current
                }
                page.fold(
                    onSuccess = { rows ->
                        current.copy(paged = current.paged.append(rows, pageSize, Item::id), loading = false)
                    },
                    onFailure = { current.copy(loading = false, loadMoreFailed = true) },
                )
            }
        }
    }

    /** The footer's Retry after a failed page. */
    fun retryLoadMore() {
        _state.update { it.copy(loadMoreFailed = false) }
        loadMore()
    }

    /** Retry after the first page failed: the same filter, from the start. */
    fun retry() = reload(_state.value.filter)

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
        val target = _state.value.items.firstOrNull { it.id == id } ?: return
        val wasWatched = target.watchedAt != null
        val optimistic = if (wasWatched) null else "optimistic"
        _state.update { s ->
            s.copy(paged = s.paged.map { if (it.id == id) it.copy(watchedAt = optimistic) else it })
        }
        screenModelScope.launch {
            val result = runCatching {
                if (wasWatched) container.chinoApi.deleteWatched(id)
                else container.chinoApi.postWatched(id)
            }
            if (result.isFailure) {
                // Roll the badge back so the card doesn't lie about server state.
                _state.update { s ->
                    s.copy(paged = s.paged.map { if (it.id == id) it.copy(watchedAt = target.watchedAt) else it })
                }
            }
        }
    }

    private fun reload(q: BrowseQuery) {
        loadJob?.cancel()
        _state.update {
            it.copy(filter = q, paged = Paged(), loading = true, loadMoreFailed = false, error = null)
        }
        loadJob = screenModelScope.launch {
            val page = runCatching { fetchPage(q, offset = 0) }
            _state.update { current ->
                if (current.filter != q) return@update current
                page.fold(
                    onSuccess = { rows ->
                        current.copy(paged = Paged<Item>().append(rows, pageSize, Item::id), loading = false)
                    },
                    onFailure = { e -> current.copy(loading = false, error = e.catalogueMessage()) },
                )
            }
        }
    }

    private suspend fun fetchPage(q: BrowseQuery, offset: Int): List<Item> =
        container.chinoApi.listItems(
            limit = pageSize,
            offset = offset,
            type = type,
            genre = q.genre,
            yearMin = q.yearMin,
            yearMax = q.yearMax,
            ratingMin = q.ratingMin,
            sort = q.sort,
        ).items
}
