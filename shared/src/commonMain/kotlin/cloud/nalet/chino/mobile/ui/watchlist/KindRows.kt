package cloud.nalet.chino.mobile.ui.watchlist

import cloud.nalet.chino.mobile.data.PreferencesRepository
import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.Watchlist
import cloud.nalet.chino.mobile.data.downloads.ServerReach
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The two rows a list splits into with "Separate movies and shows" on, movies
 *  first: [word] as the screen says it, [query] as the server asks for it. */
enum class KindRow(val word: String, val query: String) {
    Movies("movies", "movie"),
    Shows("shows", "series"),
}

/** A list's titles by row, each in the list's order. */
data class KindRows(val movies: List<Item> = emptyList(), val shows: List<Item> = emptyList()) {
    operator fun get(row: KindRow): List<Item> = when (row) {
        KindRow.Movies -> movies
        KindRow.Shows -> shows
    }

    fun isEmpty(): Boolean = movies.isEmpty() && shows.isEmpty()
}

/** Movies in one row and everything else in the other: episodes belong with
 *  their show, and a title of any other kind must not disappear. */
fun splitByKind(items: List<Item>): KindRows {
    val (movies, shows) = items.partition { it.kind == KindRow.Movies.query }
    return KindRows(movies, shows)
}

/** The count beside a row's word: the server's, or what the row holds where it
 *  gave none. */
fun Watchlist.countOf(row: KindRow, rows: KindRows): Int = when (row) {
    KindRow.Movies -> counts?.movie
    KindRow.Shows -> counts?.series
} ?: rows[row].size

/** The count beside a list's name. Split, it is the sum of the rows': a saved
 *  title the library no longer holds is in neither, so it can be less than
 *  [Watchlist.itemCount]. */
fun Watchlist.headingCount(byKind: Boolean): Int {
    val counts = counts
    return if (byKind && counts != null) counts.movie + counts.series else itemCount
}

/** Where [row]'s heading is in a whole list's grid: each row that holds
 *  anything is its heading, then its cards. */
fun sectionStart(rows: KindRows, row: KindRow): Int =
    KindRow.entries.takeWhile { it != row }
        .filter { rows[it].isNotEmpty() }
        .sumOf { rows[it].size + 1 }

/** Whether the hub splits its lists: the server keeps the setting and can fill
 *  the rows, and the setting, asked for now, is on. A server that hasn't said
 *  what it offers yet is asked first. */
internal suspend fun watchlistByKindOn(reach: ServerReach, preferences: PreferencesRepository): Boolean {
    if (reach.features.value == null) reach.check()
    if (reach.features.value?.watchlistByKind != true) return false
    preferences.refresh()
    return preferences.preferences.value?.watchlistByKind == true
}

/** A list's two rows, each asked for by kind and capped as a shelf is. A title
 *  that can't be fetched drops; a row the server can't answer fails the whole. */
internal suspend fun fetchKindRows(api: ChinoApi, listId: String): KindRows = coroutineScope {
    suspend fun fetch(row: KindRow): List<Item> = coroutineScope {
        api.getWatchlist(listId, row.query).items.take(HUB_SHELF_CAP).map { id ->
            async { runCatching { api.getItem(id) }.getOrNull() }
        }.awaitAll().filterNotNull()
    }
    val movies = async { fetch(KindRow.Movies) }
    val shows = async { fetch(KindRow.Shows) }
    KindRows(movies.await(), shows.await())
}
