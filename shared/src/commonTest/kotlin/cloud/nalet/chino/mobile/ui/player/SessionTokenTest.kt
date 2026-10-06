package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import cloud.nalet.chino.mobile.data.auth.StreamTokenManager
import cloud.nalet.chino.mobile.data.auth.StreamTokenManager.Companion.TOKEN_LIFE_MS
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The token a playback session starts with, on a fake wall clock: one that
 *  outlives what is left to play and half an hour, else a new one first. */
class SessionTokenTest {
    private var clockMs = 1_759_700_000_000L
    private var mints = 0

    private val server = FakeServer { request ->
        if (request.url.encodedPath.endsWith("/v1/me/stream-token")) {
            mints++
            respondJson("""{"stream_token":"t$mints","expires_at":"2026-10-06T22:00:00Z"}""")
        } else {
            respondText("not found", HttpStatusCode.NotFound)
        }
    }
    private val tokens = StreamTokenManager(server.api) { clockMs }

    /** Minted now, then the clock moved on until [leftMs] of its life is left. */
    private suspend fun tokenWith(leftMs: Long) {
        assertEquals("t1", tokens.valid())
        clockMs += TOKEN_LIFE_MS - leftMs
    }

    @Test
    fun aFilmStartedTenMinutesBeforeTheTokenEndsGetsANewTokenFirst() = runTest {
        tokenWith(leftMs = 10 * MIN)
        assertEquals("t2", tokens.forSession(durationMs = 150 * MIN, startMs = 0L))
        assertEquals(2, mints)
        // The images go on with it too.
        assertEquals("t2", tokens.valid())
    }

    @Test
    fun anEpisodeTheTokenOutlivesKeepsIt() = runTest {
        tokenWith(leftMs = 60 * MIN)
        assertEquals("t1", tokens.forSession(durationMs = 20 * MIN, startMs = 0L))
        assertEquals(1, mints)
    }

    @Test
    fun whatIsLeftToPlayCountsNotTheWholeFilm() = runTest {
        // 2.5 h resumed at 2 h: 30 min to play and the half hour.
        tokenWith(leftMs = 60 * MIN)
        assertEquals("t1", tokens.forSession(durationMs = 150 * MIN, startMs = 120 * MIN))
        clockMs += 1
        assertEquals("t2", tokens.forSession(durationMs = 150 * MIN, startMs = 120 * MIN))
    }

    @Test
    fun withoutALengthFourHoursAreTakenToBeLeft() = runTest {
        assertEquals(270 * MIN, sessionLifeMs(durationMs = null, startMs = 0L))
        assertEquals(270 * MIN, sessionLifeMs(durationMs = 0L, startMs = 0L))
        tokenWith(leftMs = 270 * MIN)
        assertEquals("t1", tokens.forSession(durationMs = null, startMs = 0L))
        clockMs += 1
        assertEquals("t2", tokens.forSession(durationMs = null, startMs = 0L))
    }

    @Test
    fun aSessionLongerThanATokenLivesGetsOneMintedWithinFiveMinutes() = runTest {
        // Eight hours: no token lives that long; a fresh one is not minted
        // again at every start.
        assertEquals("t1", tokens.forSession(durationMs = 480 * MIN, startMs = 0L))
        clockMs += 5 * MIN
        assertEquals("t1", tokens.forSession(durationMs = 480 * MIN, startMs = 0L))
        clockMs += 1
        assertEquals("t2", tokens.forSession(durationMs = 480 * MIN, startMs = 0L))
        assertEquals(2, mints)
    }

    private companion object {
        const val MIN = 60_000L
    }
}
