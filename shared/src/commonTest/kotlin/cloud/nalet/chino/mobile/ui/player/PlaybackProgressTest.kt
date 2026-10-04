package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where playback resumes and what the player may write back — chino-tizen's
 *  progress.test.ts cases, which the iOS player plays by. */
class PlaybackProgressTest {
    @Test
    fun theSavedPositionIsWherePlaybackResumes() {
        assertEquals(1834, resumeStartSec(ResumeInput(savedSec = 1834, durationSec = 5400.0)))
        assertEquals(1834, resumeStartSec(ResumeInput(savedSec = 1834, durationSec = 0.0)))
    }

    @Test
    fun aBarelyStartedOrFinishedPositionStartsAtTheHead() {
        assertEquals(0, resumeStartSec(ResumeInput(savedSec = 30, durationSec = 5400.0)))
        assertEquals(0, resumeStartSec(ResumeInput(savedSec = 0, durationSec = 5400.0)))
        assertEquals(0, resumeStartSec(ResumeInput(savedSec = 5340, durationSec = 5400.0)))
        assertEquals(5339, resumeStartSec(ResumeInput(savedSec = 5339, durationSec = 5400.0)))
    }

    @Test
    fun fromTheStartWinsOverEverythingAHandOffOverTheSavedPosition() {
        assertEquals(0, resumeStartSec(ResumeInput(1834, 5400.0, startOver = true, handoffSec = 600)))
        assertEquals(600, resumeStartSec(ResumeInput(1834, 5400.0, handoffSec = 600)))
        // A hand-off at the very head is no hand-off: the saved position counts.
        assertEquals(1834, resumeStartSec(ResumeInput(1834, 5400.0, handoffSec = 1)))
        assertEquals(1834, resumeStartSec(ResumeInput(1834, 5400.0, handoffSec = -1)))
    }

    @Test
    fun anUnreadableSavedPositionStartsAtTheHeadAndWritesNothing() {
        assertEquals(0, resumeStartSec(ResumeInput(savedSec = null, durationSec = 5400.0)))
        assertFalse(mayWriteProgress(ResumeInput(savedSec = null, durationSec = 5400.0)))
        // The viewer chose where to start: what they play from there is theirs.
        assertTrue(mayWriteProgress(ResumeInput(savedSec = null, startOver = true)))
        assertTrue(mayWriteProgress(ResumeInput(savedSec = null, handoffSec = 600)))
        // "Never watched" reads as 0, not as unknown.
        assertTrue(mayWriteProgress(ResumeInput(savedSec = 0, durationSec = 5400.0)))
    }

    @Test
    fun nothingIsWrittenBeforePlaybackReportsAPosition() {
        val g = ProgressGuard(writable = true)
        assertNull(g.position())
        g.played(0.0)
        g.played(Double.NaN)
        g.played(-3.0)
        assertNull(g.position())
    }

    @Test
    fun theHeadOfTheStreamBeforeTheResumeSeekLandsIsNeverWritten() {
        // AVPlayer reports 0:00 until the resume seek completes: a session
        // that resumed at 30:34 must not write the 0:0x it saw first.
        val g = ProgressGuard(writable = true)
        g.expectSeek(1834.0)
        g.played(0.2)
        g.played(1.4)
        assertNull(g.position())
        g.played(1830.5) // landed on the segment boundary just before the target
        assertEquals(1830, g.position())
        g.played(1841.2)
        assertEquals(1841, g.position())
    }

    @Test
    fun aSeekTheViewerMadeIsNotAPositionUntilPlaybackGetsThere() {
        val g = ProgressGuard(writable = true)
        g.played(600.7)
        g.expectSeek(1200.0) // scrubbed ahead; back pressed before the seek lands
        g.played(601.1) // the old position, reported while the seek is pending
        assertEquals(600, g.position())
        g.played(1200.4)
        assertEquals(1200, g.position())
        // Seeking back: the positions on the way are real, the target is reached.
        g.expectSeek(900.0)
        g.played(1201.0)
        g.played(900.2)
        assertEquals(900, g.position())
    }

    @Test
    fun aQualitySwitchKeepsTheLastPlayedPositionUntilTheReloadPlays() {
        val g = ProgressGuard(writable = true)
        g.played(2400.9)
        g.expectSeek(2400.0)
        g.played(0.5) // the rebuilt item before its seek lands
        assertEquals(2400, g.position())
    }

    @Test
    fun aSessionThatMayNotWriteNeverWrites() {
        val g = ProgressGuard(writable = false)
        g.played(42.0)
        assertNull(g.position())
    }

    @Test
    fun watchedInTheCreditsOrPast95Percent() {
        assertTrue(reachedWatched(positionMs = 100_000, durationMs = 240_000, inCredits = true))
        assertTrue(reachedWatched(positionMs = 228_000, durationMs = 240_000, inCredits = false))
        assertFalse(reachedWatched(positionMs = 227_999, durationMs = 240_000, inCredits = false))
        // An unknown duration, or nothing played, is no reason to mark it.
        assertFalse(reachedWatched(positionMs = 228_000, durationMs = 0, inCredits = false))
        assertFalse(reachedWatched(positionMs = 0, durationMs = 0, inCredits = false))
    }
}
