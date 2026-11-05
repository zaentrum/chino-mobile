package cloud.nalet.chino.mobile.ui.watchlist

import cloud.nalet.chino.mobile.data.PreferencesRepository
import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.Preferences
import cloud.nalet.chino.mobile.data.api.Watchlist
import cloud.nalet.chino.mobile.data.api.WatchlistCounts
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.downloads.ServerReach
import cloud.nalet.chino.mobile.data.model.Item
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How a list splits into its movies row and its shows row (docs/chino,
 *  watchlist), and when and how the hub asks the server for them. */
class KindRowsTest {
    private fun item(id: String, kind: String?) = Item(id = id, title = id, kind = kind)
    private fun ids(items: List<Item>) = items.map { it.id }

    @Test
    fun moviesGoInOneRowAndEverythingElseInTheOtherInTheListsOrder() {
        val rows = splitByKind(
            listOf(
                item("s1", "series"), item("m1", "movie"), item("e1", "episode"),
                item("a1", "album"), item("m2", "movie"), item("n1", null),
            ),
        )
        assertEquals(listOf("m1", "m2"), ids(rows.movies))
        assertEquals(listOf("s1", "e1", "a1", "n1"), ids(rows.shows))
    }

    @Test
    fun anEmptyListSplitsIntoNothing() {
        assertTrue(splitByKind(emptyList()).isEmpty())
    }

    @Test
    fun theHeadingsCountIsTheSumOfItsRowsNotTheTitlesSaved() {
        val list = Watchlist(id = "l", name = "L", itemCount = 8, counts = WatchlistCounts(movie = 4, series = 3))
        assertEquals(7, list.headingCount(byKind = true))
        assertEquals(8, list.headingCount(byKind = false))
        assertEquals(8, list.copy(counts = null).headingCount(byKind = true))
    }

    @Test
    fun aRowsCountIsTheServersAndWhatItHoldsWhereTheServerGaveNone() {
        val rows = KindRows(movies = listOf(item("m1", "movie")))
        val list = Watchlist(id = "l", name = "L", counts = WatchlistCounts(movie = 20, series = 3))
        assertEquals(20, list.countOf(KindRow.Movies, rows))
        assertEquals(3, list.countOf(KindRow.Shows, rows))
        assertEquals(1, list.copy(counts = null).countOf(KindRow.Movies, rows))
    }

    @Test
    fun aRowsSectionStartsAfterTheRowBeforeItAndItsHeading() {
        val movies = listOf(item("m1", "movie"), item("m2", "movie"))
        val shows = listOf(item("s1", "series"))
        assertEquals(0, sectionStart(KindRows(movies, shows), KindRow.Movies))
        assertEquals(3, sectionStart(KindRows(movies, shows), KindRow.Shows))
        assertEquals(0, sectionStart(KindRows(emptyList(), shows), KindRow.Shows))
    }

    private fun preferences(byKind: Boolean, onAsked: () -> Unit = {}) = PreferencesRepository(
        load = { onAsked(); Preferences(watchlistByKind = byKind) },
        save = { Preferences(it.watchlistByKind) },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun offering(features: String) = ServerReach(fetch = { """{"product":"chino","features":$features}""" })

    @Test
    fun theHubSplitsOnlyWhereTheServerOffersBothAndTheSettingIsOn() = runTest {
        val both = """{"preferences":true,"watchlist_kinds":true}"""
        assertTrue(watchlistByKindOn(offering(both), preferences(byKind = true)))
        assertFalse(watchlistByKindOn(offering(both), preferences(byKind = false)))
        var asked = 0
        val on = preferences(byKind = true) { asked++ }
        assertFalse(watchlistByKindOn(offering("""{"preferences":true}"""), on))
        assertFalse(watchlistByKindOn(offering("""{"watchlist_kinds":true}"""), on))
        assertFalse(watchlistByKindOn(offering("""{"feedback":true}"""), on))
        // A server that doesn't offer it isn't asked for the setting.
        assertEquals(0, asked)
    }

    @Test
    fun noAnswerFromTheServerDrawsTheHubAsOff() = runTest {
        assertFalse(watchlistByKindOn(ServerReach(fetch = { null }), preferences(byKind = true)))
    }

    @Test
    fun eachRowIsAskedForByKindAndCappedAsAShelfIs() = runTest {
        val server = FakeServer { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/watchlists/l1") -> {
                    val ids = when (request.url.parameters["kind"]) {
                        "movie" -> (1..20).map { "m$it" }
                        "series" -> listOf("s1", "e1")
                        else -> error("asked for the list without a kind")
                    }
                    respondJson("""{"id":"l1","name":"L","items":[${ids.joinToString(",") { "\"$it\"" }}]}""")
                }
                else -> {
                    val id = path.substringAfterLast('/')
                    val type = if (id.startsWith("m")) "movie" else if (id.startsWith("e")) "episode" else "series"
                    respondJson("""{"id":"$id","title":"$id","type":"$type"}""")
                }
            }
        }
        val rows = fetchKindRows(server.api, "l1")
        assertEquals((1..HUB_SHELF_CAP).map { "m$it" }, ids(rows.movies))
        assertEquals(listOf("s1", "e1"), ids(rows.shows))
    }

    @Test
    fun aRowTheServerCantAnswerFailsTheListRatherThanLookingEmpty() = runTest {
        val server = FakeServer { respondJson("{}", HttpStatusCode.BadGateway) }
        assertFailsWith<Exception> { fetchKindRows(server.api, "l1") }
    }
}
