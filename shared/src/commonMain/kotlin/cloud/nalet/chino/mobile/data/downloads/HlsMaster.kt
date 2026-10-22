package cloud.nalet.chino.mobile.data.downloads

/*
 * An HLS master playlist as a download reads it: the variants it offers (a
 * picture size and the bit rate it takes) and the audio and subtitle
 * renditions beside them. Pure, so commonTest pins it, and numbered as Media3
 * numbers them - its HlsMultivariantPlaylist, whose StreamKeys a download
 * keeps - so an index here is the stream key's index there.
 */

/** One `#EXT-X-STREAM-INF` of a master: a variant, at [uri]. */
data class HlsVariant(
    /** Its place among the master's variants: Media3's
     *  HlsMultivariantPlaylist.GROUP_INDEX_VARIANT stream index. */
    val index: Int,
    /** As the master writes it (relative, query and all). */
    val uri: String,
    /** BANDWIDTH: the peak bit rate, its audio and subtitles included. */
    val bandwidth: Long,
    /** AVERAGE-BANDWIDTH: the mean bit rate over the whole title; null where
     *  the master leaves it out. */
    val averageBandwidth: Long?,
    val width: Int?,
    val height: Int?,
    val codecs: String?,
    /** The GROUP-ID of the audio renditions it plays with (AUDIO=). */
    val audioGroup: String?,
) {
    /** The bit rate a whole title takes, for its size: the average where the
     *  master says it, else the peak. */
    val sizeBitrate: Long get() = averageBandwidth?.takeIf { it > 0 } ?: bandwidth
}

/** One `#EXT-X-MEDIA` of a master with a URI: an audio or a subtitle
 *  rendition. */
data class HlsRendition(
    /** Its place among the master's renditions of its type: Media3's
     *  GROUP_INDEX_AUDIO / GROUP_INDEX_SUBTITLE stream index. */
    val index: Int,
    val uri: String,
    val groupId: String,
    val language: String?,
    val name: String?,
    val default: Boolean,
    val forced: Boolean,
    val channels: String?,
)

/** A master playlist: its variants, then its audio and subtitle renditions,
 *  each in the order the master lists them. */
data class HlsMaster(
    val variants: List<HlsVariant>,
    val audios: List<HlsRendition>,
    val subtitles: List<HlsRendition>,
) {
    /** The audio renditions [variant] plays with, in the master's order. */
    fun audiosOf(variant: HlsVariant): List<HlsRendition> =
        variant.audioGroup?.let { group -> audios.filter { it.groupId == group } } ?: emptyList()
}

/**
 * Reads a master playlist. Variants are kept once per URI, the first time
 * the master names it, and a rendition only with a URI - as Media3's parser
 * keeps them, so the indices match its stream keys. I-frame playlists,
 * session data and unknown tags are passed over. A text that is not a
 * master gives an empty one.
 */
fun parseHlsMaster(text: String): HlsMaster {
    val variants = ArrayList<HlsVariant>()
    val seenVariantUris = HashSet<String>()
    val audios = ArrayList<HlsRendition>()
    val subtitles = ArrayList<HlsRendition>()
    var pendingVariant: Map<String, String>? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        when {
            line.startsWith(STREAM_INF) -> pendingVariant = hlsAttributes(line.substring(STREAM_INF.length))
            line.startsWith(MEDIA) -> {
                val attrs = hlsAttributes(line.substring(MEDIA.length))
                val uri = attrs["URI"]?.takeIf { it.isNotBlank() } ?: continue
                val type = attrs["TYPE"]
                val target = when (type) {
                    "AUDIO" -> audios
                    "SUBTITLES" -> subtitles
                    else -> continue
                }
                target += HlsRendition(
                    index = target.size,
                    uri = uri,
                    groupId = attrs["GROUP-ID"].orEmpty(),
                    language = attrs["LANGUAGE"]?.takeIf { it.isNotBlank() },
                    name = attrs["NAME"]?.takeIf { it.isNotBlank() },
                    default = attrs["DEFAULT"].equals("YES", ignoreCase = true),
                    forced = attrs["FORCED"].equals("YES", ignoreCase = true),
                    channels = attrs["CHANNELS"]?.takeIf { it.isNotBlank() },
                )
            }
            line.startsWith("#") -> Unit
            else -> {
                // A URI line: the variant the last EXT-X-STREAM-INF described.
                val attrs = pendingVariant ?: continue
                pendingVariant = null
                if (!seenVariantUris.add(line)) continue
                val (width, height) = resolution(attrs["RESOLUTION"])
                variants += HlsVariant(
                    index = variants.size,
                    uri = line,
                    bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                    averageBandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
                    width = width,
                    height = height,
                    codecs = attrs["CODECS"],
                    audioGroup = attrs["AUDIO"]?.takeIf { it.isNotBlank() },
                )
            }
        }
    }
    return HlsMaster(variants, audios, subtitles)
}

private const val STREAM_INF = "#EXT-X-STREAM-INF:"
private const val MEDIA = "#EXT-X-MEDIA:"

/** `KEY=value,KEY="quoted, with commas"` as a map, quotes taken off. */
internal fun hlsAttributes(list: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var i = 0
    while (i < list.length) {
        val eq = list.indexOf('=', i)
        if (eq < 0) break
        val key = list.substring(i, eq).trim().trimStart(',').trim()
        var j = eq + 1
        val value: String
        if (j < list.length && list[j] == '"') {
            val close = list.indexOf('"', j + 1).let { if (it < 0) list.length else it }
            value = list.substring(j + 1, close)
            j = close + 1
        } else {
            val comma = list.indexOf(',', j).let { if (it < 0) list.length else it }
            value = list.substring(j, comma).trim()
            j = comma
        }
        if (key.isNotEmpty()) out[key] = value
        // Past the comma after the value.
        i = if (j < list.length && list[j] == ',') j + 1 else j
        if (i < list.length && list[i] == ',') i++
    }
    return out
}

/** RESOLUTION=1280x720 as its width and height; nulls where it is missing
 *  or not two numbers. */
private fun resolution(value: String?): Pair<Int?, Int?> {
    val parts = value?.lowercase()?.split('x') ?: return null to null
    if (parts.size != 2) return null to null
    return parts[0].trim().toIntOrNull() to parts[1].trim().toIntOrNull()
}
