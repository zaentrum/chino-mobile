package cloud.nalet.chino.mobile.ui.browse

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.loadFailure
import cloud.nalet.chino.mobile.data.downloads.Reach
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.paging.Paged
import cloud.nalet.chino.mobile.data.paging.loadFirstPages
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

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
    /** It couldn't load: the design system's case, by the answer's status
     *  ([ListCouldntLoad] says it). */
    val error: LoadFailure? = null,
    /** What separates the cases, under "details": the answer's status. */
    val errorDetails: String? = null,
    /** The server can't be reached: the grid is the list the phone knows
     *  for its filter ([KnownBrowse]), under the banner. */
    val offline: Boolean = false,
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
    /** The filters the grid starts with: the ones it showed before a title
     *  opened from it covered the shell (BrowseSection keeps them). */
    initialFilter: BrowseQuery = BrowseQuery(),
    /** The rows the grid had loaded before the process was killed
     *  (BrowseSection keeps the count): its first load asks for as many
     *  again, page by page, before it shows any, so the grid can scroll
     *  back to where it was. */
    private val initialRows: Int = 0,
    private val pageSize: Int = 48,
) : ScreenModel {
    private val _state = MutableStateFlow(BrowseUiState(filter = initialFilter))
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    /** The request in flight — the first page or a later one. A filter
     *  change cancels it, so a page of the old filter never lands. */
    private var loadJob: Job? = null

    init {
        screenModelScope.launch {
            val owner = owner()
            // The genres the server gave last where it doesn't answer: the
            // chips stay as they were.
            val genres = attempt { container.chinoApi.listGenres().genres }
                .onSuccess { KnownBrowse.putGenres(owner, it) }
                .getOrElse { KnownBrowse.genres(owner).orEmpty() }
            val baseUrl = container.config.apiBaseUrl.trimEnd('/')
            // Minted again without an answer: the token the posters on the
            // phone are signed with.
            val streamToken = attempt { container.streamTokenManager.valid() }
                .getOrElse { container.streamTokenManager.current.value.orEmpty() }
            _state.update { it.copy(genres = genres, baseUrl = baseUrl, streamToken = streamToken) }
        }
        reload(_state.value.filter, rows = initialRows)
    }

    /** The grids' lists are kept per server and account ([KnownBrowse]). */
    private suspend fun owner(): String =
        container.config.apiBaseUrl + "|" + (container.accountStore.activeAccountId.first() ?: "")

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
            val page = attempt { fetchPage(filter, offset) }
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
            val now = _state.value
            page.onSuccess { if (now.filter == filter) KnownBrowse.putList(owner(), type, filter, now.paged) }
            // A page without an answer: the server asked, so the banner and
            // the bell follow where it is gone.
            page.onFailure { e -> if (e.loadFailure(singleThing = false) == LoadFailure.NoAnswer) container.serverReach.check() }
        }
    }

    /** The footer's Retry after a failed page. */
    fun retryLoadMore() {
        _state.update { it.copy(loadMoreFailed = false) }
        loadMore()
    }

    /** Retry after the first page failed: the same filter, from the start. */
    fun retry() = reload(_state.value.filter)

    /** The server answers again while the grid is the list the phone knows:
     *  the same filter asked again, as many rows as it shows, the list on
     *  screen until the new one is in. */
    fun refresh() {
        val s = _state.value
        reload(s.filter, rows = s.items.size, keepShown = true)
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

    /** From the first page with the filters [q]; [rows] > 0 asks for the
     *  pages after it too, until that many are in (loadFirstPages). Where
     *  the server can't be reached, the list the phone knows for [q]
     *  ([browseFirstLoad]). [keepShown]: the list on screen stays until the
     *  new one is in, and a failure leaves it as it is. */
    private fun reload(q: BrowseQuery, rows: Int = 0, keepShown: Boolean = false) {
        loadJob?.cancel()
        _state.update {
            if (keepShown) it.copy(filter = q, loading = true)
            else it.copy(filter = q, paged = Paged(), loading = true, loadMoreFailed = false, error = null, offline = false)
        }
        loadJob = screenModelScope.launch {
            val pages = attempt { loadFirstPages(rows, pageSize, Item::id) { offset -> fetchPage(q, offset) } }
            val owner = owner()
            pages.onSuccess { KnownBrowse.putList(owner, type, q, it.paged) }
            val load = browseFirstLoad(
                pages,
                known = KnownBrowse.list(owner, type, q),
                // The server asked, as the app's other requests do: the
                // banner and the bell follow from its answer.
                reachable = { container.serverReach.check() == Reach.Reachable },
            )
            _state.update { current ->
                if (current.filter != q) return@update current
                if (keepShown && load.error != null && current.items.isNotEmpty()) {
                    return@update current.copy(loading = false, offline = load.offline)
                }
                current.copy(
                    paged = load.paged,
                    loading = false,
                    loadMoreFailed = load.laterPageFailed,
                    error = load.error,
                    errorDetails = load.errorDetails,
                    offline = load.offline,
                )
            }
        }
    }

    /** runCatching that lets cancellation through: a request cancelled by a
     *  newer filter or a second Retry must end quietly, not land as an error
     *  over the page that replaced it. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
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
