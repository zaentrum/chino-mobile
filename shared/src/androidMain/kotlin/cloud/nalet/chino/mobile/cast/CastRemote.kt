package cloud.nalet.chino.mobile.cast

import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import org.json.JSONObject

/*
 * The TV's player as the phone reads it: the Cast SDK's RemoteMediaClient,
 * behind the few things casting reads of it and the callbacks it registers,
 * so the JVM tests drive the same code with a fake one.
 */

/** What casting reads of the TV's player. */
internal interface RemotePlayback {
    /** MediaStatus.PLAYER_STATE_*. */
    val playerState: Int

    /** MediaStatus.IDLE_REASON_*. */
    val idleReason: Int
    val approximatePositionMs: Long
    val streamDurationMs: Long

    /** The custom data of the media it plays: the phone's marker. */
    val customData: JSONObject?
    val hasMediaSession: Boolean
    fun listen(listener: RemotePlaybackListener)
    fun stopListening(listener: RemotePlaybackListener)
}

internal interface RemotePlaybackListener {
    fun onStatus()
    fun onProgress(positionMs: Long, durationMs: Long)
    fun onError(error: MediaError)
}

/** The TV's player state in casting's terms. */
internal fun remotePlayerState(playerState: Int): RemotePlayerState = when (playerState) {
    MediaStatus.PLAYER_STATE_IDLE -> RemotePlayerState.Idle
    MediaStatus.PLAYER_STATE_PLAYING -> RemotePlayerState.Playing
    MediaStatus.PLAYER_STATE_PAUSED -> RemotePlayerState.Paused
    MediaStatus.PLAYER_STATE_BUFFERING -> RemotePlayerState.Buffering
    MediaStatus.PLAYER_STATE_LOADING -> RemotePlayerState.Loading
    else -> RemotePlayerState.Unknown
}

internal fun remoteIdleReason(idleReason: Int): RemoteIdleReason = when (idleReason) {
    MediaStatus.IDLE_REASON_FINISHED -> RemoteIdleReason.Finished
    MediaStatus.IDLE_REASON_CANCELED -> RemoteIdleReason.Canceled
    MediaStatus.IDLE_REASON_INTERRUPTED -> RemoteIdleReason.Interrupted
    MediaStatus.IDLE_REASON_ERROR -> RemoteIdleReason.Error
    else -> RemoteIdleReason.None
}

/** The media status the TV reports now. */
internal fun RemotePlayback.status(): RemoteMediaStatus = RemoteMediaStatus(
    tag = castTagOf(customData),
    state = remotePlayerState(playerState),
    idleReason = remoteIdleReason(idleReason),
    positionMs = approximatePositionMs,
    durationMs = streamDurationMs,
)

/** What [RemoteMediaClientPlayback] reads of the SDK's RemoteMediaClient, and
 *  the callbacks it registers there and takes off again. */
internal interface MediaClientPort {
    val playerState: Int
    val idleReason: Int
    val approximateStreamPosition: Long
    val streamDuration: Long
    val customData: JSONObject?
    fun hasMediaSession(): Boolean
    fun registerCallback(callback: RemoteMediaClient.Callback)
    fun unregisterCallback(callback: RemoteMediaClient.Callback)
    fun addProgressListener(listener: RemoteMediaClient.ProgressListener, periodMs: Long)
    fun removeProgressListener(listener: RemoteMediaClient.ProgressListener)
}

/** The SDK's RemoteMediaClient itself. */
internal class SdkMediaClient(private val client: RemoteMediaClient) : MediaClientPort {
    override val playerState: Int get() = client.playerState
    override val idleReason: Int get() = client.idleReason
    override val approximateStreamPosition: Long get() = client.approximateStreamPosition
    override val streamDuration: Long get() = client.streamDuration
    override val customData: JSONObject? get() = client.mediaInfo?.customData
    override fun hasMediaSession(): Boolean = client.hasMediaSession()
    override fun registerCallback(callback: RemoteMediaClient.Callback) = client.registerCallback(callback)
    override fun unregisterCallback(callback: RemoteMediaClient.Callback) = client.unregisterCallback(callback)
    override fun addProgressListener(listener: RemoteMediaClient.ProgressListener, periodMs: Long) {
        client.addProgressListener(listener, periodMs)
    }

    override fun removeProgressListener(listener: RemoteMediaClient.ProgressListener) = client.removeProgressListener(listener)
}

/** The SDK's RemoteMediaClient as [RemotePlayback]: its callback and a
 *  progress tick every [tickMs], each registered once per listener and taken
 *  off with it. */
internal class RemoteMediaClientPlayback(
    private val client: MediaClientPort,
    private val tickMs: Long = PROGRESS_TICK_MS,
) : RemotePlayback {
    private val callbacks = HashMap<RemotePlaybackListener, Pair<RemoteMediaClient.Callback, RemoteMediaClient.ProgressListener>>()

    override val playerState: Int get() = client.playerState
    override val idleReason: Int get() = client.idleReason
    override val approximatePositionMs: Long get() = client.approximateStreamPosition
    override val streamDurationMs: Long get() = client.streamDuration
    override val customData: JSONObject? get() = client.customData
    override val hasMediaSession: Boolean get() = client.hasMediaSession()

    override fun listen(listener: RemotePlaybackListener) {
        if (listener in callbacks) return
        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() = listener.onStatus()
            override fun onMetadataUpdated() = listener.onStatus()
            override fun onMediaError(mediaError: MediaError) = listener.onError(mediaError)
        }
        val progress = RemoteMediaClient.ProgressListener { position, duration -> listener.onProgress(position, duration) }
        callbacks[listener] = callback to progress
        client.registerCallback(callback)
        client.addProgressListener(progress, tickMs)
    }

    override fun stopListening(listener: RemotePlaybackListener) {
        val (callback, progress) = callbacks.remove(listener) ?: return
        client.unregisterCallback(callback)
        client.removeProgressListener(progress)
    }

    companion object {
        /** How often the TV's position is read while it plays. */
        const val PROGRESS_TICK_MS = 1_000L
    }
}

/**
 * Feeds the TV's player to the position writes ([CastProgressReporter]) and
 * tells [statusSeen] of each status and [errorSeen] of each error it reports.
 */
internal class CastPlaybackBridge(
    private val reporter: CastProgressReporter,
    private val statusSeen: (RemoteMediaStatus) -> Unit = {},
    private val errorSeen: (MediaError) -> Unit = {},
) : RemotePlaybackListener {
    var playback: RemotePlayback? = null
        private set

    fun attach(remote: RemotePlayback) {
        if (playback === remote) return
        detach()
        playback = remote
        remote.listen(this)
        onStatus()
    }

    fun detach() {
        playback?.stopListening(this)
        playback = null
    }

    /** The session ends: the last position the TV confirmed is kept. */
    fun sessionEnding() = reporter.onSessionEnding()

    /** The connection to the TV is lost for now: nothing counts until the TV
     *  reports again. */
    fun suspended() = reporter.onSuspended()

    override fun onStatus() {
        val remote = playback ?: return
        val status = remote.status()
        reporter.onStatus(status)
        statusSeen(status)
    }

    override fun onProgress(positionMs: Long, durationMs: Long) = reporter.onProgress(positionMs, durationMs)

    override fun onError(error: MediaError) = errorSeen(error)
}
