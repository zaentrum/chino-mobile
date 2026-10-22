package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.ui.player.SubtitleKind
import cloud.nalet.chino.mobile.ui.player.normalizeLang
import cloud.nalet.chino.mobile.ui.player.sidecarForced
import cloud.nalet.chino.mobile.ui.player.subtitleKind

/*
 * What a download keeps of a title: one video rendition, picked by the
 * download quality; the audio in the viewer's language and the original
 * audio; the subtitles in the viewer's languages - and what that comes to on
 * the phone. Pure, so commonTest pins every rule.
 */

/** The picture sizes quality names stand for, as the player's quality menu
 *  names a packaged title's renditions ("720p" is the 1280x720 box). */
val SIZE_CLASSES: List<Int> = listOf(240, 360, 480, 540, 576, 720, 1080, 1440, 2160, 4320)

/** The download quality a phone starts with: the 720p rendition, or the
 *  nearest below it ([pickVariant]). */
const val DEFAULT_DOWNLOAD_QUALITY = 720

/**
 * The size class of a [width] x [height] picture - the smallest class whose
 * 16:9 box holds it, with 10 % of width to spare for a DCI frame (4096x2160
 * is 2160p) - as chino-stream labels a packaged title's rungs (rungLabel),
 * so a 2.39:1 film's 1280x536 rung is 720p. Its height when no class holds
 * it; null without a height.
 */
fun sizeClass(width: Int?, height: Int?): Int? {
    val h = height?.takeIf { it > 0 } ?: return null
    val w = width ?: 0
    for (c in SIZE_CLASSES) {
        val boxW = (c * 16 / 9 + 1) and 1.inv()
        if (h <= c && w * 10 <= boxW * 11) return c
    }
    return h
}

/** A quality as the player's menu names it: "720p". */
fun qualityName(sizeClass: Int): String = "${sizeClass}p"

/**
 * The variant a download takes at the [quality] size class: the tallest one
 * not taller than it, else - every variant is taller - the shortest. Among
 * variants of the same class the master's first, which is the one the server
 * puts first for this device. Null for a master without variants.
 */
fun pickVariant(variants: List<HlsVariant>, quality: Int): HlsVariant? {
    if (variants.isEmpty()) return null
    val classed = variants.map { it to (sizeClass(it.width, it.height) ?: 0) }
    val fitting = classed.filter { (_, c) -> c in 1..quality }
    val pool = fitting.ifEmpty { classed }
    val target = if (fitting.isNotEmpty()) pool.maxOf { it.second } else pool.minOf { it.second }
    return pool.first { it.second == target }.first
}

/**
 * The audio a download keeps, of the renditions its variant plays with: the
 * one in the viewer's language - the player's rule (preferredAudioTrack):
 * the preferred language ("orig" keeps the title's own), else the default,
 * else the first - and the original audio, the master's DEFAULT=YES one
 * (else its first), when that is another. In that order; empty without
 * renditions.
 */
fun pickAudios(renditions: List<HlsRendition>, audioPref: String?): List<HlsRendition> {
    if (renditions.isEmpty()) return emptyList()
    val original = renditions.firstOrNull { it.default } ?: renditions.first()
    val want = if (audioPref.isNullOrBlank() || audioPref.equals("orig", ignoreCase = true)) "" else normalizeLang(audioPref)
    val preferred = want.takeIf { it.isNotEmpty() }
        ?.let { w -> renditions.firstOrNull { normalizeLang(it.language) == w } }
        ?: original
    return if (preferred == original) listOf(original) else listOf(preferred, original)
}

/**
 * The sidecar subtitles a download keeps: the text ones (WebVTT, SubRip -
 * served as .vtt) in the viewer's languages - the subtitle language set in
 * Settings, and the audio language they prefer - and the forced ones in the
 * language of each audio it keeps, which the player turns on for the lines a
 * film shows in another language. In the server's order, each once.
 */
fun pickSubtitles(
    sidecars: List<SidecarSubtitle>,
    audioLanguages: List<String?>,
    audioPref: String?,
    subtitlePref: String?,
): List<SidecarSubtitle> {
    val viewer = buildSet {
        subtitlePref?.takeUnless { it.isBlank() || it.equals("off", ignoreCase = true) }?.let { add(normalizeLang(it)) }
        audioPref?.takeUnless { it.isBlank() || it.equals("orig", ignoreCase = true) }?.let { add(normalizeLang(it)) }
    } - ""
    val audio = audioLanguages.map { normalizeLang(it) }.filter { it.isNotEmpty() }.toSet()
    return sidecars.filter { s ->
        if (s.id.isBlank() || subtitleKind(s.format) != SubtitleKind.Text) return@filter false
        val lang = normalizeLang(s.lang)
        lang.isNotEmpty() && (lang in viewer || (sidecarForced(s) && lang in audio))
    }.distinctBy { it.id }
}

/** What a stereo audio rendition beyond the first takes: the variant's
 *  AVERAGE-BANDWIDTH holds its video and one audio rendition, a second one
 *  is not in it. A package's stereo renditions are AAC at this rate. */
const val EXTRA_AUDIO_BPS = 192_000L

/**
 * What a download of [variant] with [audioCount] audio renditions comes to
 * for a title [durationMs] long: its bit rate (AVERAGE-BANDWIDTH, which
 * holds the video, one audio rendition and the subtitles) over the length,
 * and [EXTRA_AUDIO_BPS] for each audio rendition beyond the first. Null when
 * the length or the bit rate is not known.
 */
fun estimateBytes(variant: HlsVariant, audioCount: Int, durationMs: Long?): Long? {
    val ms = durationMs?.takeIf { it > 0 } ?: return null
    val bps = variant.sizeBitrate.takeIf { it > 0 } ?: return null
    val extra = (audioCount - 1).coerceAtLeast(0) * EXTRA_AUDIO_BPS
    return (bps + extra) * ms / 8_000L
}

/** What an hour at [bitsPerSecond] takes on the phone. */
fun bytesPerHour(bitsPerSecond: Long): Long = bitsPerSecond * 3_600L / 8L

/**
 * A size as the app writes it: GB with one decimal from 1 GB up ("1.4 GB"),
 * MB below it ("820 MB"), decimal units as a phone's storage counts them.
 * Never "0 MB" for something there is: "1 MB".
 */
fun formatSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    val mb = (b + MB / 2) / MB
    // 999.6 MB rounds to a GB: "1.0 GB", not "1000 MB".
    if (b >= GB || mb >= 1_000L) {
        val tenths = (b * 10 + GB / 2) / GB
        return "${tenths / 10}.${tenths % 10} GB"
    }
    return "${mb.coerceAtLeast(if (b > 0) 1 else 0)} MB"
}

private const val MB = 1_000_000L
private const val GB = 1_000_000_000L
