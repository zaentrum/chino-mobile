package cloud.nalet.chino.mobile.data.downloads

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shell's look at the server: a server that stops while the person is
 * on Home or a grid is noticed within one look - never 20 s of a bell and
 * no banner, never only after a tab change - and one that is back, too.
 */
class ServerLookTest {
    @Test
    fun aServerThatStopsIsNoticedWithinOneLook() = runTest {
        var up = true
        var asked = 0
        val reach = ServerReach(fetch = { asked++; if (up) """{"product":"chino"}""" else throw IllegalStateException("refused") })
        reach.report(answered = true)
        val look = launch { reach.keepLooking() }
        // The server stops just after a look.
        advanceTimeBy(SERVER_LOOK_MS + 1)
        assertEquals(1, asked)
        assertEquals(Reach.Reachable, reach.state.value)
        up = false
        // Within one look - 15 s - the app knows: the banner, the bell gone.
        advanceTimeBy(SERVER_LOOK_MS)
        runCurrent()
        assertEquals(Reach.Unreachable, reach.state.value)
        assertTrue(SERVER_LOOK_MS < 20_000L)
        // Back again: the next look says so, and what waited asks again.
        up = true
        advanceTimeBy(SERVER_LOOK_MS)
        runCurrent()
        assertEquals(Reach.Reachable, reach.state.value)
        look.cancel()
    }

    @Test
    fun itLooksOnlyWhileItIsLetRun() = runTest {
        var asked = 0
        val reach = ServerReach(fetch = { asked++; """{"product":"chino"}""" })
        val look = launch { reach.keepLooking() }
        advanceTimeBy(3 * SERVER_LOOK_MS + 1)
        assertEquals(3, asked)
        // The app in the background, or the shell gone: no more looks.
        look.cancel()
        advanceTimeBy(10 * SERVER_LOOK_MS)
        assertEquals(3, asked)
    }
}
