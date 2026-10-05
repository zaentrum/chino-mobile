package cloud.nalet.chino.mobile.ui.trailer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import cloud.nalet.chino.mobile.ui.player.CodecCaps
import cloud.nalet.chino.mobile.ui.player.IosAudioSession
import cloud.nalet.chino.mobile.ui.player.masterPlaylistMissing
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.currentItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.timeControlStatus
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL

/**
 * iOS's trailer: AVPlayerViewController, the system's own player and
 * controls (play / pause, scrub, AirPlay, full screen), from 0:00 with sound
 * — the playback audio session, so the silent switch does not mute it, and
 * other apps' audio comes back after. Full screen opens and closes only as
 * the viewer likes; at the end the trailer leaves it first, so the screen
 * closes onto the app. The screen polls the item for a failure — a 404 on
 * the master is [onNotFound], as the full player reads it — and for
 * playback starting.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun TrailerVideo(
    url: String,
    modifier: Modifier,
    onStarted: () -> Unit,
    onEnded: () -> Unit,
    onNotFound: () -> Unit,
    onFailed: (String) -> Unit,
) {
    val started by rememberUpdatedState(onStarted)
    val ended by rememberUpdatedState(onEnded)
    val notFound by rememberUpdatedState(onNotFound)
    val failed by rememberUpdatedState(onFailed)

    val player = remember(url) { NSURL.URLWithString(url)?.let { AVPlayer(playerItem = AVPlayerItem(uRL = it)) } }
    if (player == null) {
        LaunchedEffect(url) { failed("The trailer's address could not be read.") }
        return
    }
    val controller = remember(player) {
        AVPlayerViewController().apply {
            this.player = player
            showsPlaybackControls = true
            entersFullScreenWhenPlaybackBegins = false
            exitsFullScreenWhenPlaybackEnds = false
            // A trailer is watched here and now: no window to keep it in.
            allowsPictureInPicturePlayback = false
        }
    }

    DisposableEffect(player) {
        val session = IosAudioSession(onInterrupted = { player.pause() }, onResumable = { player.play() })
        session.activate()
        val endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = player.currentItem,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            // Out of full screen first (it is presented over the app), then
            // the screen closes.
            if (controller.presentedViewController != null) {
                controller.dismissViewControllerAnimated(false) { ended() }
            } else {
                ended()
            }
        }
        player.play()
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(endObserver)
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            controller.player = null
            session.deactivate()
        }
    }

    // A look at the item four times a second: has it started, has it failed.
    LaunchedEffect(player) {
        var wasPlaying = false
        while (isActive) {
            val item = player.currentItem ?: break
            if (item.status == AVPlayerItemStatusFailed) {
                if (item.masterPlaylistMissing()) {
                    notFound()
                } else {
                    failed(item.error?.localizedDescription ?: "The trailer could not be played.")
                }
                break
            }
            val playing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying
            if (playing && !wasPlaying) started()
            wasPlaying = playing
            delay(250)
        }
    }

    UIKitViewController(
        factory = { controller },
        modifier = modifier,
        properties = UIKitInteropProperties(isInteractive = true, isNativeAccessibilityEnabled = true),
    )
}

/** iOS has no system Back: the screen's Back button closes it. */
@Composable
actual fun TrailerBackHandler(onBack: () -> Unit) {
}

actual fun trailerCodecCaps(): String = CodecCaps.queryParam
