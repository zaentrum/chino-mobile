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
                    label = namedQuality(
                        r.label?.takeIf { it.isNotBlank() }
                            ?: r.height?.takeIf { it > 0 }?.let { "${it}p" }
                            ?: r.id,
                    ),
                )
            }
        return QualityLadder(listOf(QualityOption(AUTO_QUALITY, "Auto")) + options, AUTO_QUALITY)
    }
    // The server's names as it gives them; two `q` it names alike are one
    // picture, and one entry - the first, the higher.
    val options = info?.qualities.orEmpty().map { q ->
        QualityOption(q.name, q.label.takeIf { it.isNotBlank() }?.let(::namedQuality) ?: labelForQuality(q.name))
    }.distinctBy { it.label.lowercase() }
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

/** The name of an on-the-fly `q` an older server sent without a label: a
 *  height by the server's table ([namedQuality]: "720p" -> "Medium
 *  (720p)"), "Original" for the original played as it is, else the bare
 *  word ("High", "Medium", "Low") - no height the phone would only guess:
 *  a step's height is the source's, which only the server knows. */
fun labelForQuality(q: String): String = when (q.lowercase()) {
    "source", "original" -> "Original"
    AUTO_QUALITY -> "Auto"
    else -> namedQuality(q).let { n -> if (n == q) q.replaceFirstChar { it.uppercase() } else n }
}

/**
 * A quality as every app names it (docs/chino words): the server's label as
 * it gives it ("Medium (720p)", "Original"); an older server's label that
 * names a height alone ("720p") gets its name by the same table
 * ([qualityTitle]) - a height never twice.
 */
fun namedQuality(label: String): String {
    val height = Regex("""^(\d{3,4})p$""").matchEntire(label.trim())?.groupValues?.get(1)?.toInt() ?: return label
    return qualityTitle(height)
}

/** The name of a picture height's quality, with the height, by the one
 *  table the server names its qualities by: Ultra from 2160p, High from
 *  1080p, Medium from 720p, Low from 480p, Lowest below it; a height between
 *  two steps takes the lower name with its own height ("Medium (900p)"). */
fun qualityTitle(height: Int): String {
    val name = when {
        height >= 2160 -> "Ultra"
        height >= 1080 -> "High"
        height >= 720 -> "Medium"
        height >= 480 -> "Low"
        else -> "Lowest"
    }
    return "$name (${height}p)"
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
