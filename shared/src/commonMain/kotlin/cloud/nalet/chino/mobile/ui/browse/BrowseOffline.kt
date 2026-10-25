package cloud.nalet.chino.mobile.ui.browse

import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.loadDetails
import cloud.nalet.chino.mobile.data.api.loadFailure
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.paging.FirstPages
import cloud.nalet.chino.mobile.data.paging.Paged

/**
 * The grids' lists as the server last gave them - each type's pages for each
 * filter, and the genres - for one server and account, while the app runs:
 * what Movies and Shows show while the server can't be reached, under the
 * banner, rather than "couldn't load" over a list loaded minutes before.
 * The last [MAX_LISTS] lists asked for; another server or account forgets
 * them all, so one account's titles never show for another.
 */
internal object KnownBrowse {
    private var owner: String? = null
    private val lists = LinkedHashMap<Pair<String, BrowseQuery>, Paged<Item>>()
    private var genres: List<String>? = null

    /** How many lists - a type with a filter - are kept. */
    const val MAX_LISTS = 12

    fun list(owner: String, type: String, filter: BrowseQuery): Paged<Item>? =
        if (this.owner == owner) lists[type to filter] else null

    fun putList(owner: String, type: String, filter: BrowseQuery, paged: Paged<Item>) {
        own(owner)
        val key = type to filter
        // The last one asked for goes to the end: the oldest go first.
        lists.remove(key)
        lists[key] = paged
        while (lists.size > MAX_LISTS) lists.remove(lists.keys.first())
    }

    /** The title [id] marked watched ([watchedAt]) or not (null) in every
     *  list kept for [owner] - the server has it so now: a grid shown from
     *  these never brings the old mark back. */
    fun markWatched(owner: String, id: String, watchedAt: String?) {
        if (this.owner != owner) return
        for (entry in lists.entries) {
            if (entry.value.items.any { it.id == id }) {
                entry.setValue(entry.value.map { if (it.id == id) it.copy(watchedAt = watchedAt) else it })
            }
        }
    }

    fun genres(owner: String): List<String>? = genres.takeIf { this.owner == owner }

    fun putGenres(owner: String, genres: List<String>) {
        own(owner)
        this.genres = genres
    }

    /** Forgets everything; for tests. */
    internal fun clear() {
        owner = null
        lists.clear()
        genres = null
    }

    private fun own(owner: String) {
        if (this.owner == owner) return
        this.owner = owner
        lists.clear()
        genres = null
    }
}

/** What a grid shows after one first load ([browseFirstLoad]). */
internal data class FirstLoad(
    val paged: Paged<Item>,
    val laterPageFailed: Boolean = false,
    /** It couldn't load and the phone knows none of it: the design
     *  system's case. */
    val error: LoadFailure? = null,
    val errorDetails: String? = null,
    /** The server can't be reached: the grid shows what the phone knows,
     *  under the banner, and the app hears it. */
    val offline: Boolean = false,
)

/**
 * A grid's first load: the pages as the server answered them, or - where
 * it had no answer and the server, asked now ([reachable]), doesn't answer
 * either - the list the phone knows for that filter ([known]) under the
 * banner, the grid as it was; with none known, "couldn't load" under the
 * banner. A list without an answer from a server that answers is the
 * list's own failure, as before: a slow query is no word on the server.
 */
internal suspend fun browseFirstLoad(
    pages: Result<FirstPages<Item>>,
    known: Paged<Item>?,
    reachable: suspend () -> Boolean,
): FirstLoad {
    pages.onSuccess { first -> return FirstLoad(first.paged, laterPageFailed = first.laterPageFailed) }
    val e = pages.exceptionOrNull() ?: error("a load without its pages or why")
    val failure = e.loadFailure(singleThing = false)
    val offline = failure == LoadFailure.NoAnswer && !reachable()
    return when {
        offline && known != null -> FirstLoad(known, offline = true)
        else -> FirstLoad(Paged(), error = failure, errorDetails = e.loadDetails(), offline = offline)
    }
}

/** Whether a grid's couldn't-load says its reason: the case's own, but not
 *  where the banner above it already says the server can't be reached. */
internal fun browseSaysWhy(failure: LoadFailure, offline: Boolean): Boolean =
    !(offline && failure == LoadFailure.NoAnswer)
