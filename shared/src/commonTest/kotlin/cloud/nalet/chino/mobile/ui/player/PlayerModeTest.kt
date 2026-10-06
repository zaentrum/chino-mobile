package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the player asks the server for and tells it, by what it plays: all
 *  of it for a title, nothing but the master for one of its extras. */
class PlayerModeTest {
    @Test
    fun aTitleReadsAndWritesEverythingThePlayerKeeps() {
        with(PlayerMode.Title) {
            assertTrue(playInfo)
            assertTrue(progress)
            assertTrue(watched)
            assertTrue(segments)
            assertTrue(trickplay)
            assertTrue(sidecarSubtitles)
            assertTrue(episodes)
            assertTrue(playbackEvents)
            // It stays at its end (the up-next countdown, the chrome).
            assertFalse(closesAtEnd)
            assertFalse(trailerPlay)
        }
    }

    @Test
    fun anExtraAsksForNothingButItsMasterAndTouchesNothingOfTheTitle() {
        with(PlayerMode.Extra) {
            // No play info, no resume, no progress, no watched (Continue
            // Watching never hears of it), no segments, no scrub previews,
            // no sidecars, no next episode or its prewarm.
            assertFalse(playInfo)
            assertFalse(progress)
            assertFalse(watched)
            assertFalse(segments)
            assertFalse(trickplay)
            assertFalse(sidecarSubtitles)
            assertFalse(episodes)
            // One trailer_play is its telemetry, and it closes at its end.
            assertFalse(playbackEvents)
            assertTrue(trailerPlay)
            assertTrue(closesAtEnd)
        }
    }

    @Test
    fun anExtraIdOpensTheExtraMode() {
        assertEquals(PlayerMode.Extra, PlayerMode.of("x1"))
        assertEquals(PlayerMode.Title, PlayerMode.of(null))
        assertEquals(PlayerMode.Title, PlayerMode.of(""))
    }
}
