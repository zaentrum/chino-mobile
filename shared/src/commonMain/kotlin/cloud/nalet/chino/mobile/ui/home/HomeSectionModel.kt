package cloud.nalet.chino.mobile.ui.home

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.ProgressBody
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(
        /** Hero rotation pool — top-rated movies, picked separately from the
         *  Top Rated row so the hero stays fresh even if the user is browsing
         *  the same titles below. */
        val heroPool: List<Item>,
        val continueWatching: List<ContinueWatchingItem>,
        val nextUp: List<ContinueWatchingItem>,
        /** Recently added — movies (chino-web splits the catalog by type
         *  here so users can find new films separately from new shows). */
        val recentMovies: List<Item>,
        /** Recently added — series (TV / anime / docu) shows. */
        val recentSeries: List<Item>,
        val topRated: List<Item>,
        val baseUrl: String,
        val streamToken: String,
    ) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

/**
 * Loads everything the Home tab needs in one parallel fan-out. Mirrors
 * chino-web's HomeSection's combined hook calls (useHeroPool +
 * useContinueWatching + useItems Recent + useItems Top Rated). All requests
 * fire in parallel so the slowest call gates the screen, not the sum.
 */
class HomeSectionModel(private val container: AppContainer) : ScreenModel {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { load() }

    /**
     * Remove an item from the Continue Watching rail. Mirrors chino-web's
     * MediaCard "Remove from Continue Watching" action: POST watched so the
     * server drops the row from the CW feed, then refetch continue-watching.
     * The row is dropped optimistically so the card disappears immediately;
     * the refetch reconciles with the server (and pulls in any substituted
     * Next Up card).
     */
    fun removeFromContinueWatching(id: String) {
        val current = _state.value
        val dur = (current as? HomeUiState.Ready)?.continueWatching?.find { it.id == id }?.durationSec ?: 0
        if (current is HomeUiState.Ready) {
            _state.value = current.copy(
                continueWatching = current.continueWatching.filterNot { it.id == id },
            )
        }
        screenModelScope.launch {
            // Stamp progress at the end (web's "dismiss" path) so the CW query
            // drops the row WITHOUT marking a part-watched item fully WATCHED
            // (postWatched would add a green badge + drop it from Zap). Fall
            // back to postWatched only when the duration is unknown.
            runCatching {
                if (dur > 0) container.chinoApi.postProgress(id, ProgressBody(positionSec = dur, durationSec = dur))
                else container.chinoApi.postWatched(id)
            }
            // Refetch the CW feed so Next Up substitutions land and the
            // optimistic drop is reconciled with server truth.
            val refreshed = runCatching { container.chinoApi.continueWatching().items }
                .getOrNull() ?: return@launch
            val latest = _state.value
            if (latest is HomeUiState.Ready) {
                _state.value = latest.copy(
                    continueWatching = refreshed.filter { !it.upNext },
                    nextUp = refreshed.filter { it.upNext },
                )
            }
        }
    }

    /**
     * #188: toggle the fully-watched flag for a catalogue item from a Home
     * rail's card overflow menu. Mirrors the detail-page eye + chino-web's
     * card watched toggle: POST to mark, DELETE to un-mark — the SAME watched
     * endpoints. The Home rails request unwatched=true and hide watched, so
     * marking watched optimistically DROPS the card from its rail (mirrors
     * removeFromContinueWatching's optimistic drop), then we refetch the Home
     * data so server truth + any rail backfill reconcile. Un-marking is the
     * inverse; the rail refetch pulls the title back if it now qualifies.
     */
    fun toggleWatched(id: String) {
        val current = _state.value as? HomeUiState.Ready ?: return
        // Determine current watched state from whatever rail the id sits in.
        val wasWatched = current.recentMovies.firstOrNull { it.id == id }?.watchedAt != null ||
            current.recentSeries.firstOrNull { it.id == id }?.watchedAt != null ||
            current.topRated.firstOrNull { it.id == id }?.watchedAt != null ||
            current.continueWatching.firstOrNull { it.id == id }?.watchedAt != null ||
            current.nextUp.firstOrNull { it.id == id }?.watchedAt != null
        val markWatched = !wasWatched
        // Optimistic local update: marking watched drops the card from every
        // unwatched-only rail it appears in (consistent with the CW remove).
        if (markWatched) {
            _state.value = current.copy(
                recentMovies = current.recentMovies.filterNot { it.id == id },
                recentSeries = current.recentSeries.filterNot { it.id == id },
                topRated = current.topRated.filterNot { it.id == id },
                continueWatching = current.continueWatching.filterNot { it.id == id },
                nextUp = current.nextUp.filterNot { it.id == id },
            )
        }
        screenModelScope.launch {
            runCatching {
                if (markWatched) container.chinoApi.postWatched(id)
                else container.chinoApi.deleteWatched(id)
            }
            // Reload Home so the rails reconcile with server truth (a marked
            // title leaves the unwatched rails; an un-marked one can return).
            load()
        }
    }

    private fun load() {
        screenModelScope.launch {
            _state.value = try {
                coroutineScope {
                    val recentMoviesDef = async {
                        runCatching {
                            container.chinoApi.listItems(
                                // #150: cap each Home rail at 20 (chino-web
                                // contract — useItems(undefined, 20, ...)). The
                                // trailing "See all" tile in MediaRow is the
                                // escape hatch to the full Movies/Shows overview.
                                limit = 20,
                                type = "movie",
                                sort = "newest",
                                unwatched = true,
                            ).items
                        }.getOrDefault(emptyList())
                    }
                    val recentSeriesDef = async {
                        runCatching {
                            container.chinoApi.listItems(
                                limit = 20,
                                type = "series",
                                sort = "newest",
                                unwatched = true,
                            ).items
                        }.getOrDefault(emptyList())
                    }
                    val topRatedDef = async {
                        runCatching {
                            container.chinoApi.listItems(
                                limit = 20,
                                type = "movie",
                                sort = "rating",
                                ratingMin = 8.0,
                                unwatched = true,
                            ).items
                        }.getOrDefault(emptyList())
                    }
                    val heroDef = async {
                        runCatching {
                            // Match chino-web's hero pool size of 8 — keeps
                            // the pagination dot row from getting too long
                            // on items with many high-rated entries.
                            val list = container.chinoApi.listItems(
                                limit = 8,
                                type = "movie",
                                sort = "rating",
                                ratingMin = 8.0,
                                unwatched = true,
                            ).items
                            // /v1/items (list) returns a slim shape WITHOUT
                            // the overview/description field — chino-api
                            // keeps the list endpoint lightweight. chino-web
                            // works around this in useHeroPool by issuing a
                            // /v1/items/{id} detail fetch for each hero
                            // candidate (useHeroPool.ts L89) and copying
                            // the full overview onto the entry. Without
                            // this enrichment HeroBanner skips the overview
                            // Text (null branch) and the middle column is
                            // empty. Detail calls run in parallel so the
                            // slowest single call gates the hero, not the
                            // sum across all 8.
                            list.map { item ->
                                async {
                                    runCatching {
                                        val detail = container.chinoApi.getItem(item.id)
                                        item.copy(overview = detail.overview ?: item.overview)
                                    }.getOrDefault(item)
                                }
                            }.map { it.await() }
                        }.getOrDefault(emptyList())
                    }
                    val cwDef = async {
                        runCatching {
                            container.chinoApi.continueWatching().items
                        }.getOrDefault(emptyList())
                    }
                    val tokenDef = async { container.streamTokenManager.valid() }

                    val cw = cwDef.await()
                    HomeUiState.Ready(
                        heroPool = heroDef.await(),
                        continueWatching = cw.filter { !it.upNext },
                        nextUp = cw.filter { it.upNext },
                        recentMovies = recentMoviesDef.await(),
                        recentSeries = recentSeriesDef.await(),
                        topRated = topRatedDef.await(),
                        baseUrl = container.config.apiBaseUrl.trimEnd('/'),
                        streamToken = tokenDef.await(),
                    )
                }
            } catch (e: Exception) {
                HomeUiState.Error(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }
}
