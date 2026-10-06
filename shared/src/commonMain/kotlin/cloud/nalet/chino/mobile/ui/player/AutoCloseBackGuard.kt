package cloud.nalet.chino.mobile.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How long the screen the player returns to ignores Back after the player
 *  closed by itself. */
val AUTO_CLOSE_BACK_WINDOW: Duration = 1.seconds

/**
 * Back, the moment after the player closed by itself — at an extra's end
 * ([PlayerMode.closesAtEnd]). A viewer pressing Back for the player just as
 * it closes would leave the screen it returns to instead: the title's page,
 * whose Back sits where the player's does, or the app, from Home. For
 * [window] after [playerClosedItself] that screen ignores Back
 * ([ignoresBack]) — the system Back ([IgnoreBackAfterAutoClose]) and the
 * page's own button; a Back after that is the viewer's, as ever. A Back that
 * closes the player arms nothing. The same on Android and iOS: pure but for
 * the clock [time], so commonTest pins the window.
 */
class AutoCloseBackGuard(
    private val window: Duration = AUTO_CLOSE_BACK_WINDOW,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    /** When the player last closed by itself; null until it has. Compose
     *  state, so the system Back handler is on as the player closes. */
    var closedAt: TimeMark? by mutableStateOf(null)
        private set

    /** The player closed by itself, now. */
    fun playerClosedItself() {
        closedAt = time.markNow()
    }

    /** How much longer Back is ignored; zero when it is the viewer's. */
    fun remaining(): Duration {
        val at = closedAt ?: return Duration.ZERO
        return (window - at.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }

    /** Whether a Back now is ignored: one the viewer meant for the player. */
    fun ignoresBack(): Boolean = remaining() > Duration.ZERO
}

/** The app's one [AutoCloseBackGuard]: the player arms it as it closes by
 *  itself, the screen it returns to asks it. App() provides it. */
val LocalAutoCloseBackGuard = staticCompositionLocalOf<AutoCloseBackGuard> {
    error("LocalAutoCloseBackGuard not provided — App() provides it beside LocalAppContainer.")
}

/**
 * Ignores the system Back while [guard] does, on whichever screen the player
 * returned to — the title's page, or Home, where Back would leave the app.
 * Composed after the Navigator, so its handler comes before the navigator's,
 * and a player opened on top comes before it again. Android holds its back
 * button and gesture; iOS has neither and is a no-op — the page's own Back
 * asks the guard there.
 */
@Composable
expect fun IgnoreBackAfterAutoClose(guard: AutoCloseBackGuard)
