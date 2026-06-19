package cloud.nalet.chino.mobile.ui.zap

import cloud.nalet.chino.mobile.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App-start (home-load) warm for Zap. When the home shell first composes we
 * kick a LIGHTWEIGHT, fire-and-forget prefetch of just the DETERMINISTIC FIRST
 * Zap card so that opening Zap is instant (its init + seek-window segments are
 * already on disk before the user even taps the tab).
 *
 * It warms EXACTLY the card the Zap screen will play first: both compute card[0]
 * from the SAME candidate source ([ZapFeed.topRankedCandidate]) at the SAME
 * fixed seek ([ZAP_FIRST_CARD_RATIO]) using the SAME shared session stream token
 * ([AppContainer.streamTokenManager.valid]) and the SAME master.m3u8 URL builder
 * ([ZapFirstCard.masterUrl]). So the warmed segment bytes are byte-identical to
 * what the screen requests — no wasted download.
 *
 * It is a GOOD app-start citizen:
 *  - process-once (guarded by [warmed]) — a single feed sample + a couple of
 *    segment downloads, never per-composition;
 *  - off the main thread (Dispatchers.Default) so it NEVER delays home
 *    first-paint;
 *  - DEFERRED a short beat after first paint so the cold-start network burst
 *    doesn't contend with home's own initial section loads.
 *
 * It feeds the same [ZapPrefetchBridge] the in-screen triggers use, so on
 * platforms without a prefetcher installed (iOS, or before any Zap surface has
 * mounted on Android) the call is a no-op. The Android shell installs the
 * prefetcher once at app scope (see InstallZapPrefetcher) so the bridge handler
 * is present when this fires.
 */
object ZapAppStartWarm {
    // Process-once guard. Home composition is main-thread, so calls to
    // [warmOnce] are effectively serialized; a benign double-fire is harmless
    // anyway (the prefetcher dedups by item id), so a plain flag is enough.
    private var warmed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fire once per process. Safe to call on every home composition. */
    fun warmOnce(container: AppContainer) {
        if (warmed) return
        warmed = true
        scope.launch {
            // Defer past first paint so the warm's network calls don't contend
            // with home's own initial section loads on a cold start. Never on
            // the main thread (this scope is Dispatchers.Default), so home
            // first-paint is unaffected regardless.
            delay(FIRST_PAINT_DEFER_MS)
            runCatching { buildAndWarm(container) }
        }
    }

    private suspend fun buildAndWarm(container: AppContainer) {
        val baseUrl = container.config.apiBaseUrl
        val caps = zapCodecCaps()

        // One lightweight feed sample — the SAME ZapFeed candidate path the
        // screen uses (no preference scoring needed: card[0] is the deterministic
        // top-ranked candidate, which the cold pref vector would pick anyway).
        val feed = ZapFeed(api = container.chinoApi)
        feed.load()
        val first = ZapFirstCard.pick(feed) ?: return // nothing zappable to warm

        // SAME shared session token the screen uses (cached process-wide), so the
        // rewritten segment URLs — and the cache keys — match what the screen plays.
        val token = runCatching { container.streamTokenManager.valid() }.getOrDefault("")
        val master = ZapFirstCard.masterUrl(baseUrl, first.item.id, token, caps)
        ZapPrefetchBridge.prefetch(listOf(ZapPrefetchCard(first.item.id, master, first.seekSec)))
    }

    /** Small beat after first paint before the cold-start warm fires. */
    private const val FIRST_PAINT_DEFER_MS = 1_200L
}
