package cloud.nalet.chino.mobile.data.paging

import kotlin.coroutines.cancellation.CancellationException

/**
 * A list read page by page the way chino-api pages: `GET /v1/items?limit=&offset=`
 * (chino-api router.go `listItems`, katalog-api underneath). The response is
 * `{ items }` with no cursor and no total, so — as chino-web's usePagedItems
 * does — the next request asks for the rows after the ones received so far,
 * and a page shorter than the limit asked for is the last one.
 *
 * [items] hold each key once. When the catalogue changes between two requests
 * a title can arrive on both sides of a page boundary, and a lazy grid keyed
 * by id must never see an id twice. [nextOffset] counts every row the server
 * sent, duplicates included, so the next request starts where the server's
 * last page ended.
 *
 * Not for `unwatched=true` lists: chino-api fills those by skipping watched
 * titles, so the rows it returns are not the rows it consumed and an offset
 * cannot follow them. Only the browse grids page; the Home rails ask once.
 */
data class Paged<T>(
    val items: List<T> = emptyList(),
    /** The `offset` of the next request: rows received so far. */
    val nextOffset: Int = 0,
    /** A page came back shorter than the page size: nothing more to ask for. */
    val endReached: Boolean = false,
) {
    /** This list with [page] — the response to a request for [pageSize] rows
     *  at [nextOffset] — added after it. */
    fun append(page: List<T>, pageSize: Int, key: (T) -> Any): Paged<T> {
        val seen = items.mapTo(HashSet(), key)
        return Paged(
            items = items + page.filter { seen.add(key(it)) },
            nextOffset = nextOffset + page.size,
            endReached = page.size < pageSize,
        )
    }

    /** The same pages with each item replaced by [transform] (an optimistic
     *  watched badge, say); offsets and the end are unchanged. */
    fun map(transform: (T) -> T): Paged<T> = copy(items = items.map(transform))
}

/** The pages [loadFirstPages] got: [laterPageFailed] when one after the
 *  first failed and the run ended there. */
data class FirstPages<T>(val paged: Paged<T>, val laterPageFailed: Boolean)

/**
 * A list's first page and, while fewer than [rows] are in and the list goes
 * on, the pages after it - the rows a grid had loaded before its process was
 * killed, asked for again so it can scroll back to where it was. [fetch]
 * answers the request for [pageSize] rows at an offset. The first page's
 * failure is thrown, as a single page's is; a later one ends the run with the
 * pages before it.
 */
suspend fun <T> loadFirstPages(
    rows: Int,
    pageSize: Int,
    key: (T) -> Any,
    fetch: suspend (offset: Int) -> List<T>,
): FirstPages<T> {
    var paged = Paged<T>().append(fetch(0), pageSize, key)
    while (paged.items.size < rows && !paged.endReached) {
        val page = try {
            fetch(paged.nextOffset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FirstPages(paged, laterPageFailed = true)
        }
        paged = paged.append(page, pageSize, key)
    }
    return FirstPages(paged, laterPageFailed = false)
}
