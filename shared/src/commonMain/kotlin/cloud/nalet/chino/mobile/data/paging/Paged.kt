package cloud.nalet.chino.mobile.data.paging

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
