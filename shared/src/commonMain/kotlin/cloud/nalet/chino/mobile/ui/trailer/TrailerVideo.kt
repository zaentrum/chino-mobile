package cloud.nalet.chino.mobile.ui.trailer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A trailer on the platform's own player, under the platform's own controls:
 * Android a Media3 PlayerView with its controller (and the start on the
 * master's first variant, FirstVariantTrackSelection), iOS an
 * AVPlayerViewController. Not the full player — that one reads progress,
 * segments and subtitles and writes progress and watched — and not Zap's
 * preview, which has no controls.
 *
 * It plays [url] from the start with sound, and asks for nothing but the
 * stream.
 *
 * @param url the extra's master, with `?stream=` and `&caps=` ([trailerMasterUrl])
 * @param onStarted each time playback runs; the screen counts the first
 * @param onEnded the trailer played to its end
 * @param onNotFound the master answered 404: the trailer is gone, or under
 *   the viewer's rating cap
 * @param onFailed the player gave up for another reason, with its technical line
 */
@Composable
expect fun TrailerVideo(
    url: String,
    modifier: Modifier = Modifier,
    onStarted: () -> Unit = {},
    onEnded: () -> Unit = {},
    onNotFound: () -> Unit = {},
    onFailed: (String) -> Unit = {},
)

/**
 * The system Back closes the trailer screen: Android's, which the screen
 * takes and consumes itself, as the player does (Voyager's own handler could
 * re-resolve to a screen just popped, bug #151). iOS has no system Back: the
 * screen's Back button is the way out.
 */
@Composable
expect fun TrailerBackHandler(onBack: () -> Unit)

/** The device's `?caps=` for the trailer's master: what the platform's
 *  decoders take, as the full player sends it. */
expect fun trailerCodecCaps(): String
