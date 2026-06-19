package cloud.nalet.chino.mobile.ui.watchlist

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.Watchlist
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
        repo.warmLists(force = true)
        val overview = repo.lists.value
        coroutineScope {
            _shelves.value = overview.map { list ->
                async {
                    val ids = runCatching { container.chinoApi.getWatchlist(list.id).items }
                        .getOrDefault(emptyList())
                    list.id to ids.take(HUB_SHELF_CAP).map { id ->
                        async { runCatching { container.chinoApi.getItem(id) }.getOrNull() }
                    }.awaitAll().filterNotNull()
                }
            }.awaitAll().toMap()
        }
        _loadingShelves.value = false
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

    /** Maps a Ktor non-2xx (the picker dialogs throw on rejection) onto a
     *  short, plain-language message. Best-effort — the server's exact 409
     *  reason ("name exists" / "too many lists") is buried in the response
     *  body the client doesn't decode, so we key off the status text. */
    private fun friendlyError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("409") && msg.contains("name", ignoreCase = true) -> "A list with that name already exists."
            msg.contains("409") -> "You've reached the maximum number of lists."
            msg.contains("400") -> "Enter a name between 1 and 60 characters."
            else -> "Couldn't save the list. Try again."
        }
    }
}
