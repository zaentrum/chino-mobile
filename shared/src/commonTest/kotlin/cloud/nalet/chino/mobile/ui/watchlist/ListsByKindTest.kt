package cloud.nalet.chino.mobile.ui.watchlist

import cloud.nalet.chino.mobile.data.KindSplit
import cloud.nalet.chino.mobile.data.WatchlistsRepository
import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import cloud.nalet.chino.mobile.data.model.Item
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Movies and shows apart (section 8.8.1 of the specification), against a fake
 * chino-api with the app's own client: each list's two rows asked one after
 * the other, each drawn as its answer came, with the bearer of the account
 * the setting is of - the shared client's bearer is another one's
 * throughout. The titles are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListsByKindTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun mainIsTheTests() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun mainAgain() = Dispatchers.resetMain()

    private val titles = mapOf(
        "m1" to """{"id":"m1","title":"Lantern Field","type":"movie"}""",
        "m2" to """{"id":"m2","title":"Glass Meadow","type":"movie"}""",
        "s1" to """{"id":"s1","title":"Orchard Hours","type":"series"}""",
        "e1" to """{"id":"e1","title":"The Kettle Bay Pilot","type":"episode","parent_id":"s1","season_number":1,"episode_number":1}""",
        "x1" to """{"id":"x1","title":"Tin Harbour","type":"movie"}""",
    )

    /** chino-api's lists: [lists] the overview, [whole] each list's ids
     *  asked without a kind, [byKind] its ids by kind - or [answer] in
     *  their place where it has one for a list and a kind. */
    private class Lists {
        var lists = """{"lists":[{"id":"w","name":"Watchlist","itemCount":3,"isDefault":true},{"id":"sn","name":"Sunday nights","itemCount":0}]}"""
        val whole = mutableMapOf("w" to listOf("m1", "s1", "e1"), "sn" to emptyList<String>())
        val byKind = mutableMapOf(
            "w" to mapOf("movie" to listOf("m1"), "series" to listOf("s1", "e1")),
            "sn" to mapOf("movie" to emptyList(), "series" to emptyList()),
        )
        val answer = mutableMapOf<Pair<String, String>, suspend MockRequestHandleScope.() -> HttpResponseData>()
    }

    private fun ids(ids: List<String>, listId: String) =
        """{"id":"$listId","name":"$listId","items":[${ids.joinToString(",") { "\"$it\"" }}]}"""

    private fun server(lists: Lists) = FakeServer(accessToken = { "tok-shared" }, dispatcher = dispatcher) { request -> route(lists, request) }

    private suspend fun MockRequestHandleScope.route(lists: Lists, request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val kind = request.url.parameters["kind"]
        return when {
            path == "/api/v1/me/watchlists" -> respondJson(lists.lists)
            path.startsWith("/api/v1/me/watchlists/") -> {
                val id = path.removePrefix("/api/v1/me/watchlists/")
                if (kind == null) {
                    respondJson(ids(lists.whole[id].orEmpty(), id))
                } else {
                    lists.answer[id to kind]?.invoke(this) ?: respondJson(ids(lists.byKind[id]?.get(kind).orEmpty(), id))
                }
            }
            path.startsWith("/api/v1/items/") -> titles[path.removePrefix("/api/v1/items/")]?.let { respondJson(it) }
                ?: respondText("not found", HttpStatusCode.NotFound)
            else -> respondText("not found", HttpStatusCode.NotFound)
        }
    }

    private val FakeServer.byKindAsks get() = requests.filter { it.url.parameters["kind"] != null }

    /** Anna's split, while [active] says she is signed in. */
    private class Anna {
        var active = true
        val split = KindSplit("anna", bearer = { "tok-anna".takeIf { active } }, isActive = { active })
    }

    /** The watchlist, drawn with [held] - for whom every list is split as
     *  far as is known without asking - and [ask], what asking beside the
     *  lists answers: by default the same. */
    private fun TestScope.model(server: FakeServer, held: () -> KindSplit?, ask: suspend (Boolean) -> KindSplit? = { held() }) =
        WatchlistScreenModel(WatchlistsRepository(server.api, backgroundScope), server.api, ask, held)

    private fun KindRow.titles(): List<String> = (this as KindRow.Loaded).items.map { it.title }

    @Test
    fun aListsTwoRowsAreAskedOneAfterTheOtherWithTheAccountsBearerAndDrawnFromTheirAnswers() = runTest(dispatcher) {
        val server = server(Lists())

        val rows = server.api.kindRows("w", HUB_SHELF_CAP, "tok-anna")

        assertEquals(1, (rows.movies as KindRow.Loaded).count)
        assertEquals(listOf("Lantern Field"), rows.movies.titles())
        assertEquals(listOf("Orchard Hours", "The Kettle Bay Pilot"), rows.shows.titles())
        assertEquals(2, (rows.shows as KindRow.Loaded).count)
        assertEquals(
            listOf("movie", "series"),
            server.byKindAsks.map { it.url.parameters["kind"] },
        )
        assertEquals("/api/v1/me/watchlists/w", server.byKindAsks.first().url.encodedPath)
        // Every request - the rows and their titles - with the account's own.
        assertEquals(setOf("Bearer tok-anna"), server.requests.map { it.headers[HttpHeaders.Authorization] }.toSet())
    }

    @Test
    fun aTitleBothAnswersHoldIsInBothRowsAndOneNeitherHoldsInNone() = runTest(dispatcher) {
        // The catalog said a title's kind between the two answers: m2 in
        // both, x1 - in the list - in neither. Drawn as they came.
        val lists = Lists().apply {
            whole["w"] = listOf("m1", "m2", "x1", "s1")
            byKind["w"] = mapOf("movie" to listOf("m1", "m2"), "series" to listOf("m2", "s1"))
        }
        val rows = server(lists).api.kindRows("w", HUB_SHELF_CAP, "tok-anna")

        assertEquals(listOf("Lantern Field", "Glass Meadow"), rows.movies.titles())
        assertEquals(listOf("Glass Meadow", "Orchard Hours"), rows.shows.titles())
        assertFalse(rows.movies.titles().contains("Tin Harbour") || rows.shows.titles().contains("Tin Harbour"))
        assertEquals(2, (rows.movies as KindRow.Loaded).count)
        assertEquals(2, (rows.shows as KindRow.Loaded).count)
    }

    @Test
    fun aRowWhoseAnswerFailedFailsAloneAndTheOtherIsDrawn() = runTest(dispatcher) {
        val lists = Lists().apply { answer["w" to "series"] = { respondText("catalog unavailable", HttpStatusCode.BadGateway) } }

        val rows = server(lists).api.kindRows("w", HUB_SHELF_CAP, "tok-anna")

        assertEquals(listOf("Lantern Field"), rows.movies.titles())
        assertEquals(KindRow.Failed(LoadFailure.ServerProblem), rows.shows)
        assertEquals(listOf(TitleKind.Movies, TitleKind.Shows), rows.drawn)
        assertFalse(rows.empty)
    }

    @Test
    fun aRowCountsItsWholeAnswerAndShowsAsManyCardsAsARowDoesToday() = runTest(dispatcher) {
        val many = (1..15).map { "m$it" }
        val lists = Lists().apply { byKind["w"] = mapOf("movie" to many, "series" to emptyList()) }

        val row = server(lists).api.kindRow("w", TitleKind.Movies, HUB_SHELF_CAP, "tok-anna")

        assertEquals(15, (row as KindRow.Loaded).count)
        // m1 and m2 are titles here; the rest can't be had and drop, as in
        // the rows without the setting - but no more than 12 are asked for.
        assertEquals(listOf("Lantern Field", "Glass Meadow"), row.items.map { it.title })
    }

    @Test
    fun aRowWithNothingInItIsLeftOutAndAListWithNothingShowsItsEmptyLine() {
        val movie = KindRow.Loaded(1, listOf(Item(id = "m1", title = "Lantern Field", kind = "movie")))
        val none = KindRow.Loaded(0, emptyList())
        val failed = KindRow.Failed(LoadFailure.NoAnswer)

        assertEquals(listOf(TitleKind.Movies), KindRows(movie, none).drawn)
        assertEquals(listOf(TitleKind.Shows), KindRows(none, failed).drawn)
        assertEquals(listOf(TitleKind.Shows), KindRows(none, KindRow.Loading).drawn)
        assertFalse(KindRows(none, failed).empty)
        assertTrue(KindRows(none, none).empty)
        assertTrue(KindRows.Empty.empty)
        assertEquals("movies · 4", kindName(TitleKind.Movies, 4))
        // A row that couldn't load keeps its name, without a count.
        assertEquals("shows", kindName(TitleKind.Shows, null))
    }

    @Test
    fun splitTheHubIsEachListsTwoRowsWithTheirOwnCounts() = runTest(dispatcher) {
        val server = server(Lists())
        val anna = Anna()
        val fresh = mutableListOf<Boolean>()
        val model = model(server, held = { anna.split }) { fresh += it; anna.split }
        advanceUntilIdle()

        val hub = assertNotNull(model.byKind.value)
        assertEquals("anna", hub.split.accountId)
        assertEquals(listOf("Lantern Field"), hub.rows.getValue("w").movies.titles())
        assertEquals(listOf("Orchard Hours", "The Kettle Bay Pilot"), hub.rows.getValue("w").shows.titles())
        assertTrue(hub.rows.getValue("sn").empty)
        // The heading's count is the list's own, from the overview.
        assertEquals(3, model.lists.value.first().itemCount)
        // No list asked whole; the setting read again as the watchlist opens.
        assertTrue(server.requests.none { it.url.encodedPath.startsWith("/api/v1/me/watchlists/") && it.url.parameters["kind"] == null })
        assertEquals(listOf(true), fresh)
        assertNull(model.failure.value)
    }

    @Test
    fun tryAgainAsksForThatOneRowAlone() = runTest(dispatcher) {
        val lists = Lists().apply { answer["w" to "series"] = { respondText("catalog unavailable", HttpStatusCode.BadGateway) } }
        val server = server(lists)
        val anna = Anna()
        val model = model(server, held = { anna.split })
        advanceUntilIdle()
        assertIs<KindRow.Failed>(model.byKind.value!!.rows.getValue("w").shows)
        val moviesBefore = model.byKind.value!!.rows.getValue("w").movies
        val asked = server.requests.size

        lists.answer.clear()
        model.retryRow("w", TitleKind.Shows)
        assertEquals(KindRow.Loading, model.byKind.value!!.rows.getValue("w").shows)
        advanceUntilIdle()

        val again = server.requests.drop(asked)
        assertEquals(listOf("series"), again.mapNotNull { it.url.parameters["kind"] })
        assertTrue(again.none { it.url.encodedPath == "/api/v1/me/watchlists" })
        assertEquals("Bearer tok-anna", again.first().headers[HttpHeaders.Authorization])
        assertEquals(listOf("Orchard Hours", "The Kettle Bay Pilot"), model.byKind.value!!.rows.getValue("w").shows.titles())
        assertEquals(moviesBefore, model.byKind.value!!.rows.getValue("w").movies)
    }

    @Test
    fun anOpenedListIsSplitTheSameWayEachPartComplete() = runTest(dispatcher) {
        val many = (1..15).map { "m$it" }
        val lists = Lists().apply { byKind["w"] = mapOf("movie" to many, "series" to listOf("s1", "m2")) }
        val server = server(lists)
        val anna = Anna()
        val model = model(server, held = { anna.split })
        advanceUntilIdle()
        val asked = server.requests.size

        model.openList("w")
        advanceUntilIdle()

        val parts = assertNotNull(model.openKinds.value)
        assertEquals(15, (parts.movies as KindRow.Loaded).count)
        assertEquals(listOf("Lantern Field", "Glass Meadow"), parts.movies.titles())
        // m2 in both answers: in both parts.
        assertEquals(listOf("Orchard Hours", "Glass Meadow"), parts.shows.titles())
        // Complete: every id of the answer asked for, not the first 12.
        val itemAsks = server.requests.drop(asked).filter { it.url.encodedPath.startsWith("/api/v1/items/") }
        assertEquals(17, itemAsks.size)
        assertEquals(listOf("movie", "series"), server.requests.drop(asked).mapNotNull { it.url.parameters["kind"] })

        model.closeList()
        assertNull(model.openKinds.value)
    }

    @Test
    fun withTheSettingOffEveryListIsOneRowAsToday() = runTest(dispatcher) {
        val server = server(Lists())
        val model = model(server, held = { null })
        advanceUntilIdle()

        assertNull(model.byKind.value)
        assertTrue(server.byKindAsks.isEmpty())
        assertEquals(listOf("Lantern Field", "Orchard Hours", "The Kettle Bay Pilot"), model.shelves.value.getValue("w").map { it.title })
        assertEquals(emptyList(), model.shelves.value.getValue("sn"))

        model.openList("w")
        advanceUntilIdle()
        assertNull(model.openKinds.value)
        assertEquals(3, model.openItems.value.size)
        assertTrue(server.byKindAsks.isEmpty())
    }

    @Test
    fun rowsAnsweredAfterAnotherAccountWasChosenAreNotDrawn() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        val lists = Lists().apply {
            answer["w" to "series"] = {
                held.await()
                respondJson(ids(listOf("s1"), "w"))
            }
        }
        val anna = Anna()
        val model = model(server(lists), held = { anna.split })
        advanceUntilIdle()

        anna.active = false
        held.complete(Unit)
        advanceUntilIdle()

        assertNull(model.byKind.value)
    }

    @Test
    fun aListCreatedWhileSplitShowsItsEmptyLine() = runTest(dispatcher) {
        val server = FakeServer(accessToken = { "tok-shared" }, dispatcher = dispatcher) { request ->
            if (request.method == io.ktor.http.HttpMethod.Post) {
                respondJson("""{"id":"new","name":"For the kids","itemCount":0,"createdAt":"2026-11-17T10:00:00Z"}""", HttpStatusCode.Created)
            } else {
                route(Lists(), request)
            }
        }
        val anna = Anna()
        val model = model(server, held = { anna.split })
        advanceUntilIdle()

        model.createList("For the kids")
        advanceUntilIdle()
        assertTrue(model.byKind.value!!.rows.getValue("new").empty)
    }

    @Test
    fun aRowWithNoCardToDrawIsLeftOutAndAListWithNoneShowsItsEmptyLine() = runTest(dispatcher) {
        // Ids the catalog has no title of: counted, but nothing to draw.
        val lists = Lists().apply {
            byKind["w"] = mapOf("movie" to listOf("m1", "gone1"), "series" to listOf("gone2"))
            byKind["sn"] = mapOf("movie" to listOf("gone3"), "series" to emptyList())
        }
        val rows = server(lists).api.kindRows("w", HUB_SHELF_CAP, "tok-anna")
        // The movies row keeps its answer's count over the card it can draw.
        assertEquals(2, (rows.movies as KindRow.Loaded).count)
        assertEquals(listOf("Lantern Field"), rows.movies.titles())
        assertEquals(listOf(TitleKind.Movies), rows.drawn)
        assertFalse(rows.empty)
        val none = server(lists).api.kindRows("sn", HUB_SHELF_CAP, "tok-anna")
        assertEquals(1, (none.movies as KindRow.Loaded).count)
        assertEquals(emptyList(), none.drawn)
        assertTrue(none.empty)
    }

    @Test
    fun theListsAreAskedBesideTheSettingDrawnWithTheChoiceHeldAndAgainOnceItAnswersOtherwise() = runTest(dispatcher) {
        val server = server(Lists())
        val anna = Anna()
        // Held off: nothing known on the phone. The read answers on, later.
        var held: KindSplit? = null
        val answer = CompletableDeferred<Unit>()
        val model = model(server, held = { held }) {
            answer.await()
            held = anna.split
            anna.split
        }
        advanceUntilIdle()

        // Drawn whole while the setting is read: the lists didn't wait.
        assertNull(model.byKind.value)
        assertEquals(listOf("Lantern Field", "Orchard Hours", "The Kettle Bay Pilot"), model.shelves.value.getValue("w").map { it.title })
        assertTrue(server.byKindAsks.isEmpty())

        answer.complete(Unit)
        advanceUntilIdle()
        val hub = assertNotNull(model.byKind.value)
        assertEquals(listOf("Lantern Field"), hub.rows.getValue("w").movies.titles())
        assertEquals(listOf("movie", "series"), server.byKindAsks.filter { it.url.encodedPath.endsWith("/w") }.map { it.url.parameters["kind"] })
    }

    private val FakeServer.overviewAsks get() = requests.count { it.url.encodedPath == "/api/v1/me/watchlists" }

    private fun FakeServer.rowAsks(listId: String) =
        requests.filter { it.url.encodedPath == "/api/v1/me/watchlists/$listId" }.map { it.url.parameters["kind"] }

    @Test
    fun anAnswerThatDiffersAfterTheFirstDrawReloadsTheHubOnce() = runTest(dispatcher) {
        val server = server(Lists())
        val anna = Anna()
        var held: KindSplit? = null
        val answer = CompletableDeferred<Unit>()
        val model = model(server, held = { held }) {
            answer.await()
            held = anna.split
            anna.split
        }
        advanceUntilIdle()
        assertEquals(1, server.overviewAsks)
        assertEquals(listOf(null), server.rowAsks("w"))

        answer.complete(Unit)
        advanceUntilIdle()

        // One load more: the lists once, each list's rows once.
        assertNotNull(model.byKind.value)
        assertEquals(2, server.overviewAsks)
        assertEquals(listOf(null, "movie", "series"), server.rowAsks("w"))
    }

    @Test
    fun anAnswerThatDiffersWhileTheRowsAreAskedForReloadsTheHubOnce() = runTest(dispatcher) {
        val rowsHeld = CompletableDeferred<Unit>()
        val lists = Lists()
        var hold = true
        val server = FakeServer(accessToken = { "tok-shared" }, dispatcher = dispatcher) { request ->
            if (hold && request.url.encodedPath == "/api/v1/me/watchlists/w") rowsHeld.await()
            route(lists, request)
        }
        val anna = Anna()
        var held: KindSplit? = null
        val answer = CompletableDeferred<Unit>()
        val model = model(server, held = { held }) {
            answer.await()
            held = anna.split
            anna.split
        }
        advanceUntilIdle()

        // The setting answers while the list's row is still out.
        answer.complete(Unit)
        advanceUntilIdle()
        hold = false
        rowsHeld.complete(Unit)
        advanceUntilIdle()

        assertNotNull(model.byKind.value)
        assertEquals(2, server.overviewAsks)
        assertEquals(listOf(null, "movie", "series"), server.rowAsks("w"))
    }

    @Test
    fun aSettingAnsweredBeforeTheListsIsDrawnWithThemAtOnceThoughAskedBesideThem() = runTest(dispatcher) {
        val listsHeld = CompletableDeferred<Unit>()
        val lists = Lists()
        val server = FakeServer(accessToken = { "tok-shared" }, dispatcher = dispatcher) { request ->
            if (request.url.encodedPath == "/api/v1/me/watchlists") listsHeld.await()
            route(lists, request)
        }
        val anna = Anna()
        var held: KindSplit? = null
        var asked = false
        val model = model(server, held = { held }) {
            asked = true
            held = anna.split
            anna.split
        }
        advanceUntilIdle()

        // The setting answered while the lists are still out: both were asked.
        assertTrue(asked)
        assertTrue(server.requests.any { it.url.encodedPath == "/api/v1/me/watchlists" })
        listsHeld.complete(Unit)
        advanceUntilIdle()
        // Drawn by kind at once, never whole first.
        assertNotNull(model.byKind.value)
        assertTrue(server.requests.none { it.url.encodedPath.startsWith("/api/v1/me/watchlists/") && it.url.parameters["kind"] == null })
    }

    @Test
    fun aListOpenedBeforeAndAnsweredOnceAnotherIsOpenIsNotDrawnUnderThatOne() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        val lists = Lists().apply {
            byKind["sn"] = mapOf("movie" to listOf("x1"), "series" to emptyList())
        }
        var holdW = false
        val server = FakeServer(accessToken = { "tok-shared" }, dispatcher = dispatcher) { request ->
            if (holdW && request.url.encodedPath == "/api/v1/me/watchlists/w") held.await()
            route(lists, request)
        }
        val anna = Anna()
        val model = model(server, held = { anna.split })
        advanceUntilIdle()

        holdW = true
        model.openList("w")
        advanceUntilIdle()
        model.closeList()
        holdW = false
        model.openList("sn")
        advanceUntilIdle()
        assertEquals(listOf("Tin Harbour"), assertNotNull(model.openKinds.value).movies.titles())

        held.complete(Unit)
        advanceUntilIdle()
        assertEquals("sn", model.openListId.value)
        assertEquals(listOf("Tin Harbour"), assertNotNull(model.openKinds.value).movies.titles())
        assertFalse(model.loadingOpenItems.value)
    }

    @Test
    fun aWholeListOpenedBeforeIsNotDrawnUnderAnotherEither() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        val lists = Lists().apply { whole["sn"] = listOf("x1") }
        var holdW = false
        val server = FakeServer(accessToken = { "tok-shared" }, dispatcher = dispatcher) { request ->
            if (holdW && request.url.encodedPath == "/api/v1/me/watchlists/w") held.await()
            route(lists, request)
        }
        val model = model(server, held = { null })
        advanceUntilIdle()

        holdW = true
        model.openList("w")
        advanceUntilIdle()
        model.closeList()
        holdW = false
        model.openList("sn")
        advanceUntilIdle()
        held.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Tin Harbour"), model.openItems.value.map { it.title })
    }

    @Test
    fun aRenewalThatGotNoAnswerFailsTheRowsOnTheHubOnTryAgainAndInAnOpenedList() = runTest(dispatcher) {
        val server = server(Lists())
        var fails = true
        val split = KindSplit(
            "anna",
            bearer = { if (fails) throw kotlinx.io.IOException("the sign-in service can't be reached") else "tok-anna" },
            isActive = { true },
        )
        val model = model(server, held = { split })
        advanceUntilIdle()
        val failed = KindRows(KindRow.Failed(LoadFailure.NoAnswer), KindRow.Failed(LoadFailure.NoAnswer))
        assertEquals(failed, model.byKind.value!!.rows.getValue("w"))
        assertTrue(server.byKindAsks.isEmpty())

        // Try again, still no answer: the row couldn't load.
        model.retryRow("w", TitleKind.Shows)
        advanceUntilIdle()
        assertEquals(KindRow.Failed(LoadFailure.NoAnswer), model.byKind.value!!.rows.getValue("w").shows)

        // See all: the opened list's parts, the same.
        model.openList("w")
        advanceUntilIdle()
        assertEquals(failed, model.openKinds.value)

        // Answered at last: the part.
        fails = false
        model.retryRow("w", TitleKind.Shows)
        advanceUntilIdle()
        assertEquals(listOf("Orchard Hours", "The Kettle Bay Pilot"), model.openKinds.value!!.shows.titles())
    }

    @Test
    fun aSignInThatCantBeRenewedFailsTheRowsRatherThanShowingEveryListEmpty() = runTest(dispatcher) {
        val server = server(Lists())
        // No bearer, Anna still signed in: her renewal was refused.
        val split = KindSplit("anna", bearer = { null }, isActive = { true })
        val model = model(server, held = { split })
        advanceUntilIdle()
        val rows = model.byKind.value!!.rows.getValue("w")
        assertEquals(KindRows(KindRow.Failed(LoadFailure.SignedOut), KindRow.Failed(LoadFailure.SignedOut)), rows)
        assertFalse(rows.empty)

        model.retryRow("w", TitleKind.Movies)
        advanceUntilIdle()
        assertEquals(KindRow.Failed(LoadFailure.SignedOut), model.byKind.value!!.rows.getValue("w").movies)

        model.openList("w")
        advanceUntilIdle()
        val parts = assertNotNull(model.openKinds.value)
        assertEquals(KindRow.Failed(LoadFailure.SignedOut), parts.movies)
        assertFalse(parts.empty)
        assertTrue(server.byKindAsks.isEmpty())
    }

    @Test
    fun aRedrawFromSplitToWholeKeepsTheRowsByKindUntilTheWholeRowsAreIn() = runTest(dispatcher) {
        val wholeHeld = CompletableDeferred<Unit>()
        var holdWhole = false
        val lists = Lists()
        val server = FakeServer(accessToken = { "tok-shared" }, dispatcher = dispatcher) { request ->
            val whole = request.url.encodedPath.startsWith("/api/v1/me/watchlists/") && request.url.parameters["kind"] == null
            if (holdWhole && whole) wholeHeld.await()
            route(lists, request)
        }
        val anna = Anna()
        var held: KindSplit? = anna.split
        val answer = CompletableDeferred<Unit>()
        val model = model(server, held = { held }) {
            answer.await()
            held = null
            null
        }
        advanceUntilIdle()
        assertNotNull(model.byKind.value)

        // Turned off on another device: read again, the lists drawn whole.
        holdWhole = true
        answer.complete(Unit)
        advanceUntilIdle()
        // The whole rows still out: the rows by kind stay, no list shows empty.
        assertNotNull(model.byKind.value)

        wholeHeld.complete(Unit)
        advanceUntilIdle()
        assertNull(model.byKind.value)
        assertEquals(listOf("Lantern Field", "Orchard Hours", "The Kettle Bay Pilot"), model.shelves.value.getValue("w").map { it.title })
    }

    @Test
    fun aSilentReloadKeepsARowShownWhoseAnswerNowFails() = runTest(dispatcher) {
        val lists = Lists()
        val server = server(lists)
        val anna = Anna()
        val model = model(server, held = { anna.split })
        advanceUntilIdle()
        val shown = model.byKind.value!!.rows.getValue("w").shows
        assertIs<KindRow.Loaded>(shown)

        // Back from an opened list: loaded again, the shows' answer failing now.
        lists.answer["w" to "series"] = { respondText("catalog unavailable", HttpStatusCode.BadGateway) }
        model.openList("w")
        advanceUntilIdle()
        model.closeList()
        advanceUntilIdle()

        assertEquals(shown, model.byKind.value!!.rows.getValue("w").shows)
    }
}
