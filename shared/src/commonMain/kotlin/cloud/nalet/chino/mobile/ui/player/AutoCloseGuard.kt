package cloud.nalet.chino.mobile.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import cloud.nalet.chino.mobile.ui.components.SystemBackHandler
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How long the screen the player returns to holds off Back and taps after
 *  the player closed by itself. */
val AUTO_CLOSE_HOLD: Duration = 1.seconds

/** How the player closed. */
enum class PlayerClose {
    /** The viewer closed it: Back, the chrome's Back, a panel's Back. */
    ByViewer,

    /** It closed by itself, at an extra's end ([PlayerMode.closesAtEnd]). */
    ByItself,
}

/**
 * The moment after the player closed by itself — at an extra's end. A viewer
 * pressing Back or tapping for the player just as it closes would act on the
 * screen it returns to instead: leave the title's page, whose Back sits where
 * the player's does, or the app from Home, or open whatever is under the
 * finger. For [window] after the player closed [PlayerClose.ByItself] that
 * screen holds off Back and taps ([holdsInput], [HoldInputAfterAutoClose]);
 * after that they are the viewer's, as ever. A close by the viewer holds off
 * nothing. The same on Android and iOS: pure but for the clock [time], so
 * commonTest pins the moment.
 */
class AutoCloseGuard(
    private val window: Duration = AUTO_CLOSE_HOLD,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    /** When the player last closed by itself; null until it has. Compose
     *  state, so the hold is on in the frame the player closes in. */
    var closedAt: TimeMark? by mutableStateOf(null)
        private set

    /** The player closed, [how]: by itself, the screen it returns to holds
     *  off Back and taps for [window]; by the viewer, nothing. */
    fun playerClosed(how: PlayerClose) {
        if (how == PlayerClose.ByItself) closedAt = time.markNow()
    }

    /** How much longer Back and taps are held off; zero when they are the
     *  viewer's. */
    fun remaining(): Duration {
        val at = closedAt ?: return Duration.ZERO
        return (window - at.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }

    /** Whether Back and taps now are held off: meant for the player. */
    fun holdsInput(): Boolean = remaining() > Duration.ZERO
}

/** The app's one [AutoCloseGuard]: the player tells it how it closed, the
 *  screen it returns to asks it. App() provides it. */
val LocalAutoCloseGuard = staticCompositionLocalOf<AutoCloseGuard> {
    error("LocalAutoCloseGuard not provided — App() provides it beside LocalAppContainer.")
}

/**
 * Holds off Back and taps while [guard] does ([AutoCloseGuard.holdsInput]),
 * on whichever screen the player returned to — the title's page, or Home,
 * where Back would leave the app: a layer over the screen takes every touch,
 * and the system Back is ignored. App() composes it after the Navigator:
 * drawn over it, and its Back handler comes before the navigator's, while a
 * player opened on top comes before it again.
 */
@Composable
fun HoldInputAfterAutoClose(guard: AutoCloseGuard) {
    // Read here, so the hold is on in the frame the player closes in — the
    // one that takes the player away — and off once the moment is over.
    val closedAt = guard.closedAt
    var held by remember(closedAt) { mutableStateOf(guard.holdsInput()) }
    LaunchedEffect(closedAt) {
        delay(guard.remaining())
        held = false
    }
    // Composed for as long as the app is, held or not, so its place among
    // the Back handlers stays where App() put it. A Back meant for the
    // player: ignored.
    SystemBackHandler(enabled = held) {}
    if (held) {
        // Invisible, and nothing to TalkBack: a touch anywhere lands here
        // and goes no further, a press that lasts past the moment included.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                },
        )
    }
}
