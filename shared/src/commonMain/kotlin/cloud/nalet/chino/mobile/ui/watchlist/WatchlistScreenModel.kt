package cloud.nalet.chino.mobile.ui.watchlist

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.Watchlist
import cloud.nalet.chino.mobile.data.api.loadDetails
import cloud.nalet.chino.mobile.data.api.loadFailure
import cloud.nalet.chino.mobile.data.api.watchlistRefusal
import kotlinx.coroutines.CancellationException
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Max posters a hub shelf renders before deferring to the See-all tile. */
internal const val HUB_SHELF_CAP = 12

/** Why the watchlists couldn't load: the design system's case, and what
 *  separates it under details. */
data class ListFailure(val failure: LoadFailure, val details: String)

/**
 * Backs the watchlist HUB + per-list MORE view ([WatchlistSection]). Loads
 * the user's list overview (default first — server order), then fans out one
 * GET /me/watchlists/{id} per list IN PARALLEL (like the Home rails' fan-out)
 * and resolves the first [HUB_SHELF_CAP] item ids of each shelf to full Items
 * via per-id getItem (no per-list items endpoint). Opening a list loads its
 * complete item set for the MORE grid. Create / rename / delete go straight
 * through the shared [cloud.nalet.chino.mobile.data.WatchlistsRepository] so
 * every other surface (detail add-to-list picker, card badges) stays in sync.
 */
class WatchlistScreenModel(
    private val container: AppContainer,
) : ScreenModel {
    private val repo = container.watchlists

    /** The lists overview lives on the repository — the screen collects it
     *  directly. Default list first, then createdAt asc (server order). */
    val lists: StateFlow<List<Watchlist>> get() = repo.lists

    /** listId -> the first [HUB_SHELF_CAP] resolved Items, newest-added
     *  first. Missing keys mean "not loaded yet"; empty lists map to an
     *  empty list so the hub can render the empty-shelf hint. */
    private val _shelves = MutableStateFlow<Map<String, List<Item>>>(emptyMap())
    val shelves: StateFlow<Map<String, List<Item>>> = _shelves.asStateFlow()

    private val _loadingShelves = MutableStateFlow(true)
    val loadingShelves: StateFlow<Boolean> = _loadingShelves.asStateFlow()

    /** The hub couldn't load, and why: the lists, or a shelf with nothing
     *  older to show; null while it loaded, or shows what it had. */
    private val _failure = MutableStateFlow<ListFailure?>(null)
    val failure: StateFlow<ListFailure?> = _failure.asStateFlow()

    /** The list whose MORE view is open (null = the hub is showing). */
    private val _openListId = MutableStateFlow<String?>(null)
    val openListId: StateFlow<String?> = _openListId.asStateFlow()

    /** The open list's COMPLETE resolved items, newest-added first. */
    private val _openItems = MutableStateFlow<List<Item>>(emptyList())
    val openItems: StateFlow<List<Item>> = _openItems.asStateFlow()

    private val _loadingOpenItems = MutableStateFlow(false)
    val loadingOpenItems: StateFlow<Boolean> = _loadingOpenItems.asStateFlow()

    /** Surfaces a create/rename failure (duplicate name, too many lists) to
     *  the dialog. null while idle. */
    private val _dialogError = MutableStateFlow<String?>(null)
    val dialogError: StateFlow<String?> = _dialogError.asStateFlow()

    init {
        screenModelScope.launch { loadShelves() }
    }

    /**
     * Loads the hub: list overview, then per-list shelf items fanned out in
     * parallel so the slowest list gates the hub, not the sum. Each shelf
     * caps at [HUB_SHELF_CAP] resolved Items; per-id detail fetches inside a
     * shelf also run in parallel, preserving the server's newest-added-first
     * order (failures drop).
     */
    private suspend fun loadShelves(showSpinner: Boolean = true) {
        if (showSpinner) _loadingShelves.value = true
        val before = _shelves.value
        val listsFailed = repo.warmLists(force = true)
        var failed: Throwable? = listsFailed
        if (listsFailed == null) {
            val overview = repo.lists.value
            val fetched = coroutineScope {
                overview.map { list ->
                    async {
                        list.id to runCatching {
                            val ids = container.chinoApi.getWatchlist(list.id).items
                            ids.take(HUB_SHELF_CAP).map { id ->
                                async { runCatching { container.chinoApi.getItem(id) }.getOrNull() }
                            }.awaitAll().filterNotNull()
                        }
                    }
                }.awaitAll()
            }
            failed = fetched.firstNotNullOfOrNull { (_, r) -> r.exceptionOrNull()?.takeUnless { it is CancellationException } }
            // A shelf that didn't load keeps what it showed; none shows empty
            // for not knowing what is in it.
            _shelves.value = fetched.mapNotNull { (id, r) -> (r.getOrNull() ?: before[id])?.let { id to it } }.toMap()
        }
        // Nothing older on screen for what didn't load: the couldn't-load
        // screen says why, never an empty hub (an empty shelf claims what
        // nobody knows yet). What showed before stays as it was.
        val unknown = listsFailed != null && before.isEmpty() ||
            repo.lists.value.any { it.id !in _shelves.value }
        _failure.value = failed?.takeIf { unknown }?.let { ListFailure(it.loadFailure(singleThing = false), it.loadDetails()) }
        _loadingShelves.value = false
    }

    /** "try again" on the hub that couldn't load. */
    fun retry() {
        _failure.value = null
        screenModelScope.launch { loadShelves() }
    }

    /** Opens [listId]'s MORE view and loads its full item set. */
    fun openList(listId: String) {
        _openListId.value = listId
        loadOpenItems(listId)
    }

    /** Back from the MORE view to the hub. Shelves re-fetch silently (no
     *  spinner) so removals made from a detail page visited via the grid
     *  reconcile without flashing the whole hub. */
    fun closeList() {
        _openListId.value = null
        screenModelScope.launch { loadShelves(showSpinner = false) }
    }

    private fun loadOpenItems(listId: String) {
        screenModelScope.launch {
            _loadingOpenItems.value = true
            _openItems.value = emptyList()
            val ids = runCatching { container.chinoApi.getWatchlist(listId).items }
                .getOrDefault(emptyList())
            // Fan out per-id detail fetches in parallel; preserve the
            // server's newest-added-first order, failures drop.
            coroutineScope {
                _openItems.value = ids.map { id ->
                    async { runCatching { container.chinoApi.getItem(id) }.getOrNull() }
                }.awaitAll().filterNotNull()
            }
            _loadingOpenItems.value = false
        }
    }

    fun createList(name: String, onDone: (Watchlist?) -> Unit = {}) {
        screenModelScope.launch {
            val result = runCatching { repo.create(name) }
            result.onSuccess { created ->
                _dialogError.value = null
                // A fresh list is empty — seed its shelf so the hub renders
                // the new header + empty hint without a refetch.
                _shelves.value = _shelves.value + (created.id to emptyList())
                onDone(created)
            }.onFailure {
                _dialogError.value = friendlyError(it)
                onDone(null)
            }
        }
    }

    fun renameList(listId: String, name: String, onDone: (Boolean) -> Unit = {}) {
        screenModelScope.launch {
            runCatching { repo.rename(listId, name) }
                .onSuccess {
                    _dialogError.value = null
                    onDone(true)
                }
                .onFailure {
                    _dialogError.value = friendlyError(it)
                    onDone(false)
                }
        }
    }

    fun deleteList(listId: String) {
        screenModelScope.launch {
            runCatching { repo.delete(listId) }.onSuccess {
                _shelves.value = _shelves.value - listId
                // Deleting from the MORE view drops the user back on the hub.
                if (_openListId.value == listId) _openListId.value = null
            }
        }
    }

    fun clearDialogError() { _dialogError.value = null }

    /** chino-api's reason for refusing a create or rename ("name exists" /
     *  "too many lists" / a name out of 1..60), else a plain retry hint. */
    private fun friendlyError(t: Throwable): String =
        t.watchlistRefusal() ?: "Couldn't save the list. Try again."
}
