package cloud.nalet.chino.mobile.ui.zap

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Minimal platform video surface for the Zap card's mid-scene preview.
 *
 * The full [cloud.nalet.chino.mobile.ui.player.PlayerScreen] is a Voyager
 * `Screen` with its own chrome (scrubber / captions / fullscreen) and a
 * progress-reporting lifecycle — far too coupled to embed as a silent teaser
 * inside a pager. So Zap gets its own bare surface: no controller, a
 * client-side seek to [seekSec] before prepare (server ?t= is ignored by
 * packaged CMAF), and a [muted] volume flag the card flips live.
 *
 * Android = Media3 ExoPlayer in a PlayerView (useController=false). iOS = a
 * stub Box (mirrors PlayerScreen.ios.kt) so the iOS target stays green until
 * AVPlayer wiring lands.
 *
 * @param masterUrl the per-card master.m3u8 URL (already carries ?stream / &caps / &q)
 * @param seekSec   mid-scene start position, applied client-side once per source
 * @param muted     when true the preview plays silently
 * @param active    when false the surface should pause (off-screen pager pages)
 * @param onPositionSec invoked ~periodically with the current playhead in
 *   whole seconds, so the card can hand the live position to the expand action
 * @param onEnded    fired when the teaser reaches natural end of source
 * @param onError    fired on a dead channel (e.g. 404 master.m3u8 / no asset)
 * @param onFirstFrame fired once the player has rendered its first video frame
 *   (Media3 Player.Listener.onRenderedFirstFrame). The card uses this to fade
 *   out the cold-start backdrop image layered underneath the surface.
 */
@Composable
expect fun ZapPreviewPlayer(
    masterUrl: String,
    seekSec: Int,
    muted: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onPositionSec: (Int) -> Unit = {},
    onEnded: () -> Unit = {},
    onError: () -> Unit = {},
    onFirstFrame: () -> Unit = {},
)

/**
 * Locks the Zap feed to portrait while it is composed (a vertical reels feed
 * has no meaningful landscape layout — rotating just letterboxes the teaser and
 * breaks the overlay), restoring the previous orientation on exit. Android sets
 * the host Activity's requestedOrientation; iOS is a no-op.
 */
@Composable
expect fun ZapPortraitLock()
