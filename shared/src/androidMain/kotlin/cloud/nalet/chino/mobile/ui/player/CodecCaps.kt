package cloud.nalet.chino.mobile.ui.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * Comma-separated codec tokens chino-stream's ParseCaps consumes
 * (`avc` / `hvc` / `av1`), computed once from the device's decoder list.
 *
 * Each video token now carries an optional `:<maxHeight>` suffix = the tallest
 * frame the device's **hardware** decoder can handle for that codec
 * (e.g. `avc:1080,hvc:1080,av1:2160`). A bare token (no suffix) means the codec
 * is decodable but only in **software** — no known HW height ceiling to report.
 *
 * Without this the mobile sent no caps and the server fell back to H.264 —
 * which some hardware decoders reject for non-16-aligned heights (e.g. the
 * 1920x1036 stream that failed to init OMX.qcom.video.decoder.avc on a
 * Samsung SM-T500, forcing slow software decode). Advertising `hvc` lets the
 * server serve the HEVC rendition the device can decode in hardware.
 *
 * The height ceiling fixes the inverse hazard: a device advertises HEVC, the
 * server serves a 4K HEVC package, but the HW decoder tops out at 1080 ->
 * silent fallback to the software decoder -> unplayably slow (the SM-T500 Zap
 * bug). Reporting the HW height ceiling lets the server route oversized
 * packages through the transcode ladder (which downscales) instead.
 * Mirrors chino-androidtv's CodecCaps so both clients negotiate the same way.
 */
internal object CodecCaps {
    val queryParam: String by lazy {
        // Token -> mime, in the legacy emit order.
        val videoCodecs = listOf(
            "avc" to "video/avc",
            "hvc" to "video/hevc",
            "av1" to "video/av01",
        )
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        buildList {
            for ((token, mime) in videoCodecs) {
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
                when {
                    // Hardware decoder present: advertise the codec + its height ceiling.
                    maxHwHeight > 0 -> add("$token:$maxHwHeight")
                    // Only a software decoder: advertise bare token (legacy form), never drop it.
                    sawDecoder -> add(token)
                    // No decoder at all: omit.
                }
            }
        }.joinToString(",")
    }
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
