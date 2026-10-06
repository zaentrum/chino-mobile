package cloud.nalet.chino.mobile.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun IgnoreSystemBack(enabled: Boolean) {
    // Composed for as long as the app is, enabled or not, so its place among
    // the back handlers stays where App() put it.
    BackHandler(enabled = enabled) {
        // A Back meant for the player: ignored.
    }
}
