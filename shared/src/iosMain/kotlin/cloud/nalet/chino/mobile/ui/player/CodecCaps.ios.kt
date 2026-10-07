package cloud.nalet.chino.mobile.ui.player

import platform.AVFoundation.AVURLAsset
import platform.CoreMedia.kCMVideoCodecType_AV1
import platform.CoreMedia.kCMVideoCodecType_HEVC
import platform.CoreMedia.kCMVideoCodecType_VP9
import platform.VideoToolbox.VTIsHardwareDecodeSupported

/**
 * The iOS `?caps=` value for chino-stream ([CodecCapsQuery]), worked out once
 * from what the platform plays natively — what the AVPlayer-based player will
 * put on its master URL, and what Zap's prewarm sends today.
 *
 * Video: `avc` always (every device iOS 15 runs on decodes H.264 in
 * hardware); `hvc` and `av1` when VideoToolbox reports a hardware decoder.
 * No API reports a decoder's height ceiling, so the tokens go bare.
 *
 * Audio: `aac` and `mp3` always — AVFoundation decodes both on every device.
 * `ac3` / `eac3` only when AVFoundation itself says it plays AC-3 / E-AC-3 in
 * MP4 (`AVURLAsset.isPlayableExtendedMIMEType`), so a device that cannot
 * decode Dolby audio never claims it. `opus` stays out: AVFoundation's answer
 * covers MP4 files, and whether AVPlayer plays Opus copied into chino-stream's
 * HLS segments is untried — the server's AAC always plays. `vorbis` and
 * `aacmc` stay out as on Android.
 *
 * And `native`: AVPlayer picks between audio groups itself, so chino-stream
 * serves it Apple's shape — with `eac3`, the stereo group and a 5.1 group of
 * the same members under the same names, each played by its companion where
 * it has one. AVPlayer lists each member once and plays the group that suits
 * the output — the 5.1 one where it takes multichannel sound. Without
 * `native` the companions came in one group with their stereo twins, two
 * options of one track. The master, /play/info and /prewarm all carry this
 * one value.
 */
internal object CodecCaps {
    val queryParam: String by lazy { CodecCapsQuery.build(videoCaps(), audioTokens(), native = true) }
}

private fun videoCaps(): List<VideoDecoderCaps> = buildList {
    add(VideoDecoderCaps("avc"))
    if (VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)) add(VideoDecoderCaps("hvc"))
    if (VTIsHardwareDecodeSupported(kCMVideoCodecType_AV1)) add(VideoDecoderCaps("av1"))
}

private fun audioTokens(): Set<String> = buildSet {
    add("aac")
    add("mp3")
    if (playsInMp4("ac-3")) add("ac3")
    if (playsInMp4("ec-3")) add("eac3")
}

/** AVFoundation's own answer for an MP4 audio track in [codec] (RFC 6381). */
private fun playsInMp4(codec: String): Boolean =
    AVURLAsset.isPlayableExtendedMIMEType("audio/mp4; codecs=\"$codec\"")

/** The player's "This device can decode" list (chino-web's CODEC_PROBES),
 *  answered by VideoToolbox (video, hardware) and AVFoundation (audio). */
internal fun iosDecodeProbes(): List<Pair<String, Boolean>> = listOf(
    "H.264 (AVC)" to true,
    "H.265 (HEVC)" to VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC),
    "VP9" to VTIsHardwareDecodeSupported(kCMVideoCodecType_VP9),
    "AV1" to VTIsHardwareDecodeSupported(kCMVideoCodecType_AV1),
    "AAC" to true,
    "MP3" to true,
    "Opus" to playsInMp4("opus"),
    "AC-3" to playsInMp4("ac-3"),
    "E-AC-3" to playsInMp4("ec-3"),
    "DTS" to playsInMp4("dtsc"),
)
