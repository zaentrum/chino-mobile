package cloud.nalet.chino.mobile.ui.trailer

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import cloud.nalet.chino.mobile.ui.player.CodecCaps
import cloud.nalet.chino.mobile.ui.zap.FirstVariantTrackSelectionFactory
import java.util.concurrent.TimeUnit

/**
 * Android's trailer: Media3 ExoPlayer in a PlayerView with its own
 * controller — tap shows it, play / pause, seek, the time bar — starting on
 * the master's first variant, the one chino-stream lists first and warms
 * (FirstVariantTrackSelection), from 0:00 with sound and audio focus. A 404
 * on the master fails at once ([onNotFound]); a segment's error is retried
 * as Media3 retries. It pauses when the app leaves the foreground.
 */
@Composable
actual fun TrailerVideo(
    url: String,
    modifier: Modifier,
    onStarted: () -> Unit,
    onEnded: () -> Unit,
    onNotFound: () -> Unit,
    onFailed: (String) -> Unit,
) {
    val context = LocalContext.current
    val started by rememberUpdatedState(onStarted)
    val ended by rememberUpdatedState(onEnded)
    val notFound by rememberUpdatedState(onNotFound)
    val failed by rememberUpdatedState(onFailed)

    // The full player's timeouts: a packaged segment can stall well past
    // 45 s under storage contention, and is let to complete.
    val client = remember {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    val player = remember(url) {
        val http = OkHttpDataSource.Factory(client).setUserAgent("chino-mobile/0.1 (Android; trailer)")
        val source = HlsMediaSource.Factory(http)
            .setLoadErrorHandlingPolicy(MasterMissingFailsFast())
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
            )
        // The next decoder (a software one) where a hardware decoder fails to
        // initialise, as the full player.
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setTrackSelector(DefaultTrackSelector(context, FirstVariantTrackSelectionFactory()))
            .build()
            .apply {
                // With sound: it takes the audio focus, so other audio pauses
                // rather than mixing over it, and a call pauses the trailer.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                setMediaSource(source)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) started()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended()
            }

            override fun onPlayerError(error: PlaybackException) {
                val http = error.cause as? HttpDataSource.InvalidResponseCodeException
                if (http?.responseCode == 404 && http.dataSpec.uri.toString().contains(".m3u8")) {
                    notFound()
                } else {
                    failed("Playback failed: ${error.errorCodeName}${error.message?.let { " — $it" } ?: ""}")
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Leaving the foreground pauses it: no sound behind the home screen.
    val lifecycleOwner = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .firstOrNull { it is LifecycleOwner } as? LifecycleOwner
    }
    DisposableEffect(lifecycleOwner, player) {
        val lifecycle = lifecycleOwner?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                // One video: nothing before or after it.
                setShowPreviousButton(false)
                setShowNextButton(false)
                keepScreenOn = true
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
    )
}

/** A 404 on the master: chino-stream has no such trailer, or the viewer's
 *  rating cap hides the title — permanent, so it fails at once instead of
 *  after the default retries. A segment's error keeps the default. */
private class MasterMissingFailsFast : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val ex = info.exception
        if (ex is HttpDataSource.InvalidResponseCodeException &&
            ex.responseCode == 404 && ex.dataSpec.uri.toString().contains(".m3u8")
        ) {
            return C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(info)
    }
}

@Composable
actual fun TrailerBackHandler(onBack: () -> Unit) {
    BackHandler(enabled = true, onBack = onBack)
}

actual fun trailerCodecCaps(): String = CodecCaps.queryParam
