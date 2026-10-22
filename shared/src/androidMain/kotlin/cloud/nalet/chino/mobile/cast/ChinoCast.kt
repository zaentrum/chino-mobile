package cloud.nalet.chino.mobile.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.ProgressBody
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the screens read of casting. */
internal data class CastSessionState(
    /** A device that can play is on the network. */
    val available: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    /** The connected device's own name, from the network; null while none
     *  is connected, or for one that names itself nothing. */
    val device: String? = null,
    /** The TV has media: the bar shows. */
    val hasMedia: Boolean = false,
)

/** How a load on the TV ended. */
internal sealed interface CastLoadResult {
    /** The TV plays it. */
    data object Playing : CastLoadResult

    /** The TV can't play it; casting has stopped. */
    data class Failed(val failure: CastFailure) : CastLoadResult

    /** No session to load into, or it ended first - the viewer stopped
     *  casting - or another load took its place. */
    data object Ended : CastLoadResult
}

/**
 * Casting's sender, one per process: the Cast framework's context, the
 * session (started from the Cast button, resumed after the app was
 * restarted, ended by stop casting), the load of a title on the TV, the
 * positions the TV plays written back, and a title the TV can't play.
 *
 * Everything runs on the main thread, as the framework's callbacks do.
 */
internal object ChinoCast {
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    private val _state = MutableStateFlow(CastSessionState())
    val state: StateFlow<CastSessionState> = _state.asStateFlow()

    private val _failure = MutableStateFlow<CastFailure?>(null)

    /** The title the TV couldn't play, until the viewer puts the warning
     *  away or casts again. */
    val failure: StateFlow<CastFailure?> = _failure.asStateFlow()

    private var castContext: CastContext? = null
    private var starting = false
    private var container: AppContainer? = null
    private var ownerWatch: Job? = null

    /** The owner key of the account signed in now ([castOwnerKey]). */
    @Volatile
    private var owner: String? = null
    private var session: CastSession? = null
    private var lastError: MediaError? = null
    private var pending: PendingLoad? = null
    private var failing = false

    private val reporter = CastProgressReporter(owner = { owner }, now = SystemClock::elapsedRealtime, write = ::write)
    private val bridge = CastPlaybackBridge(reporter, statusSeen = ::statusSeen, errorSeen = { lastError = it })

    /**
     * Casting for [container]'s server and signed-in account: the framework
     * started once, the account followed for as long as the container is the
     * app's. On a device without Google Play services there is no casting,
     * and nothing shows.
     */
    fun attach(context: Context, container: AppContainer) {
        if (this.container !== container) {
            this.container = container
            ownerWatch?.cancel()
            ownerWatch = container.appScope.launch {
                val base = container.config.apiBaseUrl
                container.accountStore.activeAccountId.collect { id ->
                    owner = id?.let { castOwnerKey(base, it) }
                }
            }
        }
        if (castContext != null || starting) return
        starting = true
        val app = context.applicationContext
        try {
            CastContext.getSharedInstance(app, ContextCompat.getMainExecutor(app))
                .addOnSuccessListener { ready(it) }
                .addOnFailureListener { starting = false }
        } catch (e: RuntimeException) {
            starting = false
        }
    }

    private fun ready(ctx: CastContext) {
        starting = false
        castContext = ctx
        ctx.addCastStateListener(castStates)
        ctx.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        castStates.onCastStateChanged(ctx.castState)
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::attachSession)
    }

    private val castStates = CastStateListener { s ->
        _state.update { it.copy(available = s != CastState.NO_DEVICES_AVAILABLE, connecting = s == CastState.CONNECTING) }
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = _state.update { it.copy(connecting = true) }
        override fun onSessionStarted(session: CastSession, sessionId: String) = attachSession(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) = detachSession()

        // Stop casting, or the session lost: the TV's last position is the
        // one the phone keeps - written while the media is still there.
        override fun onSessionEnding(session: CastSession) = bridge.sessionEnding()
        override fun onSessionEnded(session: CastSession, error: Int) = detachSession()
        override fun onSessionResuming(session: CastSession, sessionId: String) = _state.update { it.copy(connecting = true) }

        // After the app was restarted, or the connection came back.
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attachSession(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) = detachSession()
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    private fun attachSession(s: CastSession) {
        session = s
        failing = false
        _state.update { it.copy(connected = true, connecting = false, device = deviceName(s)) }
        val client = s.remoteMediaClient
        if (client != null) bridge.attach(RemoteMediaClientPlayback(client)) else bridge.detach()
    }

    private fun detachSession() {
        bridge.detach()
        session = null
        _state.update { it.copy(connected = false, connecting = false, device = null, hasMedia = false) }
        pending?.result?.complete(CastLoadResult.Ended)
    }

    /** The session to load into or set the volume of, while one is connected. */
    fun connectedSession(): CastSession? =
        (session ?: sharedContext()?.sessionManager?.currentCastSession)?.takeIf { it.isConnected }

    /** The framework's context: this sender's, or - where the full controls
     *  were opened from the notification before the app's screens attached
     *  it - the one the framework started for them. */
    private fun sharedContext(): CastContext? =
        castContext ?: runCatching { CastContext.getSharedInstance() }.getOrNull()

    /**
     * Plays [media] on the TV of the session that is connected. Waits for the
     * TV to answer: [CastLoadResult.Playing], or [CastLoadResult.Failed] -
     * casting then stops and [failure] says why - or [CastLoadResult.Ended].
     * The load goes on when the caller is gone (the viewer left the player
     * while the TV loaded): a failure is then shown in the bar's place.
     */
    suspend fun load(media: CastMedia): CastLoadResult = scope.async { loadNow(media) }.await()

    private suspend fun loadNow(media: CastMedia): CastLoadResult {
        val s = connectedSession() ?: return CastLoadResult.Ended
        val client = s.remoteMediaClient ?: return CastLoadResult.Ended
        val me = owner.orEmpty()
        val device = deviceName(s) ?: UNNAMED_CAST_DEVICE
        // The TV plays this title already, about there: it plays on.
        if (pending == null && alreadyOnTv(bridge.playback?.status(), media, me)) {
            if (media.autoplay && !client.isPlaying) client.play()
            return CastLoadResult.Playing
        }
        pending?.result?.complete(CastLoadResult.Ended)
        val load = PendingLoad(media.itemId, media.extraId, me, device)
        pending = load
        _failure.value = null
        failing = false
        lastError = null
        if (media.writesProgress) reporter.loading(media.itemId, media.startMs)
        try {
            client.load(media.toLoadRequest(me)).setResultCallback { answer -> answered(load, answer) }
        } catch (e: RuntimeException) {
            load.result.complete(CastLoadResult.Failed(failureOf(device, null, CastStatusCodes.INTERNAL_ERROR, media.itemId)))
        }
        val result = withTimeoutOrNull(LOAD_TIMEOUT_MS) { load.result.await() }
            ?: CastLoadResult.Failed(failureOf(device, lastError, CastStatusCodes.TIMEOUT, media.itemId))
        if (pending === load) pending = null
        if (result is CastLoadResult.Failed) fail(result.failure)
        return result
    }

    private fun answered(load: PendingLoad, answer: RemoteMediaClient.MediaChannelResult) {
        val code = answer.status.statusCode
        when {
            answer.status.isSuccess -> load.result.complete(CastLoadResult.Playing)
            code == CastStatusCodes.REPLACED || code == CastStatusCodes.CANCELED || code == CastStatusCodes.INTERRUPTED ->
                load.result.complete(CastLoadResult.Ended)
            // The phone stopped waiting, not the TV: its status says how the
            // load went (statusSeen).
            code == CastStatusCodes.TIMEOUT -> {
                load.waitingForStatus = true
                bridge.onStatus()
            }
            else -> load.result.complete(
                CastLoadResult.Failed(failureOf(load.device, answer.mediaError ?: lastError, code, load.itemId)),
            )
        }
    }

    private fun statusSeen(status: RemoteMediaStatus) {
        _state.update { it.copy(hasMedia = bridge.playback?.hasMediaSession == true) }
        val tag = status.tag
        val load = pending
        if (load != null) {
            val same = tag != null && tag.itemId == load.itemId && tag.extraId == load.extraId && tag.owner == load.owner
            if (same && load.waitingForStatus) {
                when (status.state) {
                    RemotePlayerState.Playing, RemotePlayerState.Paused, RemotePlayerState.Buffering ->
                        load.result.complete(CastLoadResult.Playing)
                    RemotePlayerState.Idle -> if (status.idleReason == RemoteIdleReason.Error) {
                        load.result.complete(CastLoadResult.Failed(failureOf(load.device, lastError, null, load.itemId)))
                    }
                    else -> Unit
                }
            }
            return
        }
        // A title this phone cast stopped on the TV with an error: casting
        // stops. The error itself comes in a message of its own, often just
        // after the status.
        if (status.state == RemotePlayerState.Idle && status.idleReason == RemoteIdleReason.Error && tag != null && tag.owner == owner) {
            val device = _state.value.device ?: UNNAMED_CAST_DEVICE
            main.postDelayed({ fail(failureOf(device, lastError, null, tag.itemId)) }, ERROR_SETTLE_MS)
        }
    }

    private fun failureOf(device: String, error: MediaError?, senderStatus: Int?, itemId: String?): CastFailure {
        val report = CastErrorReport(
            httpStatus = error?.let { httpStatusOf(flatCustomData(it.customData), it.reason) },
            detailedCode = error?.detailedErrorCode,
            senderStatus = senderStatus,
        )
        return CastFailure(device, castFailureDetails(report, serverHost()), itemId)
    }

    /** The TV can't play the title: say so, and stop casting. */
    private fun fail(failure: CastFailure) {
        if (failing) return
        failing = true
        _failure.value = failure
        sharedContext()?.sessionManager?.endCurrentSession(true)
    }

    /** The viewer put the warning away. */
    fun dismissFailure() {
        _failure.value = null
    }

    /** Stop casting: the session ends, and the receiver on the TV with it;
     *  the TV's last position is kept (onSessionEnding). */
    fun stopCasting() {
        sharedContext()?.sessionManager?.endCurrentSession(true)
    }

    private fun write(w: CastProgressWrite) {
        val c = container ?: return
        c.appScope.launch {
            runCatching { c.chinoApi.postProgress(w.itemId, ProgressBody(positionSec = w.positionSec, durationSec = w.durationSec)) }
                .onSuccess { if (w.final) CastEvents.keptPositionOf(w.itemId) }
        }
    }

    /** The server's host and port as the app reaches it ("media.example.com"). */
    private fun serverHost(): String =
        container?.config?.apiBaseUrl?.substringAfter("://")?.substringBefore('/').orEmpty()

    /** The device's own name, from the network; null for one that names
     *  itself nothing. */
    private fun deviceName(s: CastSession): String? =
        s.castDevice?.friendlyName?.trim()?.takeIf { it.isNotEmpty() }

    private class PendingLoad(val itemId: String, val extraId: String?, val owner: String, val device: String) {
        val result = CompletableDeferred<CastLoadResult>()

        /** The phone's request ran out of time: the TV's status decides. */
        var waitingForStatus = false
    }

    /** How long a load may take in all: chino-stream can take many seconds
     *  to start a title's live encode, and the TV its manifest after that. */
    private const val LOAD_TIMEOUT_MS = 60_000L

    /** How long an error status waits for the error message that names it. */
    private const val ERROR_SETTLE_MS = 400L
}
