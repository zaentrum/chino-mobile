package cloud.nalet.chino.mobile.ui.player

/**
 * What a player does when playback fails — a stream that will not load, a
 * decoder that gives up — before it tells the viewer.
 *
 * A packaged title is pre-segmented files: its ladder for the device's caps
 * (Auto) or the one rung the viewer picked, never a transcode, so there is
 * nothing for the player to step down to; a lower `?q=` would be served the
 * same files. It is retried in place, at its position, a few times
 * (chino-web's stallAction; the iOS player's Try again). A title that is
 * not packaged runs on the fly and steps down the transcode ladder a rung
 * at a time — high, medium, low — where it was (chino-androidtv's quality
 * fallback); a direct stream's "high" moves onto the ladder at medium.
 */
sealed interface PlaybackRecovery {
    /** Load the same quality again, where playback was. */
    data object RetryInPlace : PlaybackRecovery

    /** Rebuild at [quality], where playback was. */
    data class StepDown(val quality: String) : PlaybackRecovery

    /** Nothing further to try: say so. */
    data object GiveUp : PlaybackRecovery
}

/** In-place retries of a packaged title before the player gives up (chino-web's
 *  IN_PLACE_TRIES). */
const val IN_PLACE_TRIES = 3

/** The on-the-fly ladder, top down — the `?q=` values chino-stream transcodes. */
val ON_THE_FLY_LADDER: List<String> = listOf("high", "medium", "low")

/**
 * The recovery for a failure of a title in [mode] (/play/info's; null when
 * it could not be read) playing at [quality], after [triesInPlace] in-place
 * retries. A mode not known is taken for on the fly: stepping a packaged
 * title down only reloads its ladder.
 */
fun playbackRecovery(mode: String?, quality: String, triesInPlace: Int): PlaybackRecovery {
    if (mode.equals("packaged", ignoreCase = true)) {
        return if (triesInPlace < IN_PLACE_TRIES) PlaybackRecovery.RetryInPlace else PlaybackRecovery.GiveUp
    }
    val rung = ON_THE_FLY_LADDER.indexOf(quality.lowercase())
    if (rung < 0 || rung == ON_THE_FLY_LADDER.lastIndex) return PlaybackRecovery.GiveUp
    return PlaybackRecovery.StepDown(ON_THE_FLY_LADDER[rung + 1])
}

/** Playback that has got this far past the previous failure counts as having
 *  recovered: the in-place retries start again. */
const val RECOVERED_AFTER_MS = 30_000L

/**
 * The failures of one playback screen, in turn: each [next] says what to do
 * with one ([playbackRecovery]) and counts the in-place retries. A failure
 * more than [RECOVERED_AFTER_MS] of playback past the one before starts the
 * count again, so a film that stumbles twice an hour apart is not given up
 * on, while one that fails again where it was is, after [IN_PLACE_TRIES].
 */
class PlaybackRecoveries {
    private var triesInPlace = 0
    private var lastFailureAtMs: Long? = null

    /** The recovery for a failure at [positionMs], playing [quality] of a
     *  title in [mode]. */
    fun next(mode: String?, quality: String, positionMs: Long): PlaybackRecovery {
        val last = lastFailureAtMs
        if (last != null && positionMs - last > RECOVERED_AFTER_MS) triesInPlace = 0
        lastFailureAtMs = positionMs
        val recovery = playbackRecovery(mode, quality, triesInPlace)
        if (recovery == PlaybackRecovery.RetryInPlace) triesInPlace += 1
        return recovery
    }
}
