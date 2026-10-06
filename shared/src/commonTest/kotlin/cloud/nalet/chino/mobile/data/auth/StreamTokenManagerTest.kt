package cloud.nalet.chino.mobile.data.auth

import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import cloud.nalet.chino.mobile.data.auth.StreamTokenManager.Companion.SLACK_MS
import cloud.nalet.chino.mobile.data.auth.StreamTokenManager.Companion.TOKEN_LIFE_MS
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When the stream token is minted again, by the wall clock - on a fake one:
 *  kept while it has more than 5 min of its 6 h left, minted again then, and
 *  at the next use after a device has slept past its end. */
class StreamTokenManagerTest {
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

    @Test
    fun aTokenIsMintedOnceAndKeptWhileItHasMoreThanFiveMinutesLeft() = runTest {
        assertNull(tokens.current.value)
        assertEquals("t1", tokens.valid())
        clockMs += TOKEN_LIFE_MS - SLACK_MS - 1
        assertEquals("t1", tokens.valid())
        assertEquals(1, mints)
        assertEquals("t1", tokens.current.value)
    }

    @Test
    fun fiveMinutesBeforeItsEndTheNextOneIsMinted() = runTest {
        assertEquals("t1", tokens.valid())
        clockMs += TOKEN_LIFE_MS - SLACK_MS
        assertEquals("t2", tokens.valid())
        assertEquals("t2", tokens.current.value)
        // The next one's life counts from its own mint.
        clockMs += TOKEN_LIFE_MS - SLACK_MS - 1
        assertEquals("t2", tokens.valid())
        clockMs += 1
        assertEquals("t3", tokens.valid())
        assertEquals(3, mints)
    }

    @Test
    fun afterANightAsleepTheNextUseMintsAgain() = runTest {
        assertEquals("t1", tokens.valid())
        // No timer ran while the device slept; its wall clock moved on.
        clockMs += 9L * 60 * 60 * 1000
        assertEquals("t2", tokens.valid())
        assertEquals("t2", tokens.valid())
        assertEquals(2, mints)
    }

    @Test
    fun anInvalidatedTokenIsMintedAgainAtItsNextUse() = runTest {
        assertEquals("t1", tokens.valid())
        tokens.invalidate()
        assertNull(tokens.current.value)
        assertEquals("t2", tokens.valid())
    }
}
