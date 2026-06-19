package cloud.nalet.chino.mobile.ui.zap

import androidx.compose.runtime.Composable

/**
 * Installs the platform prefetcher into [ZapPrefetchBridge] for the lifetime of
 * the Zap surface and removes it on dispose. Android builds a [ZapPrefetcher]
 * (Media3) bound to the Compose Context; iOS is a no-op (leaves the bridge
 * handler null, so [ZapScreenModel]'s prefetch calls do nothing).
 *
 * Call once near the top of the Zap screen.
 */
@Composable
expect fun InstallZapPrefetcher()

/** A card the platform layer should background-prefetch: its master.m3u8 URL
 *  (already carrying device caps + token) and the mid-scene seek the player
 *  will start on. Pure-common so [ZapScreenModel] can build the list without
 *  any platform types. */
data class ZapPrefetchCard(val itemId: String, val masterUrl: String, val seekSec: Int)

/**
 * Thin common -> platform bridge for the Zap client-side prefetch.
 *
 * The actual download + on-disk cache machinery is Media3-only and lives in
 * androidMain ([ZapPrefetcher] / [ZapMediaCache]); iOS has no implementation
 * yet. Rather than push a Context-bearing `expect` into commonMain, the Android
 * Zap surface installs a [handler] (a closure that captures its Context) when
 * the screen mounts, and clears it on dispose. [ZapScreenModel] (commonMain)
 * just calls [prefetch] / [cancel] — a no-op on platforms that never install a
 * handler (iOS), so it stays decoupled from the player engine.
 */
object ZapPrefetchBridge {
    /** Installed by the Android Zap surface; null elsewhere (no-op). */
    var handler: Handler? = null

    interface Handler {
        /** Warm the given upcoming cards (bounded + deduped by the impl). */
        fun prefetch(cards: List<ZapPrefetchCard>)
        /** Cancel all in-flight prefetch (leaving Zap). */
        fun cancel()
    }

    fun prefetch(cards: List<ZapPrefetchCard>) {
        if (cards.isEmpty()) return
        handler?.prefetch(cards)
    }

    fun cancel() {
        handler?.cancel()
    }
}
