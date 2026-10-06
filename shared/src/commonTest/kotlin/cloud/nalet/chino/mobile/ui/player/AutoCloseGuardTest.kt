package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Back and taps after the player closed: held off for a second on the
 *  screen it returns to when it closed by itself, the viewer's again after
 *  that, and never held when the viewer closed it. */
class AutoCloseGuardTest {
    @Test
    fun nothingIsHeldUntilThePlayerClosesItself() {
        val guard = AutoCloseGuard(time = TestTimeSource())
        assertFalse(guard.holdsInput())
        assertEquals(Duration.ZERO, guard.remaining())
        assertNull(guard.closedAt)
    }

    @Test
    fun backAndTapsAreHeldForASecondAfterThePlayerClosedItself() {
        val time = TestTimeSource()
        val guard = AutoCloseGuard(time = time)
        guard.playerClosed(PlayerClose.ByItself)
        assertTrue(guard.holdsInput())
        assertEquals(1.seconds, guard.remaining())

        time += 999.milliseconds
        assertTrue(guard.holdsInput())
        assertEquals(1.milliseconds, guard.remaining())

        // A deliberate Back or tap after the moment is the viewer's, as ever.
        time += 1.milliseconds
        assertFalse(guard.holdsInput())
        assertEquals(Duration.ZERO, guard.remaining())
        time += 1.seconds
        assertFalse(guard.holdsInput())
        assertEquals(Duration.ZERO, guard.remaining())
    }

    @Test
    fun aCloseByTheViewerHoldsNothing() {
        val time = TestTimeSource()
        val guard = AutoCloseGuard(time = time)
        // Back, the chrome's Back, a panel's: the next press is the viewer's.
        guard.playerClosed(PlayerClose.ByViewer)
        assertFalse(guard.holdsInput())
        assertEquals(Duration.ZERO, guard.remaining())
        assertNull(guard.closedAt)

        // Nor after a moment of the player's own has passed.
        guard.playerClosed(PlayerClose.ByItself)
        time += 2.seconds
        guard.playerClosed(PlayerClose.ByViewer)
        assertFalse(guard.holdsInput())
        assertEquals(Duration.ZERO, guard.remaining())
    }

    @Test
    fun aCloseByTheViewerLeavesTheMomentOfOneByItselfAsItIs() {
        val time = TestTimeSource()
        val guard = AutoCloseGuard(time = time)
        guard.playerClosed(PlayerClose.ByItself)
        time += 400.milliseconds
        guard.playerClosed(PlayerClose.ByViewer)
        assertTrue(guard.holdsInput())
        assertEquals(600.milliseconds, guard.remaining())
    }

    @Test
    fun closingItselfAgainStartsTheMomentAgain() {
        val time = TestTimeSource()
        val guard = AutoCloseGuard(time = time)
        guard.playerClosed(PlayerClose.ByItself)
        time += 800.milliseconds
        guard.playerClosed(PlayerClose.ByItself)
        time += 800.milliseconds
        assertTrue(guard.holdsInput())
        assertEquals(200.milliseconds, guard.remaining())
        time += 200.milliseconds
        assertFalse(guard.holdsInput())
    }

    @Test
    fun theMomentIsTheGuardsWindow() {
        val time = TestTimeSource()
        val guard = AutoCloseGuard(window = 300.milliseconds, time = time)
        guard.playerClosed(PlayerClose.ByItself)
        time += 299.milliseconds
        assertTrue(guard.holdsInput())
        time += 1.milliseconds
        assertFalse(guard.holdsInput())
    }
}
