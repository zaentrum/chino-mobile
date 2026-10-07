package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.TrackInfo

/*
 * The audio menu's words for a track — its codec and channels — and which of
 * /play/info's tracks a player's audio rendition plays: the same on Android
 * and iOS.
 */

/** A codec as the audio menu names it ("AAC", "E-AC-3"), from /play/info's
 *  spelling ("mp4a", "mp4a.40.2", "ec-3", "eac3") or a player's; another by
 *  its family, upper-cased ("FLAC"). Null for none. */
fun audioCodecName(codec: String?): String? {
    val c = codec?.trim()?.lowercase().orEmpty()
    if (c.isEmpty()) return null
    return when {
        c == "mp3" || c == "mp4a.40.34" || c == "mp4a.6b" -> "MP3"
        c == "aac" || c.startsWith("mp4a") -> "AAC"
        c == "ec-3" || c == "eac3" || c == "ec3" || c == "ec+3" -> "E-AC-3"
        c == "ac-3" || c == "ac3" -> "AC-3"
        c == "opus" -> "Opus"
        else -> c.substringBefore('.').uppercase()
    }
}

/** A channel count as the menu names it: "Mono", "Stereo", "5.1", "7.1",
 *  else "6ch". */
fun channelLayoutName(channels: Int): String = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${channels}ch"
}

/** What the audio menu says under a track: its codec and its channels
 *  ("E-AC-3 · 5.1", "AAC · Stereo"); null when neither is known. */
fun audioDetail(codec: String?, channels: Int?): String? =
    listOfNotNull(audioCodecName(codec), channels?.takeIf { it > 0 }?.let(::channelLayoutName))
        .joinToString(" · ")
        .ifEmpty { null }

/** What the audio menu says under a /play/info track: its codec and
 *  channels, and for a native player the 5.1 companion's that plays it where
 *  the player picks the 5.1 group ("AAC · Stereo or E-AC-3 · 5.1"). */
fun audioDetail(track: TrackInfo): String? =
    listOfNotNull(audioDetail(track.codec, track.channels), track.surround?.let { audioDetail(it.codec, it.channels) })
        .joinToString(" or ")
        .ifEmpty { null }

/** What /play/info calls a track: its name — its rendition's NAME — else its
 *  title; null for neither. */
val TrackInfo.displayName: String?
    get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: title?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The /play/info track a player's audio rendition plays, of [tracks]: the one
 * [named] picks — /play/info names each track as the master names its
 * rendition (chino-stream gives every one the rendition's NAME), so that one
 * is it. For a server that names them otherwise: the one in the rendition's
 * [place] when there are as many tracks as renditions ([count]), else the
 * first in its [language]. Null when none is.
 */
fun audioTrackFor(
    tracks: List<TrackInfo>,
    place: Int,
    count: Int,
    language: String?,
    named: (TrackInfo) -> Boolean,
): TrackInfo? {
    tracks.firstOrNull(named)?.let { return it }
    if (tracks.size == count) tracks.getOrNull(place)?.let { return it }
    val lang = normalizeLang(language)
    return if (lang.isEmpty()) null else tracks.firstOrNull { normalizeLang(it.language) == lang }
}
