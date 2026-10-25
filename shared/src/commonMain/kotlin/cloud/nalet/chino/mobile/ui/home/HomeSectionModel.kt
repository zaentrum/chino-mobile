package cloud.nalet.chino.mobile.ui.home

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.ProgressBody
import cloud.nalet.chino.mobile.data.api.Season
import cloud.nalet.chino.mobile.data.api.NO_ANSWER_SENTENCE
import cloud.nalet.chino.mobile.data.api.catalogueMessage
import cloud.nalet.chino.mobile.data.api.loadDetails
import cloud.nalet.chino.mobile.data.api.loadFailure
import cloud.nalet.chino.mobile.data.api.sentence
import cloud.nalet.chino.mobile.data.api.signInRefused
import cloud.nalet.chino.mobile.data.downloads.Reach
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(
        /** Hero rotation pool — the newest movies and series with a trailer,
         *  those this server plays one of first, each group shuffled, as
         *  chino-web picks it (pickHeroPool). */
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
        /** Each hero title's first button, by its id ([heroPlay]): "resume
         *  0:58" and "start over", "play S01E01"; none where it is not known
         *  yet - "play", which finds the episode when pressed. */
        val heroPlays: Map<String, HeroPlay> = emptyMap(),
        /** The server can't be reached: the rows are what the phone knows
         *  from before ([KnownHome]), under the banner ([homeLoad]). */
        val offline: Boolean = false,
        /** Continue watching couldn't load, and the phone knows none of it:
         *  its couldn't-load line in its place, never nothing. */
        val continueWatchingFailed: ShelfFailure? = null,
    ) : HomeUiState
    /** [signInRefused]: the server refuses the sign-in (a 401 or 403), so
     *  the page offers the ways out - another server, signing in again. */
    data class Error(val message: String, val signInRefused: Boolean = false, val details: String? = null) : HomeUiState
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

    /** The seasons of the hero's series, where the hero needed them to name
     *  an episode ([heroNeedsSeasons]). */
    private var heroSeasons: Map<String, List<Season>> = emptyMap()

    /** The hero's first buttons for [pool] after the Continue watching
     *  [feed] - as the titles' pages have them. */
    private fun heroPlaysOf(pool: List<Item>, feed: List<ContinueWatchingItem>): Map<String, HeroPlay> =
        pool.mapNotNull { item -> heroPlay(item, feed, heroSeasons[item.id])?.let { item.id to it } }.toMap()

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
            val feed = (current.continueWatching + current.nextUp).filterNot { it.id == id }
            _state.value = current.copy(
                continueWatching = current.continueWatching.filterNot { it.id == id },
                heroPlays = heroPlaysOf(current.heroPool, feed),
            )
        }
        screenModelScope.launch {
            // Stamp progress at the end, as a dismiss (web's "dismiss" path),
            // so the CW query drops the row - a finished one - WITHOUT marking
            // a part-watched item fully WATCHED: chino-api marks a title saved
            // at its end watched but for a dismiss, and postWatched would add
            // a green badge + drop it from Zap. Fall back to postWatched only
            // when the duration is unknown.
            runCatching {
                if (dur > 0) container.chinoApi.postProgress(id, ProgressBody(positionSec = dur, durationSec = dur, dismiss = true))
                else container.chinoApi.postWatched(id)
            }
            // Refetch the CW feed so Next Up substitutions land and the
            // optimistic drop is reconciled with server truth.
            val refreshed = runCatching { container.chinoApi.continueWatching().items }
                .getOrNull() ?: return@launch
            // What the phone knows of it from now on, too.
            val key = knownKey()
            KnownHome.put(key, (KnownHome.get(key) ?: KnownRows()).copy(feed = refreshed))
            val latest = _state.value
            if (latest is HomeUiState.Ready) {
                _state.value = latest.copy(
                    continueWatching = refreshed.filter { !it.upNext },
                    nextUp = refreshed.filter { it.upNext },
                    heroPlays = heroPlaysOf(latest.heroPool, refreshed),
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

    /**
     * The hero's pool as chino-web picks it (pickHeroPool): the newest movies
     * and series, those whose details have a trailer — one of this server's
     * first, then a YouTube link — each group shuffled, eight. One details
     * fetch per candidate, all in parallel — so it is kept for an hour per
     * server and account (HeroPoolCache), as web keeps it per session. Empty
     * when the server has no such title: no hero, as on web.
     */
    private suspend fun heroPool(): List<Item> = coroutineScope {
        val api = container.chinoApi
        val key = container.config.apiBaseUrl + "|" + (container.accountStore.activeAccountId.first() ?: "")
        HeroPoolCache.get(key)?.let { return@coroutineScope it }
        val movies = async {
            runCatching { api.listItems(limit = HERO_CANDIDATES, type = "movie", sort = "newest").items }.getOrDefault(emptyList())
        }
        val series = async {
            runCatching { api.listItems(limit = HERO_CANDIDATES, type = "series", sort = "newest").items }.getOrDefault(emptyList())
        }
        val candidates = movies.await().map { it.copy(kind = it.kind ?: "movie") } +
            series.await().map { it.copy(kind = it.kind ?: "series") }
        val details = candidates
            .map { candidate -> async { runCatching { api.getItem(candidate.id) }.getOrNull() } }
            .mapNotNull { it.await() }
            .associateBy { it.id }
        pickHeroPool(candidates, details).also { HeroPoolCache.put(key, it) }
    }

    /**
     * Where Play on a hero title goes: a movie plays itself; a series plays
     * its episode (episodeToPlay), else opens its page — chino-web's
     * usePlayTitle. [then] gets the episode id, or null for the page.
     */
    fun seriesEpisode(seriesId: String, then: (String?) -> Unit) {
        screenModelScope.launch { then(seriesEpisodeToPlay(container.chinoApi, seriesId)) }
    }

    /** "try again" on a Home that couldn't load: loads it again. */
    fun retry() {
        _state.value = HomeUiState.Loading
        load()
    }

    /** Loads Home again where it stands - the server answers again after
     *  it couldn't be reached: the rows it shows stay until the new ones
     *  are in. */
    fun refresh() = load()

    /** Home's rows are kept per server and account ([KnownHome]). */
    private suspend fun knownKey(): String =
        container.config.apiBaseUrl + "|" + (container.accountStore.activeAccountId.first() ?: "")

    private fun load() {
        screenModelScope.launch {
            _state.value = try {
                coroutineScope {
                    val recentMoviesDef = async {
                        ask {
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
                        }
                    }
                    val recentSeriesDef = async {
                        ask {
                            container.chinoApi.listItems(
                                limit = 20,
                                type = "series",
                                sort = "newest",
                                unwatched = true,
                            ).items
                        }
                    }
                    val topRatedDef = async {
                        ask {
                            container.chinoApi.listItems(
                                limit = 20,
                                type = "movie",
                                sort = "rating",
                                ratingMin = 8.0,
                                unwatched = true,
                            ).items
                        }
                    }
                    val heroDef = async { heroPool() }
                    val cwDef = async { ask { container.chinoApi.continueWatching().items } }
                    // Minted again hours into a session: one ask more,
                    // never the whole page gone where it has no answer.
                    val tokenDef = async { ask { container.streamTokenManager.valid() } }
                    val key = knownKey()
                    val known = KnownHome.get(key)
                    // The hero names a series' episode as its page does
                    // ("play S01E01"): its seasons, where the feed doesn't
                    // say the episode's numbers - asked beside the rails.
                    val heroSeasonsDef = async {
                        val feed = cwDef.await().value ?: known?.feed.orEmpty()
                        heroDef.await()
                            .filter { it.kind == "series" && heroNeedsSeasons(it.id, feed) }
                            .map { s -> s.id to async { runCatching { container.chinoApi.seriesEpisodes(s.id).seasons }.getOrNull() } }
                            .mapNotNull { (id, seasons) -> seasons.await()?.let { id to it } }
                            .toMap()
                    }

                    val feed = cwDef.await()
                    // What this phone played since, of the rows it knows:
                    // where they resume now.
                    val playedHere = if (feed.value != null) {
                        emptyMap()
                    } else {
                        known?.feed.orEmpty().mapNotNull { row -> container.progressSync.lastPlayedSec(row.id)?.let { row.id to it } }.toMap()
                    }
                    val load = homeLoad(
                        feed,
                        recentMoviesDef.await(),
                        recentSeriesDef.await(),
                        topRatedDef.await(),
                        tokenDef.await(),
                        known,
                        playedHere,
                        lastToken = container.streamTokenManager.current.value,
                        // One row without an answer among answers: the
                        // server asked, as the app's other requests do.
                        reachable = { container.serverReach.check() == Reach.Reachable },
                    )
                    val view = load.view
                    KnownHome.put(key, view.toKeep)
                    // The server can't be reached: the whole app hears it -
                    // the banner, the bell gone - as on its other screens.
                    if (view.offline) container.serverReach.report(answered = false)
                    val pool = heroDef.await()
                    // Asked again each load; where an ask fails, the seasons
                    // the model had.
                    heroSeasons = heroSeasons + heroSeasonsDef.await()
                    val cw = view.continueWatching + view.nextUp
                    HomeUiState.Ready(
                        heroPool = pool,
                        continueWatching = view.continueWatching,
                        nextUp = view.nextUp,
                        recentMovies = view.recentMovies,
                        recentSeries = view.recentSeries,
                        topRated = view.topRated,
                        baseUrl = container.config.apiBaseUrl.trimEnd('/'),
                        streamToken = load.streamToken,
                        // A partly watched title resumes from what the phone
                        // knows of it, never a bare "play".
                        heroPlays = heroPlaysOf(pool, cw),
                        offline = view.offline,
                        continueWatchingFailed = view.continueWatchingFailed,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // The design system's case for it, by its status - a list:
                // a 404 there is the app and the server not matching - in
                // words a person can act on; a refused sign-in keeps its own
                // ways on (catalogueMessage).
                if (e.signInRefused) {
                    HomeUiState.Error(e.catalogueMessage(), signInRefused = true)
                } else {
                    if (e.loadFailure(singleThing = false) == LoadFailure.NoAnswer) container.serverReach.report(answered = false)
                    HomeUiState.Error(
                        e.loadFailure(singleThing = false).sentence() ?: NO_ANSWER_SENTENCE,
                        details = e.loadDetails(),
                    )
                }
            }
        }
    }
}
