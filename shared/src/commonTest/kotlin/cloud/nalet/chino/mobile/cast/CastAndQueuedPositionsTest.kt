package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import cloud.nalet.chino.mobile.data.downloads.MemoryProgressBookStore
import cloud.nalet.chino.mobile.data.downloads.ProgressQueue
import cloud.nalet.chino.mobile.data.downloads.ProgressSync
import cloud.nalet.chino.mobile.data.downloads.SendOutcome
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The TV's positions and the ones the phone kept without the server, end to
 * end: the phone's own code sends both - the TV's as casting writes them
 * (the reporter's writes through [writeCastProgress]), a queued one as
 * ProgressSync flushes it, with its age - to a progress route that keeps
 * them as chino-api does (store.SaveProgressPlayed, covered there against
 * PostgreSQL with these bodies): a position with an age is written only over
 * an older one, one without is written as of now.
 */
class CastAndQueuedPositionsTest {
    /** The wall clock the server and the phone share. */
    private var nowMs = 1_800_000_000_000L
    private var reachable = false

    private class Stored(val positionSec: Int, val atMs: Long)

    private val stored = mutableMapOf<String, Stored>()
    private val bodies = mutableListOf<String>()

    private val server = FakeServer { request ->
        val path = request.url.encodedPath
        val id = path.substringAfter("/v1/items/").substringBefore("/progress")
        when {
            !path.endsWith("/progress") -> respondText("not found", HttpStatusCode.NotFound)
            request.method == HttpMethod.Post -> {
                val body = (request.body as TextContent).text
                bodies += body
                val json = Json.parseToJsonElement(body).jsonObject
                val position = json.getValue("position_sec").jsonPrimitive.int
                val age = json["played_ms_ago"]?.jsonPrimitive?.long
                val at = nowMs - (age ?: 0L).coerceAtLeast(0L)
                val had = stored[id]
                if (age == null || had == null || had.atMs < at) stored[id] = Stored(position, at)
                respond("", HttpStatusCode.NoContent)
            }
            else -> respondJson("""{"position_sec":${stored[id]?.positionSec ?: 0}}""")
        }
    }

    private fun TestScope.sync() = ProgressSync(
        queue = ProgressQueue(MemoryProgressBookStore()),
        post = { id, body, bearer ->
            if (!reachable) throw IOException("no connection")
            server.api.postProgress(id, body, bearer)
        },
        account = { ME.accountId },
        server = { "https://media.example.com/api" },
        scope = this,
        bearerOf = { "token" },
        now = { nowMs },
    )

    /** A TV that plays "film" for this phone and account from [fromSec] for
     *  [seconds], each of its writes sent as casting sends it. */
    private suspend fun castAndStop(sync: ProgressSync?, fromSec: Int, seconds: Int) {
        val writes = mutableListOf<CastProgressWrite>()
        var mono = 0L
        val reporter = CastProgressReporter(writer = { ME }, now = { mono }, write = { writes += it })
        reporter.loading("L1", "film", startMs = fromSec * 1000L)
        val tag = CastTag("film", null, ME.owner, writesProgress = true, load = "L1", linksExpireAtMs = 0L)
        reporter.onStatus(RemoteMediaStatus(tag, RemotePlayerState.Playing, positionMs = fromSec * 1000L, durationMs = 1_800_000))
        for (s in 1..seconds) {
            mono += 1_000
            nowMs += 1_000
            reporter.onProgress((fromSec + s) * 1000L, 1_800_000)
        }
        reporter.onSessionEnding()
        for (w in writes) assertTrue(writeCastProgress(w, { id, body -> server.api.postProgress(id, body) }, sync))
    }

    @Test
    fun aPositionQueuedOfflineNeverGoesOverTheTvsNewerOne() = runTest {
        val sync = sync()
        // On a flight: the download played to 10:00, kept on the phone.
        assertEquals(SendOutcome.Wait, sync.saveNow("film", 600, 1_800))
        assertTrue(sync.hasUnsent("film"))
        // Landed an hour later, the server reachable again, the title cast
        // to the TV from 20:00 for a minute.
        nowMs += 60 * 60 * 1000
        reachable = true
        castAndStop(sync, fromSec = 1_200, seconds = 60)
        assertEquals(1_260, stored.getValue("film").positionSec)
        // The cast's writes went as of now, with no age.
        assertTrue(bodies.none { "played_ms_ago" in it })
        // This phone's queued 10:00 is older than what it cast: it never goes,
        // and the download resumes here where the TV stopped.
        assertFalse(sync.hasUnsent("film"))
        assertEquals(0, sync.flush()?.sent)
        assertEquals(1_260, stored.getValue("film").positionSec)
        assertEquals(1_260, sync.lastPlayedSec("film"))
    }

    @Test
    fun aQueuedPositionSentLateLosesToANewerWriteItDidNotSee() = runTest {
        val sync = sync()
        // Played offline on this phone to 10:00.
        sync.saveNow("film", 600, 1_800)
        // Later, another phone of the account casts it to 21:00 - this one
        // knows nothing of that write.
        nowMs += 30 * 60 * 1000
        castAndStop(sync = null, fromSec = 1_200, seconds = 60)
        assertEquals(1_260, stored.getValue("film").positionSec)
        // Back online, the queued 10:00 goes with its age - and the server
        // keeps the newer one.
        reachable = true
        val result = sync.flush()
        assertEquals(1, result?.sent)
        val queued = bodies.last()
        assertTrue("\"played_ms_ago\":" in queued, queued)
        assertEquals(1_260, stored.getValue("film").positionSec)
        assertEquals(1_260, server.api.getProgress("film").positionSec)
    }

    @Test
    fun aQueuedPositionNewerThanTheServersIsWritten() = runTest {
        val sync = sync()
        // The TV played it to 21:00 first, then the phone, offline, to 25:00.
        reachable = true
        castAndStop(sync, fromSec = 1_200, seconds = 60)
        reachable = false
        nowMs += 10 * 60 * 1000
        sync.saveNow("film", 1_500, 1_800)
        nowMs += 60 * 60 * 1000
        reachable = true
        assertEquals(1, sync.flush()?.sent)
        assertEquals(1_500, stored.getValue("film").positionSec)
    }

    private companion object {
        val ME = CastWriter("account-a", "owner-a")
    }
}
