package cloud.nalet.chino.mobile.ui.detail

import cloud.nalet.chino.mobile.ui.player.RESUME_FLOOR_SEC
import cloud.nalet.chino.mobile.ui.player.ResumeInput
import cloud.nalet.chino.mobile.ui.player.resumeStartSec

/**
 * What the detail page's play button offers for a title's saved position:
 * what the player will do with it ([resumeStartSec], the rule both players
 * start by). "Resume" only where the player resumes.
 */
sealed interface PlayAction {
    /** The player picks up at [sec]: "Resume 3:59", and "Start over". */
    data class Resume(val sec: Int) : PlayAction

    /** Never really started (at most [RESUME_FLOOR_SEC] in): "Play". */
    data object Play : PlayAction

    /** Saved in its last minute — finished; the player starts it over:
     *  "Start over". */
    data object StartOver : PlayAction
}

/** The play button for [savedSec] (GET /progress's position_sec, 0 when
 *  never watched) of a title [durationSec] long (0 when unknown). */
fun playAction(savedSec: Int, durationSec: Double): PlayAction {
    val start = resumeStartSec(ResumeInput(savedSec = savedSec, durationSec = durationSec))
    return when {
        start > 0 -> PlayAction.Resume(start)
        savedSec > RESUME_FLOOR_SEC -> PlayAction.StartOver
        else -> PlayAction.Play
    }
}

/** Whether an episode row shows its "Resume" line and progress bar: the
 *  player resumes it ([resumeStartSec]) — more than 30 s in and not in its
 *  last minute of [durationSec] (0 when unknown). */
fun resumesAt(savedSec: Int, durationSec: Double): Boolean =
    resumeStartSec(ResumeInput(savedSec = savedSec, durationSec = durationSec)) > 0
