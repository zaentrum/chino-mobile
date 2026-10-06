package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.PlayInfo

/** `?q=` that leaves the rendition to the player's own adaptive pick, where
 *  the server lists packaged rungs. */
const val AUTO_QUALITY = "auto"

/** One entry of the quality menu: the `?q=` value and what it reads as. */
data class QualityOption(val id: String, val label: String)

/** The quality menu and the `?q=` playback starts with. The menu shows only
 *  when there is more than one option to pick from. */
data class QualityLadder(val options: List<QualityOption>, val initial: String) {
    val pickable: Boolean get() = options.size > 1
}

/**
 * What /play/info offers to pick from. A packaged title whose renditions the
 * server lists (`rungs`) plays at [AUTO_QUALITY] and offers Auto plus each
 * rung, tallest first. Otherwise the on-the-fly ladder (`qualities`: high /
 * medium / low, for a transcode) at [startQuality] — what the Android player
 * asks for.
 */
fun qualityLadder(info: PlayInfo?): QualityLadder {
    val rungs = info?.rungs.orEmpty().filter { it.id.isNotBlank() }
    if (rungs.isNotEmpty()) {
        val options = rungs
            .sortedByDescending { it.height ?: 0 }
            .map { r ->
                QualityOption(
                    id = r.id,
                    label = r.label?.takeIf { it.isNotBlank() }
                        ?: r.height?.takeIf { it > 0 }?.let { "${it}p" }
                        ?: r.id,
                )
            }
        return QualityLadder(listOf(QualityOption(AUTO_QUALITY, "Auto")) + options, AUTO_QUALITY)
    }
    val options = info?.qualities.orEmpty().map { q ->
        QualityOption(q.name, q.label.takeIf { it.isNotBlank() } ?: labelForQuality(q.name))
    }
    return QualityLadder(options, startQuality(info))
}

/**
 * The `?q=` a title's playback starts at without a rung of the menu to start
 * on: the server's `default_quality`, else "high", the top of the on-the-fly
 * ladder. Without play info at all - /play/info failed or ran out of its 8 s
 * - [AUTO_QUALITY]: the master decides what the device is served, from its
 * caps, never the top rung by default (it handed a phone without a hardware
 * HEVC decoder 1080p HEVC). The Android player starts here; the iOS ladder
 * where the server lists no rungs.
 */
fun startQuality(info: PlayInfo?): String =
    if (info == null) AUTO_QUALITY else info.defaultQuality?.takeIf { it.isNotBlank() } ?: "high"

/** The fallback name of an on-the-fly rung ("high" -> "1080p"), as the
 *  Android and TV players show a bare rung. */
fun labelForQuality(q: String): String = when (q.lowercase()) {
    "high" -> "1080p"
    "medium" -> "720p"
    "low" -> "480p"
    "source" -> "Source"
    AUTO_QUALITY -> "Auto"
    else -> q
}

/**
 * chino-stream's master playlist URL, as the Android player builds it:
 * `?stream=` first, then `&caps=` (only when non-empty), then `&q=` - none at
 * [AUTO_QUALITY] ([withQuality], as an extra's): without one the master
 * decides on every path, a packaged title's ladder for the device, an
 * on-the-fly title's default, a passthrough's stream copy - which `q=auto`
 * turned into a transcode, chino-stream copying only without a `q` or at
 * "high". The stream token is the long-lived one (/me/stream-token), so the
 * URL survives silent OIDC renewals; chino-stream appends the same query to
 * every rendition, segment and audio URI in the playlists.
 */
fun buildMasterUrl(apiBase: String, itemId: String, streamToken: String, quality: String, caps: String): String =
    withQuality(
        buildString {
            append(apiBase.trimEnd('/'))
            append("/v1/items/").append(itemId).append("/play/master.m3u8?stream=").append(streamToken)
            if (caps.isNotEmpty()) append("&caps=").append(caps)
        },
        quality,
    )
