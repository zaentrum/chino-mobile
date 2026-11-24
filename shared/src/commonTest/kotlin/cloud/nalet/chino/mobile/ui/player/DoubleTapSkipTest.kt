package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A double tap on the picture skips 10 seconds (change 11.C54 of the
 * specification, its section 11.4.4): the timing of the taps, the halves,
 * the sums, the ends of the title, and the mark's words. A title of ten
 * minutes at its first minute, unless a test says otherwise.
 */
class DoubleTapSkipTest {
    private val length = 600_000L
    private val minute = 60_000L

    private fun DoubleTapSkips.right(atMs: Long, positionMs: Long = minute, durationMs: Long = length, offered: Boolean = true) =
        tap(atMs, forward = true, positionMs = positionMs, durationMs = durationMs, offered = offered)

    private fun DoubleTapSkips.left(atMs: Long, positionMs: Long = minute, durationMs: Long = length, offered: Boolean = true) =
        tap(atMs, forward = false, positionMs = positionMs, durationMs = durationMs, offered = offered)

    @Test
    fun change11_C54_twoTapsInTheRightHalfWithin300MsSkip10SecondsForward() {
        val taps = DoubleTapSkips()

        assertIs<PictureTap.First>(taps.right(atMs = 1_000))
        val skip = taps.right(atMs = 1_300)

        assertEquals(PictureTap.Skip(forward = true, seconds = 10, toMs = minute + 10_000), skip)
    }

    @Test
    fun change11_C54_twoTapsInTheLeftHalfSkip10SecondsBack() {
        val taps = DoubleTapSkips()

        taps.left(atMs = 1_000)
        val skip = taps.left(atMs = 1_150)

        assertEquals(PictureTap.Skip(forward = false, seconds = 10, toMs = minute - 10_000), skip)
    }

    @Test
    fun change11_C54_theStepIsThePlayersSeekStep() {
        val taps = DoubleTapSkips()
        taps.right(atMs = 0)

        val skip = assertIs<PictureTap.Skip>(taps.right(atMs = 100))

        assertEquals(minute + SEEK_STEP_MS, skip.toMs)
        assertEquals((SEEK_STEP_MS / 1000).toInt(), skip.seconds)
    }

    @Test
    fun change11_C54_aSecondTapLaterThan300MsIsASingleTapEach() {
        val taps = DoubleTapSkips()
        taps.right(atMs = 1_000)

        // No double tap: the first was a single tap, and this one waits in
        // its turn.
        val second = assertIs<PictureTap.First>(taps.right(atMs = 1_301))

        assertTrue(second.earlierWasSingle)
        assertTrue(taps.waited(second.id))
    }

    @Test
    fun change11_C54_aSingleTapIsOneOnceThe300MsHavePassed() {
        val taps = DoubleTapSkips()
        val first = assertIs<PictureTap.First>(taps.right(atMs = 1_000))
        // A tap alone says nothing of the one before it.
        assertFalse(first.earlierWasSingle)

        // The wait is over: a single tap, once.
        assertTrue(taps.waited(first.id))
        assertFalse(taps.waited(first.id))
        // The next tap begins anew, however soon it comes.
        val next = assertIs<PictureTap.First>(taps.right(atMs = 1_310))
        assertFalse(next.earlierWasSingle)
    }

    @Test
    fun change11_C54_aTapInTheLeftHalfAndThenOneInTheRightAreTwoSingleTaps() {
        val taps = DoubleTapSkips()
        val left = assertIs<PictureTap.First>(taps.left(atMs = 1_000))

        val right = assertIs<PictureTap.First>(taps.right(atMs = 1_100))

        // The left one is a single tap at once; the right one waits out its
        // own 300 ms, and is one too.
        assertTrue(right.earlierWasSingle)
        assertFalse(taps.waited(left.id))
        assertTrue(taps.waited(right.id))
    }

    @Test
    fun change11_C54_furtherTapsWithin600MsAddUpAndFourTapsGo30Seconds() {
        val taps = DoubleTapSkips()
        taps.right(atMs = 0)

        // The title plays on between the taps: the sum counts from the place
        // the first skip started at, not from where each seek has got to.
        val second = taps.right(atMs = 200, positionMs = minute)
        val third = taps.right(atMs = 800, positionMs = minute + 10_400)
        val fourth = taps.right(atMs = 1_400, positionMs = minute + 20_300)

        assertEquals(PictureTap.Skip(forward = true, seconds = 10, toMs = minute + 10_000), second)
        assertEquals(PictureTap.Skip(forward = true, seconds = 20, toMs = minute + 20_000), third)
        assertEquals(PictureTap.Skip(forward = true, seconds = 30, toMs = minute + 30_000), fourth)
    }

    @Test
    fun change11_C54_aTapMoreThan600MsAfterTheLastBeginsAnew() {
        val taps = DoubleTapSkips()
        taps.left(atMs = 0)
        taps.left(atMs = 200)

        val late = assertIs<PictureTap.First>(taps.left(atMs = 801))

        assertFalse(late.earlierWasSingle)
        // And two taps from there are a new skip of 10 seconds.
        assertEquals(PictureTap.Skip(forward = false, seconds = 10, toMs = minute - 10_000), taps.left(atMs = 1_000))
    }

    @Test
    fun change11_C54_aTapInTheOtherHalfAddsNothing() {
        val taps = DoubleTapSkips()
        taps.right(atMs = 0)
        taps.right(atMs = 200)

        // In the left half: the first of maybe two there.
        val other = assertIs<PictureTap.First>(taps.left(atMs = 400))
        assertFalse(other.earlierWasSingle)
        // Back in the right half, still within 600 ms of the skip's last
        // tap: the left one was a single tap, and nothing is added.
        val back = assertIs<PictureTap.First>(taps.right(atMs = 600))
        assertTrue(back.earlierWasSingle)
    }

    @Test
    fun change11_C54_twoTapsInTheOtherHalfSkipTheOtherWayFromWhereTheTitleIs() {
        val taps = DoubleTapSkips()
        taps.right(atMs = 0)
        taps.right(atMs = 200)
        taps.left(atMs = 400, positionMs = minute + 10_000)

        val skip = taps.left(atMs = 600, positionMs = minute + 10_000)

        assertEquals(PictureTap.Skip(forward = false, seconds = 10, toMs = minute), skip)
    }

    @Test
    fun change11_C54_backNeverGoesBeforeTheStartAndTheMarkSaysTheSecondsAskedFor() {
        val taps = DoubleTapSkips()
        taps.left(atMs = 0, positionMs = 4_000)

        val skip = taps.left(atMs = 200, positionMs = 4_000)
        val more = taps.left(atMs = 400, positionMs = 0)

        assertEquals(PictureTap.Skip(forward = false, seconds = 10, toMs = 0), skip)
        assertEquals(PictureTap.Skip(forward = false, seconds = 20, toMs = 0), more)
    }

    @Test
    fun change11_C54_forwardStopsASecondBeforeTheEnd() {
        val taps = DoubleTapSkips()
        taps.right(atMs = 0, positionMs = length - 5_000)

        val skip = taps.right(atMs = 200, positionMs = length - 5_000)
        val more = taps.right(atMs = 400, positionMs = length - 1_000)

        // The skip itself never ends the title; the mark says what was asked.
        assertEquals(PictureTap.Skip(forward = true, seconds = 10, toMs = length - 1_000), skip)
        assertEquals(PictureTap.Skip(forward = true, seconds = 20, toMs = length - 1_000), more)
    }

    @Test
    fun change11_C54_forwardInTheLastSecondGoesNowhereAndNeverBack() {
        assertEquals(length - 400, skipTargetMs(fromMs = length - 400, skipMs = 10_000, forward = true, durationMs = length))
        assertEquals(length - 1_000, skipTargetMs(fromMs = length - 1_000, skipMs = 10_000, forward = true, durationMs = length))
    }

    @Test
    fun change11_C54_threeTapsBackLandAtThePlaceTheFirstSkipStartedAtMinus20Seconds() {
        val taps = DoubleTapSkips()
        taps.left(atMs = 0, positionMs = minute)
        taps.left(atMs = 200, positionMs = minute)

        // The first seek has landed and the title has played a little.
        val third = taps.left(atMs = 700, positionMs = minute - 9_600)

        assertEquals(PictureTap.Skip(forward = false, seconds = 20, toMs = minute - 20_000), third)
    }

    @Test
    fun change11_C54_whileTheTitlesLengthIsNotKnownForwardDoesNothing() {
        assertEquals(null, skipTargetMs(fromMs = minute, skipMs = 10_000, forward = true, durationMs = 0))
        // The taps still count: with the length known at the third, it goes
        // to their sum from the place the first skip started at.
        val taps = DoubleTapSkips()
        taps.right(atMs = 0, durationMs = 0)
        assertEquals(PictureTap.Skip(forward = true, seconds = 10, toMs = null), taps.right(atMs = 200, durationMs = 0))
        assertEquals(PictureTap.Skip(forward = true, seconds = 20, toMs = minute + 20_000), taps.right(atMs = 400, positionMs = minute + 200))
        // Back needs no length.
        assertEquals(minute - 10_000, skipTargetMs(fromMs = minute, skipMs = 10_000, forward = false, durationMs = 0))
    }

    @Test
    fun change11_C54_onlyThePictureNotWithAScreenReaderOnAMenuOpenOrACardShowing() {
        assertTrue(doubleTapOffered(oneFingerAlone = true, screenReaderOn = false, menuOpen = false, cardShown = false))
        // The reader's own gesture; and a menu's or a card's tap, by time:
        // also a tap on the picture beside them.
        assertFalse(doubleTapOffered(oneFingerAlone = true, screenReaderOn = true, menuOpen = false, cardShown = false))
        assertFalse(doubleTapOffered(oneFingerAlone = true, screenReaderOn = false, menuOpen = true, cardShown = false))
        assertFalse(doubleTapOffered(oneFingerAlone = true, screenReaderOn = false, menuOpen = false, cardShown = true))
    }

    @Test
    fun change11_C54_onlyATouchOfOneFingerAloneIsATapOfADoubleTap() {
        // Two fingers that never became a pinch: no tap of a double tap.
        assertFalse(doubleTapOffered(oneFingerAlone = false, screenReaderOn = false, menuOpen = false, cardShown = false))
        // Two such touches within 0.3 seconds skip nothing: each is a
        // single tap at once; and one after a tap that waited ends its wait.
        val taps = DoubleTapSkips()
        assertEquals(PictureTap.Single(earlierWasSingle = false), taps.right(atMs = 0, offered = false))
        assertEquals(PictureTap.Single(earlierWasSingle = false), taps.right(atMs = 150, offered = false))
        assertIs<PictureTap.First>(taps.right(atMs = 1_000))
        assertEquals(PictureTap.Single(earlierWasSingle = true), taps.right(atMs = 1_150, offered = false))
    }

    @Test
    fun change11_C54_whereNoDoubleTapIsOfferedEveryTapIsASingleTapAtOnce() {
        val taps = DoubleTapSkips()

        // Every tap is a single tap, at once: no waiting, no skip.
        assertEquals(PictureTap.Single(earlierWasSingle = false), taps.right(atMs = 0, offered = false))
        assertEquals(PictureTap.Single(earlierWasSingle = false), taps.right(atMs = 100, offered = false))
        assertIs<PictureTap.First>(taps.right(atMs = 200))
        // A tap that waited when the menu opened is a single tap too.
        assertEquals(PictureTap.Single(earlierWasSingle = true), taps.right(atMs = 300, offered = false))
        // And no skip under way is added to.
        taps.right(atMs = 1_000)
        taps.right(atMs = 1_100)
        taps.right(atMs = 1_200, offered = false)
        assertIs<PictureTap.First>(taps.right(atMs = 1_300))
    }

    @Test
    fun change11_C54_theMarkSaysTheSecondsSoFarInTheWordsTablesWords() {
        assertEquals("−10 s", skipMarkText(forward = false, seconds = 10))
        assertEquals("+10 s", skipMarkText(forward = true, seconds = 10))
        assertEquals("−20 s", skipMarkText(forward = false, seconds = 20))
        assertEquals("+30 s", skipMarkText(forward = true, seconds = 30))
        // The minus sign, not a hyphen.
        assertEquals(0x2212, skipMarkText(forward = false, seconds = 10).first().code)
    }

    @Test
    fun change11_C54_theTimesAreTheSpecifications() {
        assertEquals(300L, DOUBLE_TAP_WITHIN_MS)
        assertEquals(600L, DOUBLE_TAP_ADDS_WITHIN_MS)
        assertEquals(600L, SKIP_MARK_MS)
        assertEquals(1_000L, SKIP_KEEPS_FROM_END_MS)
    }
}
