package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Downloads asked for on a page: started in the app's scope, in order,
 *  each one once - and why one could not start, kept for the page. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadStartsTest {
    private val started = mutableListOf<String>()
    private val gates = mutableMapOf<String, CompletableDeferred<DownloadFailure?>>()

    private fun TestScope.starts(app: CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))) =
        DownloadStarts(app) { id ->
            started += id
            gates.getOrPut(id) { CompletableDeferred() }.await()
        }

    @Test
    fun aSeasonStartsInItsOrderAndShowsQueuedMeanwhile() = runTest {
        val s = starts()
        s.startAll(listOf("e1", "e2", "e3"))
        runCurrent()
        // All three show queued at once; the first is being worked out.
        assertEquals(setOf("e1", "e2", "e3"), s.working.value)
        assertEquals(listOf("e1"), started)
        gates.getValue("e1").complete(null)
        runCurrent()
        assertEquals(listOf("e1", "e2"), started)
        assertEquals(setOf("e2", "e3"), s.working.value)
        gates.getValue("e2").complete(null)
        runCurrent()
        gates.getValue("e3").complete(null)
        advanceUntilIdle()
        assertEquals(listOf("e1", "e2", "e3"), started)
        assertTrue(s.working.value.isEmpty())
    }

    @Test
    fun leavingThePageStopsNoneOfThem() = runTest {
        val app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val s = starts(app)
        // The page's scope asks, and is gone at once.
        val page = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        page.launch { s.startAll(listOf("e1", "e2")) }
        runCurrent()
        page.cancel()
        gates.getOrPut("e1") { CompletableDeferred() }.complete(null)
        gates.getOrPut("e2") { CompletableDeferred() }.complete(null)
        advanceUntilIdle()
        assertEquals(listOf("e1", "e2"), started)
    }

    @Test
    fun oneBeingWorkedOutIsNotAskedForTwice() = runTest {
        val s = starts()
        s.start("film")
        s.start("film")
        s.startAll(listOf("film", "film"))
        gates.getOrPut("film") { CompletableDeferred() }.complete(null)
        advanceUntilIdle()
        assertEquals(listOf("film"), started)
    }

    @Test
    fun whyOneCouldNotStartIsKeptUntilItIsAskedForAgain() = runTest {
        val s = DownloadStarts(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))) { id ->
            when (id) {
                "stream" -> DownloadFailure.StreamOnly
                "broken" -> throw ApiStatusException(502, "v1/items/broken/play/info")
                else -> null
            }
        }
        s.startAll(listOf("stream", "broken", "film"))
        advanceUntilIdle()
        assertEquals(mapOf("stream" to DownloadFailure.StreamOnly, "broken" to DownloadFailure.ServerProblem), s.failed.value)
        assertTrue(s.working.value.isEmpty())
        // Asked for again: the old reason goes while it is worked out.
        s.start("broken")
        assertEquals(setOf("stream"), s.failed.value.keys)
    }
}
