package cloud.nalet.chino.mobile.ui.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/**
 * The player's renderers: Media3's, with two changes.
 *
 * A decoder that fails to start hands over to the next one (e.g. the software
 * c2.android.avc.decoder): some device decoders advertise support for H.264
 * High@4.0 1080p (format_supported=YES) yet throw on MediaCodec init ->
 * ERROR_CODE_DECODER_INIT_FAILED. Fallback keeps playback alive on those
 * devices (seen on SM-T500).
 *
 * The one text renderer decodes the side-loaded subtitles itself (legacy
 * subtitle decoding). Media3 1.5 disabled this path by default - but
 * SingleSampleMediaSource.Factory has no SubtitleParser.Factory setter in
 * 1.5.1, so the side-loaded samples can't be parsed during extraction
 * either: without it the player stops mid-prepare with "Legacy decoding is
 * disabled, can't handle text/vtt samples (expected
 * application/x-media3-cues)". Its decoders are Media3's, but for PGS: a
 * .sup sidecar is read by SupParser, its display sets drawn as each is shown
 * ([SubtitleDecoders], SupSubtitles.kt).
 */
internal class PlayerRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out += TextRenderer(output, outputLooper, SubtitleDecoders()).apply { experimentalSetLegacyDecodingEnabled(true) }
    }
}
