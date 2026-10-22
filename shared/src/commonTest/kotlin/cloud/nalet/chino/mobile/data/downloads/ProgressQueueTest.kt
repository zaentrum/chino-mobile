package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.ProgressBody
import cloud.nalet.chino.mobile.data.api.forgetBearer
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A store that keeps the book as the file would: written whole, as JSON,
 *  and read back from that - what survives the app being closed. */
private class JsonBookStore : ProgressBookStore {
    var text: String? = null
    var writes = 0
    override suspend fun read(): ProgressBook = text?.let { Json.decodeFromString(ProgressBook.serializer(), it) } ?: ProgressBook()
    override suspend fun write(book: ProgressBook) {
        writes++
        text = Json.encodeToString(ProgressBook.serializer(), book)
    }
}

private const val SERVER = "https://media.example.com/api"

private val lenient = Json { ignoreUnknownKeys = true }

private fun played(item: String, sec: Int, at: Long, account: String = "anna", server: String = SERVER) =
    PlayedPosition(server = server, accountId = account, itemId = item, positionSec = sec, durationSec = 5_400, atMs = at)

class ProgressQueueTest {
    @Test
    fun queuedPositionsSurviveARestart() = runTest {
        val store = JsonBookStore()
        ProgressQueue(store).enqueue(played("film", 600, at = 1))
        // A new process: a new queue over the same file.
        val again = ProgressQueue(store)
        assertEquals(listOf(600), again.pending(SERVER, "anna").map { it.positionSec })
    }

    @Test
    fun aLaterPositionOfATitleTakesTheEarliersPlace() = runTest {
        val q = ProgressQueue(JsonBookStore())
        q.enqueue(played("film", 600, at = 1))
        q.enqueue(played("episode", 30, at = 2))
        q.enqueue(played("film", 900, at = 3))
        val pending = q.pending(SERVER, "anna")
        // One per title, in the order they were last played.
        assertEquals(listOf("episode" to 30, "film" to 900), pending.map { it.itemId to it.positionSec })
    }

    @Test
    fun aFlushSendsInTheOrderPlayedAndEmptiesTheQueue() = runTest {
        val q = ProgressQueue(JsonBookStore())
        q.enqueue(played("b", 20, at = 2))
        q.enqueue(played("a", 10, at = 1))
        q.enqueue(played("c", 30, at = 3))
        val sent = mutableListOf<String>()
        val result = q.flush(SERVER, "anna") { sent += it.itemId; SendOutcome.Sent }
        assertEquals(listOf("a", "b", "c"), sent)
        assertEquals(FlushResult(sent = 3, dropped = 0, left = 0), result)
        assertTrue(q.pending(SERVER, "anna").isEmpty())
    }

    @Test
    fun whenTheServerCanTakeNoneTheFlushStopsAndKeepsTheRestUncounted() = runTest {
        val q = ProgressQueue(JsonBookStore())
        q.enqueue(played("a", 10, at = 1))
        q.enqueue(played("b", 20, at = 2))
        q.enqueue(played("c", 30, at = 3))
        val tried = mutableListOf<String>()
        val first = q.flush(SERVER, "anna") { p ->
            tried += p.itemId
            if (p.itemId == "b") SendOutcome.Wait else SendOutcome.Sent
        }
        assertEquals(listOf("a", "b"), tried)
        assertEquals(FlushResult(sent = 1, dropped = 0, left = 2), first)
        assertEquals(listOf("b" to 0, "c" to 0), q.pending(SERVER, "anna").map { it.itemId to it.attempts })
        // Once the server takes them, the rest goes out, in order.
        val second = q.flush(SERVER, "anna") { SendOutcome.Sent }
        assertEquals(FlushResult(sent = 2, dropped = 0, left = 0), second)
    }

    @Test
    fun onePositionTheServerKeepsFailingHoldsNoneBackAndIsDroppedInTheEnd() = runTest {
        val q = ProgressQueue(JsonBookStore())
        q.enqueue(played("broken", 10, at = 1))
        q.enqueue(played("fine", 20, at = 2))
        val first = q.flush(SERVER, "anna") { if (it.itemId == "broken") SendOutcome.Retry else SendOutcome.Sent }
        // The one after it went out all the same.
        assertEquals(FlushResult(sent = 1, dropped = 0, left = 1), first)
        assertEquals(listOf("broken" to 1), q.pending(SERVER, "anna").map { it.itemId to it.attempts })
        repeat(MAX_ATTEMPTS - 2) { q.flush(SERVER, "anna") { SendOutcome.Retry } }
        assertEquals(MAX_ATTEMPTS - 1, q.pending(SERVER, "anna").single().attempts)
        // The last attempt: given up.
        assertEquals(FlushResult(sent = 0, dropped = 1, left = 0), q.flush(SERVER, "anna") { SendOutcome.Retry })
    }

    @Test
    fun aPositionTheServerRefusesForGoodIsDropped() = runTest {
        val q = ProgressQueue(JsonBookStore())
        q.enqueue(played("gone", 10, at = 1))
        q.enqueue(played("here", 20, at = 2))
        val result = q.flush(SERVER, "anna") { if (it.itemId == "gone") SendOutcome.Drop else SendOutcome.Sent }
        assertEquals(FlushResult(sent = 1, dropped = 1, left = 0), result)
    }

    @Test
    fun whatAFailedSendMeansForThePosition() {
        fun status(code: Int) = sendOutcomeOf(ApiStatusException(code, "v1/items/x/progress"))
        // Refused for good: gone, not the viewer's, not a position it takes.
        listOf(400, 403, 404, 409, 410, 422).forEach { assertEquals(SendOutcome.Drop, status(it), "HTTP $it") }
        // The server answered for it but could not take it now: counted.
        listOf(500, 501, 408, 429).forEach { assertEquals(SendOutcome.Retry, status(it), "HTTP $it") }
        // The server can take none now: no answer, down behind its proxy, a
        // sign-in it did not accept.
        listOf(401, 502, 503, 504).forEach { assertEquals(SendOutcome.Wait, status(it), "HTTP $it") }
        assertEquals(SendOutcome.Wait, sendOutcomeOf(IOException("no route to host")))
    }

    @Test
    fun aPositionPlayedWhileAFlushIsOnItsWayIsKeptForTheNext() = runTest {
        val q = ProgressQueue(JsonBookStore())
        q.enqueue(played("film", 600, at = 1))
        // The player saves the film again while its older position is on
        // its way - the queue takes it at once, it is not locked meanwhile.
        val result = q.flush(SERVER, "anna") { p ->
            q.enqueue(played("film", 660, at = 2))
            assertEquals(600, p.positionSec)
            SendOutcome.Sent
        }
        assertEquals(FlushResult(sent = 1, dropped = 0, left = 1), result)
        assertEquals(listOf(660), q.pending(SERVER, "anna").map { it.positionSec })
    }

    @Test
    fun theFirstPositionWaitingForAnAccountSaysSo() = runTest {
        val q = ProgressQueue(JsonBookStore())
        assertTrue(q.enqueue(played("film", 600, at = 1)))
        assertFalse(q.enqueue(played("episode", 30, at = 2)))
        // Another account's first, another server's first.
        assertTrue(q.enqueue(played("film", 10, at = 3, account = "ben")))
        assertTrue(q.enqueue(played("film", 10, at = 4, server = "https://other.example.com/api")))
    }

    @Test
    fun anotherAccountsAndAnotherServersPositionsWaitForTheirOwnSignIn() = runTest {
        val q = ProgressQueue(JsonBookStore())
        q.enqueue(played("film", 10, at = 1, account = "anna"))
        q.enqueue(played("film", 20, at = 2, account = "ben"))
        q.enqueue(played("film", 30, at = 3, server = "https://other.example.com/api"))
        val sent = mutableListOf<PlayedPosition>()
        q.flush(SERVER, "anna") { sent += it; SendOutcome.Sent }
        assertEquals(listOf(10), sent.map { it.positionSec })
        assertEquals(listOf(20), q.pending(SERVER, "ben").map { it.positionSec })
        assertEquals(listOf(30), q.pending("https://other.example.com/api", "anna").map { it.positionSec })
    }

    @Test
    fun theLastPositionPlayedIsKeptForADownloadsResume() = runTest {
        val q = ProgressQueue(JsonBookStore())
        q.remember(played("film", 600, at = 1))
        q.remember(played("film", 1_200, at = 2))
        assertEquals(1_200, q.lastPlayed(SERVER, "anna", "film")?.positionSec)
        assertNull(q.lastPlayed(SERVER, "ben", "film"))
    }
}

/** The player's positions: to the server at once, else kept and sent once
 *  it can be reached - with their age, as the account that played them,
 *  positions only; the server's finished rule applies. */
class ProgressSyncTest {
    private var serverUp = false
    private var account: String? = "anna"
    private val posted = mutableListOf<Pair<String, ProgressBody>>()
    private val watchedPosts = mutableListOf<String>()
    /** Each watched mark's body as it went out ("" for none). */
    private val watchedBodies = mutableListOf<String>()
    private val bearers = mutableListOf<String?>()
    private val refused = mutableListOf<String>()
    private var switchToBenAfterFirstPost = false
    private var forgetBearer = false
    private val server = FakeServer(accessToken = { account?.let { "token-of-$it" } }) { request ->
        if (!serverUp) throw IOException("no connection")
        bearers += request.headers[HttpHeaders.Authorization]
        val path = request.url.encodedPath
        if (path.endsWith("/watched")) {
            watchedPosts += path.substringAfter("/v1/me/items/").substringBefore("/watched")
            watchedBodies += (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            return@FakeServer respondJson("{}")
        }
        val id = path.substringAfter("/v1/items/").substringBefore("/progress")
        if (id == "gone") return@FakeServer respondText("not found", HttpStatusCode.NotFound)
        val body = (request.body as io.ktor.http.content.TextContent).text
        posted += id to lenient.decodeFromString(ProgressBody.serializer(), body)
        // Another account picked while the flush is on its way - and the
        // client's own bearer forgotten, as the app does on a switch.
        if (switchToBenAfterFirstPost && posted.size == 1) {
            account = "ben"
            forgetBearer = true
        }
        respondJson("{}")
    }
    private var clock = 1_000L
    private var queuedSignals = 0
    private val reach = mutableListOf<Boolean>()
    private var onPhone: (String) -> Boolean = { true }

    private fun TestScope.sync(store: ProgressBookStore) = ProgressSync(
        queue = ProgressQueue(store),
        post = { id, body, bearer ->
            if (forgetBearer) {
                server.http.forgetBearer()
                forgetBearer = false
            }
            server.api.postProgress(id, body, bearer)
        },
        account = { account },
        server = { SERVER },
        scope = this,
        postWatched = { id, bearer, playedMsAgo -> server.api.postWatched(id, bearer, playedMsAgo) },
        // The bearer of the account asked about, while it is signed in.
        bearerOf = { id -> "token-of-$id".takeIf { account == id } },
        onPhone = { _, itemId -> onPhone(itemId) },
        onRefused = { _, itemId -> refused += itemId },
        onQueued = { queuedSignals++ },
        onReach = { reach += it },
        now = { clock },
    )

    @Test
    fun withoutTheServerAPositionIsKeptAndSentOnceItAnswersWithItsAge() = runTest {
        val store = JsonBookStore()
        val s = sync(store)
        clock = 10_000
        assertEquals(SendOutcome.Wait, s.saveNow("film", 600, 5_400))
        clock = 20_000
        assertEquals(SendOutcome.Wait, s.saveNow("film", 900, 5_400))
        clock = 30_000
        assertEquals(SendOutcome.Wait, s.saveNow("episode", 120, 1_800))
        // The platform is asked once, as the first position waits.
        assertEquals(1, queuedSignals)
        assertEquals(listOf(false, false, false), reach)
        assertTrue(s.hasUnsent("film"))
        assertTrue(s.hasPending())
        // The app closed and opened again; the server answers now, 65 s on.
        serverUp = true
        clock = 85_000
        val result = sync(store).flush()
        assertEquals(FlushResult(sent = 2, dropped = 0, left = 0), result)
        // In the order played; the film's last position only; each with how
        // long ago it was played; no dismiss.
        assertEquals(listOf("film" to 900, "episode" to 120), posted.map { it.first to it.second.positionSec })
        assertEquals(listOf(65_000L, 55_000L), posted.map { it.second.playedMsAgo })
        assertFalse(posted.any { it.second.dismiss })
        assertFalse(sync(store).hasUnsent("film"))
        assertFalse(sync(store).hasPending())
    }

    @Test
    fun aPositionPlayedNowGoesWithoutAnAge() = runTest {
        serverUp = true
        sync(JsonBookStore()).saveNow("film", 700, 5_400)
        assertEquals(listOf<Long?>(null), posted.map { it.second.playedMsAgo })
    }

    @Test
    fun aFlushSendsAsTheAccountThatPlayedEvenWhenAnotherIsPickedMeanwhile() = runTest {
        val s = sync(JsonBookStore())
        s.saveNow("a", 10, 100)
        s.saveNow("b", 20, 100)
        serverUp = true
        bearers.clear()
        // Ben is picked as the first of anna's positions goes out: the rest
        // goes as anna all the same, with her own bearer.
        switchToBenAfterFirstPost = true
        assertEquals(2, s.flush()?.sent)
        assertEquals("ben", account)
        assertEquals(listOf<String?>("Bearer token-of-anna", "Bearer token-of-anna"), bearers)
    }

    @Test
    fun anotherAccountsQueueWaitsForItsOwnSignIn() = runTest {
        val store = JsonBookStore()
        val s = sync(store)
        s.saveNow("d", 40, 100)
        account = "ben"
        serverUp = true
        bearers.clear()
        assertEquals(FlushResult(0, 0, 0), s.flush())
        assertTrue(bearers.isEmpty())
        assertEquals(listOf(40), ProgressQueue(store).pending(SERVER, "anna").map { it.positionSec })
    }

    @Test
    fun aFlushIsNoneWithoutTheAccountsBearer() = runTest {
        val store = JsonBookStore()
        sync(store).saveNow("a", 10, 100)
        serverUp = true
        val s = ProgressSync(
            queue = ProgressQueue(store),
            post = { id, body, bearer -> server.api.postProgress(id, body, bearer) },
            account = { "anna" },
            server = { SERVER },
            scope = this,
            bearerOf = { null },
        )
        assertNull(s.flush())
        assertEquals(1, ProgressQueue(store).pending(SERVER, "anna").size)
    }

    @Test
    fun aWatchedMarkWithoutTheServerIsKeptAndSentAfterThePositionsWithItsAge() = runTest {
        val store = JsonBookStore()
        val s = sync(store)
        clock = 10_000
        s.saveNow("film", 5_000, 5_400)
        assertEquals(SendOutcome.Wait, s.markWatchedNow("film"))
        assertEquals(1, queuedSignals)
        assertTrue(s.hasPending())
        // The server answers 50 s later: the mark goes with how long ago the
        // film was finished, as its position does.
        serverUp = true
        clock = 60_000
        assertEquals(FlushResult(sent = 2, dropped = 0, left = 0), sync(store).flush())
        assertEquals(listOf("film"), posted.map { it.first })
        assertEquals(listOf("film"), watchedPosts)
        assertEquals(listOf("""{"played_ms_ago":50000}"""), watchedBodies)
    }

    @Test
    fun aWatchedMarkSentAtOnceHasNoAge() = runTest {
        serverUp = true
        assertEquals(SendOutcome.Sent, sync(JsonBookStore()).markWatchedNow("film"))
        assertEquals(listOf("film"), watchedPosts)
        assertEquals(listOf(""), watchedBodies)
    }

    @Test
    fun withTheServerAPositionGoesAtOnceAndTakesAnOlderQueuedOnesPlace() = runTest {
        val store = JsonBookStore()
        val s = sync(store)
        s.saveNow("film", 600, 5_400)
        serverUp = true
        assertEquals(SendOutcome.Sent, s.saveNow("film", 700, 5_400))
        assertEquals(listOf("film" to 700), posted.map { it.first to it.second.positionSec })
        // Nothing older left to send over it.
        assertEquals(FlushResult(0, 0, 0), s.flush())
        assertEquals(700, s.lastPlayedSec("film"))
    }

    @Test
    fun aTitleStreamedWritesNothingOnThePhone() = runTest {
        serverUp = true
        onPhone = { false }
        val store = JsonBookStore()
        val s = sync(store)
        repeat(3) { s.saveNow("streamed", 10 * it, 600) }
        // Sent each time, and nothing written: no last position for a title
        // that is not on the phone, no queue to change.
        assertEquals(3, posted.size)
        assertEquals(0, store.writes)
        assertNull(s.lastPlayedSec("streamed"))
    }

    @Test
    fun onlyTheTitlesOnThePhoneKeepTheirLastPosition() = runTest {
        val store = JsonBookStore()
        val s = sync(store)
        serverUp = true
        s.saveNow("film", 600, 5_400)
        s.saveNow("other", 60, 5_400)
        onPhone = { it == "film" }
        s.forgetLastOfTitlesNotOnThePhone()
        assertEquals(600, s.lastPlayedSec("film"))
        assertNull(s.lastPlayedSec("other"))
    }

    @Test
    fun aTitleTheLibraryNoLongerHasIsNotKeptForLaterAndSaysSo() = runTest {
        serverUp = true
        val s = sync(JsonBookStore())
        assertEquals(SendOutcome.Drop, s.saveNow("gone", 60, 600))
        assertEquals(0, queuedSignals)
        assertEquals(listOf(true), reach)
        assertEquals(listOf("gone"), refused)
    }

    @Test
    fun nobodySignedInSavesNothing() = runTest {
        account = null
        val s = sync(JsonBookStore())
        assertNull(s.saveNow("film", 60, 600))
        assertNull(s.flush())
        assertFalse(s.hasPending())
    }
}
