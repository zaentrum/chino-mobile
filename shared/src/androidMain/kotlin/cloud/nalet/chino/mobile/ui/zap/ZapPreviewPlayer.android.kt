package cloud.nalet.chino.mobile.ui.zap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * Android Zap preview surface — Media3 ExoPlayer in a controller-less
 * PlayerView. Mirrors the full PlayerScreen's HLS + OkHttp + 404-fast-fail
 * setup but with none of the chrome. One player per pager page (this surface
 * only mounts for the active + neighbour pages); swapping the source on URL
 * change avoids decoder churn.
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
    val context = LocalContext.current

    // Rebuild the player when the channel (masterUrl) changes — a fresh
    // source + a fresh client-side seek. ExoPlayer is released in the
    // DisposableEffect below.
    val player = remember(masterUrl) {
        // Upstream OkHttp wrapped by the shared on-disk SimpleCache so segments
        // the ZapPrefetcher already downloaded (init + the seek-window media
        // segments) serve locally — the swap to this card begins without a
        // network round-trip. Cache misses fall through to the network and are
        // written back, so a re-watch is warm too.
        val httpFactory = ZapMediaCache.playerDataSourceFactory(
            context,
            userAgent = "chino-mobile/0.1 (Android; zap)",
        )
        // A 404 on the manifest = chino-stream has no playable asset for the
        // item — permanent, so fail fast (the card treats this as a dead
        // channel and the ScreenModel auto-skips). Segment 404s keep the
        // default transient policy.
        val retryPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(
                info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
            ): Long {
                val ex = info.exception
                if (ex is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException &&
                    ex.responseCode == 404 && ex.dataSpec.uri.toString().contains(".m3u8")
                ) {
                    return C.TIME_UNSET
                }
                return super.getRetryDelayMsFor(info)
            }
        }
        val source = HlsMediaSource.Factory(httpFactory)
            .setLoadErrorHandlingPolicy(retryPolicy)
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(masterUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
            )
        // Fall back to the next decoder (e.g. software AVC) when the
        // preferred hardware decoder fails to init — same SM-T500 mitigation
        // the full player uses.
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            // Request audio focus while playing so a Zap card WITH sound pauses
            // other audio (e.g. Spotify) instead of mixing over it, and the
            // player ducks/pauses itself on focus loss (calls, other media).
            // Only the active card has playWhenReady=true, so only it grabs focus.
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            setMediaSource(source)
            volume = if (muted) 0f else 1f
            repeatMode = Player.REPEAT_MODE_OFF
            if (seekSec > 0) seekTo(seekSec * 1000L) // mid-scene, before prepare
            playWhenReady = active
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                onError()
            }
            // First decoded+presented video frame — the card fades out the
            // cold-start backdrop image layered under this surface (web's
            // hasFirstFrame). Fires once per source; a fresh masterUrl rebuilds
            // the player and re-arms it.
            override fun onRenderedFirstFrame() {
                onFirstFrame()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Live mute toggles without restarting the channel.
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }

    // Pause when the app leaves the foreground so Zap doesn't keep playing audio
    // behind the home screen / another app (and so it releases audio focus back
    // to e.g. Spotify). Track the host lifecycle, resolved from the Compose
    // context (unwrap ContextWrapper to the Activity).
    val lifecycleOwner = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .firstOrNull { it is LifecycleOwner } as? LifecycleOwner
    }
    var foreground by remember {
        mutableStateOf(
            lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true,
        )
    }
    DisposableEffect(lifecycleOwner) {
        val lc = lifecycleOwner?.lifecycle
        val obs = LifecycleEventObserver { _, _ ->
            foreground = lc?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
        }
        lc?.addObserver(obs)
        onDispose { lc?.removeObserver(obs) }
    }
    // Pause off-screen pages AND the whole feed when backgrounded; resume only
    // the active card while the app is in the foreground.
    LaunchedEffect(player, active, foreground) { player.playWhenReady = active && foreground }

    // Surface the live playhead so the expand action can resume from the
    // exact scene the teaser reached.
    LaunchedEffect(player) {
        while (true) {
            onPositionSec((player.currentPosition / 1000L).toInt())
            delay(500)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                keepScreenOn = true
                this.player = player
            }
        },
    )
}

@Composable
actual fun ZapPortraitLock() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .firstOrNull { it is android.app.Activity } as? android.app.Activity
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation =
                original ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
