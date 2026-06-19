package cloud.nalet.chino.mobile.ui.zap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android: wire the Media3 [ZapPrefetcher] into [ZapPrefetchBridge] for the
 * lifetime of the Zap surface. The prefetcher is remembered (one session per
 * mount) and bound to the Compose Context; its session Job is cancelled on
 * dispose so leaving Zap kills every in-flight download.
 */
@Composable
actual fun InstallZapPrefetcher() {
    val context = LocalContext.current
    val prefetcher = remember { ZapPrefetcher(context) }
    DisposableEffect(prefetcher) {
        val handler = object : ZapPrefetchBridge.Handler {
            override fun prefetch(cards: List<ZapPrefetchCard>) {
                prefetcher.prefetch(
                    cards.map { ZapPrefetchRequest(it.itemId, it.masterUrl, it.seekSec) },
                )
            }

            override fun cancel() = prefetcher.cancel()
        }
        ZapPrefetchBridge.handler = handler
        onDispose {
            // Clear the bridge first so no late call schedules new work, then
            // cancel any in-flight downloads for this session.
            if (ZapPrefetchBridge.handler === handler) ZapPrefetchBridge.handler = null
            prefetcher.cancel()
        }
    }
}
