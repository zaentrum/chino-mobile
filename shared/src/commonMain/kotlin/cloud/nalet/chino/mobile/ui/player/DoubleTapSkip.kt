package cloud.nalet.chino.mobile.ui.player

/*
 * A double tap on the picture skips 10 seconds (section 11.4.4 of the
 * specification, change 11.C54): what each tap on the picture is - a single
 * tap, the first of maybe two, a skip - and where a skip goes. Pure, with the
 * time of each tap given to it, so the tests pin the timing, the halves, the
 * sums and the ends; the player only hands it its taps and does what it says.
 */

/** A second tap in the same half within this of the first makes the two a
 *  double tap; a tap that has waited this long alone is a single tap. */
const val DOUBLE_TAP_WITHIN_MS = 300L

/** A further tap in the same half within this of the last adds to the skip. */
const val DOUBLE_TAP_ADDS_WITHIN_MS = 600L

/** How long a double tap's mark stays after the last tap. */
const val SKIP_MARK_MS = 600L

/** A skip never lands closer to the title's end than this, so the skip
 *  itself never ends a title. */
const val SKIP_KEEPS_FROM_END_MS = 1_000L

/** What a tap on the picture turned out to be. */
sealed interface PictureTap {
    /** Whether the tap before this one, which waited for a second, was a
     *  single tap after all - this one fell in the other half, or came too
     *  late - and is due now. */
    val earlierWasSingle: Boolean

    /** A single tap, at once: where no double tap is offered. */
    data class Single(override val earlierWasSingle: Boolean) : PictureTap

    /** Maybe the first of two: a single tap unless a second follows in its
     *  half within [DOUBLE_TAP_WITHIN_MS] - [DoubleTapSkips.waited] says so
     *  for [id] once that time has passed. */
    data class First(val id: Int, override val earlierWasSingle: Boolean) : PictureTap

    /** The second tap of two, or one more after them: the title goes to
     *  [toMs], and the mark says [seconds], the sum so far, back or
     *  [forward] - the seconds asked for, also where the title's start or
     *  its end cut the skip short. [toMs] null: the skip does nothing, and
     *  shows no mark ([skipTargetMs]). */
    data class Skip(val forward: Boolean, val seconds: Int, val toMs: Long?) : PictureTap {
        override val earlierWasSingle: Boolean get() = false
    }
}

/**
 * Whether a double tap is offered for a tap on the picture. Only for a touch
 * of one finger alone, from down to up ([oneFingerAlone]): two fingers that
 * never became a pinch are no tap of a double tap. Not with a screen reader
 * on: a double tap is the reader's own gesture, for pressing what has its
 * focus. Not while a menu is open or a card shows over the picture - up
 * next, the next episode's: a tap is that part's then, by time and not by
 * place, so also a tap beside it. Such a tap does at once what a single tap
 * does.
 */
fun doubleTapOffered(oneFingerAlone: Boolean, screenReaderOn: Boolean, menuOpen: Boolean, cardShown: Boolean): Boolean =
    oneFingerAlone && !screenReaderOn && !menuOpen && !cardShown

/** A double tap's mark as the Words table writes it: "−10 s", "+30 s". */
fun skipMarkText(forward: Boolean, seconds: Int): String = if (forward) "+$seconds s" else "−$seconds s"

/**
 * Where a skip of [skipMs] from [fromMs] lands in a title of [durationMs] (0
 * or less: not known), or null where it does nothing. Back never goes before
 * the start. Forward stops [SKIP_KEEPS_FROM_END_MS] before the end - and
 * never goes back to get there; while the title's length is not known it
 * does nothing.
 */
fun skipTargetMs(fromMs: Long, skipMs: Long, forward: Boolean, durationMs: Long): Long? = when {
    !forward -> (fromMs - skipMs).coerceAtLeast(0L)
    durationMs <= 0L -> null
    else -> (fromMs + skipMs).coerceAtMost((durationMs - SKIP_KEEPS_FROM_END_MS).coerceAtLeast(fromMs))
}

/**
 * The taps on the picture, in turn. Two taps in the same half, the second
 * within [DOUBLE_TAP_WITHIN_MS] of the first, skip [stepMs]: back in the left
 * half, forward in the right. Each further tap in that half within
 * [DOUBLE_TAP_ADDS_WITHIN_MS] of the last adds [stepMs], and the title goes
 * to the sum, counted from the place the first skip started at - where the
 * title was at the second tap - whatever it has played since. A tap in the
 * left half and then one in the right are two single taps. The halves are
 * those of the whole player, the black beside the picture too.
 */
class DoubleTapSkips(private val stepMs: Long = SEEK_STEP_MS) {
    private class Waiting(val id: Int, val atMs: Long, val forward: Boolean)

    private class Run(val forward: Boolean, val fromMs: Long, var taps: Int, var lastAtMs: Long)

    private var ids = 0
    private var waiting: Waiting? = null
    private var run: Run? = null

    /**
     * A tap on the picture at [atMs], in its right half ([forward]) or its
     * left, the title at [positionMs] of [durationMs]. [offered]: whether a
     * double tap is - not with a screen reader on, whose own gesture it is,
     * nor while a menu is open or a card shows over the picture, also for a
     * tap beside them; then every tap is a single tap at once, and ends the
     * taps so far.
     */
    fun tap(atMs: Long, forward: Boolean, positionMs: Long, durationMs: Long, offered: Boolean = true): PictureTap {
        val before = waiting
        waiting = null
        if (!offered) {
            run = null
            return PictureTap.Single(earlierWasSingle = before != null)
        }
        // One more tap of a skip under way.
        run?.takeIf { before == null && it.forward == forward && atMs - it.lastAtMs <= DOUBLE_TAP_ADDS_WITHIN_MS }?.let { more ->
            more.taps += 1
            more.lastAtMs = atMs
            return skip(more, durationMs)
        }
        run = null
        // The second tap of two.
        if (before != null && before.forward == forward && atMs - before.atMs <= DOUBLE_TAP_WITHIN_MS) {
            return skip(Run(forward, fromMs = positionMs, taps = 2, lastAtMs = atMs).also { run = it }, durationMs)
        }
        val first = Waiting(id = ++ids, atMs = atMs, forward = forward)
        waiting = first
        return PictureTap.First(first.id, earlierWasSingle = before != null)
    }

    /** [DOUBLE_TAP_WITHIN_MS] have passed since the tap [id]: whether it is
     *  a single tap - no second one came. */
    fun waited(id: Int): Boolean {
        if (waiting?.id != id) return false
        waiting = null
        return true
    }

    private fun skip(run: Run, durationMs: Long): PictureTap.Skip {
        val skipMs = (run.taps - 1) * stepMs
        return PictureTap.Skip(
            forward = run.forward,
            seconds = (skipMs / 1000L).toInt(),
            toMs = skipTargetMs(run.fromMs, skipMs, run.forward, durationMs),
        )
    }
}
