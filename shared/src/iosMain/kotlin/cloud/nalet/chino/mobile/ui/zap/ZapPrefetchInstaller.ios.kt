package cloud.nalet.chino.mobile.ui.zap

import androidx.compose.runtime.Composable

/**
 * iOS: no client-side prefetch yet (the Zap preview surface is itself a stub
 * pending AVPlayer wiring). Leaving the bridge handler null means
 * [ZapScreenModel]'s prefetch calls are no-ops on iOS.
 */
@Composable
actual fun InstallZapPrefetcher() {
    // no-op
}
