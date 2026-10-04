package cloud.nalet.chino.mobile.ui.player

/**
 * Where playback starts, and which position the player may write back.
 *
 * chino-api keeps ONE position per user and item (GET/POST
 * /items/{id}/progress) and every client resumes from it, so a position the
 * player writes overwrites what the viewer watched on the web or the TV. The
 * player therefore only ever writes a second it actually played to in this
 * session: never the head of the stream before the resume seek lands, never a
 * seek target playback has not reached, nothing at all when the saved position
 * could not be read.
 *
 * chino-web's resume rules (PlayerPage.tsx) and chino-tizen's guard
 * (src/lib/progress.ts) — the TV's fix for the 0:0x it posted before its
 * resume seek landed, which cost every other device the viewer's place.
 */

/** A saved position at or below this is "not really started": playback starts
 *  at the head. chino-api's continue-watching applies the same floor. */
const val RESUME_FLOOR_SEC = 30

/** A saved position within this of the end is "finished": playback starts at
 *  the head instead of in the credits. */
const val FINISHED_MARGIN_SEC = 60

/** How far short of an expected seek a reported playhead may be and still
 *  count as the seek having landed. Seeks snap back to a segment or key frame,
 *  a few seconds at most; the head of the stream, reported before a resume
 *  seek lands, is much further off. */
const val SEEK_LANDING_SLACK_SEC = 10.0

/** What playback starts from. */
data class ResumeInput(
    /** GET /items/{id}/progress's position_sec; null when it could not be read. */
    val savedSec: Int?,
    /** The title's duration in seconds, 0 when unknown. */
    val durationSec: Double = 0.0,
    /** The viewer asked to start from the head ("Play from start"). */
    val startOver: Boolean = false,
    /** A hand-off that played up to this second (Zap's "Watch from here");
     *  negative for none. */
    val handoffSec: Int = -1,
)

/**
 * Where playback starts, in whole seconds (0 = the head): from the head when
 * asked; exactly where a hand-off was; otherwise the saved position, unless it
 * is barely started or finished.
 */
fun resumeStartSec(o: ResumeInput): Int {
    if (o.startOver) return 0
    if (o.handoffSec > 1) return o.handoffSec
    val saved = o.savedSec ?: return 0
    if (saved <= RESUME_FLOOR_SEC) return 0
    if (o.durationSec > 0 && saved >= o.durationSec - FINISHED_MARGIN_SEC) return 0
    return saved
}

/**
 * Whether this session may write a position at all. When the saved position
 * could not be read, playback starts at the head without knowing what it would
 * overwrite, so nothing is written — unless the viewer chose where to start
 * (from the head, or a hand-off).
 */
fun mayWriteProgress(o: ResumeInput): Boolean =
    o.startOver || o.handoffSec > 1 || o.savedSec != null

/**
 * The last position playback actually reached, which is the only one the
 * player writes. Nothing counts until the player reports a playing position;
 * while a seek is pending ([expectSeek]: the resume seek, a quality-switch
 * reload, a skip, a scrub), positions short of its target are ignored. A UI's
 * optimistic playhead never reaches the guard.
 */
class ProgressGuard(private val writable: Boolean) {
    private var expected: Double? = null
    private var last: Double? = null

    /** A seek to [sec] is under way that playback has not reached yet. */
    fun expectSeek(sec: Double) {
        expected = if (sec.isFinite() && sec > 0) sec else null
    }

    /** The player reported the playhead at [sec] while playing. */
    fun played(sec: Double) {
        if (!sec.isFinite() || sec <= 0) return
        val target = expected
        if (target != null) {
            if (sec < target - SEEK_LANDING_SLACK_SEC) return
            expected = null
        }
        last = sec
    }

    /** The position to write now, in whole seconds, or null when there is none
     *  (nothing played yet, or this session may not write). */
    fun position(): Int? {
        if (!writable) return null
        val pos = last?.toInt() ?: return null
        return pos.takeIf { it > 0 }
    }
}

/**
 * Whether the title counts as watched: the playhead is in the credits, or past
 * 95 % of a known duration — chino-web's rule, which POSTs
 * /me/items/{id}/watched once when either is first true.
 */
fun reachedWatched(positionMs: Long, durationMs: Long, inCredits: Boolean): Boolean =
    inCredits || (durationMs > 0 && positionMs > 0 && positionMs >= durationMs * 0.95)
