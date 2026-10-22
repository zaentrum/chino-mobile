package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.ProgressResponse

/*
 * Where the TV starts, and where the phone's player stands again when casting
 * does not take over - the hand-over both ways. Pure, so commonTest pins it.
 *
 * Casting starts while the phone plays: the TV continues where the phone was,
 * playing or paused as it was, and the phone's player closes once the TV
 * plays. Casting starts from a title's page (Play, Resume, an episode): the
 * player starts on the TV where it would have started on the phone - the
 * resume point, the head for a finished title. Casting stops: the TV's last
 * position is written back (CastProgressReporter), so playing again continues
 * from there.
 */

/** What the phone's player hands to the TV as casting starts. */
data class CastHandoff(
    /** Where the TV starts ([handoffStartMs]). */
    val startMs: Long,
    /** Whether the phone was playing (the TV plays) or paused (it waits). */
    val playing: Boolean,
    /** The subtitle the phone showed. */
    val subtitle: CastSubtitleStart,
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
 * decided yet. A sidecar is shown on the TV too; Off stays off; a subtitle
 * the TV has no copy of - one of the master's own renditions - and an
 * undecided player leave it to the default rule ([auto]).
 */
fun handoffSubtitle(phoneKey: String?, auto: CastSubtitleStart.Auto): CastSubtitleStart {
    if (phoneKey == null) return auto
    if (phoneKey.isEmpty()) return CastSubtitleStart.Off
    val sidecar = when {
        phoneKey.startsWith(SIDECAR_TRACK_PREFIX) -> phoneKey.removePrefix(SIDECAR_TRACK_PREFIX)
        phoneKey.substringAfter(':', "").startsWith(SIDECAR_TRACK_PREFIX) ->
            phoneKey.substringAfter(':').removePrefix(SIDECAR_TRACK_PREFIX)
        else -> null
    }
    return if (sidecar.isNullOrEmpty()) auto else CastSubtitleStart.Sidecar(sidecar)
}

/**
 * The resume point GET /items/{id}/progress gives: its position, or none for
 * a title the viewer finished. chino-api's one rule (its last 5 % or 2
 * minutes) answers such a title with `finished` and no position; the phone
 * and the TV then start it from the head.
 */
fun resumeSecOf(progress: ProgressResponse): Int =
    if (progress.finished) 0 else progress.positionSec

/** How close to where it would start the TV may already be in the same title
 *  for a load to be left unsent: about a write's interval, and a few seconds. */
const val ALREADY_ON_TV_MS: Long = 30_000L

/**
 * Whether the TV already plays [media], as this phone's [owner] cast it, near
 * where it would start ([ALREADY_ON_TV_MS]) - Resume on the title's page while
 * it plays there. A load would only start it again a little earlier; the TV
 * plays on instead. Start over, or a position far from the TV's, loads anew.
 */
fun alreadyOnTv(status: RemoteMediaStatus?, media: CastMedia, owner: String): Boolean {
    val tag = status?.tag ?: return false
    if (tag.itemId != media.itemId || tag.extraId != media.extraId || tag.owner != owner) return false
    val playing = status.state == RemotePlayerState.Playing ||
        status.state == RemotePlayerState.Paused ||
        status.state == RemotePlayerState.Buffering
    if (!playing) return false
    val off = status.positionMs - media.startMs
    return off in -ALREADY_ON_TV_MS..ALREADY_ON_TV_MS
}

/** Where the phone's player stands again when a load on the TV ended without
 *  the TV playing: at the position it handed over. After a failure it waits
 *  paused, the title ready to play on the phone ([afterFailure]); when the
 *  session ended first - the viewer stopped casting - it plays on as it was. */
data class LocalAgain(val startMs: Long, val autoplay: Boolean)

fun localAgainAfterCast(handoff: CastHandoff, afterFailure: Boolean): LocalAgain =
    LocalAgain(startMs = handoff.startMs, autoplay = handoff.playing && !afterFailure)
