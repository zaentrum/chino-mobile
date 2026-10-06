package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Back after the player closed by itself: ignored for a second on the
 *  screen it returns to, the viewer's again after that. */
class AutoCloseBackGuardTest {
    @Test
    fun backIsTheViewersUntilThePlayerClosesItself() {
        val guard = AutoCloseBackGuard(time = TestTimeSource())
        assertFalse(guard.ignoresBack())
        assertEquals(Duration.ZERO, guard.remaining())
    }

    @Test
    fun backIsIgnoredForASecondAfterThePlayerClosedItself() {
        val time = TestTimeSource()
        val guard = AutoCloseBackGuard(time = time)
        guard.playerClosedItself()
        assertTrue(guard.ignoresBack())
        assertEquals(1.seconds, guard.remaining())

        time += 999.milliseconds
        assertTrue(guard.ignoresBack())
        assertEquals(1.milliseconds, guard.remaining())

        // A deliberate Back after the moment leaves the screen, as ever.
        time += 1.milliseconds
        assertFalse(guard.ignoresBack())
        assertEquals(Duration.ZERO, guard.remaining())
        time += 1.seconds
        assertFalse(guard.ignoresBack())
        assertEquals(Duration.ZERO, guard.remaining())
    }

    @Test
    fun closingItselfAgainStartsTheMomentAgain() {
        val time = TestTimeSource()
        val guard = AutoCloseBackGuard(time = time)
        guard.playerClosedItself()
        time += 800.milliseconds
        guard.playerClosedItself()
        time += 800.milliseconds
        assertTrue(guard.ignoresBack())
        assertEquals(200.milliseconds, guard.remaining())
        time += 200.milliseconds
        assertFalse(guard.ignoresBack())
    }

    @Test
    fun theMomentIsTheGuardsWindow() {
        val time = TestTimeSource()
        val guard = AutoCloseBackGuard(window = 300.milliseconds, time = time)
        guard.playerClosedItself()
        time += 299.milliseconds
        assertTrue(guard.ignoresBack())
        time += 1.milliseconds
        assertFalse(guard.ignoresBack())
    }
}
