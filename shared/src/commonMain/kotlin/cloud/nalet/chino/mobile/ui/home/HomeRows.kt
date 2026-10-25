package cloud.nalet.chino.mobile.ui.home

import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.NO_ANSWER_SENTENCE
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

/** Why Continue watching couldn't load where the phone knows nothing of it:
 *  the reason - none where the banner already says the server can't be
 *  reached - and what tells the cases apart, under details. */
data class ShelfFailure(val sentence: String?, val details: String?)

/** What Home shows of one load ([homeView]). */
internal data class HomeView(
    val continueWatching: List<ContinueWatchingItem>,
    val nextUp: List<ContinueWatchingItem>,
    val recentMovies: List<Item>,
    val recentSeries: List<Item>,
    val topRated: List<Item>,
    /** The server didn't answer: Home shows what the phone knows, under the
     *  banner, and the app hears the server can't be reached. */
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
 * where a row had no answer at all, as the design system's banner says it
 * for a list still on the screen.
 */
internal fun homeView(
    feed: Asked<List<ContinueWatchingItem>>,
    recentMovies: Asked<List<Item>>,
    recentSeries: Asked<List<Item>>,
    topRated: Asked<List<Item>>,
    known: KnownRows?,
    playedHere: Map<String, Int> = emptyMap(),
): HomeView {
    val failures = listOfNotNull(feed.failure, recentMovies.failure, recentSeries.failure, topRated.failure)
    val offline = failures.any { it.loadFailure(singleThing = false) == LoadFailure.NoAnswer }
    val shownFeed = feed.value ?: known?.feed?.map { row -> playedHere[row.id]?.let { row.copy(positionSec = it) } ?: row }
    val failed = feed.failure?.takeIf { shownFeed == null }?.let { e ->
        ShelfFailure(
            sentence = if (offline) null else e.loadFailure(singleThing = false).sentence() ?: NO_ANSWER_SENTENCE,
            details = e.loadDetails(),
        )
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
 * whole page gone. A sign-in the server refuses on the mint (a 401 or 403)
 * is thrown, for Home's own page for it with its ways on.
 */
internal fun homeLoad(
    feed: Asked<List<ContinueWatchingItem>>,
    recentMovies: Asked<List<Item>>,
    recentSeries: Asked<List<Item>>,
    topRated: Asked<List<Item>>,
    token: Asked<String>,
    known: KnownRows?,
    playedHere: Map<String, Int> = emptyMap(),
    lastToken: String? = null,
): HomeLoad {
    token.failure?.takeIf { it.signInRefused }?.let { throw it }
    return HomeLoad(
        view = homeView(feed, recentMovies, recentSeries, topRated, known, playedHere),
        streamToken = token.value ?: lastToken.orEmpty(),
    )
}
