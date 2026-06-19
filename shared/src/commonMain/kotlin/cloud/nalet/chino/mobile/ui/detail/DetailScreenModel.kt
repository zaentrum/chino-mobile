package cloud.nalet.chino.mobile.ui.detail

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Ready(
        val item: Item,
        val resumePositionSec: Int,
        val baseUrl: String,
        val streamToken: String,
        /** Series only: seasons + episodes for the "Episodes" accordion.
         *  Empty for movies. */
        val seasons: List<cloud.nalet.chino.mobile.data.api.Season> = emptyList(),
        /** "More like this" — empty when chino-api can't find similars
         *  or the source item is unknown. */
        val similar: List<Item> = emptyList(),
    ) : DetailUiState
    data class Error(val message: String) : DetailUiState
}

class DetailScreenModel(
    private val container: AppContainer,
    private val itemId: String,
) : ScreenModel {
    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** Optimistic local override for the detail item's own watched flag.
     *  null = follow the server-stamped `item.watchedAt`; non-null = the
     *  user has tapped the toggle this session. Mirrors chino-web's
     *  EpisodeRow `watchedOverride` pattern, lifted up to drive the
     *  Eye/Check toggle in the action row. */
    private val _watchedOverride = MutableStateFlow<Boolean?>(null)
    val watchedOverride: StateFlow<Boolean?> = _watchedOverride.asStateFlow()

    /** Per-episode optimistic overrides keyed by episode id. Seeded empty;
     *  each entry wins over the episode payload's `watched_at`. */
    private val _episodeWatched = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val episodeWatched: StateFlow<Map<String, Boolean>> = _episodeWatched.asStateFlow()

    /** Surfaces a "create list from the add-to-list sheet" failure (duplicate
     *  name / too many lists) to the sheet. null while idle. */
    private val _addToListError = MutableStateFlow<String?>(null)
    val addToListError: StateFlow<String?> = _addToListError.asStateFlow()

    init {
        // Warm UserFlags cache so watchlist + likes toggles render with the
        // right initial state without each Detail visit re-fetching.
        screenModelScope.launch { container.userFlags.warm() }
        // Warm the lists overview + this item's memberships so the add-to-list
        // picker seeds its checkmarks and the watchlist icon reflects
        // "in >=1 list" without the user opening the sheet first.
        screenModelScope.launch { container.watchlists.warmLists() }
        screenModelScope.launch { container.watchlists.warmMemberships(listOf(itemId)) }
        load()
    }

    /** Single-tap default: add the item to the DEFAULT list when it isn't in
     *  it, else remove it. Goes through the back-compat /me/watchlist route
     *  (UserFlagsRepository), keeping Zap + the card badge in sync. Mirrors
     *  chino-web's plain-tap behaviour. */
    fun toggleDefaultWatchlist() {
        val inDefault = itemId in container.userFlags.watchlist.value
        container.userFlags.setWatchlist(itemId, !inDefault)
    }

    /** Toggles the item's membership in a specific [listId] from the picker. */
    fun toggleListMembership(listId: String, checked: Boolean) {
        container.watchlists.setMembership(itemId, listId, checked)
    }

    /** Creates a list from the picker's inline field and adds the item to it.
     *  On a server rejection the error is surfaced via [addToListError]. */
    fun createListAndAdd(name: String) {
        screenModelScope.launch {
            runCatching { container.watchlists.create(name) }
                .onSuccess { created ->
                    _addToListError.value = null
                    container.watchlists.setMembership(itemId, created.id, true)
                }
                .onFailure { _addToListError.value = friendlyListError(it) }
        }
    }

    fun clearAddToListError() { _addToListError.value = null }

    private fun friendlyListError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("409") && msg.contains("name", ignoreCase = true) -> "A list with that name already exists."
            msg.contains("409") -> "You've reached the maximum number of lists."
            msg.contains("400") -> "Enter a name between 1 and 60 characters."
            else -> "Couldn't create the list. Try again."
        }
    }

    /** Real watched TOGGLE for the detail item. Mirrors chino-web's
     *  useWatchedToggle: POST to mark, DELETE to un-mark. Flips the local
     *  override optimistically so the Eye/Check button reflects the tap
     *  immediately; on network failure the override is rolled back. */
    fun toggleWatched(currentlyWatched: Boolean) {
        val next = !currentlyWatched
        _watchedOverride.value = next
        screenModelScope.launch {
            val result = runCatching {
                if (next) container.chinoApi.postWatched(itemId)
                else container.chinoApi.deleteWatched(itemId)
            }
            // Roll back the optimistic flip if the write didn't land, so the
            // UI doesn't lie about server state.
            if (result.isFailure) _watchedOverride.value = !next
        }
    }

    /** Per-episode watched toggle for the series episode list. Same
     *  POST/DELETE contract as [toggleWatched]; optimistic per-episode
     *  override, rolled back on failure. Mirrors chino-web's EpisodeRow. */
    fun toggleEpisodeWatched(episodeId: String, currentlyWatched: Boolean) {
        val next = !currentlyWatched
        _episodeWatched.value = _episodeWatched.value + (episodeId to next)
        screenModelScope.launch {
            val result = runCatching {
                if (next) container.chinoApi.postWatched(episodeId)
                else container.chinoApi.deleteWatched(episodeId)
            }
            if (result.isFailure) {
                _episodeWatched.value = _episodeWatched.value + (episodeId to !next)
            }
        }
    }

    private fun load() {
        screenModelScope.launch {
            _state.value = try {
                coroutineScope {
                    val itemDef = async { container.chinoApi.getItem(itemId) }
                    val progressDef = async {
                        runCatching { container.chinoApi.getProgress(itemId).positionSec }.getOrDefault(0)
                    }
                    val tokenDef = async { container.streamTokenManager.valid() }
                    val similarDef = async {
                        runCatching { container.chinoApi.similar(itemId).items }.getOrDefault(emptyList())
                    }
                    val item = itemDef.await()
                    val seasons = if (item.kind == "series") {
                        runCatching { container.chinoApi.seriesEpisodes(itemId).seasons }
                            .getOrDefault(emptyList())
                    } else emptyList()
                    DetailUiState.Ready(
                        item = item,
                        resumePositionSec = progressDef.await(),
                        baseUrl = container.config.apiBaseUrl.trimEnd('/'),
                        streamToken = tokenDef.await(),
                        seasons = seasons,
                        similar = similarDef.await(),
                    )
                }
            } catch (e: Exception) {
                DetailUiState.Error(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }
}
