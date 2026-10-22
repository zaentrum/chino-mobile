package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** A packaged title retries in place; only an on-the-fly title steps down. */
class PlaybackRecoveryTest {
    @Test
    fun aPackagedTitleIsRetriedInPlaceNeverSteppedDown() {
        for (q in listOf("auto", "v1", "high", "medium", "low")) {
            assertEquals(PlaybackRecovery.RetryInPlace, playbackRecovery("packaged", q, triesInPlace = 0), q)
            assertEquals(PlaybackRecovery.RetryInPlace, playbackRecovery("packaged", q, triesInPlace = 2), q)
            assertEquals(PlaybackRecovery.GiveUp, playbackRecovery("packaged", q, triesInPlace = IN_PLACE_TRIES), q)
        }
    }

    @Test
    fun anOnTheFlyTitleStepsDownTheLadderARungAtATime() {
        for (mode in listOf("transcode", "remux", "passthrough", null)) {
            assertEquals(PlaybackRecovery.StepDown("medium"), playbackRecovery(mode, "high", 0), "$mode")
            assertEquals(PlaybackRecovery.StepDown("low"), playbackRecovery(mode, "medium", 0), "$mode")
            // Low is the last rung; the retries in place are a packaged title's.
            assertEquals(PlaybackRecovery.GiveUp, playbackRecovery(mode, "low", 0), "$mode")
        }
        // A q that is no rung of the ladder (a packaged pick): nothing below it.
        assertEquals(PlaybackRecovery.GiveUp, playbackRecovery("transcode", "v1", 0))
        assertEquals(PlaybackRecovery.StepDown("medium"), playbackRecovery("transcode", "HIGH", 0))
    }

    @Test
    fun anOnTheFlyTitleStepsDownTheListedEntriesToAnotherPicture() {
        // A 720p source: high and medium are one picture, "Medium (720p)" -
        // high steps to low, never to the same picture again.
        val listed = listOf(
            cloud.nalet.chino.mobile.data.api.QualityRung("high", "Medium (720p)"),
            cloud.nalet.chino.mobile.data.api.QualityRung("medium", "Medium (720p)"),
            cloud.nalet.chino.mobile.data.api.QualityRung("low", "Low (480p)"),
        )
        assertEquals(PlaybackRecovery.StepDown("low"), playbackRecovery("transcode", "high", 0, listed))
        assertEquals(PlaybackRecovery.StepDown("low"), playbackRecovery("transcode", "medium", 0, listed))
        assertEquals(PlaybackRecovery.GiveUp, playbackRecovery("transcode", "low", 0, listed))
        // A 360p source: every q one picture - nothing to step to.
        val small = listOf("high", "medium", "low").map { cloud.nalet.chino.mobile.data.api.QualityRung(it, "Lowest (360p)") }
        assertEquals(PlaybackRecovery.GiveUp, playbackRecovery("transcode", "high", 0, small))
        // No list (a direct stream): the fixed ladder.
        assertEquals(PlaybackRecovery.StepDown("medium"), playbackRecovery("passthrough", "high", 0, emptyList()))
        // The menu: one entry for one picture, the server's names as given.
        val ladder = qualityLadder(cloud.nalet.chino.mobile.data.api.PlayInfo(mode = "transcode", qualities = listed))
        assertEquals(listOf("high" to "Medium (720p)", "low" to "Low (480p)"), ladder.options.map { it.id to it.label })
    }

    @Test
    fun aPackagedTitleThatKeepsFailingWhereItWasIsGivenUpOn() {
        val r = PlaybackRecoveries()
        assertEquals(PlaybackRecovery.RetryInPlace, r.next("packaged", "auto", 600_000))
        assertEquals(PlaybackRecovery.RetryInPlace, r.next("packaged", "auto", 601_000))
        assertEquals(PlaybackRecovery.RetryInPlace, r.next("packaged", "auto", 601_500))
        assertEquals(PlaybackRecovery.GiveUp, r.next("packaged", "auto", 602_000))
    }

    @Test
    fun playbackThatGotOnStartsTheRetriesAgain() {
        val r = PlaybackRecoveries()
        repeat(IN_PLACE_TRIES) { assertEquals(PlaybackRecovery.RetryInPlace, r.next("packaged", "auto", 600_000)) }
        // Half an hour on, a new stumble gets its own retries...
        repeat(IN_PLACE_TRIES) { n ->
            assertEquals(PlaybackRecovery.RetryInPlace, r.next("packaged", "auto", 2_400_000 + n * 1_000L))
        }
        // ...and one that fails again before playback got on is given up on.
        assertEquals(PlaybackRecovery.GiveUp, r.next("packaged", "auto", 2_402_000 + RECOVERED_AFTER_MS))
    }

    @Test
    fun anOnTheFlyTitleStepsDownOnEachFailureUntilLow() {
        val r = PlaybackRecoveries()
        assertEquals(PlaybackRecovery.StepDown("medium"), r.next("transcode", "high", 120_000))
        assertEquals(PlaybackRecovery.StepDown("low"), r.next("transcode", "medium", 121_000))
        assertEquals(PlaybackRecovery.GiveUp, r.next("transcode", "low", 122_000))
    }
}
