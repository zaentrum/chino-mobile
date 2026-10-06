package cloud.nalet.chino.mobile.ui.components

import androidx.compose.runtime.Composable

/** iOS has no system Back: a screen's own Back button does the same. */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
