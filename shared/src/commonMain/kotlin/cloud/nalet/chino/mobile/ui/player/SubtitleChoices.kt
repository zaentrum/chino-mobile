package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.api.TrackInfo
import cloud.nalet.chino.mobile.data.api.artworkUrl
import cloud.nalet.chino.mobile.ui.settings.AUDIO_ORIGINAL
import cloud.nalet.chino.mobile.ui.settings.SUBTITLES_OFF

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
    /** Named by language, then kind ([subtitleLabel]); never numbered: two
     *  that read the same show once ([distinctSubtitles]). */
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
 * {index}.vtt — ffmpeg extracts them as WebVTT). Labels name the language,
 * then the kind: forced, for the deaf and hard of hearing ([subtitleLabel]);
 * two that read the same show once, the file's own before a sidecar
 * ([distinctSubtitles]).
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
    val choices = raw.map { r ->
        r to SubtitleChoice(
            id = r.id,
            label = subtitleLabel(SubtitleLabelInput(r.lang, r.title, r.forced)),
            lang = normalizeLang(r.lang),
            url = artworkUrl(apiBase, r.path, streamToken) ?: r.path,
            kind = r.kind,
            forced = r.forced,
            available = r.available,
        )
    }
    return distinctSubtitles(
        choices,
        label = { it.second.label },
        tag = { subtitleLanguage(it.first.lang, it.first.title) },
        own = { it.second.id.startsWith(EMBEDDED_SUBTITLE_PREFIX) },
        picture = { it.second.kind != SubtitleKind.Text },
        retag = { kept, twin -> kept.first.copy(lang = twin.first.lang) to kept.second.copy(lang = twin.second.lang) },
    ).map { it.second }
}

/** Whether a sidecar is a forced track: it says so (`forced`), or — from a
 *  server that does not pass the flag on — its label does, as the Android
 *  player reads a track's label. */
fun sidecarForced(s: SidecarSubtitle): Boolean = s.forced || s.label.orEmpty().contains("forced", ignoreCase = true)

/**
 * An unpackaged title's embedded text subtitles (/play/info subtitle_tracks),
 * as sidecars the player loads beside the stream: each at
 * /items/{id}/play/subtitles/{index}.vtt, which chino-stream extracts as
 * WebVTT, signed with [streamToken]. A picture track (PGS, VobSub) is not
 * extracted, and a row without a stream index - a packaged title's - is not
 * addressable: neither is listed.
 */
fun embeddedSidecars(itemId: String, tracks: List<TrackInfo>, apiBase: String, streamToken: String): List<SidecarSubtitle> =
    tracks.mapNotNull { t ->
        val index = t.index?.takeIf { it >= 0 } ?: return@mapNotNull null
        if (subtitleKind(t.codec) != SubtitleKind.Text) return@mapNotNull null
        SidecarSubtitle(
            id = "$EMBEDDED_SUBTITLE_PREFIX$index",
            label = t.title?.takeIf { it.isNotBlank() },
            lang = t.language.orEmpty(),
            url = "${apiBase.trimEnd('/')}/v1/items/$itemId/play/subtitles/$index.vtt?stream=$streamToken",
            format = "webvtt",
            forced = t.forced,
        )
    }

/** Whether a track's own title says the platform made it ("AI"), the kind
 *  the menu lists last in its language ("Chinese (Simplified) · AI"). */
fun isAiSubtitle(title: String?): Boolean = title != null && Regex("""(^|[^A-Za-z])AI([^A-Za-z]|$)""").containsMatchIn(title)

/** The id prefix of an embedded subtitle ([embeddedSidecars]). */
const val EMBEDDED_SUBTITLE_PREFIX = "emb-"

/**
 * The subtitles menu under "off" (the design system's subtitles pattern):
 * [top], the languages that matter here, and [more], what "more languages"
 * opens - empty where there is nothing more to open.
 */
data class SubtitleMenu<T>(val top: List<T>, val more: List<T>)

/**
 * The tracks that would read the same ([label]) once each, where the first
 * of them was (docs/chino subtitles; never numbered). Of two, the menu shows
 * the one [on] if either is; else the file's own ([own]: an original's
 * embedded track, timed to this file) before one the server adds; else a
 * text one before a picture one ([picture]); else the first - tagged with a
 * language or not. The one shown stands for both: where it is tagged with no
 * language ([tag] empty) and reads as one only by its title ("English" on an
 * "und" track), it takes its twin's ([retag]), so it is listed with that
 * language and comes on by it. chino-web's better and standsForBoth.
 */
fun <T> distinctSubtitles(
    tracks: List<T>,
    label: (T) -> String,
    tag: (T) -> String,
    on: (T) -> Boolean = { false },
    own: (T) -> Boolean = { false },
    picture: (T) -> Boolean = { false },
    retag: (kept: T, twin: T) -> T = { kept, _ -> kept },
): List<T> {
    fun better(a: T, b: T): T = when {
        on(b) -> b
        on(a) -> a
        own(a) != own(b) -> if (own(a)) a else b
        picture(a) != picture(b) -> if (picture(a)) b else a
        else -> a
    }
    fun standsForBoth(a: T, b: T): T {
        val kept = better(a, b)
        val twin = if (kept === a) b else a
        return if (tag(kept).isEmpty() && tag(twin).isNotEmpty()) retag(kept, twin) else kept
    }
    val firstOf = LinkedHashMap<String, T>()
    for (t in tracks) {
        val key = label(t)
        firstOf[key] = firstOf[key]?.let { standsForBoth(it, t) } ?: t
    }
    return firstOf.values.toList()
}

/** A language setting's value as a language: "" for none ([none]: "off",
 *  "orig"; empty). */
private fun settingLanguage(value: String?, none: String): String {
    val v = value?.trim().orEmpty()
    return if (v.isNotEmpty() && !v.equals(none, ignoreCase = true)) v else ""
}

/**
 * The languages that matter to the person where the server names no
 * subtitle languages (docs/chino subtitles, "The menu"), in this order: the
 * subtitle setting's ([preferred], none for "off"), the audio setting's
 * unless it is Original ([audio], "orig"), and the device's ([device]) -
 * each language once, where the first of them puts it. chino-web's
 * ownLanguages.
 */
fun ownLanguages(preferred: String?, audio: String?, device: String?): List<String> {
    val own = ArrayList<String>()
    for (w in listOf(settingLanguage(preferred, SUBTITLES_OFF), settingLanguage(audio, AUDIO_ORIGINAL), device?.trim().orEmpty())) {
        if (languageTag(w).isNotEmpty() && own.none { normalizeLang(it) == normalizeLang(w) }) own += w
    }
    return own
}

/**
 * The subtitles menu of a title's [tracks], given in the order the file
 * brings them (docs/chino subtitles, "The menu"). It never lists every track
 * a file brings - a title can carry a hundred:
 *
 * - [SubtitleMenu.top], the languages that matter to the person: the
 *   subtitle setting's ([preferred] - "off" names none), then each other
 *   subtitle language the server is set up for ([serverLanguages], its
 *   order). A wanted language takes a track by its language, and by its
 *   script and region where both name one ([tag]: its tag with what its own
 *   title says, [subtitleLanguage]): "zh-Hans" takes an unmarked "Chinese",
 *   never "zh-Hant"; "en" takes "en-GB" too.
 * - On a server set up for no subtitle languages (none, or a server from
 *   before the route), the person's own instead ([ownLanguages]): the
 *   subtitle setting's, the audio setting's unless it is Original ([audio]),
 *   the device's ([device]). Each of them the whole language, the AI ones
 *   included, a language with its region or script counting as that
 *   language: a device in "zh-TW" puts Chinese on top, all of it, its runs
 *   in the order below - never its own run first and the rest after it.
 * - [SubtitleMenu.more], what "more languages" opens: the rest, the
 *   languages in alphabetical order of their names, a track in no language
 *   by its own - a packaged title's as any title's.
 *
 * Within a language each region or script has a run of its own, the bare
 * name first and the others in alphabetical order ("Chinese", "Chinese
 * (Simplified)", "Chinese (Simplified) · AI", "Chinese (Traditional)"); within
 * a run plain first, then for deaf and hard of hearing, then foreign-language
 * parts only, then AI ([ai]: made by the platform), each in the file's order.
 * Tracks that read the same show once ([distinctSubtitles]).
 */
fun <T> subtitleMenu(
    tracks: List<T>,
    tag: (T) -> String,
    forced: (T) -> Boolean,
    sdh: (T) -> Boolean,
    label: (T) -> String,
    preferred: String?,
    serverLanguages: List<String>,
    /** The audio setting ("orig" for Original): one of the person's own
     *  languages where the server names none. */
    audio: String? = null,
    /** The device's language: one of the person's own languages where the
     *  server names none. */
    device: String? = null,
    on: (T) -> Boolean = { false },
    own: (T) -> Boolean = { false },
    ai: (T) -> Boolean = { false },
    picture: (T) -> Boolean = { false },
    retag: (kept: T, twin: T) -> T = { kept, _ -> kept },
): SubtitleMenu<T> {
    val unique = distinctSubtitles(tracks, label, tag, on, own, picture, retag)
    val rank = { t: T -> if (ai(t)) 3 else if (forced(t)) 2 else if (sdh(t)) 1 else 0 }
    // A language's tracks: a run per region or script, the bare name first,
    // the others by their names; in a run by kind, then in the file's order.
    fun runs(group: List<T>): List<T> {
        val variant = { t: T -> namedTag(languageTag(tag(t))) }
        val tags = group.map(variant).distinct()
        val bare = tags.filter { it == normalizeLang(it) }
        val named = (tags - bare.toSet()).sortedWith { a, b -> compareNames(languageName(a), languageName(b)) }
        return (bare + named).flatMap { v -> group.filter { variant(it) == v }.sortedBy(rank) }
    }
    // The server's languages after the subtitle setting's; where it names
    // none, the person's own, each the whole language.
    val named = serverLanguages.any { languageTag(it).isNotEmpty() }
    val wanted = if (named) {
        listOf(settingLanguage(preferred, SUBTITLES_OFF)) + serverLanguages
    } else {
        ownLanguages(preferred, audio, device)
    }.filter { languageTag(it).isNotEmpty() }.distinctBy { languageTag(it) }
    val takes = { w: String, t: T -> if (named) sameLanguage(w, tag(t)) else normalizeLang(tag(t)) == normalizeLang(w) }
    val placed = HashSet<Int>()
    // On top, one block per language asked for, by its language.
    val top = ArrayList<T>()
    for (w in wanted) {
        val group = unique.indices.filter { it !in placed && takes(w, unique[it]) }
        placed += group
        top += runs(group.map { unique[it] })
    }
    // The rest by language; a track in no language on its own.
    val rest = LinkedHashMap<String, MutableList<T>>()
    unique.forEachIndexed { i, t ->
        if (i in placed) return@forEachIndexed
        val base = normalizeLang(tag(t))
        rest.getOrPut(base.ifEmpty { "\u0000$i" }) { ArrayList() } += t
    }
    val groups = rest.values.map { group -> normalizeLang(tag(group.first())) to runs(group) }
    // A language by its name, a track in no language by its label.
    val more = groups.sortedWith { (a, x), (b, y) ->
        compareNames(if (a.isEmpty()) label(x.first()) else languageName(a), if (b.isEmpty()) label(y.first()) else languageName(b))
    }
    return SubtitleMenu(top, more.flatMap { it.second })
}

/** Whether a track tagged [code] is in the language [want] names: the same
 *  language, and the same script and region where both name one. */
fun sameLanguage(want: String?, code: String?): Boolean {
    val w = languageTag(want)
    val t = languageTag(code)
    if (w.isEmpty() || t.isEmpty() || normalizeLang(w) != normalizeLang(t)) return false
    val ws = scriptOfTag(w)
    val ts = scriptOfTag(t)
    if (ws.isNotEmpty() && ts.isNotEmpty() && ws != ts) return false
    val wr = regionOfTag(w)
    val tr = regionOfTag(t)
    return wr.isEmpty() || tr.isEmpty() || wr == tr
}

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
    sdh: (T) -> Boolean = { false },
): List<T> {
    val (sidecars, renditions) = tracks.partition(isSidecar)
    val carried = sidecars.mapTo(HashSet()) { sameSubtitleKey(lang(it), forced(it), sdh(it)) }
    return sidecars + renditions.filter { sameSubtitleKey(lang(it), forced(it), sdh(it)) !in carried }
}

/**
 * What makes a rendition and a sidecar the same subtitle
 * ([sidecarsThenOtherRenditions]): its language with the script and region it
 * names ([languageTag]: "pt-BR" is not "pt-PT", "zh-Hant" not "zh-Hans"),
 * whether it is forced, and whether it is for the deaf and hard of hearing
 * ([isSdhSubtitle]). Two subtitles of one language are two.
 */
fun sameSubtitleKey(lang: String?, forced: Boolean, sdh: Boolean = false): Triple<String, Boolean, Boolean> =
    Triple(languageTag(lang), forced, sdh)

private val SDH_WORDS = Regex(
    """(^|[^A-Za-z])(SDH|CC|HoH|closed[ -]captions?|hearing[ -]impaired|hard[ -]of[ -]hearing|deaf)([^A-Za-z]|$)""",
    RegexOption.IGNORE_CASE,
)

/** The HLS characteristic of a subtitle for the deaf and hard of hearing. */
private const val DESCRIBES_SOUND = "public.accessibility.describes-music-and-sound"

/** Whether a subtitle is for the deaf and hard of hearing: its name says so
 *  ("English SDH", "English (CC)", "for deaf and hard of hearing"), or its
 *  HLS characteristics do. */
fun isSdhSubtitle(name: String?, characteristics: String? = null): Boolean =
    characteristics?.contains(DESCRIBES_SOUND, ignoreCase = true) == true ||
        (name != null && SDH_WORDS.containsMatchIn(name))

/** The subtitle that comes on by itself ([autoSubtitleTrack]) among the ones
 *  this player can show: the default rule's ([defaultSubtitleTrack]), else
 *  the forced one in the audio's language, a text one before a picture one.
 *  Null for off. */
fun autoSubtitleChoice(
    choices: List<SubtitleChoice>,
    audioLang: String?,
    subtitlePref: String?,
): SubtitleChoice? = autoSubtitleTrack(
    tracks = choices.filter { it.available },
    lang = { it.lang },
    forced = { it.forced },
    text = { it.kind == SubtitleKind.Text },
    audioLang = audioLang,
    subtitlePref = subtitlePref,
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
