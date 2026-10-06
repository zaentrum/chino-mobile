package cloud.nalet.chino.mobile.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@Composable
actual fun IgnoreBackAfterAutoClose(guard: AutoCloseBackGuard) {
    // Read here, so the handler is on in the frame the player closes in — the
    // one that takes the player's own handler away — and off once the moment
    // is over.
    val closedAt = guard.closedAt
    var over by remember(closedAt) { mutableStateOf(closedAt == null) }
    LaunchedEffect(closedAt) {
        delay(guard.remaining())
        over = true
    }
    BackHandler(enabled = !over) {
        // A Back meant for the player: ignored.
    }
}
