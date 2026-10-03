package cloud.nalet.chino.mobile.data.api

import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.paging.Paged
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The browse grids page /v1/items the way chino-api pages it: `limit` +
 * `offset`, answered with `{ product, items, source }` and nothing to say
 * whether more follow. They used to ask for a `page_token` the API never
 * returns, so every grid stopped after its first 48 titles.
 */
class ListItemsPagingTest {
    /** chino-api over a catalogue of [size] movies: the page at `offset`,
     *  `limit` long (50 when absent), as router.go listItems answers. */
    private fun catalogue(size: Int) = FakeServer { request ->
        val limit = request.url.parameters["limit"]?.toInt() ?: 50
        val offset = request.url.parameters["offset"]?.toInt() ?: 0
        val page = (1..size).drop(offset).take(limit).joinToString(",") { n ->
            """{"id":"m$n","type":"movie","title":"Movie $n","year":2000,"poster_url":"/api/v1/items/m$n/poster"}"""
        }
        respondJson("""{"product":"chino","items":[$page],"source":"katalog"}""")
    }

    private suspend fun browseAll(server: FakeServer, pageSize: Int = 48): Paged<Item> {
        var paged = Paged<Item>()
        while (!paged.endReached) {
            val rows = server.api.listItems(limit = pageSize, offset = paged.nextOffset, type = "movie").items
            paged = paged.append(rows, pageSize, Item::id)
        }
        return paged
    }

    @Test
    fun aGridReadsTheWholeCatalogueNotJustTheFirst48() = runTest {
        val server = catalogue(size = 100)

        val paged = browseAll(server)

        assertEquals((1..100).map { "m$it" }, paged.items.map { it.id })
        // offset 0 is left off, as chino-web leaves it; then 48, 96.
        assertEquals(listOf(null, "48", "96"), server.requests.map { it.url.parameters["offset"] })
        assertTrue(server.requests.all { it.url.parameters["limit"] == "48" })
        assertTrue(server.requests.all { it.url.parameters["type"] == "movie" })
    }

    @Test
    fun anExactMultipleEndsOnTheEmptyPage() = runTest {
        val server = catalogue(size = 96)

        val paged = browseAll(server)

        assertEquals(96, paged.items.size)
        assertEquals(listOf(null, "48", "96"), server.requests.map { it.url.parameters["offset"] })
    }

    @Test
    fun noPageTokenIsAskedFor() = runTest {
        val server = catalogue(size = 10)

        server.api.listItems(limit = 48, offset = 48, type = "series", sort = "newest")

        val request = server.requests.single()
        assertEquals("/api/v1/items", request.url.encodedPath)
        assertNull(request.url.parameters["page_token"])
        assertEquals("48", request.url.parameters["offset"])
        assertEquals("newest", request.url.parameters["sort"])
    }
}
