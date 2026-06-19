package cloud.nalet.chino.mobile.ui.zap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * iOS Zap preview stub. AVPlayer + AVPlayerLayer wiring lands when iOS ships
 * as a real target (same status as PlayerScreen.ios.kt). For now this keeps
 * the shared `expect fun` declaration satisfied so commonMain compiles for
 * iOS targets — the card overlay still renders over this Box so the layout is
 * representative.
 */
@Composable
actual fun ZapPreviewPlayer(
    masterUrl: String,
    seekSec: Int,
    muted: Boolean,
    active: Boolean,
    modifier: Modifier,
    onPositionSec: (Int) -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
    onFirstFrame: () -> Unit,
) {
    // No real player yet, so there is no first frame to wait for — report it
    // immediately so the card hides its cold-start backdrop and shows this
    // representative stub box (AVPlayer wiring will fire it for real later).
    LaunchedEffect(masterUrl) { onFirstFrame() }
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "iOS preview — coming soon", color = Color.White)
    }
}

/** No-op on iOS (orientation is handled by the app's Info.plist; the iOS Zap
 *  surface isn't built yet). Keeps the shared `expect fun` satisfied. */
@Composable
actual fun ZapPortraitLock() {
}
