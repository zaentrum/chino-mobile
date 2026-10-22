package cloud.nalet.chino.mobile.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The position the TV plays, written back while casting, as the phone's own
 * player writes it. A fake TV reports its status and ticks the way the Cast
 * SDK's RemoteMediaClient does: a status when something changes, a tick a
 * second while it plays.
 */
class CastProgressTest {
    /** The fake TV and the writes the reporter made of what it reported. */
    private class Tv(var writer: CastWriter? = ME) {
        var now = 0L
        val writes = mutableListOf<CastProgressWrite>()
        var asks = 0
        val reporter = CastProgressReporter(writer = { writer }, now = { now }, write = { writes += it }, askStatus = { asks++ })
        var tag: CastTag? = tagOf("m1")
        var durationMs = 5_400_000L

        fun status(state: RemotePlayerState, positionMs: Long, idle: RemoteIdleReason = RemoteIdleReason.None) =
            reporter.onStatus(RemoteMediaStatus(tag, state, idle, positionMs, durationMs))

        /** One tick, a second after the last. */
        fun tick(positionMs: Long) {
            now += 1_000
            reporter.onProgress(positionMs, durationMs)
        }

        /** Plays [seconds] from [fromMs], a tick a second. */
        fun play(fromMs: Long, seconds: Int) {
            status(RemotePlayerState.Playing, fromMs)
            for (s in 1..seconds) tick(fromMs + s * 1_000L)
        }
    }

    private fun write(itemId: String, sec: Int, final: Boolean, durationSec: Int = 5400, account: String = ME.accountId) =
        CastProgressWrite(account, itemId, sec, durationSec, final)

    @Test
    fun nothingIsWrittenBeforeTheTvPlays() {
        val tv = Tv()
        tv.reporter.loading("L1", "m1", startMs = 600_000)
        tv.status(RemotePlayerState.Loading, 0)
        tv.status(RemotePlayerState.Buffering, 0)
        repeat(30) { tv.tick(0) }
        tv.reporter.onSessionEnding()
        assertTrue(tv.writes.isEmpty())
    }

    @Test
    fun theTvsPositionIsWrittenEveryTenSecondsWhilePlayingForTheAccountItIsOf() {
        val tv = Tv()
        tv.reporter.loading("L1", "m1", startMs = 600_000)
        tv.play(fromMs = 600_000, seconds = 25)
        assertEquals(listOf(write("m1", 610, final = false), write("m1", 620, final = false)), tv.writes)
    }

    @Test
    fun aPositionShortOfWhereTheTvWasToldToStartIsNotPlayedYet() {
        val tv = Tv()
        tv.reporter.loading("L1", "m1", startMs = 600_000)
        // The head of the stream, reported before the start lands.
        tv.status(RemotePlayerState.Playing, 0)
        for (s in 1..9) tv.tick(s * 1_000L)
        tv.tick(600_400)
        assertEquals(listOf(write("m1", 600, final = false)), tv.writes)
    }

    @Test
    fun aPauseWritesThePositionReached() {
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 5)
        tv.status(RemotePlayerState.Paused, 1_005_000)
        assertEquals(listOf(write("m1", 1005, final = false)), tv.writes)
        // Paused, the ticks write nothing more.
        repeat(60) { tv.tick(1_005_000) }
        assertEquals(1, tv.writes.size)
    }

    @Test
    fun stopCastingKeepsTheLastPositionTheTvConfirmed() {
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 4)
        tv.reporter.onSessionEnding()
        assertEquals(listOf(write("m1", 1004, final = true)), tv.writes)
        assertNull(tv.reporter.trackedItemId)
    }

    @Test
    fun theEndWritesTheLastPositionTheServerDecidesItIsFinished() {
        val tv = Tv()
        tv.play(fromMs = 5_390_000, seconds = 9)
        tv.status(RemotePlayerState.Idle, 0, RemoteIdleReason.Finished)
        // The last second played, not the idle player's 0: chino-api's rule
        // then marks the title finished.
        assertEquals(listOf(write("m1", 5399, final = true)), tv.writes)
    }

    @Test
    fun anotherPhonesMediaAnExtraAndAnotherSendersAreNotWritten() {
        val other = Tv().apply { tag = tagOf("m1", owner = "another-phone") }
        other.play(fromMs = 1_000_000, seconds = 30)
        other.reporter.onSessionEnding()
        assertTrue(other.writes.isEmpty())

        val extra = Tv().apply { tag = tagOf("m1", extraId = "x1", progress = false) }
        extra.play(fromMs = 0, seconds = 30)
        extra.reporter.onSessionEnding()
        assertTrue(extra.writes.isEmpty())

        val none = Tv().apply { tag = null }
        none.play(fromMs = 0, seconds = 30)
        assertTrue(none.writes.isEmpty())
    }

    @Test
    fun ticksAfterAnAccountSwitchWriteNothing() {
        // Another account is picked on the phone (setActive and a new
        // shell); the TV plays on and reports no new status, only the SDK's
        // one-second ticks.
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 5)
        tv.writer = YOU
        for (s in 6..20) tv.tick(1_000_000 + s * 1_000L)
        tv.reporter.onSessionEnding()
        assertTrue(tv.writes.isEmpty(), "written while another account is signed in: ${tv.writes}")
        assertNull(tv.reporter.trackedItemId)
    }

    @Test
    fun anAccountSwitchLetsTheTitleGoUnwritten() {
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 5)
        tv.writer = YOU
        tv.reporter.writerChanged()
        tv.status(RemotePlayerState.Paused, 1_006_000)
        tv.reporter.onSessionEnding()
        assertTrue(tv.writes.isEmpty())
    }

    @Test
    fun theSameTitleLoadedAgainKeepsItsStartGuard() {
        val tv = Tv()
        tv.play(fromMs = 2_400_000, seconds = 3)
        // The phone loads the same title again from 12:00; the receiver ends
        // the old media session first.
        tv.reporter.loading("L2", "m1", startMs = 720_000)
        tv.status(RemotePlayerState.Idle, 0, RemoteIdleReason.Interrupted)
        assertEquals(listOf(write("m1", 2403, final = true)), tv.writes)
        tv.writes.clear()
        // The head, before the start lands.
        tv.tag = tagOf("m1", load = "L2")
        tv.status(RemotePlayerState.Playing, 3_000)
        for (s in 4..13) tv.tick(s * 1_000L)
        assertTrue(tv.writes.isEmpty(), "the head written over the resume point: ${tv.writes}")
    }

    @Test
    fun aNewLoadOfTheTitleEndsTheOldOneAtItsPositionEvenWithoutAnIdle() {
        val tv = Tv()
        tv.play(fromMs = 2_400_000, seconds = 3)
        tv.reporter.loading("L2", "m1", startMs = 720_000)
        tv.tag = tagOf("m1", load = "L2")
        tv.status(RemotePlayerState.Buffering, 0)
        tv.status(RemotePlayerState.Playing, 720_000)
        for (s in 1..10) tv.tick(720_000 + s * 1_000L)
        assertEquals(listOf(write("m1", 2403, final = true), write("m1", 730, final = false)), tv.writes)
    }

    @Test
    fun anotherTitleOnTheTvEndsTheFirstAtItsLastPosition() {
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 5)
        tv.tag = tagOf("m2", load = "L2")
        tv.status(RemotePlayerState.Buffering, 0)
        assertEquals(listOf(write("m1", 1005, final = true)), tv.writes)
        assertEquals("m2", tv.reporter.trackedItemId)
    }

    @Test
    fun theLengthIsTheTvsInSeconds() {
        val tv = Tv().apply { durationMs = 1_320_500 }
        tv.play(fromMs = 60_000, seconds = 10)
        assertEquals(1320, tv.writes.single().durationSec)
    }

    @Test
    fun afterAGapInTheTicksNothingCountsUntilTheTvReports() {
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 5)
        // The phone was frozen a minute. The SDK reckons on from its last
        // status; the TV had paused at 1030 s meanwhile.
        tv.now += 60_000
        tv.reporter.onProgress(1_066_000, tv.durationMs)
        for (s in 67..70) tv.tick(1_000_000 + s * 1_000L)
        assertEquals(1, tv.asks, "the TV is asked once, not at every tick")
        assertTrue(tv.writes.isEmpty())
        // The TV's own word.
        tv.status(RemotePlayerState.Paused, 1_030_000)
        tv.reporter.onSessionEnding()
        assertTrue(tv.writes.all { it.positionSec <= 1005 }, "a guess written: ${tv.writes}")
        assertEquals(write("m1", 1005, final = true), tv.writes.last())
    }

    @Test
    fun afterAGapTheTvsNextStatusCountsAgain() {
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 5)
        tv.now += 60_000
        tv.reporter.onProgress(1_066_000, tv.durationMs)
        tv.status(RemotePlayerState.Playing, 1_066_000)
        for (s in 67..80) tv.tick(1_000_000 + s * 1_000L)
        assertEquals(listOf(write("m1", 1067, final = false), write("m1", 1077, final = false)), tv.writes)
    }

    @Test
    fun whileTheConnectionIsLostNothingCounts() {
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 5)
        tv.reporter.onSuspended()
        for (s in 6..30) tv.tick(1_000_000 + s * 1_000L)
        assertTrue(tv.writes.isEmpty())
        assertTrue(tv.asks >= 1)
        tv.reporter.onSessionEnding()
        assertEquals(listOf(write("m1", 1005, final = true)), tv.writes)
    }

    private companion object {
        val ME = CastWriter("account-a", "owner-a")
        val YOU = CastWriter("account-b", "owner-b")

        fun tagOf(
            itemId: String,
            load: String = "L1",
            owner: String = ME.owner,
            extraId: String? = null,
            progress: Boolean = true,
        ) = CastTag(itemId, extraId, owner, progress, load, linksExpireAtMs = 0L)
    }
}
