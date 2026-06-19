package cloud.nalet.chino.mobile.ui.player

import cafe.adriel.voyager.core.screen.Screen

/**
 * Platform-specific HLS playback. Mirrors chino-androidtv's PlayerScreen
 * — same item lookup, same stream-token-bearing master URL, same progress
 * reporting cadence — but with touch-driven chrome instead of DPAD.
 *
 * The implementation is `expect/actual` because the native video surface
 * is platform-specific: Android uses Media3 ExoPlayer + PlayerView; iOS
 * will use AVPlayer + AVPlayerLayer (stub today). The shared `Screen`
 * declaration lets DetailScreen + HeroBanner push the same `PlayerScreen`
 * regardless of target.
 *
 * @param itemId chino-api item id to play
 * @param fromStart when true, ignores the saved resume position and
 *   starts at 0:00 — wired from DetailScreen's "Play from start" button.
 * @param resumeSec when >= 0, starts playback at exactly this many seconds
 *   instead of the saved progress — wired from Zap's expand ("Watch from
 *   here") so the full player picks up at the teaser's scene. -1 (default)
 *   means "use the saved resume position" (the pre-Zap behaviour). Ignored
 *   when [fromStart] is true.
 */
expect class PlayerScreen(
    itemId: String,
    fromStart: Boolean = false,
    resumeSec: Int = -1,
) : Screen
