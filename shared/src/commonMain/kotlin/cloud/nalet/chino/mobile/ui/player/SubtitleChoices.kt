package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.api.TrackInfo
import cloud.nalet.chino.mobile.data.api.artworkUrl

/** How a subtitle format can be shown by a player that draws text cues. */
enum class SubtitleKind {
    /** WebVTT / SRT: cues the overlay draws. */
    Text,

    /** PGS (Blu-ray) pictures. */
    Pgs,

    /** Other picture formats (VobSub, DVB, XSUB). */
    Bitmap,
}

private val PGS_FORMATS = setOf("pgs", "sup", "hdmv_pgs_subtitle", "pgssub")
private val BITMAP_FORMATS = setOf("vobsub", "dvd_subtitle", "dvdsub", "dvb", "dvbsub", "dvb_subtitle", "xsub")

/** The kind of a sidecar's `format` or an embedded stream's codec; missing
 *  or unknown is text (older servers send no format for WebVTT sidecars). */
fun subtitleKind(format: String?): SubtitleKind {
    val f = format?.trim()?.lowercase().orEmpty()
    return when (f) {
        in PGS_FORMATS -> SubtitleKind.Pgs
        in BITMAP_FORMATS -> SubtitleKind.Bitmap
        else -> SubtitleKind.Text
    }
}

/** One entry of the captions menu. */
data class SubtitleChoice(
    /** The sidecar's id, or "emb-<stream index>" for an embedded track. */
    val id: String,
    /** Named by language ([subtitleLabels]). */
    val label: String,
    /** The normalised language ([normalizeLang]); "" when none. */
    val lang: String,
    /** Where the cue file is, with `?stream=` (chino-api's stream-token routes). */
    val url: String,
    val kind: SubtitleKind,
    val forced: Boolean,
    /** Whether this player can show it; a picture format is listed but not
     *  selectable where nothing draws it. */
    val available: Boolean,
)

/**
 * The captions menu, as chino-web merges it: the sidecars of
 * GET /items/{id}/subtitles first (each at /api/v1/play/subs/{id}.vtt), then
 * the embedded text streams /play/info lists (/items/{id}/play/subtitles/
 * {index}.vtt — ffmpeg extracts them as WebVTT). Labels name the language.
 *
 * Picture formats stay in the list, marked unavailable unless [drawsPgs]
 * (and an embedded picture stream is always unavailable: the extractor cannot
 * turn it into WebVTT). An embedded row without a stream index cannot be
 * addressed and is left out — a packaged title's rows, whose subtitles are its
 * sidecars.
 */
fun buildSubtitleChoices(
    itemId: String,
    sidecars: List<SidecarSubtitle>,
    embedded: List<TrackInfo>,
    apiBase: String,
    streamToken: String,
    drawsPgs: Boolean = false,
): List<SubtitleChoice> {
    data class Raw(val id: String, val lang: String?, val title: String?, val path: String, val kind: SubtitleKind, val forced: Boolean, val available: Boolean)

    val raw = ArrayList<Raw>()
    for (s in sidecars) {
        if (s.id.isBlank()) continue
        val kind = subtitleKind(s.format)
        raw += Raw(
            id = s.id,
            lang = s.lang,
            title = s.label,
            path = s.url.ifBlank { "/api/v1/play/subs/${s.id}.vtt" },
            kind = kind,
            forced = sidecarForced(s),
            available = kind == SubtitleKind.Text || (kind == SubtitleKind.Pgs && drawsPgs),
        )
    }
    for (t in embedded) {
        val index = t.index ?: continue
        if (index < 0) continue
        val kind = subtitleKind(t.codec)
        raw += Raw(
            id = "emb-$index",
            lang = t.language,
            title = t.title,
            path = "/api/v1/items/$itemId/play/subtitles/$index.vtt",
            kind = kind,
            forced = t.forced,
            available = kind == SubtitleKind.Text,
        )
    }
    val labels = subtitleLabels(raw.map { SubtitleLabelInput(it.lang, it.title, it.forced) })
    return raw.mapIndexed { i, r ->
        SubtitleChoice(
            id = r.id,
            label = labels[i],
            lang = normalizeLang(r.lang),
            url = artworkUrl(apiBase, r.path, streamToken) ?: r.path,
            kind = r.kind,
            forced = r.forced,
            available = r.available,
        )
    }
}

/** Whether a sidecar is a forced track: it says so (`forced`), or — from a
 *  server that does not pass the flag on — its label does, as the Android
 *  player reads a track's label. */
fun sidecarForced(s: SidecarSubtitle): Boolean = s.forced || s.label.orEmpty().contains("forced", ignoreCase = true)

/**
 * The captions menu of a player that side-loads the sidecars and also plays
 * what the HLS master lists (Media3 does): the sidecars first, then the
 * master's SUBTITLES renditions that are not a sidecar again.
 *
 * A package carries each WebVTT subtitle twice — the sidecar every client
 * loads, and the HLS rendition the packager writes beside it (hls/sN), which
 * the master names once the packager runs with HLS_SUBTITLES. Listed as they
 * come, each would show twice. A rendition is a sidecar again when a sidecar
 * has its language ([normalizeLang]) and is forced as it is; a rendition no
 * sidecar has stays. chino-web and the iOS player load no rendition at all.
 */
fun <T> sidecarsThenOtherRenditions(
    tracks: List<T>,
    isSidecar: (T) -> Boolean,
    lang: (T) -> String?,
    forced: (T) -> Boolean,
): List<T> {
    val (sidecars, renditions) = tracks.partition(isSidecar)
    val carried = sidecars.mapTo(HashSet()) { normalizeLang(lang(it)) to forced(it) }
    return sidecars + renditions.filter { (normalizeLang(lang(it)) to forced(it)) !in carried }
}

/** The subtitle that comes on by itself ([autoSubtitleTrack]) among the ones
 *  this player can show: the default rule's ([defaultSubtitleTrack]), else
 *  the forced one in the audio's language, a text one before a picture one.
 *  Null for off. */
fun autoSubtitleChoice(
    choices: List<SubtitleChoice>,
    audioLang: String?,
    subtitlePref: String?,
    audioPref: String?,
): SubtitleChoice? = autoSubtitleTrack(
    tracks = choices.filter { it.available },
    lang = { it.lang },
    forced = { it.forced },
    text = { it.kind == SubtitleKind.Text },
    audioLang = audioLang,
    subtitlePref = subtitlePref,
    audioPref = audioPref,
)

/**
 * Who decides a playback's subtitles. The player, as it starts and again
 * whenever the audio's language changes ([autoSubtitleTrack]), until the
 * viewer picks in the subtitles menu; from then on the viewer, for the rest
 * of the session: an Off — a forced track turned off too — stays off
 * whatever the audio, and a track picked stays on. A switch between a
 * track's stereo and 5.1 renditions is no change of language and changes
 * nothing. chino-web's rule.
 */
class SubtitleSession {
    /** The viewer has picked in the subtitles menu: a track, or Off. */
    var viewerPicked: Boolean = false
        private set

    /** The language ([normalizeLang]) of the audio the player last decided
     *  for; null before its first decision. */
    private var decidedFor: String? = null

    /** Whether the player decides the subtitle now, for audio playing in
     *  [audioLang]: its first time, and whenever the language has changed
     *  since — never once the viewer has picked. */
    fun playerDecides(audioLang: String?): Boolean {
        if (viewerPicked) return false
        val lang = normalizeLang(audioLang)
        if (lang == decidedFor) return false
        decidedFor = lang
        return true
    }

    /** The viewer picked a track, or Off: theirs from now on. */
    fun viewerPicks() {
        viewerPicked = true
    }
}

/**
 * The audio track that plays first, of the ones /play/info lists — chino-web's
 * pick: the one in the preferred language (Settings; "orig" keeps the title's
 * own), else the file's default, else the first. Null when none is listed.
 */
fun preferredAudioTrack(tracks: List<TrackInfo>, audioPref: String?): TrackInfo? {
    if (tracks.isEmpty()) return null
    val want = if (audioPref.isNullOrBlank() || audioPref.equals("orig", ignoreCase = true)) "" else normalizeLang(audioPref)
    if (want.isNotEmpty()) tracks.firstOrNull { normalizeLang(it.language) == want }?.let { return it }
    return tracks.firstOrNull { it.default } ?: tracks.first()
}
