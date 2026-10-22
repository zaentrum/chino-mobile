package cloud.nalet.chino.mobile.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The position the TV plays, written back while casting, as the phone's own
 * player writes it. A fake TV reports its status and ticks the way the Cast
 * SDK's RemoteMediaClient does.
 */
class CastProgressTest {
    /** The fake TV and the writes the reporter made of what it reported. */
    private class Tv(owner: String? = "me") {
        var now = 0L
        var me: String? = owner
        val writes = mutableListOf<CastProgressWrite>()
        val reporter = CastProgressReporter(owner = { me }, now = { now }, write = { writes += it })
        var tag: CastTag? = CastTag("m1", null, "me", writesProgress = true)
        var durationMs = 5_400_000L

        fun status(state: RemotePlayerState, positionMs: Long, idle: RemoteIdleReason = RemoteIdleReason.None) =
            reporter.onStatus(RemoteMediaStatus(tag, state, idle, positionMs, durationMs))

        /** Plays [seconds] from [fromMs], a tick a second. */
        fun play(fromMs: Long, seconds: Int) {
            status(RemotePlayerState.Playing, fromMs)
            for (s in 1..seconds) {
                now += 1_000
                reporter.onProgress(fromMs + s * 1_000L, durationMs)
            }
        }
    }

    @Test
    fun nothingIsWrittenBeforeTheTvPlays() {
        val tv = Tv()
        tv.reporter.loading("m1", startMs = 600_000)
        tv.status(RemotePlayerState.Loading, 0)
        tv.status(RemotePlayerState.Buffering, 0)
        tv.now += 30_000
        tv.reporter.onProgress(0, tv.durationMs)
        tv.reporter.onSessionEnding(null)
        assertTrue(tv.writes.isEmpty())
    }

    @Test
    fun theTvsPositionIsWrittenEveryTenSecondsWhilePlaying() {
        val tv = Tv()
        tv.reporter.loading("m1", startMs = 600_000)
        tv.play(fromMs = 600_000, seconds = 25)
        assertEquals(
            listOf(CastProgressWrite("m1", 610, 5400, final = false), CastProgressWrite("m1", 620, 5400, final = false)),
            tv.writes,
        )
    }

    @Test
    fun aPositionShortOfWhereTheTvWasToldToStartIsNotPlayedYet() {
        val tv = Tv()
        tv.reporter.loading("m1", startMs = 600_000)
        // The head of the stream, reported before the start lands.
        tv.status(RemotePlayerState.Playing, 0)
        tv.now += 10_000
        tv.reporter.onProgress(2_000, tv.durationMs)
        assertTrue(tv.writes.isEmpty())
        tv.now += 10_000
        tv.reporter.onProgress(600_400, tv.durationMs)
        assertEquals(listOf(CastProgressWrite("m1", 600, 5400, final = false)), tv.writes)
    }

    @Test
    fun aPauseWritesThePositionReached() {
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 5)
        tv.status(RemotePlayerState.Paused, 1_005_000)
        assertEquals(listOf(CastProgressWrite("m1", 1005, 5400, final = false)), tv.writes)
        // Paused, the ticks write nothing more.
        tv.now += 60_000
        tv.reporter.onProgress(1_005_000, tv.durationMs)
        assertEquals(1, tv.writes.size)
    }

    @Test
    fun stopCastingKeepsTheTvsLastPosition() {
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 4)
        tv.reporter.onSessionEnding(1_004_600)
        assertEquals(listOf(CastProgressWrite("m1", 1004, 5400, final = true)), tv.writes)
        assertNull(tv.reporter.trackedItemId)
    }

    @Test
    fun theEndWritesTheLastPositionTheServerDecidesItIsFinished() {
        val tv = Tv()
        tv.play(fromMs = 5_390_000, seconds = 9)
        tv.status(RemotePlayerState.Idle, 0, RemoteIdleReason.Finished)
        // The last second played, not the idle player's 0: chino-api's rule
        // then marks the title finished.
        assertEquals(listOf(CastProgressWrite("m1", 5399, 5400, final = true)), tv.writes)
    }

    @Test
    fun anotherAccountsMediaAndAnExtraAreNotWritten() {
        val other = Tv().apply { tag = CastTag("m1", null, "someone-else", writesProgress = true) }
        other.play(fromMs = 1_000_000, seconds = 30)
        other.reporter.onSessionEnding(1_030_000)
        assertTrue(other.writes.isEmpty())

        val extra = Tv().apply { tag = CastTag("m1", "x1", "me", writesProgress = false) }
        extra.play(fromMs = 0, seconds = 30)
        extra.reporter.onSessionEnding(30_000)
        assertTrue(extra.writes.isEmpty())

        val none = Tv().apply { tag = null }
        none.play(fromMs = 0, seconds = 30)
        assertTrue(none.writes.isEmpty())
    }

    @Test
    fun anotherAccountSignedInLetsTheTitleGoUnwritten() {
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 5)
        tv.me = "you"
        tv.status(RemotePlayerState.Playing, 1_006_000)
        tv.now += 30_000
        tv.reporter.onProgress(1_036_000, tv.durationMs)
        tv.reporter.onSessionEnding(1_036_000)
        assertTrue(tv.writes.isEmpty())
    }

    @Test
    fun anotherTitleOnTheTvEndsTheFirstAtItsLastPosition() {
        val tv = Tv()
        tv.play(fromMs = 1_000_000, seconds = 5)
        tv.tag = CastTag("m2", null, "me", writesProgress = true)
        tv.status(RemotePlayerState.Buffering, 0)
        assertEquals(listOf(CastProgressWrite("m1", 1005, 5400, final = true)), tv.writes)
        assertEquals("m2", tv.reporter.trackedItemId)
    }

    @Test
    fun theLengthIsTheTvsInSeconds() {
        val tv = Tv().apply { durationMs = 1_320_500 }
        tv.play(fromMs = 60_000, seconds = 10)
        assertEquals(1320, tv.writes.single().durationSec)
    }
}
