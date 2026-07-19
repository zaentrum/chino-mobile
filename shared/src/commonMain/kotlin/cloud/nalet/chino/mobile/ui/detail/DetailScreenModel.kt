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
        /** When the detail target was an EPISODE, the page redirects to its
         *  parent SERIES and stamps the episode id here so the episodes
         *  accordion auto-expands that season, scrolls the row into view, and
         *  highlights it. Null for a series/movie opened directly. */
        val focusEpisodeId: String? = null,
        /** Series only: episodeId -> in-progress resume state derived from the
         *  continue-watching feed. Rows absent here have nothing to resume. */
        val episodeResume: Map<String, EpisodeResume> = emptyMap(),
    ) : DetailUiState
    data class Error(val message: String) : DetailUiState
}

/** One episode row's in-progress state (from /me/continue-watching): more
 *  than 30s in, not finished, not an up-next substitution. */
data class EpisodeResume(val positionSec: Int, val durationSec: Int)

class DetailScreenModel(
    private val container: AppContainer,
    private val itemId: String,
) : ScreenModel {
    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** The id the page actually renders detail for. Normally the constructor
     *  [itemId]; when [itemId] resolves to an EPISODE the page redirects to its
     *  parent SERIES, so this becomes the series id and drives the action-row
     *  watchlist / watched / list-membership toggles (which are series-level).
     *  Per-episode toggles still target their own episode ids explicitly. */
    private var displayItemId: String = itemId

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
        val inDefault = displayItemId in container.userFlags.watchlist.value
        container.userFlags.setWatchlist(displayItemId, !inDefault)
    }

    /** Toggles [targetId]'s membership in a specific [listId] from the picker.
     *  The target is the displayed item when the picker was opened from the
     *  action row, or an EPISODE id when opened from an episode row's "+". */
    fun toggleListMembership(targetId: String, listId: String, checked: Boolean) {
        container.watchlists.setMembership(targetId, listId, checked)
    }

    /** Creates a list from the picker's inline field and adds [targetId] to
     *  it. On a server rejection the error is surfaced via [addToListError]. */
    fun createListAndAdd(targetId: String, name: String) {
        screenModelScope.launch {
            runCatching { container.watchlists.create(name) }
                .onSuccess { created ->
                    _addToListError.value = null
                    container.watchlists.setMembership(targetId, created.id, true)
                }
                .onFailure { _addToListError.value = friendlyListError(it) }
        }
    }

    /** Best-effort membership warm for an episode row's add-to-list picker,
     *  so the sheet's checkmarks seed correctly on first open (only the
     *  displayed item's memberships are warmed at load time). */
    fun warmMemberships(id: String) {
        screenModelScope.launch { runCatching { container.watchlists.warmMemberships(listOf(id)) } }
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
                if (next) container.chinoApi.postWatched(displayItemId)
                else container.chinoApi.deleteWatched(displayItemId)
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
                    val tokenDef = async { container.streamTokenManager.valid() }
                    val requested = container.chinoApi.getItem(itemId)
                    // Episode redirect: if the target is an episode, render the
                    // PARENT SERIES detail instead of a standalone episode page,
                    // and remember the requested episode so the accordion
                    // expands + scrolls + highlights it. Falls back to showing
                    // the episode itself if the parent can't be resolved.
                    val isEpisode = requested.kind == "episode" ||
                        (requested.kind != "series" && requested.parentId != null)
                    val parent = if (isEpisode) {
                        requested.parentId?.let { pid ->
                            runCatching { container.chinoApi.getItem(pid) }.getOrNull()
                        }
                    } else null
                    val focusEpisodeId = if (parent != null) requested.id else null
                    val item = parent ?: requested
                    // Point the action-row toggles at whatever the page now
                    // renders (the series when redirected). Re-warm the flag /
                    // membership caches for that id so the icons seed correctly.
                    if (item.id != displayItemId) {
                        displayItemId = item.id
                        launch { runCatching { container.watchlists.warmMemberships(listOf(item.id)) } }
                    }
                    val progressDef = async {
                        runCatching { container.chinoApi.getProgress(item.id).positionSec }.getOrDefault(0)
                    }
                    val similarDef = async {
                        runCatching { container.chinoApi.similar(item.id).items }.getOrDefault(emptyList())
                    }
                    // Per-episode resume state (series only, best-effort):
                    // the episodes payload carries no progress fields, but the
                    // continue-watching feed stamps position/duration on its
                    // in-progress rows — episodes included. Keyed by episode
                    // id, so lookups only ever hit this series' own rows.
                    // Up-next substitutions (position=0) and finished rows
                    // (within 60s of the end) are skipped — nothing to
                    // resume. Rows the feed stamps with duration<=0 are KEPT
                    // (web parity): the EpisodeRow falls back to the
                    // episode's catalogue runtime for the bar + remaining.
                    val episodeResumeDef = async {
                        if (item.kind != "series") return@async emptyMap<String, EpisodeResume>()
                        runCatching { container.chinoApi.continueWatching().items }
                            .getOrDefault(emptyList())
                            .filter {
                                !it.upNext && it.positionSec > 30 &&
                                    (it.durationSec <= 0 || it.positionSec < it.durationSec - 60)
                            }
                            .associate { it.id to EpisodeResume(it.positionSec, it.durationSec) }
                    }
                    val seasons = if (item.kind == "series") {
                        runCatching { container.chinoApi.seriesEpisodes(item.id).seasons }
                            .getOrDefault(emptyList())
                    } else emptyList()
                    DetailUiState.Ready(
                        item = item,
                        resumePositionSec = progressDef.await(),
                        baseUrl = container.config.apiBaseUrl.trimEnd('/'),
                        streamToken = tokenDef.await(),
                        seasons = seasons,
                        similar = similarDef.await(),
                        focusEpisodeId = focusEpisodeId,
                        episodeResume = episodeResumeDef.await(),
                    )
                }
            } catch (e: Exception) {
                DetailUiState.Error(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }
}
