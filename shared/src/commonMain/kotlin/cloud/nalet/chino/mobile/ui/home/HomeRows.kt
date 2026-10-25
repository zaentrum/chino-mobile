package cloud.nalet.chino.mobile.ui.home

import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.NO_ACCESS_TITLE
import cloud.nalet.chino.mobile.data.api.loadDetails
import cloud.nalet.chino.mobile.data.api.loadFailure
import cloud.nalet.chino.mobile.data.api.sentence
import cloud.nalet.chino.mobile.data.api.signInRefused
import cloud.nalet.chino.mobile.data.model.Item
import kotlin.coroutines.cancellation.CancellationException

/** One of Home's asks: what the server answered, or why it didn't. */
internal class Asked<T>(val value: T?, val failure: Exception? = null)

/** Asks [block]: its answer, or why there was none. */
internal suspend fun <T> ask(block: suspend () -> T): Asked<T> = try {
    Asked(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Asked(null, e)
}

/** What the phone knows of Home's rows from the last time the server
 *  answered them, each row on its own: null for a row it never had. */
internal data class KnownRows(
    /** Continue watching as the server gave it: Next Up's rows in it too. */
    val feed: List<ContinueWatchingItem>? = null,
    val recentMovies: List<Item>? = null,
    val recentSeries: List<Item>? = null,
    val topRated: List<Item>? = null,
)

/**
 * Home's rows as the server last gave them, for one server and account,
 * while the app runs: what Home shows while the server can't be reached,
 * under the banner, rather than rows that vanish without a word.
 */
internal object KnownHome {
    private var key: String? = null
    private var rows: KnownRows? = null

    fun get(key: String): KnownRows? = rows.takeIf { this.key == key }

    fun put(key: String, rows: KnownRows) {
        this.key = key
        this.rows = rows
    }
}

/** Why Continue watching couldn't load where the phone knows nothing of it,
 *  by the design system's case: its [title] - what couldn't load, or "you
 *  don't have access to this" for a 403 - the reason, none where the banner
 *  already says the server can't be reached or the case has none; whether
 *  "try again" can help ([tryAgain]: not where access is refused); and what
 *  tells the cases apart, under details. */
data class ShelfFailure(
    val sentence: String?,
    val details: String?,
    val title: String = CONTINUE_WATCHING_COULDNT_LOAD,
    val tryAgain: Boolean = true,
)

/** What one load's row asks say of the server ([homeReach]). */
internal enum class HomeReach {
    /** Each had its answer: rows, or a status. */
    Answered,

    /** None had an answer: the server can't be reached. */
    NoAnswer,

    /** One went without an answer where others had theirs - too slow, or
     *  dropped on its way: by itself no word on the server, which is asked
     *  ([cloud.nalet.chino.mobile.data.downloads.ServerReach.check]). */
    Unsure,
}

/** What Home's row asks say of the server: one with an answer - its rows,
 *  or a status (a 500 is the server answering) - says it is there. */
internal fun homeReach(vararg asks: Asked<*>): HomeReach =
    when (asks.count { it.failure?.loadFailure(singleThing = false) == LoadFailure.NoAnswer }) {
        0 -> HomeReach.Answered
        asks.size -> HomeReach.NoAnswer
        else -> HomeReach.Unsure
    }

/** What Home shows of one load ([homeView]). */
internal data class HomeView(
    val continueWatching: List<ContinueWatchingItem>,
    val nextUp: List<ContinueWatchingItem>,
    val recentMovies: List<Item>,
    val recentSeries: List<Item>,
    val topRated: List<Item>,
    /** The server can't be reached: Home shows what the phone knows, under
     *  the banner, and the app hears it. */
    val offline: Boolean,
    /** Continue watching couldn't load and the phone knows none of it. */
    val continueWatchingFailed: ShelfFailure?,
    /** What the phone knows from now on: the rows that answered, the kept
     *  ones for those that didn't. */
    val toKeep: KnownRows,
)

/**
 * Home of one load: each row as the server answered it; a row it didn't
 * answer as the phone knows it from before ([known]) - Continue watching
 * with the positions played on this phone since ([playedHere], seconds by
 * title), so a partly watched title still resumes - and a row it never knew,
 * empty. Continue watching never goes silently: where the phone knows
 * none of it, it couldn't load, with the reason ([ShelfFailure]). [offline]
 * where the server can't be reached - by default where no row had an answer
 * ([homeReach]) - as the design system's banner says it for a list still on
 * the screen; one row without an answer among answers is that row's alone.
 */
internal fun homeView(
    feed: Asked<List<ContinueWatchingItem>>,
    recentMovies: Asked<List<Item>>,
    recentSeries: Asked<List<Item>>,
    topRated: Asked<List<Item>>,
    known: KnownRows?,
    playedHere: Map<String, Int> = emptyMap(),
    offline: Boolean = homeReach(feed, recentMovies, recentSeries, topRated) == HomeReach.NoAnswer,
): HomeView {
    val shownFeed = feed.value ?: known?.feed?.map { row -> playedHere[row.id]?.let { row.copy(positionSec = it) } ?: row }
    val failed = feed.failure?.takeIf { shownFeed == null }?.let { e ->
        when (val case = e.loadFailure(singleThing = false)) {
            // No access (403): the design system's heading, nothing under it
            // but the status - "try again" can't change it.
            LoadFailure.NoAccess -> ShelfFailure(sentence = null, details = e.loadDetails(), title = NO_ACCESS_TITLE, tryAgain = false)
            // The case's own reason, where it has one: never a word of the
            // server out of reach from one that answered.
            else -> ShelfFailure(sentence = if (offline) null else case.sentence(), details = e.loadDetails())
        }
    }
    val rows = shownFeed.orEmpty()
    return HomeView(
        continueWatching = rows.filter { !it.upNext },
        nextUp = rows.filter { it.upNext },
        recentMovies = recentMovies.value ?: known?.recentMovies.orEmpty(),
        recentSeries = recentSeries.value ?: known?.recentSeries.orEmpty(),
        topRated = topRated.value ?: known?.topRated.orEmpty(),
        offline = offline,
        continueWatchingFailed = failed,
        toKeep = KnownRows(
            feed = feed.value ?: known?.feed,
            recentMovies = recentMovies.value ?: known?.recentMovies,
            recentSeries = recentSeries.value ?: known?.recentSeries,
            topRated = topRated.value ?: known?.topRated,
        ),
    )
}

/** What one load of Home comes to ([homeLoad]). */
internal class HomeLoad(val view: HomeView, val streamToken: String)

/**
 * One load of Home from its asks ([ask]): its rows ([homeView]), and the
 * token its posters and the hero are signed with - the one [token] got, else
 * the one the phone signed with last ([lastToken], the posters it already
 * shows): a mint that had no answer is one ask more that didn't, never the
 * whole page gone. A sign-in the server refuses is thrown, for Home's own
 * page for it with its ways on: a 401 on any ask, which the app's own
 * renewal didn't mend - no row's empty state, the sign-in - and a 401 or
 * 403 on the mint, the account's own.
 *
 * Offline - the banner, and the app hearing the server can't be reached -
 * where no row had an answer; where one went without among answers, only
 * if the server, asked now ([reachable]), doesn't answer either: a slow
 * query or a dropped request is no word on the server.
 */
internal suspend fun homeLoad(
    feed: Asked<List<ContinueWatchingItem>>,
    recentMovies: Asked<List<Item>>,
    recentSeries: Asked<List<Item>>,
    topRated: Asked<List<Item>>,
    token: Asked<String>,
    known: KnownRows?,
    playedHere: Map<String, Int> = emptyMap(),
    lastToken: String? = null,
    reachable: suspend () -> Boolean,
): HomeLoad {
    listOf(feed, recentMovies, recentSeries, topRated)
        .firstNotNullOfOrNull { asked -> asked.failure?.takeIf { it.loadFailure(singleThing = false) == LoadFailure.SignedOut } }
        ?.let { throw it }
    token.failure?.takeIf { it.signInRefused }?.let { throw it }
    val offline = when (homeReach(feed, recentMovies, recentSeries, topRated)) {
        HomeReach.Answered -> false
        HomeReach.NoAnswer -> true
        HomeReach.Unsure -> !reachable()
    }
    return HomeLoad(
        view = homeView(feed, recentMovies, recentSeries, topRated, known, playedHere, offline),
        streamToken = token.value ?: lastToken.orEmpty(),
    )
}

/**
 * Home as it stands once the server can't be reached - found by the
 * shell's look at it, a pull-down's ask or another request, not by a load
 * of Home's own: the same rows, under the banner ([HomeUiState.Ready.offline]);
 * a Continue watching that couldn't load keeps its line, without the reason
 * the banner now says.
 */
internal fun homeServerGone(s: HomeUiState.Ready): HomeUiState.Ready = s.copy(
    offline = true,
    continueWatchingFailed = s.continueWatchingFailed?.let { f -> if (f.title == CONTINUE_WATCHING_COULDNT_LOAD) f.copy(sentence = null) else f },
)
