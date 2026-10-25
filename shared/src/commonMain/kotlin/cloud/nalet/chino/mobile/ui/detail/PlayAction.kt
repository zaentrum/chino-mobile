package cloud.nalet.chino.mobile.ui.detail

import cloud.nalet.chino.mobile.ui.player.RESUME_FLOOR_SEC
import cloud.nalet.chino.mobile.ui.player.ResumeInput
import cloud.nalet.chino.mobile.ui.player.resumeStartSec

/**
 * What the detail page's play button offers for a title's resume point:
 * what the player will do with it ([resumeStartSec], the rule both players
 * start by). "Resume" only where the player resumes; else "Play", from the
 * start. chino-api gives no resume point for a title the viewer finished
 * (its last 5 % or 2 minutes, its one rule), so such a title has "Play" -
 * no "Resume", no "Start over" - as on the web and the TV.
 */
sealed interface PlayAction {
    /** The player picks up at [sec]: "Resume 3:59", and "Start over". */
    data class Resume(val sec: Int) : PlayAction

    /** Nothing to resume - never really started (at most
     *  [RESUME_FLOOR_SEC] in), or finished: "Play". */
    data object Play : PlayAction
}

/** The play button for [savedSec] (GET /progress's position_sec, 0 when
 *  never watched or finished) of a title [durationSec] long (0 when
 *  unknown). */
fun playAction(savedSec: Int, durationSec: Double): PlayAction {
    val start = resumeStartSec(ResumeInput(savedSec = savedSec, durationSec = durationSec))
    return if (start > 0) PlayAction.Resume(start) else PlayAction.Play
}

/** Whether an episode row shows its "Resume" line and progress bar: the
 *  player resumes it ([resumeStartSec]) — more than 30 s in and not in its
 *  last minute of [durationSec] (0 when unknown). */
fun resumesAt(savedSec: Int, durationSec: Double): Boolean =
    resumeStartSec(ResumeInput(savedSec = savedSec, durationSec = durationSec)) > 0

/** The play button's label: "resume 1:43" where the player resumes, else
 *  "play" - on a title's page and on Home's hero alike. */
val PlayAction.label: String
    get() = when (this) {
        is PlayAction.Resume -> "resume ${positionClock(sec)}"
        PlayAction.Play -> "play"
    }

/** A position as a clock: "1:43", "1:02:05". */
fun positionClock(s: Int): String {
    if (s < 0) return "0:00"
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}
