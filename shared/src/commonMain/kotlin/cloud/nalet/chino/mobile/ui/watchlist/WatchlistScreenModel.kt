package cloud.nalet.chino.mobile.ui.watchlist

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.KindSplit
import cloud.nalet.chino.mobile.data.WatchlistsRepository
import cloud.nalet.chino.mobile.data.api.ChinoApi
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
import kotlinx.coroutines.flow.update
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
 *
 * With the account's Separate movies and shows on, where the server fills a
 * list by kind (section 8.8.1 of the specification), each list is asked for
 * twice instead, its movies and then its shows, with the bearer of the
 * account the setting is of ([KindSplit]); each row is what its answer said:
 * a row that couldn't load fails alone, and nothing of one answer is checked
 * against the other. The lists are asked for at once, beside the setting and
 * what the server offers rather than after them: they are drawn with the
 * choice held for the account - the one last known on the phone while it is
 * read, off while the server hasn't said it fills lists by kind - and again
 * where the answer differs.
 */
class WatchlistScreenModel internal constructor(
    private val repo: WatchlistsRepository,
    private val api: ChinoApi,
    /** For whom every list is split by kind, asked: what the server offers
     *  where it hasn't said, and [fresh], the setting read again
     *  (AppContainer.kindSplit). */
    private val kindSplit: suspend (fresh: Boolean) -> KindSplit?,
    /** The same as far as is known now, asking nothing
     *  (AppContainer.kindSplitNow); null draws every list whole. */
    private val kindSplitNow: () -> KindSplit?,
) : ScreenModel {
    constructor(container: AppContainer) : this(container.watchlists, container.chinoApi, container::kindSplit, container::kindSplitNow)

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

    /** With Separate movies and shows on: for whom, and each list's two
     *  rows; null draws every list whole, as with the setting off. */
    private val _byKind = MutableStateFlow<KindHub?>(null)
    val byKind: StateFlow<KindHub?> = _byKind.asStateFlow()

    /** With Separate movies and shows on: the open list's two parts, each
     *  COMPLETE as its answer came; null while the list is drawn whole. */
    private val _openKinds = MutableStateFlow<KindRows?>(null)
    val openKinds: StateFlow<KindRows?> = _openKinds.asStateFlow()

    /** How many loads of the hub are running: one that ends checks the
     *  setting again itself. */
    private var loads = 0

    /** How often a list was opened: an answer for one opened before is not
     *  shown. */
    private var opened = 0L

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
     * order (failures drop). Split by kind ([kindSplitNow]), each list's two
     * rows instead. A load of its own - the first, try again - asks for the
     * setting again ([fresh]) beside the lists, not before them: it may have
     * been changed on another device, and where its answer differs from what
     * is drawn, the lists are drawn again.
     */
    private suspend fun loadShelves(showSpinner: Boolean = true, fresh: Boolean = showSpinner) {
        loads++
        try {
            loadShelvesOnce(showSpinner, fresh)
        } finally {
            loads--
        }
    }

    private suspend fun loadShelvesOnce(showSpinner: Boolean, fresh: Boolean) {
        if (showSpinner) _loadingShelves.value = true
        if (fresh) {
            screenModelScope.launch {
                // Can't be said (a sign-in that can't be renewed now): what is
                // held stays.
                try {
                    kindSplit(true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (loads == 0 && differs(splitNow(), _byKind.value?.split)) loadShelves(showSpinner = false, fresh = false)
            }
        }
        val before = _shelves.value
        val listsFailed = repo.warmLists(force = true)
        // As known once the lists are in: a setting answered first is drawn
        // at once.
        val split = splitNow()
        var failed: Throwable? = listsFailed
        if (listsFailed == null) {
            val overview = repo.lists.value
            if (split != null) {
                loadByKind(split, overview)
            } else {
                val fetched = coroutineScope {
                    overview.map { list ->
                        async {
                            list.id to runCatching {
                                val ids = api.getWatchlist(list.id).items
                                ids.take(HUB_SHELF_CAP).map { id ->
                                    async { runCatching { api.getItem(id) }.getOrNull() }
                                }.awaitAll().filterNotNull()
                            }
                        }
                    }.awaitAll()
                }
                failed = fetched.firstNotNullOfOrNull { (_, r) -> r.exceptionOrNull()?.takeUnless { it is CancellationException } }
                // A shelf that didn't load keeps what it showed; none shows empty
                // for not knowing what is in it. Drawn split until now, the
                // rows by kind go with the whole rows in their place, never
                // before them.
                _shelves.value = fetched.mapNotNull { (id, r) -> (r.getOrNull() ?: before[id])?.let { id to it } }.toMap()
                _byKind.value = null
            }
        }
        // Nothing older on screen for what didn't load: the couldn't-load
        // screen says why, never an empty hub (an empty shelf claims what
        // nobody knows yet). What showed before stays as it was. Split by
        // kind, each row says on its own that it couldn't load.
        val unknown = _byKind.value == null && (
            listsFailed != null && before.isEmpty() ||
                repo.lists.value.any { it.id !in _shelves.value }
            )
        _failure.value = failed?.takeIf { unknown }?.let { ListFailure(it.loadFailure(singleThing = false), it.loadDetails()) }
        _loadingShelves.value = false
        // The setting answered while the rows were asked for: drawn again
        // with it.
        if (listsFailed == null && differs(splitNow(), split)) loadShelvesOnce(showSpinner = false, fresh = false)
    }

    /** For whom every list is split by kind as far as is known now; where
     *  that can't be said, every list whole. */
    private fun splitNow(): KindSplit? = try {
        kindSplitNow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Whether [now] draws the lists otherwise than [drawn]. */
    private fun differs(now: KindSplit?, drawn: KindSplit?): Boolean = now?.accountId != drawn?.accountId

    /** Each list's two rows for [split]'s account - the lists in parallel,
     *  each list's rows one after the other - drawn only while that account
     *  is the one signed in. Where no bearer can be had for it - its sign-in
     *  can't be renewed, or the renewal got no answer - every row couldn't
     *  load. A row whose answer failed keeps the one shown for the account
     *  before, as a whole row does. */
    private suspend fun loadByKind(split: KindSplit, overview: List<Watchlist>) {
        val rows = when (val bearer = bearerFor(split)) {
            is Bearer.OtherAccount -> return
            is Bearer.Failed -> overview.associate { it.id to KindRows(KindRow.Failed(bearer.failure), KindRow.Failed(bearer.failure)) }
            is Bearer.Token -> coroutineScope {
                overview.map { list -> async { list.id to api.kindRows(list.id, HUB_SHELF_CAP, bearer.token) } }.awaitAll().toMap()
            }
        }
        // Answered after another account was chosen: not drawn.
        if (!split.isActive()) return
        val shown = _byKind.value?.takeIf { it.split.accountId == split.accountId }?.rows.orEmpty()
        _byKind.value = KindHub(split, rows.mapValues { (id, new) -> new.keepingShown(shown[id]) })
    }

    /** What a request for [split]'s account goes out with. */
    private sealed interface Bearer {
        data class Token(val token: String) : Bearer

        /** Another account is signed in now: nothing is asked, or drawn. */
        data object OtherAccount : Bearer

        /** The account is still the one signed in, but no bearer can be had
         *  for it, and why. */
        data class Failed(val failure: LoadFailure) : Bearer
    }

    private suspend fun bearerFor(split: KindSplit): Bearer {
        val token = try {
            split.bearer()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Bearer.Failed(e.loadFailure(singleThing = false))
        }
        return when {
            token != null -> Bearer.Token(token)
            !split.isActive() -> Bearer.OtherAccount
            else -> Bearer.Failed(LoadFailure.SignedOut)
        }
    }

    /** "try again" on the hub that couldn't load. */
    fun retry() {
        _failure.value = null
        screenModelScope.launch { loadShelves() }
    }

    /** Opens [listId]'s MORE view and loads its full item set: by kind
     *  where the hub is drawn so. */
    fun openList(listId: String) {
        _openListId.value = listId
        val ask = ++opened
        val hub = _byKind.value
        if (hub != null) loadOpenKinds(hub.split, listId, ask) else loadOpenItems(listId, ask)
    }

    /** Whether [listId], opened as [ask], is still the open list: the answer
     *  for one opened before is not shown under another. */
    private fun stillOpen(listId: String, ask: Long): Boolean = opened == ask && _openListId.value == listId

    /** Back from the MORE view to the hub. Shelves re-fetch silently (no
     *  spinner) so removals made from a detail page visited via the grid
     *  reconcile without flashing the whole hub. */
    fun closeList() {
        _openListId.value = null
        _openKinds.value = null
        _loadingOpenItems.value = false
        screenModelScope.launch { loadShelves(showSpinner = false) }
    }

    private fun loadOpenItems(listId: String, ask: Long) {
        screenModelScope.launch {
            _loadingOpenItems.value = true
            _openItems.value = emptyList()
            _openKinds.value = null
            val ids = runCatching { api.getWatchlist(listId).items }
                .getOrDefault(emptyList())
            // Fan out per-id detail fetches in parallel; preserve the
            // server's newest-added-first order, failures drop.
            val items = coroutineScope {
                ids.map { id ->
                    async { runCatching { api.getItem(id) }.getOrNull() }
                }.awaitAll().filterNotNull()
            }
            if (!stillOpen(listId, ask)) return@launch
            _openItems.value = items
            _loadingOpenItems.value = false
        }
    }

    /** [listId]'s two parts, complete, for [split]'s account: its movies,
     *  then its shows, each as its answer came. */
    private fun loadOpenKinds(split: KindSplit, listId: String, ask: Long) {
        screenModelScope.launch {
            _loadingOpenItems.value = true
            _openItems.value = emptyList()
            _openKinds.value = null
            val parts = when (val bearer = bearerFor(split)) {
                is Bearer.OtherAccount -> null
                is Bearer.Failed -> KindRows(KindRow.Failed(bearer.failure), KindRow.Failed(bearer.failure))
                is Bearer.Token -> api.kindRows(listId, limit = null, bearer = bearer.token)
            }
            if (!stillOpen(listId, ask)) return@launch
            if (parts != null && split.isActive()) _openKinds.value = parts
            _loadingOpenItems.value = false
        }
    }

    /**
     * try again on a row of [listId] that couldn't load: that one row asked
     * again, by its [kind] - in the open list where [listId] is open, else on
     * the hub - the other row left as it came.
     */
    fun retryRow(listId: String, kind: TitleKind) {
        val hub = _byKind.value ?: return
        val open = _openListId.value == listId
        val before = (if (open) _openKinds.value else hub.rows[listId])?.get(kind) ?: return
        if (before == KindRow.Loading) return
        setRow(hub.split, listId, kind, KindRow.Loading, open)
        screenModelScope.launch {
            val row = when (val bearer = bearerFor(hub.split)) {
                is Bearer.OtherAccount -> before
                is Bearer.Failed -> KindRow.Failed(bearer.failure)
                is Bearer.Token -> api.kindRow(listId, kind, if (open) null else HUB_SHELF_CAP, bearer.token)
            }
            if (hub.split.isActive()) setRow(hub.split, listId, kind, row, open)
        }
    }

    private fun setRow(split: KindSplit, listId: String, kind: TitleKind, row: KindRow, open: Boolean) {
        if (open) {
            if (_openListId.value == listId) _openKinds.update { it?.with(kind, row) }
        } else {
            _byKind.update { hub ->
                val rows = hub?.takeIf { it.split === split }?.rows?.get(listId) ?: return@update hub
                hub.with(listId, rows.with(kind, row))
            }
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
                _byKind.update { it?.with(created.id, KindRows.Empty) }
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
                _byKind.update { it?.without(listId) }
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
