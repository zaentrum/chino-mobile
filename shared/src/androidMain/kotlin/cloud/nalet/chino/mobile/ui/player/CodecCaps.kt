package cloud.nalet.chino.mobile.ui.player

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioCapabilities

/**
 * The device's `?caps=` value for chino-stream ([CodecCapsQuery]): video
 * tokens (`avc` / `hvc` / `av1`) and the audio tokens it plays (`aac`, `mp3`,
 * `opus`, `ac3`, `eac3`), e.g. `avc:1080,hvc:1080,av1:2160,aac,mp3,opus,eac3`.
 *
 * Each video token carries an optional `:<maxHeight>` suffix = the tallest
 * frame the device's **hardware** decoder can handle for that codec. A bare
 * token (no suffix) means the codec is decodable but only in **software** —
 * no known HW height ceiling to report.
 *
 * Without caps the server fell back to H.264 — which some hardware decoders
 * reject for non-16-aligned heights (e.g. the 1920x1036 stream that failed to
 * init OMX.qcom.video.decoder.avc on a Samsung SM-T500, forcing slow software
 * decode). Advertising `hvc` lets the server serve the HEVC rendition the
 * device can decode in hardware. The height ceiling fixes the inverse hazard:
 * a device advertises HEVC, the server serves a 4K HEVC package, but the HW
 * decoder tops out at 1080 -> silent fallback to the software decoder ->
 * unplayably slow (the SM-T500 Zap bug).
 *
 * The audio tokens are what keeps an AAC (or, with a Dolby decoder, an AC-3 /
 * E-AC-3) track from being re-encoded: with only video tokens on the wire the
 * server took the device for one that decodes no audio (see [CodecCapsQuery]).
 *
 * `eac3` and `ac3` also bring the 5.1 companions into the master, so they go
 * only where Media3 plays the codec: a decoder for it (MediaCodecList), or an
 * output that takes its bitstream as it is — an HDMI receiver, a TV over
 * eARC — which Media3 then passes through (its [AudioCapabilities] of the
 * route the sound goes to now, read as each playback starts). A phone on its
 * speaker without a Dolby decoder says neither, and gets the stereo group.
 */
internal object CodecCaps {
    /** The decoder list's part, read once: the video caps and every MIME
     *  type some decoder takes. */
    private val decoders: Decoders by lazy {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        Decoders(
            video = VIDEO_MIMES.mapNotNull { (token, mime) -> videoCaps(infos, token, mime) },
            audioMimes = infos.filter { !it.isEncoder }.flatMap { it.supportedTypes.asList() },
        )
    }

    /** The caps of a player starting now: the decoders', and the Dolby
     *  tokens the output passes through as it is right now. */
    fun queryParam(context: Context): String =
        CodecCapsQuery.build(decoders.video, CodecCapsQuery.audioTokensFor(decoders.audioMimes, passthroughTokens(context)))

    /** Zap's caps: the decoders' without the [CodecCapsQuery.SURROUND_TOKENS]
     *  — so its masters keep to the stereo group. A card plays seconds of a
     *  title from the bytes prefetched for its start; with the 5.1 companions
     *  in the group Media3 would first load every audio rendition there to
     *  tell their codecs apart. */
    val stereoQueryParam: String by lazy {
        CodecCapsQuery.build(decoders.video, CodecCapsQuery.audioTokensFor(decoders.audioMimes) - CodecCapsQuery.SURROUND_TOKENS)
    }

    private class Decoders(val video: List<VideoDecoderCaps>, val audioMimes: List<String>)
}

/** Token -> mime, in the legacy emit order. Literals, as MediaFormat's
 *  constants (AV1's is API 29) would be on minSdk 24. */
private val VIDEO_MIMES = listOf(
    "avc" to "video/avc",
    "hvc" to "video/hevc",
    "av1" to "video/av01",
)

/** The Dolby tokens whose 5.1 bitstream the output the sound goes to now
 *  takes as it is (Media3's capabilities of that route): what Media3 then
 *  plays without a decoder. None where it cannot be read. */
private fun passthroughTokens(context: Context): Set<String> {
    val route = runCatching { AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null) }.getOrNull()
        ?: return emptySet()
    return buildSet {
        if (route.isPassthroughPlaybackSupported(surround(MimeTypes.AUDIO_AC3), AudioAttributes.DEFAULT)) add("ac3")
        if (route.isPassthroughPlaybackSupported(surround(MimeTypes.AUDIO_E_AC3), AudioAttributes.DEFAULT)) add("eac3")
    }
}

/** A 5.1 track at 48 kHz in [mime], as a package's companions are. */
private fun surround(mime: String): Format =
    Format.Builder().setSampleMimeType(mime).setChannelCount(6).setSampleRate(48_000).build()

/** The codec's caps when any decoder takes [mime] — with the tallest frame a
 *  hardware one handles — or null when none does (omitted from the list). */
private fun videoCaps(infos: Array<MediaCodecInfo>, token: String, mime: String): VideoDecoderCaps? {
    var sawDecoder = false
    var maxHwHeight = 0
    for (info in infos) {
        if (info.isEncoder) continue
        if (!info.supportsType(mime)) continue
        sawDecoder = true
        if (!info.isHardware()) continue
        val upper = runCatching {
            info.getCapabilitiesForType(mime).videoCapabilities?.supportedHeights?.upper
        }.getOrNull() ?: continue
        if (upper > maxHwHeight) maxHwHeight = upper
    }
    if (!sawDecoder) return null
    return VideoDecoderCaps(token, maxHardwareHeight = maxHwHeight.takeIf { it > 0 })
}

/**
 * True when this decoder runs on dedicated hardware.
 *
 * API 29+ exposes [MediaCodecInfo.isHardwareAccelerated] directly. Below 29 it
 * isn't available, so fall back to the well-known software-decoder name
 * prefixes (`OMX.google.*` legacy SW codecs, `c2.android.*` Codec2 SW codecs);
 * everything else is treated as hardware. Matches chino-stream codecFamily()'s
 * family keys via the token map above.
 */
private fun MediaCodecInfo.isHardware(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isHardwareAccelerated
    } else {
        val n = name.lowercase()
        !(n.startsWith("omx.google") || n.startsWith("c2.android"))
    }

/** Pre-API-29 [MediaCodecInfo.supportsType] shim (the API exists since 21). */
private fun MediaCodecInfo.supportsType(mime: String): Boolean =
    supportedTypes.any { it.equals(mime, ignoreCase = true) }
