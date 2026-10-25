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
 * The quality menu's rows, as the Android player lists /play/info's
 * qualities: one per name - the server's, else [labelForQuality]'s - each
 * with the `q`s that name alike, the first the one picked: those are one
 * picture ("Low (480p)" three times for a 480p source is one row). Fewer
 * than two rows is nothing to switch to, and no quality button.
 */
fun qualityMenuRows(listed: List<cloud.nalet.chino.mobile.data.api.QualityRung>): List<Pair<String, List<cloud.nalet.chino.mobile.data.api.QualityRung>>> =
    listed
        .map { rung -> rung to (rung.label.takeIf { it.isNotBlank() }?.let(::namedQuality) ?: labelForQuality(rung.name)) }
        .groupBy { it.second.lowercase() }
        .values
        .map { same -> same.first().second to same.map { it.first } }

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
    // By its step, as the server names a height alone: "1079p" is 1080p.
    return qualityTitle(namedHeight(0, height))
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

/** The standard picture heights (docs/chino playback, chino-stream's
 *  sizeClasses). */
private val SIZE_CLASSES = listOf(240, 360, 480, 540, 576, 720, 1080, 1440, 2160, 4320)

/**
 * The height a [width] x [height] frame is named by, its step (docs/chino
 * playback, "Quality names"), as chino-stream's namedHeight (7f546e1) and
 * chino-web's: its own height, or the height its width stands for at 16:9,
 * whichever is larger - a 2.39:1 film's 1920x800 is 1080p. A step up to
 * 10 % above a standard height, in a frame no taller than it, counts as that
 * height (2048x858 is 1080p, DCI 4096x2160 2160p), and so does a step up to
 * 3 % below one, whatever the frame's shape - a standard frame cropped at the
 * sides or a few lines short (1916x800, 1904x1072 are 1080p, 1272x716
 * 720p); any other step is its own height (1666x900 is 937p, 1728x720
 * 972p, 1848x1040 1040p). A width not known leaves the height as the step;
 * 0 without a height.
 */
fun namedHeight(width: Int, height: Int): Int {
    if (height <= 0) return 0
    val step = if (width > 0) maxOf(height, (width * 9 + 8) / 16) else height
    for (c in SIZE_CLASSES.asReversed()) if (c >= height && c <= step && step * 10 <= c * 11) return c
    for (c in SIZE_CLASSES) if (c > step && step * 100 >= c * 97) return c
    return step
}

/** A frame's name, as the server names one: the table's name of its step
 *  ([namedHeight]), "High (1080p)" for 1920x800, "Medium (937p)" for
 *  1666x900; null without a height. */
fun frameQualityName(width: Int, height: Int): String? = namedHeight(width, height).takeIf { it > 0 }?.let(::qualityTitle)

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
