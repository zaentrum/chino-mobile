package cloud.nalet.chino.mobile.cast

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.auth.StreamTokenManager
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.security.SecureRandom
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the screens read of casting. */
internal data class CastSessionState(
    /** A device that can play is on the network. */
    val available: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    /** The name the connected device goes by ([castDeviceName]); null while
     *  none is connected, or for one that names itself nothing. */
    val device: String? = null,
    /** The TV has media: the bar shows. */
    val hasMedia: Boolean = false,
)

/**
 * Casting's sender, one per process, started with the app
 * ([attachCasting]): the Cast framework's context, the session (started from
 * the Cast button, resumed after the app was restarted, ended by stop
 * casting), the loads of titles on the TV ([CastLoader]), the positions the
 * TV plays written back for the account signed in, a title the TV can't
 * play, and the foreground service that keeps the writes going while the
 * phone is locked or in another app.
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

    private val _writing = MutableStateFlow(0)

    private val _keepAlive = MutableStateFlow(false)

    /**
     * Whether casting's foreground service is wanted ([CastPlaybackService]):
     * while a load, a recovery or a signing of the links anew is under way,
     * while the TV's media is this phone's, while a title is tracked, and
     * while a position is on its way to the server ([keepAliveNow]).
     */
    val keepAlive: StateFlow<Boolean> = _keepAlive.asStateFlow()

    /** Loads, recoveries and renewals under way. */
    @Volatile
    private var busy = 0

    /** The TV's media is this phone's, for the account signed in. */
    @Volatile
    private var oursOnTv = false

    /** The service runs (it says so as it starts and as it ends). */
    @Volatile
    var serviceRunning = false
        internal set
    private var appWatched = false

    private var appContext: Context? = null
    private var castContext: CastContext? = null
    private var starting = false
    private var startFailures = 0

    /** The Cast module loads here, off the main thread. */
    private val startExecutor: Executor by lazy { Executors.newSingleThreadExecutor() }
    private var container: AppContainer? = null
    private var ownerWatch: Job? = null

    /** Who writes: the account signed in, and this phone's owner id of it. */
    private val writer = MutableStateFlow<CastWriter?>(null)

    /** The account signed in now, as the account store says it - set the
     *  moment it changes, before anything else hears of it. */
    @Volatile
    private var activeAccountId: String? = null

    private var session: CastSession? = null
    private var lastError: MediaError? = null
    private var pending: PendingLoad? = null
    private var failing = false

    /** A title of this phone's as it last played on the TV: where to load it
     *  again should it fail. */
    private var lastOurs: OursOnTv? = null

    /** The load of the TV's last status, and whether it was meant to play
     *  then, for "playing again". */
    private var lastSeen: Pair<String, Boolean?>? = null

    /** Whether the load is meant to play or wait paused: its last Playing or
     *  Paused; a Buffering is neither (a seek while paused buffers too). */
    private var intent: Pair<String, Boolean>? = null

    /** The load a recovery made: should it fail too, the TV can't. */
    private var recoveryLoad: String? = null
    private var recovering = false

    private val reporter = CastProgressReporter(
        writer = { writer.value },
        now = SystemClock::elapsedRealtime,
        write = ::write,
        askStatus = ::askStatus,
    )
    private val bridge = CastPlaybackBridge(reporter, statusSeen = ::statusSeen, errorSeen = { lastError = it })
    private val loader = CastLoader(
        writer = ::awaitWriter,
        token = ::tvToken,
        fetchMaster = ::fetchMaster,
        now = ::currentTimeMillis,
        newLoad = ::newLoadId,
        serverHost = ::serverHost,
        loading = { load, itemId, startMs, floorMs -> loading(load, itemId, startMs, floorMs) },
        superseded = { pending?.result?.complete(CastAnswer.Ended) },
        thisDevice = ::thisDevice,
    )

    /** The TV's master fetched as the TV fetches it: no sign-in, no shared
     *  cookies; long enough for chino-stream to start a live encode. */
    private val masterHttp: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = MASTER_CONNECT_TIMEOUT_MS
                socketTimeoutMillis = MASTER_TIMEOUT_MS
                requestTimeoutMillis = MASTER_TIMEOUT_MS
            }
        }
    }

    /**
     * Casting for [container]'s server and signed-in account: the framework
     * started once - retried where it failed - the account followed for as
     * long as the container is the app's. On a device without Google Play
     * services there is no casting, and nothing shows.
     */
    fun attach(context: Context, container: AppContainer) {
        appContext = context.applicationContext
        // While a device is connected, Play on a downloaded title goes to the
        // TV as a streamed title does - the server's master, the TV's own
        // links - and nothing of the phone's download goes to it
        // (downloads/DownloadedPlayback.kt).
        container.playsOnAnotherScreen = ::castingNow
        // The account signed in leaves - signed out, removed, another one in
        // its place - while it casts: the questions say casting stops, and
        // it does, its last position written first (leave).
        container.castingDeviceOf = ::castingDeviceOf
        container.leaveCasting = ::leave
        // The account is being deleted: nothing written for it meanwhile.
        container.holdCastWrites = ::holdWrites
        if (this.container !== container) {
            this.container = container
            ownerWatch?.cancel()
            val store = PrefsOwnerStore(context.applicationContext)
            ownerWatch = container.appScope.launch {
                val base = container.config.apiBaseUrl
                container.accountStore.activeAccountId.distinctUntilChanged().collect { id ->
                    activeAccountId = id
                    val next = id?.let { CastWriter(it, castOwnerId(store, base, it, ::newOwnerId)) }
                    main.post { writerIs(next) }
                }
            }
        }
        watchTheAppInFront()
        start()
    }

    /** The app comes to the front with a title of this phone's on the TV and
     *  no service running - it could not start from the background: now. */
    private fun watchTheAppInFront() {
        if (appWatched) return
        appWatched = true
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (keepAliveNow()) ensureService()
                }
            })
        }
    }

    private fun inFront(): Boolean =
        runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)

    /** Whether the service is wanted now, as things are ([keepAlive]). */
    fun keepAliveNow(): Boolean = busy > 0 || oursOnTv || reporter.trackedItemId != null || _writing.value > 0

    /** The service is wanted or not now; wanted, with the app in front and
     *  the service not running - a title found on the TV after the app
     *  opened - it starts. */
    private fun keepAliveChanged() {
        val keep = keepAliveNow()
        _keepAlive.value = keep
        if (keep && !serviceRunning && Looper.myLooper() == Looper.getMainLooper() && inFront()) startService()
    }

    /** Casting's foreground service, unless it runs already. Started from
     *  the front: a load the viewer asked for, a session found as the app
     *  opens, the app coming back with a title on the TV. */
    private fun ensureService() {
        if (!serviceRunning) startService()
    }

    private suspend fun <T> busyWith(block: suspend () -> T): T {
        busy++
        keepAliveChanged()
        try {
            return block()
        } finally {
            busy--
            keepAliveChanged()
        }
    }

    /** The Cast button or the bar is drawn: the framework starts, should it
     *  have failed to so far. */
    fun ensureStarted() {
        if (castContext == null && !starting) {
            startFailures = 0
            start()
        }
    }

    private fun start() {
        val app = appContext ?: return
        if (castContext != null || starting) return
        starting = true
        val onMain = ContextCompat.getMainExecutor(app)
        try {
            CastContext.getSharedInstance(app, startExecutor)
                .addOnSuccessListener(onMain) { ready(it) }
                .addOnFailureListener(onMain) { startFailed() }
        } catch (e: RuntimeException) {
            startFailed()
        }
    }

    /** The framework did not start: again a little later, a few times. */
    private fun startFailed() {
        starting = false
        startFailures++
        if (startFailures <= START_RETRIES) main.postDelayed(::start, START_RETRY_MS * startFailures)
    }

    private fun ready(ctx: CastContext) {
        starting = false
        castContext = ctx
        ctx.addCastStateListener(castStates)
        ctx.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        castStates.onCastStateChanged(ctx.castState)
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::attachSession)
    }

    private fun writerIs(next: CastWriter?) {
        if (writer.value == next) return
        writer.value = next
        // What the TV plays is no longer this account's to write; whatever
        // it plays of the one signed in now is.
        reporter.writerChanged()
        lastSeen = null
        bridge.onStatus()
        keepAliveChanged()
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

        // The connection is lost for now: the SDK's positions are its guess.
        override fun onSessionSuspended(session: CastSession, reason: Int) = bridge.suspended()
    }

    private fun attachSession(s: CastSession) {
        session = s
        failing = false
        lastSeen = null
        _state.update { it.copy(connected = true, connecting = false, device = deviceName(s)) }
        val client = s.remoteMediaClient
        if (client != null) {
            bridge.attach(RemoteMediaClientPlayback(SdkMediaClient(client)))
            // A session resumed: ask the TV where it is, not the SDK.
            askStatus()
        } else {
            bridge.detach()
        }
        // With the app in front, the service may start now; it ends by
        // itself, a little later, when the TV plays nothing of this phone's.
        if (inFront()) ensureService()
    }

    private fun detachSession() {
        // A session that ended without a word of it ending (its resume
        // failed): what the TV last confirmed is kept all the same.
        bridge.sessionEnding()
        bridge.detach()
        session = null
        lastOurs = null
        lastSeen = null
        intent = null
        oursOnTv = false
        _state.update { it.copy(connected = false, connecting = false, device = null, hasMedia = false) }
        pending?.result?.complete(CastAnswer.Ended)
        keepAliveChanged()
    }

    /** The session to load into or set the volume of, while one is connected. */
    fun connectedSession(): CastSession? =
        (session ?: sharedContext()?.sessionManager?.currentCastSession)?.takeIf { it.isConnected }

    /** Whether a device is connected now, from any thread: on the main
     *  thread the framework's own word ([connectedSession]), elsewhere what
     *  it last reported. */
    fun castingNow(): Boolean =
        if (Looper.myLooper() == Looper.getMainLooper()) connectedSession() != null else _state.value.connected

    /** The framework's context: this sender's, or - where the full controls
     *  were opened before the app's start had finished - the one the
     *  framework started for them. */
    private fun sharedContext(): CastContext? =
        castContext ?: runCatching { CastContext.getSharedInstance() }.getOrNull()

    private fun client(): RemoteMediaClient? = connectedSession()?.remoteMediaClient

    /**
     * Plays [input] on the TV of the session that is connected ([CastLoader]).
     * Waits for the TV to answer: [CastLoadResult.Playing], or
     * [CastLoadResult.Failed] - [failure] says why, and where the TV failed
     * at it casting stops; found before the TV was asked, the TV plays on -
     * or [CastLoadResult.Ended]. The load goes on when the caller is gone
     * (the viewer left the player while the TV loaded): a failure is then
     * shown in the bar's place. Asked from a screen, it starts casting's
     * service first: the TV may take long, and the phone be locked meanwhile.
     */
    suspend fun load(input: CastMediaInput): CastLoadResult = scope.async {
        if (connectedSession() == null) return@async CastLoadResult.Ended
        _failure.value = null
        failing = false
        recoveryLoad = null
        val result = busyWith {
            ensureService()
            try {
                loader.load(Tv, input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The phone's own code: not the TV's error, nor the server's.
                CastLoadResult.Failed(failureOf(CastErrorReport(onThePhone = true), input.itemId), endsCasting = false)
            }
        }
        if (result is CastLoadResult.Failed) {
            if (result.endsCasting) fail(result.failure) else _failure.value = result.failure
        }
        result
    }.await()

    /** The loader is about to tell the TV to play: the position writes'
     *  start guard, and the floor of a takeover; a recovery's load noted as
     *  such. */
    private fun loading(load: String, itemId: String, startMs: Long, floorMs: Long?) {
        reporter.loading(load, itemId, startMs, floorMs)
        if (recovering) recoveryLoad = load
    }

    private fun statusSeen(status: RemoteMediaStatus) {
        val client = client()
        _state.update { it.copy(hasMedia = client?.hasMediaSession() == true) }
        val tag = status.tag
        val ours = tag != null && tag.owner == writer.value?.owner
        val load = pending
        if (load != null && load.waitingForStatus && tag?.load == load.load) {
            when (status.state) {
                RemotePlayerState.Playing, RemotePlayerState.Paused, RemotePlayerState.Buffering ->
                    load.result.complete(CastAnswer.Playing)
                RemotePlayerState.Idle -> if (status.idleReason == RemoteIdleReason.Error) {
                    load.result.complete(CastAnswer.Failed(reportOf(lastError, null)))
                }
                else -> Unit
            }
        }
        // What the TV is meant to do: play or wait. Buffering says neither.
        if (ours && tag != null) {
            when (status.state) {
                RemotePlayerState.Playing -> intent = tag.load to true
                RemotePlayerState.Paused -> intent = tag.load to false
                else -> Unit
            }
        }
        val meant = intent?.takeIf { it.first == tag?.load }?.second
        val on = status.state == RemotePlayerState.Playing || status.state == RemotePlayerState.Paused ||
            status.state == RemotePlayerState.Buffering
        oursOnTv = ours && (on || status.state == RemotePlayerState.Loading)
        if (ours && tag != null && on) {
            lastOurs = OursOnTv(
                load = tag.load,
                itemId = tag.itemId,
                positionMs = status.positionMs,
                playing = meant ?: (status.state == RemotePlayerState.Playing),
                activeTrackIds = Tv.activeTrackIds(),
                media = client?.mediaInfo,
            )
            // The title's first status in this session, or it is meant to
            // play again after a pause: its links are signed anew where they
            // run out before it ends.
            val seen = lastSeen
            val playsAgain = seen != null && seen.first == tag.load && seen.second == false && meant == true
            if (load == null && (seen == null || seen.first != tag.load || playsAgain)) renew()
        }
        lastSeen = tag?.let { it.load to meant }
        keepAliveChanged()
        // A title of this phone's stopped on the TV with an error, no load
        // waiting for it: loaded once more, else casting stops. The error
        // itself comes in a message of its own, often just after the status.
        if (load == null && ours && tag != null && status.state == RemotePlayerState.Idle && status.idleReason == RemoteIdleReason.Error) {
            main.postDelayed({ recoverFrom(tag) }, ERROR_SETTLE_MS)
        }
    }

    /** Its links signed anew where they run out ([CastLoader.renewLinks]);
     *  a reload that fails ends as a recovery that does. */
    private fun renew() {
        scope.launch {
            val renewal = busyWith { runCatching { loader.renewLinks(Tv) }.getOrNull() }
            if (renewal is CastRenewal.Failed) fail(renewal.failure)
        }
    }

    private fun recoverFrom(tag: CastTag) {
        if (failing || recovering || pending != null) return
        val report = reportOf(lastError, null)
        val base = lastOurs?.takeIf { it.load == tag.load }
        // Where it stood: the later of the last position the TV confirmed
        // (its ticks) and its last status - never an older one.
        val atMs = castRecoveryAtMs(reporter.lastConfirmedMs(tag.load), base?.positionMs)
        if (atMs == null || tag.load == recoveryLoad) {
            fail(failureOf(report, tag.itemId))
            return
        }
        val at = CastStoppedAt(
            itemId = tag.itemId,
            atMs = atMs,
            playing = base?.playing ?: true,
            activeTrackIds = base?.activeTrackIds.orEmpty(),
        )
        recovering = true
        scope.launch {
            val failure = busyWith {
                try {
                    loader.recover(Tv, at, report)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failureOf(report, at.itemId)
                } finally {
                    recovering = false
                }
            }
            failure?.let(::fail)
        }
    }

    private fun failureOf(report: CastErrorReport, itemId: String): CastFailure =
        CastFailure(Tv.device ?: UNNAMED_CAST_DEVICE, castFailureDetails(report, serverHost(), Tv.device, thisDevice()), itemId, report.serverUnreachable)

    /** What the phone is called in a failure's reason: "tablet" from 600 dp
     *  of its shorter side up, as everywhere in the app, else "phone". */
    private fun thisDevice(): String =
        if ((appContext?.resources?.configuration?.smallestScreenWidthDp ?: 0) >= 600) "tablet" else "phone"

    /** The device [accountId] casts to now - a session connected while it
     *  is the account signed in - by the name it goes by ("the device" for
     *  none); null where it casts nothing. */
    fun castingDeviceOf(accountId: String): String? {
        val s = _state.value
        if (!s.connected || activeAccountId != accountId) return null
        return castDeviceInLine(s.device)
    }

    /**
     * [accountId] leaves the phone - signed out, removed, another account
     * signed in instead - while it casts: what the TV plays of it is written
     * as that account first - the last position the TV confirmed, the one
     * the phone keeps - and casting stops, so that the TV does not play on
     * for whoever comes next. Waits for the write, at most
     * [LEAVE_WRITE_WAIT_MS]. Not [writeLast] - the server deleted the
     * account - nothing of what the TV plays is written any more. The device
     * it stopped casting on; null where the account cast nothing.
     */
    suspend fun leave(accountId: String, writeLast: Boolean): String? = withContext(Dispatchers.Main) {
        if (connectedSession() == null || activeAccountId != accountId) return@withContext null
        val device = castDeviceInLine(_state.value.device)
        if (writeLast) {
            bridge.sessionEnding()
            withTimeoutOrNull(LEAVE_WRITE_WAIT_MS) { _writing.first { it == 0 } }
        } else {
            reporter.letGoUnwritten(accountId)
        }
        stopCasting()
        device
    }

    /** The account whose writes are held ([holdWrites]); null for none. */
    @Volatile
    private var held: String? = null

    /**
     * [accountId]'s writes of what the TV plays held ([hold]) - the server
     * is deleting the account: none starts for it, and one already out is
     * waited for, at most [LEAVE_WRITE_WAIT_MS], so that it lands before the
     * deletion and never after it. Not [hold]: they go on as before, the
     * account was not deleted (once it was, [leave] lets what the TV plays
     * go unwritten, and the account is gone).
     */
    suspend fun holdWrites(accountId: String, hold: Boolean) {
        withContext(Dispatchers.Main) {
            if (hold) {
                held = accountId
                withTimeoutOrNull(LEAVE_WRITE_WAIT_MS) { _writing.first { it == 0 } }
            } else if (held == accountId) {
                held = null
            }
        }
    }

    private fun reportOf(error: MediaError?, senderStatus: Int?): CastErrorReport =
        CastErrorReport(detailedCode = error?.detailedErrorCode, reason = error?.reason, senderStatus = senderStatus)

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

    /** The foreground service ([keepAlive]), started from the front. Where
     *  the system does not allow it now, the writes go on while the app is
     *  open, and the service starts once it is in front again. */
    private fun startService() {
        val app = appContext ?: return
        try {
            ContextCompat.startForegroundService(app, Intent(app, CastPlaybackService::class.java))
        } catch (e: RuntimeException) {
            Unit
        }
    }

    /** Asks the TV for its status. */
    private fun askStatus() {
        runCatching { client()?.requestStatus() }
    }

    /** One position write, for the account it is of - refused once another
     *  is signed in, and while the account's writes are held (it is being
     *  deleted). */
    private fun write(w: CastProgressWrite) {
        val c = container ?: return
        if (w.accountId != activeAccountId || w.accountId == held) return
        _writing.update { it + 1 }
        keepAliveChanged()
        c.appScope.launch {
            try {
                // Once more as it goes out: the request carries the bearer of
                // whoever is signed in then, and none goes for an account
                // being deleted.
                if (w.accountId == activeAccountId && w.accountId != held) {
                    // With the account's own bearer, and only while it is
                    // the one signed in - never the shared client's, which
                    // may be the next account's by the time it goes out.
                    val bearer = runCatching { c.tokenManager.validAccessToken() }.getOrNull()
                        ?.takeIf { c.accountStore.snapshotBlocking().activeAccount?.id == w.accountId }
                    // Newer than any position of the title the phone still
                    // keeps from playing it without the server: that one
                    // goes, and a download of it resumes here where the TV
                    // is.
                    if (bearer != null) {
                        val sent = writeCastProgress(w, { id, body -> c.chinoApi.postProgress(id, body, bearer) }, c.progressSync)
                        if (sent && w.final) CastEvents.keptPositionOf(w.itemId)
                    } else {
                        // No bearer now (a renewal with no answer): the last
                        // position is kept, and goes once it can.
                        runCatching { keepForLater(w, c.progressSync) }
                    }
                }
            } finally {
                _writing.update { it - 1 }
                keepAliveChanged()
            }
        }
    }

    /** The account signed in, once it is known; null when none is in time. */
    private suspend fun awaitWriter(): CastWriter? =
        writer.value ?: withTimeoutOrNull(WRITER_WAIT_MS) { writer.filterNotNull().first() }

    /** A token for the TV's links: one that lives as long as a token is
     *  minted for, near enough ([StreamTokenManager.validFor]); [fresh], the
     *  current one turned down, minted anew. */
    private suspend fun tvToken(fresh: Boolean): CastToken {
        val tokens = container?.streamTokenManager ?: error("casting is not attached")
        if (fresh) tokens.invalidate()
        val life = StreamTokenManager.TOKEN_LIFE_MS - StreamTokenManager.SLACK_MS
        val token = tokens.validFor(life)
        return CastToken(token, currentTimeMillis() + life)
    }

    private suspend fun fetchMaster(url: String): MasterFetch = try {
        val response = masterHttp.get(url)
        val status = response.status.value
        MasterFetch.Answered(status, if (status in 200..299) response.bodyAsText() else "")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MasterFetch.NoAnswer
    }

    /** The server's host and port as the app reaches it ("media.example.com"). */
    private fun serverHost(): String =
        container?.config?.apiBaseUrl?.substringAfter("://")?.substringBefore('/').orEmpty()

    private fun deviceName(s: CastSession): String? = castDeviceName(s.castDevice?.friendlyName, s.castDevice?.modelName)

    private fun newLoadId(): String = randomId(LOAD_ID_BYTES)

    private fun newOwnerId(): String = randomId(OWNER_ID_BYTES)

    private fun randomId(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private val random by lazy { SecureRandom() }

    /** The TV, as the loader drives it: the session's RemoteMediaClient. */
    private object Tv : CastTv {
        override val device: String? get() = _state.value.device

        override fun status(): RemoteMediaStatus? = bridge.playback?.status()

        override fun playIntent(): Boolean? {
            val load = status()?.tag?.load ?: return null
            return intent?.takeIf { it.first == load }?.second
        }

        override fun contentUrl(): String? =
            client()?.mediaInfo?.takeIf { castTagOf(it.customData) != null }?.contentUrl ?: lastOurs?.media?.contentUrl

        override fun tracks(): List<TvTrack> = client()?.mediaInfo?.mediaTracks.orEmpty().map { it.toTvTrack() }

        override fun activeTrackIds(): List<Long> = client()?.mediaStatus?.activeTrackIds?.toList().orEmpty()

        override suspend fun load(media: CastMedia, owner: String): CastAnswer =
            send(media.load) { it.load(media.toLoadRequest(owner)) }

        override suspend fun reload(again: CastReload): CastAnswer {
            val playing = client()?.mediaInfo?.takeIf { castTagOf(it.customData) != null }
            val info = playing ?: lastOurs?.media ?: return CastAnswer.Ended
            val request = reloadRequest(info, again) ?: return CastAnswer.Ended
            return send(again.load) { it.load(request) }
        }

        override suspend fun setActiveTracks(ids: List<Long>): Boolean {
            val c = client() ?: return false
            return withTimeoutOrNull(TRACKS_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    try {
                        c.setActiveMediaTracks(ids.toLongArray()).setResultCallback { cont.resume(it.status.isSuccess) }
                    } catch (e: RuntimeException) {
                        cont.resume(false)
                    }
                }
            } ?: false
        }

        override fun play() {
            runCatching { client()?.play() }
        }
    }

    /** Sends one load request and waits for the TV's answer. */
    private suspend fun send(load: String, request: (RemoteMediaClient) -> PendingResult<RemoteMediaClient.MediaChannelResult>): CastAnswer {
        val c = client() ?: return CastAnswer.Ended
        pending?.result?.complete(CastAnswer.Ended)
        val p = PendingLoad(load)
        pending = p
        lastError = null
        try {
            request(c).setResultCallback { answer -> answered(p, answer) }
        } catch (e: RuntimeException) {
            p.result.complete(CastAnswer.Failed(CastErrorReport(senderStatus = CastStatusCodes.INTERNAL_ERROR)))
        }
        val answer = withTimeoutOrNull(LOAD_TIMEOUT_MS) { p.result.await() }
            ?: CastAnswer.Failed(reportOf(lastError, CastStatusCodes.TIMEOUT))
        if (pending === p) pending = null
        return answer
    }

    private fun answered(load: PendingLoad, answer: RemoteMediaClient.MediaChannelResult) {
        val code = answer.status.statusCode
        when {
            answer.status.isSuccess -> load.result.complete(CastAnswer.Playing)
            code == CastStatusCodes.REPLACED || code == CastStatusCodes.CANCELED || code == CastStatusCodes.INTERRUPTED ->
                load.result.complete(CastAnswer.Ended)
            // The phone stopped waiting, not the TV: its status says how the
            // load went (statusSeen).
            code == CastStatusCodes.TIMEOUT -> {
                load.waitingForStatus = true
                bridge.onStatus()
            }
            else -> load.result.complete(CastAnswer.Failed(reportOf(answer.mediaError ?: lastError, code)))
        }
    }

    private class PendingLoad(val load: String) {
        val result = CompletableDeferred<CastAnswer>()

        /** The phone's request ran out of time: the TV's status decides. */
        var waitingForStatus = false
    }

    /** A title of this phone's as the TV last played it. */
    private class OursOnTv(
        val load: String,
        val itemId: String,
        val positionMs: Long,
        val playing: Boolean,
        val activeTrackIds: List<Long>,
        val media: MediaInfo?,
    )

    /** The phone's owner ids, in its own preferences file. */
    private class PrefsOwnerStore(context: Context) : CastOwnerStore {
        private val prefs = context.getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE)
        override fun read(key: String): String? = prefs.getString(key, null)
        override fun write(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    }

    /** How long a load may take in all: chino-stream can take many seconds
     *  to start a title's live encode, and the TV its manifest after that. */
    private const val LOAD_TIMEOUT_MS = 60_000L

    /** How long an error status waits for the error message that names it. */
    private const val ERROR_SETTLE_MS = 400L

    /** How long a load waits for the account to be known. */
    private const val WRITER_WAIT_MS = 10_000L

    /** How long an account leaving waits for its last position to be
     *  written before casting stops. */
    private const val LEAVE_WRITE_WAIT_MS = 5_000L

    /** How long the TV may take to switch its tracks. */
    private const val TRACKS_TIMEOUT_MS = 10_000L

    /** The phone's fetch of the TV's master: to reach the server, and in all. */
    private const val MASTER_CONNECT_TIMEOUT_MS = 10_000L
    private const val MASTER_TIMEOUT_MS = 30_000L

    /** The framework's start, tried again after a failure: a few times, a
     *  little later each time. */
    private const val START_RETRIES = 5
    private const val START_RETRY_MS = 5_000L

    private const val OWNER_PREFS = "chino_cast_owner"
    private const val OWNER_ID_BYTES = 16
    private const val LOAD_ID_BYTES = 8
}
