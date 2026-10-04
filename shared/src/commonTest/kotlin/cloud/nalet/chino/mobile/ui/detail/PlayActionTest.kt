package cloud.nalet.chino.mobile.ui.detail

import cloud.nalet.chino.mobile.ui.player.ResumeInput
import cloud.nalet.chino.mobile.ui.player.resumeStartSec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The detail page offers "Resume" exactly where the player resumes. */
class PlayActionTest {
    @Test
    fun aTitleInProgressResumesWhereThePlayerWill() {
        assertEquals(PlayAction.Resume(239), playAction(savedSec = 239, durationSec = 5400.0))
        // No known duration: the player resumes any position past the floor.
        assertEquals(PlayAction.Resume(5390), playAction(savedSec = 5390, durationSec = 0.0))
    }

    @Test
    fun aTitleSavedInItsLastMinuteIsStartedOverNotResumed() {
        // 3:59 of a 4:30 clip (the bug): the player starts it over.
        assertEquals(PlayAction.StartOver, playAction(savedSec = 239, durationSec = 270.0))
        assertEquals(PlayAction.StartOver, playAction(savedSec = 5340, durationSec = 5400.0))
        assertEquals(PlayAction.Resume(5339), playAction(savedSec = 5339, durationSec = 5400.0))
    }

    @Test
    fun aBarelyStartedOrUnwatchedTitleIsPlayed() {
        assertEquals(PlayAction.Play, playAction(savedSec = 0, durationSec = 5400.0))
        assertEquals(PlayAction.Play, playAction(savedSec = 30, durationSec = 5400.0))
        assertEquals(PlayAction.Resume(31), playAction(savedSec = 31, durationSec = 5400.0))
    }

    @Test
    fun theButtonAndThePlayerAgreeEverywhere() {
        for (duration in listOf(0.0, 45.0, 90.0, 270.0, 5400.0)) {
            for (saved in 0..5400 step 7) {
                val player = resumeStartSec(ResumeInput(savedSec = saved, durationSec = duration))
                val button = playAction(saved, duration)
                assertEquals(player > 0, button is PlayAction.Resume, "saved $saved of $duration")
                if (button is PlayAction.Resume) assertEquals(player, button.sec)
            }
        }
    }

    @Test
    fun anEpisodeRowResumesLikeThePlayer() {
        assertTrue(resumesAt(savedSec = 600, durationSec = 2700.0))
        assertFalse(resumesAt(savedSec = 2650, durationSec = 2700.0))
        assertFalse(resumesAt(savedSec = 20, durationSec = 2700.0))
        // The feed's duration unknown: past the floor resumes.
        assertTrue(resumesAt(savedSec = 2650, durationSec = 0.0))
    }
}
