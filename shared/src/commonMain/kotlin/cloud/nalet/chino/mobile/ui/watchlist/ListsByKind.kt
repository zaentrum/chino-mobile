package cloud.nalet.chino.mobile.ui.watchlist

import cloud.nalet.chino.mobile.data.KindSplit
import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.loadFailure
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/*
 * Movies and shows apart (section 8.8.1 of the specification): with the
 * account's Separate movies and shows on, every list is drawn as two rows,
 * its movies and then its shows, each from its own answer.
 */

/** The two kinds a list is split into, in their order: what chino-api is
 *  asked for ([query]) and the row's name ([label]). Shows are everything
 *  else a list holds: its shows, its episodes, any other kind. */
enum class TitleKind(val query: String, val label: String) {
    Movies("movie", "movies"),
    Shows("series", "shows"),
}

/** One row of a list split by kind, as its one answer came. */
sealed interface KindRow {
    /** The answer: [count], the titles of the kind it holds; [items], the
     *  first of them as titles - [HUB_SHELF_CAP] on the watchlist, all of
     *  them in an opened list. */
    data class Loaded(val count: Int, val items: List<Item>) : KindRow

    /** The answer failed, and why: the row says so on its own. */
    data class Failed(val failure: LoadFailure) : KindRow

    /** Asked again, after try again: no answer yet. */
    data object Loading : KindRow
}

/**
 * A list's two rows, movies then shows, each as its own answer came. What
 * one says is never mended by the other: a title both answers hold is in
 * both rows, a title neither holds is in none ("Row by row", section 8.8.1).
 */
data class KindRows(val movies: KindRow, val shows: KindRow) {
    operator fun get(kind: TitleKind): KindRow = when (kind) {
        TitleKind.Movies -> movies
        TitleKind.Shows -> shows
    }

    fun with(kind: TitleKind, row: KindRow): KindRows = when (kind) {
        TitleKind.Movies -> copy(movies = row)
        TitleKind.Shows -> copy(shows = row)
    }

    /** Both answers came, and neither has a card to draw - nothing in them,
     *  or only titles the catalog has none of: the list's empty line, as
     *  with the setting off. */
    val empty: Boolean
        get() = (movies as? KindRow.Loaded)?.items?.isEmpty() == true &&
            (shows as? KindRow.Loaded)?.items?.isEmpty() == true

    /** The rows drawn, in their order: a row with nothing in it is left
     *  out, and so is one with no card to draw. */
    val drawn: List<TitleKind>
        get() = TitleKind.entries.filter { (this[it] as? KindRow.Loaded)?.items?.isEmpty() != true }

    /** These rows, each that couldn't load keeping [shown]'s where that one
     *  was loaded: a list loaded again keeps what it showed, as a whole row
     *  does. */
    fun keepingShown(shown: KindRows?): KindRows {
        shown ?: return this
        fun kept(row: KindRow, before: KindRow) = if (row is KindRow.Failed && before is KindRow.Loaded) before else row
        return KindRows(kept(movies, shown.movies), kept(shows, shown.shows))
    }

    companion object {
        /** A list just created: nothing in either row. */
        val Empty = KindRows(KindRow.Loaded(0, emptyList()), KindRow.Loaded(0, emptyList()))
    }
}

/** The watchlist split by kind for [split]'s account: each list's two rows,
 *  by list id. */
class KindHub(val split: KindSplit, val rows: Map<String, KindRows>) {
    fun with(listId: String, rows: KindRows): KindHub = KindHub(split, this.rows + (listId to rows))

    fun without(listId: String): KindHub = KindHub(split, rows - listId)
}

/**
 * One row of [listId] by [kind], asked with [bearer]: its count and its first
 * [limit] titles (all of them where null), in the answer's order, or why it
 * failed. Each title is asked for with the same bearer; one that can't be had
 * drops, as in the rows without the setting.
 */
internal suspend fun ChinoApi.kindRow(listId: String, kind: TitleKind, limit: Int?, bearer: String): KindRow {
    val ids = try {
        getWatchlistOfKind(listId, kind.query, bearer).items
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return KindRow.Failed(e.loadFailure(singleThing = false))
    }
    val shown = if (limit != null) ids.take(limit) else ids
    val items = coroutineScope {
        shown.map { id -> async { runCatching { getItem(id, bearer) }.getOrNull() } }.awaitAll().filterNotNull()
    }
    return KindRow.Loaded(count = ids.size, items = items)
}

/** A list's two rows, asked one after the other - movies, then shows - each
 *  kept as its answer came ("Row by row", section 8.8.1). */
internal suspend fun ChinoApi.kindRows(listId: String, limit: Int?, bearer: String): KindRows {
    val movies = kindRow(listId, TitleKind.Movies, limit, bearer)
    val shows = kindRow(listId, TitleKind.Shows, limit, bearer)
    return KindRows(movies, shows)
}
