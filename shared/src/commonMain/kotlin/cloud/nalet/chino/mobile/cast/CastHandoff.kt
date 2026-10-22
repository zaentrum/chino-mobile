package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.ProgressResponse
import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.ui.player.SESSION_MARGIN_MS
import cloud.nalet.chino.mobile.ui.player.sidecarForced

/*
 * Where the TV starts, and where the phone's player stands again when casting
 * does not take over - the hand-over both ways. Pure, so commonTest pins it.
 *
 * Casting starts while the phone plays: the TV continues where the phone was,
 * playing or paused as it was, with its audio and subtitle, and the phone's
 * player closes once the TV plays. Casting starts from a title's page (Play,
 * Resume, an episode): the player starts on the TV where it would have
 * started on the phone - the resume point, the head for a finished title -
 * unless the TV plays that title for this phone already: then it plays on.
 * Casting stops: the TV's last position is written back
 * (CastProgressReporter), so playing again continues from there; the phone's
 * player does not start by itself.
 */

/** What the phone's player hands to the TV as casting starts. */
data class CastHandoff(
    /** Where the TV starts ([handoffStartMs]). */
    val startMs: Long,
    /** Whether the phone was playing (the TV plays) or paused (it waits). */
    val playing: Boolean,
    /** The language of the audio the phone played, or starts the title
     *  with; null when it is not known. */
    val audioLang: String?,
    /** The subtitle the phone showed. */
    val subtitle: CastSubtitleStart,
    /** The phone says where the TV starts - its player hands over where it
     *  is, or Play from start - even where the TV plays the title already. */
    val restart: Boolean,
)

/**
 * Where the TV continues from a phone's player at [positionMs] of a title
 * [durationMs] long (0 when not known): there, but never past the title's
 * last second - and from the head when the phone had played to the end.
 */
fun handoffStartMs(positionMs: Long, durationMs: Long, ended: Boolean): Long {
    if (ended) return 0L
    val pos = positionMs.coerceAtLeast(0L)
    return if (durationMs > 0) pos.coerceAtMost((durationMs - 1_000L).coerceAtLeast(0L)) else pos
}

/** The prefix a side-loaded sidecar's track id carries in the Android
 *  player ("sidecar:<id>", "1:sidecar:<id>" once Media3 merges it). */
private const val SIDECAR_TRACK_PREFIX = "sidecar:"

/**
 * The subtitle the TV starts with, from the one the phone's player showed:
 * [phoneKey] is its track's id, "" for Off, null while the player had not
 * decided yet. A sidecar of [sidecars] is shown on the TV too; Off stays off;
 * a subtitle the phone knows no sidecar of - one of the master's own
 * renditions - and an undecided player leave it to the default rule ([auto]).
 */
fun handoffSubtitle(phoneKey: String?, sidecars: List<SidecarSubtitle>, auto: CastSubtitleStart.Auto): CastSubtitleStart {
    if (phoneKey == null) return auto
    if (phoneKey.isEmpty()) return CastSubtitleStart.Off
    val id = when {
        phoneKey.startsWith(SIDECAR_TRACK_PREFIX) -> phoneKey.removePrefix(SIDECAR_TRACK_PREFIX)
        phoneKey.substringAfter(':', "").startsWith(SIDECAR_TRACK_PREFIX) ->
            phoneKey.substringAfter(':').removePrefix(SIDECAR_TRACK_PREFIX)
        else -> null
    }
    val sidecar = id?.takeIf { it.isNotEmpty() }?.let { sid -> sidecars.firstOrNull { it.id == sid } } ?: return auto
    return CastSubtitleStart.Sidecar(sidecar.id, sidecar.lang, sidecarForced(sidecar))
}

/**
 * The resume point GET /items/{id}/progress gives: its position, or none for
 * a title the viewer finished. chino-api's one rule (its last 5 % or 2
 * minutes) answers such a title with `finished` and no position; the phone
 * and the TV then start it from the head.
 */
fun resumeSecOf(progress: ProgressResponse): Int =
    if (progress.finished) 0 else progress.positionSec

/**
 * Whether the TV plays the title [itemId] (its extra [extraId]) as this
 * phone and account [owner] cast it - playing, paused or buffering. Opened
 * again on the phone (Resume on its page, the title from Continue Watching),
 * it plays on there: the TV's position is the one the viewer watched to,
 * whatever the server had been told last. Only the phone saying where to
 * start ([CastHandoff.restart]) loads it anew.
 */
fun playsOnTv(status: RemoteMediaStatus?, itemId: String, extraId: String?, owner: String): Boolean {
    val tag = status?.tag ?: return false
    if (tag.itemId != itemId || tag.extraId != extraId || tag.owner != owner) return false
    return status.state == RemotePlayerState.Playing ||
        status.state == RemotePlayerState.Paused ||
        status.state == RemotePlayerState.Buffering
}

/**
 * Whether the TV's links stop working before the title ends, at [nowMs]
 * (epoch ms): what is left of it from [positionMs] ([durationMs], 0 when not
 * known), and the player's half hour for pauses ([SESSION_MARGIN_MS]), is
 * more than the links have left ([linksExpireAtMs]; 0, not known: they are
 * left as they are). Then the phone signs them anew.
 */
fun linksRunOut(linksExpireAtMs: Long, nowMs: Long, positionMs: Long, durationMs: Long): Boolean {
    if (linksExpireAtMs <= 0L) return false
    val toPlay = if (durationMs > 0) (durationMs - positionMs).coerceAtLeast(0L) else 0L
    return linksExpireAtMs - nowMs < toPlay + SESSION_MARGIN_MS
}

/** Where the phone's player stands again when a load on the TV ended without
 *  the TV playing - it couldn't, or the viewer stopped casting first: at the
 *  position it handed over, and paused. It never plays by itself. */
data class LocalAgain(val startMs: Long, val autoplay: Boolean)

fun localAgainAfterCast(handoff: CastHandoff): LocalAgain = LocalAgain(startMs = handoff.startMs, autoplay = false)
