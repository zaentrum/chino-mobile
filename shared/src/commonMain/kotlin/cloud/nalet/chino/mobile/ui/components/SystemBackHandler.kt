package cloud.nalet.chino.mobile.ui.components

import androidx.compose.runtime.Composable

/**
 * The system Back, while [enabled] and composed: [onBack] instead of the
 * navigator's pop — for what the app draws over a screen in its own window,
 * a sheet or a scrim that should close first. The handler composed last
 * comes first. Android's back button and gesture; iOS has neither and is a
 * no-op, its screens' own Back buttons doing the same.
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean = true, onBack: () -> Unit)
