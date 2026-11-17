package cloud.nalet.chino.mobile.data.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether the server fills lists by kind (features.watchlist_kinds) is known
 * from the start: what it said the last time it answered is kept on the
 * phone with the server, and held until it answers again - on a slow server,
 * and where its /api/config couldn't be read.
 */
class WatchlistKindsKeptTest {
    private val offers = """{"product":"chino","features":{"watchlist_kinds":true}}"""
    private val offersNot = """{"product":"chino","features":{"watchlist_kinds":false}}"""

    @Test
    fun whatTheServerSaidBeforeIsKnownBeforeItAnswers() = runTest {
        val answer = CompletableDeferred<String>()
        val reach = ServerReach(fetch = { answer.await() }, keptWatchlistKinds = true, keepWatchlistKinds = {})
        assertTrue(reach.watchlistKinds.value)

        // Asked, and slow: still known.
        launch { reach.check() }
        runCurrent()
        assertNull(reach.features.value)
        assertTrue(reach.watchlistKinds.value)

        answer.complete(offers)
        runCurrent()
        assertTrue(reach.watchlistKinds.value)
    }

    @Test
    fun aConfigReadThatFailedLeavesWhatWasKept() = runTest {
        val kept = mutableListOf<Boolean>()
        val reach = ServerReach(fetch = { throw IllegalStateException("timeout") }, keptWatchlistKinds = true, keepWatchlistKinds = { kept += it })

        assertEquals(Reach.Unreachable, reach.check())
        assertNull(reach.features.value)
        assertTrue(reach.watchlistKinds.value)
        assertEquals(emptyList(), kept)
    }

    @Test
    fun eachAnswerIsFollowedAndKeptWhereItDiffersFromWhatIsKept() = runTest {
        val kept = mutableListOf<Boolean>()
        var body = offersNot
        val reach = ServerReach(fetch = { body }, keptWatchlistKinds = true, keepWatchlistKinds = { kept += it })

        reach.check()
        assertFalse(reach.watchlistKinds.value)
        assertEquals(listOf(false), kept)
        // The same answer again: nothing more to keep.
        reach.check()
        assertEquals(listOf(false), kept)

        body = offers
        reach.check()
        assertTrue(reach.watchlistKinds.value)
        assertEquals(listOf(false, true), kept)

        // A server from before the field: it fills no list by kind.
        body = """{"product":"chino"}"""
        reach.check()
        assertFalse(reach.watchlistKinds.value)
        assertEquals(listOf(false, true, false), kept)
    }

    @Test
    fun nothingKeptIsOffUntilTheServerSays() = runTest {
        val kept = mutableListOf<Boolean>()
        val reach = ServerReach(fetch = { offers }, keptWatchlistKinds = null, keepWatchlistKinds = { kept += it })
        assertFalse(reach.watchlistKinds.value)

        reach.check()
        assertTrue(reach.watchlistKinds.value)
        assertEquals(listOf(true), kept)
    }

    @Test
    fun aCopyThatCouldntBeKeptLeavesTheAnswerStandingAndIsKeptNextTime() = runTest {
        var fails = true
        val kept = mutableListOf<Boolean>()
        val reach = ServerReach(
            fetch = { offers },
            keptWatchlistKinds = null,
            keepWatchlistKinds = { if (fails) throw IllegalStateException("disk full") else kept += it },
        )

        assertEquals(Reach.Reachable, reach.check())
        assertTrue(reach.watchlistKinds.value)
        fails = false
        reach.check()
        assertEquals(listOf(true), kept)
    }
}
