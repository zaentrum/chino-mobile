package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.PlayInfo
import cloud.nalet.chino.mobile.data.api.QualityRung
import cloud.nalet.chino.mobile.data.api.TrackInfo
import cloud.nalet.chino.mobile.data.api.artworkUrl
import cloud.nalet.chino.mobile.data.model.Extra
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.Trailer
import cloud.nalet.chino.mobile.ui.trailer.pickTrailer
import kotlinx.coroutines.CancellationException

/*
 * One of a title's extras — its trailer — in the player ([PlayerMode.Extra]):
 * where its master is, and what the player knows of it. chino-api serves an
 * extra's master and its renditions and nothing else, so what a title's
 * /play/info tells the player — the rungs to pick from, the codecs, the audio
 * — is read off the master itself, as chino-stream reads a packaged title's
 * (internal/play/ladder.go: parseMaster, packagedQualities, rungLabel).
 */

/** What the player plays in extra mode. */
sealed interface ExtraPlayback {
    /**
     * It plays: [masterUrl] (Auto, the extra's ladder for the device), under
     * [heading] ("Sintel · Trailer"), with [info] read off the master
     * ([extraPlayInfo]). [item] is the title's detail (its poster for Now
     * Playing); [link] its trailer link, offered should the master answer
     * 404 after all. [streamToken] signs the master, the session's token.
     */
    data class Ready(
        val masterUrl: String,
        val heading: String,
        val info: PlayInfo,
        val item: Item,
        val link: Trailer?,
        val streamToken: String,
    ) : ExtraPlayback

    /** There is no such extra to play: the title or the extra is gone, the
     *  viewer's rating cap hides the title, or the master answered 404.
     *  [title] is the title's name and [link] its trailer link, when known. */
    data class NotAvailable(val title: String?, val link: Trailer?) : ExtraPlayback
}

/**
 * The extra [extraId] of the title [itemId], as the title's detail lists it,
 * and its master as this device is served it — the two requests an extra
 * makes ([PlayerMode.Extra]): no progress, watched, play info, segments,
 * trickplay, subtitles, episodes or prewarm. A 404 for the title (gone, or
 * above the viewer's rating cap), an extra the detail no longer lists and a
 * master that answers 404 are [ExtraPlayback.NotAvailable]; any other failure
 * of the detail throws. A master that cannot be read otherwise plays as it is
 * served, without a quality menu: the player says whatever is wrong with it.
 * The master is signed with [streamToken] for the extra's session - its
 * length from the head ([sessionLifeMs]), asked for once the detail gives it.
 */
internal suspend fun loadExtraPlayback(
    api: ChinoApi,
    apiBase: String,
    streamToken: suspend (lifeMs: Long) -> String,
    caps: String,
    itemId: String,
    extraId: String,
): ExtraPlayback {
    val item = try {
        api.getItem(itemId)
    } catch (e: ApiStatusException) {
        if (e.status == 404) return ExtraPlayback.NotAvailable(title = null, link = null)
        throw e
    }
    val link = pickTrailer(item.trailers)
    val extra = item.extras.firstOrNull { it.id == extraId && it.playable }
        ?: return ExtraPlayback.NotAvailable(item.title, link)
    val token = streamToken(sessionLifeMs(extra.durationMs, startMs = 0L))
    val url = extraMasterUrl(apiBase, extra.playPath, token, caps)
        ?: return ExtraPlayback.NotAvailable(item.title, link)
    val master = try {
        api.hlsMaster(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiStatusException) {
        if (e.status == 404) return ExtraPlayback.NotAvailable(item.title, link)
        null
    } catch (e: Exception) {
        null
    }
    return ExtraPlayback.Ready(
        masterUrl = url,
        heading = "${item.title} · ${extraLabel(extra)}",
        info = extraPlayInfo(master, extra.durationMs),
        item = item,
        link = link,
        streamToken = token,
    )
}

/** What an extra is called in the heading: its title, else its kind
 *  ("Teaser"), else "Trailer". */
fun extraLabel(extra: Extra): String =
    extra.title.trim().ifEmpty { extra.kind.trim().replaceFirstChar { it.uppercase() } }.ifEmpty { "Trailer" }

/**
 * An extra's master URL: its [playPath], from the server root, against the
 * API base [apiBase] with the stream token ([artworkUrl], the asset helper),
 * the device's [caps], and `q` for a rung the viewer picked — as a title's
 * master is asked for. [AUTO_QUALITY] asks for no `q`: chino-stream serves
 * the ladder, the variant to start on first. Null without a [playPath].
 */
fun extraMasterUrl(
    apiBase: String,
    playPath: String,
    streamToken: String,
    caps: String,
    quality: String = AUTO_QUALITY,
): String? {
    var url = artworkUrl(apiBase, playPath, streamToken) ?: return null
    if (caps.isNotEmpty()) url += (if ('?' in url) '&' else '?') + "caps=" + caps
    return withQuality(url, quality)
}

/** [masterUrl] (an extra's, at Auto) at [quality]: with `q=` for a rung,
 *  as it is for [AUTO_QUALITY] or none. */
fun withQuality(masterUrl: String, quality: String): String =
    if (quality.isBlank() || quality.equals(AUTO_QUALITY, ignoreCase = true)) {
        masterUrl
    } else {
        masterUrl + (if ('?' in masterUrl) '&' else '?') + "q=" + quality
    }

/**
 * What /play/info says of a packaged title, read off an extra's [master] as
 * chino-stream's writePackagedInfo reads a title's: packaged; the rungs to
 * pick from, Auto first, then each picture size once, the tallest first —
 * none for fewer than two ([QualityRung.name] is the `q` that asks for the
 * rung alone); the video, its size and the audio of the variant playback
 * starts on, the first; the audio renditions of its group, in the master's
 * order. [durationMs] is the extra's (the title's detail lists it). Without a
 * master, the mode and the duration alone.
 */
fun extraPlayInfo(master: String?, durationMs: Long?): PlayInfo {
    val parsed = master?.let { parseHlsMaster(it) }
    val first = parsed?.variants?.firstOrNull()
    val audio = parsed?.audio.orEmpty().filter { first?.audioGroup == null || it.group == first.audioGroup }
    val audioCodec = first?.audioCodecs?.firstOrNull()
    return PlayInfo(
        container = if (first != null) "cmaf" else null,
        videoCodec = first?.videoCodec,
        audioCodec = audioCodec?.let { if (it.startsWith("mp4a", ignoreCase = true)) "aac" else it },
        width = first?.width?.takeIf { it > 0 },
        height = first?.height?.takeIf { it > 0 },
        durationMs = durationMs?.takeIf { it > 0 },
        mode = "packaged",
        qualities = parsed?.let { masterQualities(it) }.orEmpty(),
        defaultQuality = AUTO_QUALITY,
        audioTracks = audio.mapIndexed { i, a ->
            TrackInfo(
                index = i,
                codec = audioCodec?.substringBefore('.'),
                language = a.language,
                title = a.name,
                default = a.default,
                channels = a.channels,
            )
        },
    )
}

/** The quality menu of a master, as chino-stream's packagedQualities lists
 *  a packaged title's: Auto, then one rung per picture size ([rungLabel]),
 *  the tallest first; nothing for fewer than two. */
private fun masterQualities(master: HlsMaster): List<QualityRung> {
    val picks = master.rungs().distinctBy { rungLabel(it.rung, it.width, it.height) }
    if (picks.size < 2) return emptyList()
    return listOf(QualityRung(AUTO_QUALITY, "Auto")) +
        picks.sortedByDescending { it.height }.map { QualityRung(it.rung, rungLabel(it.rung, it.width, it.height)) }
}

/** The picture heights quality labels name (chino-stream's sizeClasses). */
private val SIZE_CLASSES = listOf(240, 360, 480, 540, 576, 720, 1080, 1440, 2160, 4320)

/**
 * A rung's name, as chino-stream's rungLabel names it ("720p" = the 1280x720
 * box): the smallest class whose 16:9 box holds the frame, 10 % of width to
 * spare for DCI frames — a 2.39:1 film's 1280x536 rung is 720p. [id] when the
 * height is not known.
 */
fun rungLabel(id: String, width: Int, height: Int): String {
    if (height <= 0) return id
    for (c in SIZE_CLASSES) {
        val boxWidth = (c * 16 / 9 + 1) and 1.inv()
        if (height <= c && width * 10 <= boxWidth * 11) return "${c}p"
    }
    return "${height}p"
}

/** One EXT-X-STREAM-INF of a master and its URI. */
internal data class MasterVariant(
    /** The URI's first path segment, "v0": the rung, the `q` that asks for it. */
    val rung: String,
    val width: Int,
    val height: Int,
    /** The video's CODECS entry ("avc1.64001f"), null when it lists none. */
    val videoCodec: String?,
    /** The CODECS entries that are not the video's ("mp4a.40.2"). */
    val audioCodecs: List<String>,
    /** Its AUDIO group id. */
    val audioGroup: String?,
)

/** One EXT-X-MEDIA TYPE=AUDIO line of a master. */
internal data class MasterAudio(
    val group: String?,
    val name: String?,
    val language: String?,
    val channels: Int?,
    val default: Boolean,
)

/** A master's variants and audio renditions, in its order. */
internal class HlsMaster(val variants: List<MasterVariant>, val audio: List<MasterAudio>) {
    /** Each video rung once, as its first variant lists it (the stereo
     *  group's — the packager writes it first), the top rung first. */
    fun rungs(): List<MasterVariant> = variants.filter { it.rung.isNotEmpty() }.distinctBy { it.rung }
}

/** The CODECS entries that are a video's (chino-stream's videoCodecs). */
private val VIDEO_CODEC_PREFIXES = listOf(
    "avc1", "avc3", "hvc1", "hev1", "dvh1", "dvhe", "dva1", "dvav", "dav1", "vp08", "vp09", "av01", "mp4v",
)

internal fun parseHlsMaster(body: String): HlsMaster {
    val variants = ArrayList<MasterVariant>()
    val audio = ArrayList<MasterAudio>()
    val lines = body.lines().map { it.trimEnd('\r') }
    for ((i, line) in lines.withIndex()) {
        when {
            line.startsWith("#EXT-X-STREAM-INF:") -> {
                val a = hlsAttributes(line)
                val codecs = a["CODECS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val video = codecs.firstOrNull { c -> VIDEO_CODEC_PREFIXES.any { c.startsWith(it, ignoreCase = true) } }
                val (width, height) = a["RESOLUTION"].orEmpty().split('x').let { wh ->
                    if (wh.size == 2) (wh[0].toIntOrNull() ?: 0) to (wh[1].toIntOrNull() ?: 0) else 0 to 0
                }
                val uri = lines.drop(i + 1).map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()
                variants += MasterVariant(
                    rung = uri.substringBefore('?').substringBefore('/'),
                    width = width,
                    height = height,
                    videoCodec = video,
                    audioCodecs = codecs.filter { it != video },
                    audioGroup = a["AUDIO"],
                )
            }
            line.startsWith("#EXT-X-MEDIA:") -> {
                val a = hlsAttributes(line)
                if (a["TYPE"] == "AUDIO") {
                    audio += MasterAudio(
                        group = a["GROUP-ID"],
                        name = a["NAME"],
                        language = a["LANGUAGE"],
                        channels = a["CHANNELS"]?.substringBefore('/')?.toIntOrNull(),
                        default = a["DEFAULT"] == "YES",
                    )
                }
            }
        }
    }
    return HlsMaster(variants, audio)
}

/** A tag line's attributes, KEY=value, a quoted value without its quotes
 *  (it may hold commas). */
private fun hlsAttributes(line: String): Map<String, String> {
    val out = HashMap<String, String>()
    var i = line.indexOf(':').takeIf { it >= 0 }?.plus(1) ?: return out
    while (i < line.length) {
        val eq = line.indexOf('=', i)
        if (eq < 0) break
        val key = line.substring(i, eq).trim()
        var end: Int
        val value = if (eq + 1 < line.length && line[eq + 1] == '"') {
            val close = line.indexOf('"', eq + 2).let { if (it < 0) line.length else it }
            end = close + 1
            line.substring(eq + 2, close)
        } else {
            end = line.indexOf(',', eq + 1).let { if (it < 0) line.length else it }
            line.substring(eq + 1, end)
        }
        out[key] = value
        end = line.indexOf(',', end)
        i = if (end < 0) line.length else end + 1
    }
    return out
}
