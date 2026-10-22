package cloud.nalet.chino.mobile.cast

import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Progress while casting, end to end on the phone's side: a fake of the Cast
 * SDK's RemoteMediaClient reports the TV's status and ticks in the SDK's own
 * constants, the bridge feeds them to the reporter, and the reporter writes
 * what the phone's player would have written.
 */
class CastPlaybackBridgeTest {
    /** The TV's player, as RemoteMediaClient reports it. */
    private class FakeRemoteMediaClient : RemotePlayback {
        override var playerState = MediaStatus.PLAYER_STATE_IDLE
        override var idleReason = MediaStatus.IDLE_REASON_NONE
        override var approximatePositionMs = 0L
        override var streamDurationMs = 2_640_000L
        override var customData: JSONObject? = castTagJson(CastTag("e3", null, "owner-a", writesProgress = true, load = "L1", linksExpireAtMs = 0L))
        override var hasMediaSession = true
        val listeners = mutableListOf<RemotePlaybackListener>()

        override fun listen(listener: RemotePlaybackListener) {
            listeners += listener
        }

        override fun stopListening(listener: RemotePlaybackListener) {
            listeners -= listener
        }

        fun report(state: Int, positionMs: Long, idle: Int = MediaStatus.IDLE_REASON_NONE) {
            playerState = state
            idleReason = idle
            approximatePositionMs = positionMs
            listeners.toList().forEach { it.onStatus() }
        }

        fun tick(positionMs: Long) {
            approximatePositionMs = positionMs
            listeners.toList().forEach { it.onProgress(positionMs, streamDurationMs) }
        }

        fun error(error: MediaError) = listeners.toList().forEach { it.onError(error) }
    }

    private var now = 0L
    private val writes = mutableListOf<CastProgressWrite>()
    private val statuses = mutableListOf<RemoteMediaStatus>()
    private val errors = mutableListOf<MediaError>()
    private val me = CastWriter("account-a", "owner-a")
    private val reporter = CastProgressReporter(writer = { me }, now = { now }, write = { writes += it })
    private val bridge = CastPlaybackBridge(reporter, statusSeen = { statuses += it }, errorSeen = { errors += it })

    @Test
    fun theTvsPositionIsWrittenEveryTenSecondsAtAPauseAndAsCastingStops() {
        val tv = FakeRemoteMediaClient()
        reporter.loading("L1", "e3", startMs = 600_000)
        bridge.attach(tv)
        tv.report(MediaStatus.PLAYER_STATE_BUFFERING, 0)
        tv.report(MediaStatus.PLAYER_STATE_PLAYING, 600_000)
        for (s in 1..12) {
            now += 1_000
            tv.tick(600_000 + s * 1_000L)
        }
        tv.report(MediaStatus.PLAYER_STATE_PAUSED, 612_000)
        tv.report(MediaStatus.PLAYER_STATE_PLAYING, 612_000)
        now += 3_000
        tv.tick(615_000)
        bridge.sessionEnding()
        assertEquals(
            listOf(
                CastProgressWrite("account-a", "e3", 610, 2640, final = false),
                CastProgressWrite("account-a", "e3", 612, 2640, final = false),
                CastProgressWrite("account-a", "e3", 615, 2640, final = true),
            ),
            writes,
        )
    }

    @Test
    fun theEndOfTheTitleOnTheTvIsWrittenAsKept() {
        val tv = FakeRemoteMediaClient()
        bridge.attach(tv)
        tv.report(MediaStatus.PLAYER_STATE_PLAYING, 2_630_000)
        for (s in 1..9) {
            now += 1_000
            tv.tick(2_630_000 + s * 1_000L)
        }
        tv.report(MediaStatus.PLAYER_STATE_IDLE, 0, MediaStatus.IDLE_REASON_FINISHED)
        assertEquals(listOf(CastProgressWrite("account-a", "e3", 2639, 2640, final = true)), writes)
    }

    @Test
    fun theScreensHearEachStatusAndError() {
        val tv = FakeRemoteMediaClient()
        bridge.attach(tv)
        tv.report(MediaStatus.PLAYER_STATE_IDLE, 0, MediaStatus.IDLE_REASON_ERROR)
        val last = statuses.last()
        assertEquals(RemotePlayerState.Idle, last.state)
        assertEquals(RemoteIdleReason.Error, last.idleReason)
        assertEquals("e3", last.tag?.itemId)
        val error = MediaError(MediaError.ERROR_TYPE_ERROR, 7L, 104, "unsupported", null)
        tv.error(error)
        assertSame(error, errors.single())
    }

    @Test
    fun detachedTheTvIsNoLongerHeard() {
        val tv = FakeRemoteMediaClient()
        bridge.attach(tv)
        bridge.detach()
        assertTrue(tv.listeners.isEmpty())
        tv.report(MediaStatus.PLAYER_STATE_PLAYING, 600_000)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun whileTheConnectionIsLostTheTicksCountNothing() {
        val tv = FakeRemoteMediaClient()
        bridge.attach(tv)
        tv.report(MediaStatus.PLAYER_STATE_PLAYING, 1_000_000)
        for (s in 1..5) {
            now += 1_000
            tv.tick(1_000_000 + s * 1_000L)
        }
        bridge.suspended()
        for (s in 6..40) {
            now += 1_000
            tv.tick(1_000_000 + s * 1_000L)
        }
        bridge.sessionEnding()
        assertEquals(listOf(CastProgressWrite("account-a", "e3", 1005, 2640, final = true)), writes)
    }

    /** The SDK's RemoteMediaClient as the adapter uses it: what it
     *  registers there, and takes off. */
    private class FakeClient : MediaClientPort {
        override var playerState = MediaStatus.PLAYER_STATE_PLAYING
        override var idleReason = MediaStatus.IDLE_REASON_NONE
        override var approximateStreamPosition = 0L
        override var streamDuration = 0L
        override var customData: JSONObject? = null
        val callbacks = mutableListOf<RemoteMediaClient.Callback>()
        val progress = mutableListOf<Pair<RemoteMediaClient.ProgressListener, Long>>()
        override fun hasMediaSession(): Boolean = true
        override fun registerCallback(callback: RemoteMediaClient.Callback) {
            callbacks += callback
        }
        override fun unregisterCallback(callback: RemoteMediaClient.Callback) {
            callbacks -= callback
        }
        override fun addProgressListener(listener: RemoteMediaClient.ProgressListener, periodMs: Long) {
            progress += listener to periodMs
        }
        override fun removeProgressListener(listener: RemoteMediaClient.ProgressListener) {
            progress.removeAll { it.first === listener }
        }
    }

    @Test
    fun theAdapterRegistersOnceAndTakesOffWhatItRegistered() {
        val client = FakeClient()
        val playback = RemoteMediaClientPlayback(client)
        bridge.attach(playback)
        // Once, however often the bridge listens.
        playback.listen(bridge)
        assertEquals(1, client.callbacks.size)
        assertEquals(listOf(1_000L), client.progress.map { it.second })
        // The SDK's calls reach the bridge.
        client.callbacks.single().onStatusUpdated()
        client.progress.single().first.onProgressUpdated(1_000, 2_640_000)
        bridge.detach()
        assertTrue(client.callbacks.isEmpty())
        assertTrue(client.progress.isEmpty())
        // Taken off twice: nothing more happens.
        playback.stopListening(bridge)
        assertTrue(client.callbacks.isEmpty())
    }

    @Test
    fun theSdksStatesInCastingsTerms() {
        assertEquals(RemotePlayerState.Playing, remotePlayerState(MediaStatus.PLAYER_STATE_PLAYING))
        assertEquals(RemotePlayerState.Paused, remotePlayerState(MediaStatus.PLAYER_STATE_PAUSED))
        assertEquals(RemotePlayerState.Buffering, remotePlayerState(MediaStatus.PLAYER_STATE_BUFFERING))
        assertEquals(RemotePlayerState.Loading, remotePlayerState(MediaStatus.PLAYER_STATE_LOADING))
        assertEquals(RemotePlayerState.Idle, remotePlayerState(MediaStatus.PLAYER_STATE_IDLE))
        assertEquals(RemotePlayerState.Unknown, remotePlayerState(MediaStatus.PLAYER_STATE_UNKNOWN))
        assertEquals(RemoteIdleReason.Finished, remoteIdleReason(MediaStatus.IDLE_REASON_FINISHED))
        assertEquals(RemoteIdleReason.Canceled, remoteIdleReason(MediaStatus.IDLE_REASON_CANCELED))
        assertEquals(RemoteIdleReason.Interrupted, remoteIdleReason(MediaStatus.IDLE_REASON_INTERRUPTED))
        assertEquals(RemoteIdleReason.Error, remoteIdleReason(MediaStatus.IDLE_REASON_ERROR))
        assertEquals(RemoteIdleReason.None, remoteIdleReason(MediaStatus.IDLE_REASON_NONE))
    }
}
