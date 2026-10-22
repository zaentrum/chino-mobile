package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.ui.player.HlsMaster
import cloud.nalet.chino.mobile.ui.player.MasterSubtitle
import cloud.nalet.chino.mobile.ui.player.SubtitleLabelInput
import cloud.nalet.chino.mobile.ui.player.autoSubtitleTrack
import cloud.nalet.chino.mobile.ui.player.isSdhSubtitle
import cloud.nalet.chino.mobile.ui.player.languageTag
import cloud.nalet.chino.mobile.ui.player.normalizeLang
import cloud.nalet.chino.mobile.ui.player.sameSubtitleKey
import cloud.nalet.chino.mobile.ui.player.sidecarForced
import cloud.nalet.chino.mobile.ui.player.EMBEDDED_SUBTITLE_PREFIX
import cloud.nalet.chino.mobile.ui.player.distinctSubtitles
import cloud.nalet.chino.mobile.ui.player.subtitleLabel
import cloud.nalet.chino.mobile.ui.player.subtitleLanguage

/*
 * Which audio and which subtitle the TV plays. The Default Media Receiver
 * plays what the master lists - its audio renditions, and its subtitle
 * renditions where the server packages them - beside the sidecars the phone
 * side-loads, and lists them all among the media's tracks once the title is
 * loaded. The phone picks from that list as its own player picks: the audio
 * the viewer listened to, else the Settings language; the subtitle the viewer
 * had on, else the one the default rule turns on for that audio
 * (autoSubtitleTrack). Pure, so commonTest pins it.
 */

/** What a track of the media on the TV is. */
enum class TvTrackType { Audio, Text, Video, Other }

/** A track of the media on the TV, as its media status lists it. */
data class TvTrack(
    val id: Long,
    val type: TvTrackType,
    val language: String?,
    val name: String?,
    /** A forced subtitle: one of the phone's that is, or one of the
     *  master's that the master marks FORCED. */
    val forced: Boolean = false,
    /** The sidecar it is, for one the phone side-loaded; null for the
     *  master's own. */
    val sidecarId: String? = null,
    /** For the deaf and hard of hearing ([isSdhSubtitle]). */
    val sdh: Boolean = false,
)

/** What the phone asks the TV to play with. */
data class CastTrackWish(
    /** The language of the audio the phone played - or starts the title
     *  with - null when it is not known. */
    val audioLang: String?,
    /** Settings' audio language; "orig" keeps the title's own. */
    val audioPref: String?,
    val subtitle: CastSubtitleStart,
)

/** The audio and the subtitle the TV is to play; null for its own pick, or
 *  for no subtitle. */
data class CastTrackPicks(val audio: TvTrack?, val text: TvTrack?)

/**
 * The audio and subtitle for [wish] among [tracks], with [active] the ones
 * on now. The audio: the one in the phone's language - the one playing where
 * that is it, so a stereo track is not swapped for a 5.1 one of the same
 * language - else the one in the Settings language, else the one playing.
 * The subtitle: none for Off; a side-loaded one the load turned on stays on;
 * else the phone's sidecar, or where the TV has it as a rendition of the
 * master instead, the rendition of that very subtitle (its language, script
 * and region, forced or SDH - sameSubtitleKey); and for the default rule,
 * its pick for the audio the TV plays.
 */
fun castTrackPicks(tracks: List<TvTrack>, active: List<Long>, wish: CastTrackWish): CastTrackPicks {
    val audio = tracks.filter { it.type == TvTrackType.Audio }
    val text = tracks.filter { it.type == TvTrackType.Text }
    val playing = audio.firstOrNull { it.id in active }
    val pickedAudio = pickAudio(audio, playing, wish)
    val audioLang = pickedAudio?.language ?: wish.audioLang
    val sideLoadedOn = text.firstOrNull { it.id in active && it.sidecarId != null }
    val pickedText = when (val start = wish.subtitle) {
        CastSubtitleStart.Off -> null
        else -> if (sideLoadedOn != null) sideLoadedOn else pickText(text, start, audioLang)
    }
    return CastTrackPicks(pickedAudio, pickedText)
}

private fun pickText(text: List<TvTrack>, start: CastSubtitleStart, audioLang: String?): TvTrack? =
    when (start) {
        CastSubtitleStart.Off -> null
        is CastSubtitleStart.Sidecar -> text.firstOrNull { it.sidecarId == start.sidecarId }
            ?: text.firstOrNull {
                it.sidecarId == null &&
                    sameSubtitleKey(it.language, it.forced, it.sdh) == sameSubtitleKey(start.lang, start.forced, start.sdh)
            }
        is CastSubtitleStart.Auto -> autoSubtitleTrack(
            tracks = text,
            lang = { it.language },
            forced = { it.forced },
            text = { true },
            audioLang = audioLang,
            subtitlePref = start.subtitlePref,
        )
    }

private fun pickAudio(audio: List<TvTrack>, playing: TvTrack?, wish: CastTrackWish): TvTrack? {
    if (audio.isEmpty()) return null
    val pref = wish.audioPref?.trim()?.takeUnless { it.isEmpty() || it.equals("orig", ignoreCase = true) }
    for (lang in listOf(wish.audioLang, pref)) {
        val want = normalizeLang(lang)
        if (want.isEmpty()) continue
        if (playing != null && normalizeLang(playing.language) == want) return playing
        audio.firstOrNull { normalizeLang(it.language) == want }?.let { return it }
    }
    return playing ?: audio.first()
}

/**
 * The track ids to make active on the TV for [wish] ([castTrackPicks]): its
 * video as it is, the audio and the subtitle picked. Null when the ones
 * active now are those already, and nothing need be sent.
 */
fun castActiveTracks(tracks: List<TvTrack>, active: List<Long>, wish: CastTrackWish): List<Long>? {
    val known = tracks.mapTo(HashSet()) { it.id }
    val now = active.filter { it in known }.toSet()
    val picks = castTrackPicks(tracks, active, wish)
    val kept = tracks.filter { it.id in now && it.type != TvTrackType.Audio && it.type != TvTrackType.Text }.map { it.id }
    val want = kept + listOfNotNull(picks.audio?.id, picks.text?.id)
    return want.takeIf { it.toSet() != now }
}

/**
 * What the phone's chooser of the TV's tracks calls each of the TV's
 * subtitles, by id: what the phone's own menus call a subtitle
 * ([subtitleLabel]) - the language, then the kind in words - whatever the
 * master's NAME says ("English SDH" is "English · for deaf and hard of
 * hearing", "German (forced)" "German · foreign-language parts only"). Never
 * numbered: of the ones that read the same only one is named, the one on
 * ([active]) where it is one of them, else the file's own, else the first
 * ([distinctSubtitles]) - the chooser lists the named ones. Audio and video
 * keep their names.
 */
fun tvSubtitleNames(tracks: List<TvTrack>, active: Collection<Long> = emptyList()): Map<Long, String> {
    val name = { t: TvTrack -> subtitleLabel(SubtitleLabelInput(t.language, t.name, t.forced, t.sdh)) }
    return distinctSubtitles(
        tracks.filter { it.type == TvTrackType.Text },
        label = name,
        tag = { subtitleLanguage(it.language, it.name) },
        on = { it.id in active },
        own = { it.sidecarId?.startsWith(EMBEDDED_SUBTITLE_PREFIX) == true },
    ).associate { it.id to name(it) }
}

/**
 * The sidecars the phone side-loads beside the subtitles [master] carries
 * itself: every one the master has no rendition of. A package carries each
 * WebVTT subtitle twice - the sidecar and its HLS rendition - and the TV
 * lists the master's whatever the phone sends, so a sidecar the master
 * carries again would show twice: the phone's own player's rule
 * (sidecarsThenOtherRenditions, sameSubtitleKey), the other way round.
 */
internal fun sidecarsBesideTheMaster(sidecars: List<SidecarSubtitle>, master: List<MasterSubtitle>): List<SidecarSubtitle> {
    if (master.isEmpty()) return sidecars
    val carried = master.mapTo(HashSet()) { sameSubtitleKey(it.language, it.forced, isSdhSubtitle(it.name, it.characteristics)) }
    return sidecars.filter { sameSubtitleKey(it.lang, sidecarForced(it), isSdhSubtitle(it.label)) !in carried }
}

/**
 * [tracks] as the TV lists them after a load of [media], told what the
 * phone knows of them: a side-loaded one (an id of [media]'s text tracks,
 * from [CAST_TEXT_TRACK_FIRST_ID] on) is its sidecar - forced, SDH, its own
 * language - and one of the master's is forced or SDH as [master] says.
 */
internal fun withWhatThePhoneKnows(tracks: List<TvTrack>, media: CastMedia, master: HlsMaster?): List<TvTrack> {
    val ours = media.textTracks.associateBy { it.id }
    return tracks.map { t ->
        val mine = ours[t.id]
        when {
            mine != null -> t.copy(
                type = TvTrackType.Text,
                language = mine.language,
                forced = mine.forced,
                sidecarId = mine.sidecarId,
                sdh = mine.sdh,
            )
            t.type == TvTrackType.Text -> master?.subtitles.orEmpty()
                .firstOrNull { languageTag(it.language) == languageTag(t.language) && (it.name == null || it.name == t.name) }
                ?.let { m -> t.copy(forced = t.forced || m.forced, sdh = t.sdh || isSdhSubtitle(m.name, m.characteristics)) }
                ?: t
            else -> t
        }
    }
}

/**
 * The subtitle that is on as the TV starts, where it is one the phone
 * side-loads: the pick for [wish] among the TV's tracks to come - the
 * sidecars of [tracks] and what [master] lists, audio and subtitles, the
 * audio the master plays first taken to play. Null when the pick is none, or
 * one of the master's own: the phone turns that on once the TV has loaded
 * the title and numbered its tracks.
 */
internal fun firstTextTrack(tracks: List<CastTextTrack>, master: HlsMaster?, wish: CastTrackWish): Long? {
    val ours = tracks.map { TvTrack(it.id, TvTrackType.Text, it.language, it.name, it.forced, it.sidecarId, it.sdh) }
    // The master's tracks, under ids of their own until the TV numbers them.
    val theirs = master?.subtitles.orEmpty().mapIndexed { i, s ->
        TvTrack(MASTER_TRACK_ID - i, TvTrackType.Text, s.language, s.name, s.forced, sdh = isSdhSubtitle(s.name, s.characteristics))
    }
    val audio = master?.audio.orEmpty().mapIndexed { i, a ->
        TvTrack(MASTER_AUDIO_ID - i, TvTrackType.Audio, a.language, a.name)
    }
    val first = master?.audio.orEmpty().indexOfFirst { it.default }.takeIf { it >= 0 } ?: 0
    val playing = audio.getOrNull(first)?.let { listOf(it.id) }.orEmpty()
    return castTrackPicks(ours + theirs + audio, playing, wish).text?.id?.takeIf { it >= CAST_TEXT_TRACK_FIRST_ID }
}

private const val MASTER_TRACK_ID = -1L
private const val MASTER_AUDIO_ID = -100_000L
