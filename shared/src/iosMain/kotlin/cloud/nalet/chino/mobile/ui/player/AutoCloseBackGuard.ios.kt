package cloud.nalet.chino.mobile.ui.player

import androidx.compose.runtime.Composable

/** iOS has no system Back: the page's own Back asks the guard. */
@Composable
actual fun IgnoreBackAfterAutoClose(guard: AutoCloseBackGuard) {
}
