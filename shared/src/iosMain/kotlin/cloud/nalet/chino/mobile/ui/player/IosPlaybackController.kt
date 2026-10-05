package cloud.nalet.chino.mobile.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cloud.nalet.chino.mobile.IosSystemChrome
import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.ProgressBody
import cloud.nalet.chino.mobile.feedback.bugFingerprint
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState

/** How long a title may take to show its first frame before the player gives
 *  up and says so (chino-web's STARTUP_DEADLINE_MS). Counted while the app is
 *  in the foreground. */
internal const val STARTUP_DEADLINE_MS = 20_000L

/**
 * One playback session of the iOS player: the engine, what it has played, and
 * everything the chrome shows. A plain object remembered by the screen, so the
 * player's clock, the remote commands and the 10 s save always act on current
 * values (no state captured by a long-lived effect goes stale).
 */
@Stable
internal class IosPlaybackController(
    val state: IosPlayState,
    private val container: AppContainer,
    private val onSwitchItem: (String) -> Unit,
) {
    val engine = IosVideoPlayer()
    private val guard = ProgressGuard(state.writable)
    private val api = container.chinoApi
    private val telemetry = container.telemetry
    private val itemId = state.itemId

    var snapshot by mutableStateOf(PlayerSnapshot())
        private set
    var quality by mutableStateOf(state.quality.initial)
        private set

    /** Bumped by a quality switch and by Try again: the item is rebuilt at
     *  [resumeAtMs], where the old one had got to. */
    var loadKey by mutableStateOf(0)
        private set
    private var resumeAtMs = state.startSec * 1000L
    private var playIntent = true

    /** The opening, then each Try again: the startup deadline is per attempt. */
    var attempt by mutableStateOf(0)
        private set

    /** A frame has shown since the start seek landed (this attempt). */
    var started by mutableStateOf(false)
        private set
    var failure by mutableStateOf<PlayerFailure?>(null)
        private set

    var muted by mutableStateOf(false)
        private set
    var volume by mutableStateOf(1f)
        private set
    var speed by mutableStateOf(1f)
        private set
    var fullscreen by mutableStateOf(false)
        private set
    private var forcedLandscape = false

    var audioChoices by mutableStateOf<List<AudioChoice>>(emptyList())
        private set

    /** A manual audio pick holds across a rebuilt item; Settings until then. */
    private var pickedAudioLang: String? = null

    var activeSubtitleId by mutableStateOf(state.defaultSubtitleId)
        private set
    private var cues: List<SubtitleCue> = emptyList()
    var subtitleText by mutableStateOf("")
        private set

    var pipActive by mutableStateOf(false)
        private set

    private var markedWatched = false
    private var lastPublishedPlaying = false
    private var lastPublishAt = 0L
    private var foregroundWaitMs = 0L
    private var backgroundObserver: Any? = null

    private val audioSession = IosAudioSession(
        onInterrupted = { pause() },
        onResumable = { play() },
    )
    private val nowPlaying = IosNowPlaying(
        object : RemoteControls {
            override fun play() = this@IosPlaybackController.play()
            override fun pause() = this@IosPlaybackController.pause()
            override fun togglePlayPause() = this@IosPlaybackController.togglePlayPause()
            override fun seekBy(deltaMs: Long) = seek(snapshot.positionMs + deltaMs)
            override fun seekTo(ms: Long) = seek(ms)
            override val next: (() -> Unit)? = state.nextEpisodeId?.let { id -> { onSwitchItem(id) } }
        },
    )

    val durationMs: Long get() = snapshot.durationMs.takeIf { it > 0 } ?: state.durationMs

    val isPlaying: Boolean get() = snapshot.playing || (engine.wantsPlay && snapshot.waiting)

    init {
        // The audio renditions are known once the item has its playlist: play
        // the viewer's pick, else the Settings language (chino-web's pick).
        engine.onAudioGroupLoaded = {
            val renditions = engine.audioRenditions()
            val want = normalizeLang(pickedAudioLang ?: state.preferredAudio?.language)
            val match = (if (want.isEmpty()) null else renditions.firstOrNull { normalizeLang(it.language) == want })
                ?: state.preferredAudio?.title?.takeIf { pickedAudioLang == null && it.isNotBlank() }
                    ?.let { t -> renditions.firstOrNull { it.name.equals(t, ignoreCase = true) } }
            if (match != null && !match.selected) engine.selectAudio(match.index)
            refreshAudioChoices()
        }
    }

    /** The screen came up: sound, Now Playing, the background save. */
    fun start() {
        audioSession.activate()
        nowPlaying.start(state.title)
        backgroundObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> saveProgress() }
    }

    /** The screen went: the last position, then everything down. */
    fun release() {
        backgroundObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        backgroundObserver = null
        saveProgress()
        nowPlaying.stop()
        engine.release()
        audioSession.deactivate()
        IosSystemChrome.setImmersive(false)
        if (forcedLandscape) IosSystemChrome.requestOrientation(landscape = false)
    }

    /** (Re)builds the item for [loadKey]: master URL at the current rung, the
     *  start seek armed in the guard. */
    fun load() {
        val url = buildMasterUrl(state.apiBase, itemId, state.streamToken, quality, state.caps)
        guard.expectSeek(resumeAtMs / 1000.0)
        engine.load(url, startAtSec = resumeAtMs / 1000.0, play = playIntent)
        engine.speed = speed
        engine.muted = muted
        engine.volume = volume
    }

    /** One look at the player (5 a second): what played, the subtitle on
     *  screen, a failure, the watched moment, Now Playing. */
    fun tick() {
        val s = engine.tick()
        snapshot = s
        pipActive = engine.pipActive
        if (s.startSeekDone && s.firstFrame && !started) started = true
        if (started && s.playing) guard.played(s.positionMs / 1000.0)
        subtitleText = if (cues.isEmpty()) "" else cueTextAt(cues, s.positionMs)
        if (failure == null) {
            engine.failure()?.let { f ->
                telemetry.event("media_error", itemId = itemId, extra = mapOf("signature" to f.signature, "message" to f.message, "not_found" to f.notFound.toString()))
                giveUp(f)
            }
        }
        val dur = durationMs
        val credits = inCredits(state.segments, s.positionMs)
        if (!markedWatched && started && s.playing && reachedWatched(s.positionMs, dur, credits)) {
            markedWatched = true
            container.appScope.launch { runCatching { api.postWatched(itemId) } }
            telemetry.event(
                "mark_watched",
                itemId = itemId,
                extra = mapOf("at" to (s.positionMs / 1000).toString(), "via" to if (credits) "credits" else "p95"),
            )
        }
        val now = currentTimeMillis()
        if (s.playing != lastPublishedPlaying || now - lastPublishAt > 5_000) {
            lastPublishedPlaying = s.playing
            lastPublishAt = now
            publishNowPlaying()
        }
    }

    /** Called every second until a frame shows: give up after 20 s. */
    fun checkStartup() {
        if (started || failure != null) return
        if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) foregroundWaitMs += 1_000
        if (foregroundWaitMs < STARTUP_DEADLINE_MS) return
        telemetry.event("startup_timeout", itemId = itemId, extra = mapOf("attempt" to attempt.toString()))
        giveUp(
            PlayerFailure(
                notFound = false,
                title = "This title won't start",
                message = "Nothing playable arrived from the server within ${STARTUP_DEADLINE_MS / 1000} seconds. " +
                    "Try again in a moment, or report it if it keeps happening.",
                tech = listOf(
                    "no frame within ${STARTUP_DEADLINE_MS / 1000} s",
                    "item: $itemId",
                    "mode: ${state.info?.mode ?: "unknown"}",
                    "quality: $quality",
                    "caps: ${state.caps.ifEmpty { "(none)" }}",
                ).joinToString("\n"),
                signature = STARTUP_TIMEOUT,
            ),
        )
    }

    /** The position this session played to, if it may write one. */
    fun saveProgress() {
        val pos = guard.position() ?: return
        val dur = (durationMs / 1000).toInt()
        container.appScope.launch {
            runCatching { api.postProgress(itemId, ProgressBody(positionSec = pos, durationSec = dur)) }
        }
    }

    fun play() {
        playIntent = true
        engine.play()
        publishNowPlaying()
    }

    fun pause() {
        playIntent = false
        engine.pause()
        saveProgress()
        publishNowPlaying()
    }

    fun togglePlayPause() = if (engine.wantsPlay) pause() else play()

    /** A seek the viewer made: the guard waits for playback to get there. */
    fun seek(ms: Long) {
        val dur = durationMs
        val target = ms.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        guard.expectSeek(target / 1000.0)
        engine.seekTo(target)
        snapshot = snapshot.copy(positionMs = target)
        publishNowPlaying()
    }

    fun skip(seg: cloud.nalet.chino.mobile.data.api.Segment) {
        seek(seg.endMs)
        telemetry.event("skip_segment", itemId = itemId, extra = mapOf("kind" to seg.kind))
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        muted = false
        engine.muted = false
        engine.volume = volume
    }

    fun toggleMute() {
        muted = !muted
        engine.muted = muted
    }

    fun setSpeed(s: Float) {
        speed = s
        engine.speed = s
        publishNowPlaying()
    }

    fun toggleFullscreen() {
        fullscreen = !fullscreen
        forcedLandscape = fullscreen
        IosSystemChrome.requestOrientation(landscape = fullscreen)
    }

    fun togglePip() = engine.togglePip()

    fun switchQuality(q: String) {
        if (q == quality) return
        telemetry.event("quality_switch", itemId = itemId, extra = mapOf("from" to quality, "to" to q))
        if (snapshot.startSeekDone && snapshot.positionMs > 0) resumeAtMs = snapshot.positionMs
        playIntent = engine.wantsPlay
        quality = q
        loadKey += 1
    }

    fun selectAudio(choice: AudioChoice) {
        val from = audioChoices.firstOrNull { it.selected }
        if (from?.index != choice.index) {
            telemetry.event(
                "audio_switch",
                itemId = itemId,
                extra = mapOf("from" to (from?.language ?: from?.index?.toString().orEmpty()), "to" to (choice.language ?: choice.index.toString())),
            )
        }
        pickedAudioLang = choice.language
        engine.selectAudio(choice.index)
        refreshAudioChoices()
    }

    /** Off (null) or a track the overlay can draw. */
    fun selectSubtitle(choice: SubtitleChoice?) {
        if (choice != null && !choice.available) return
        activeSubtitleId = choice?.id
    }

    /** Loads the active track's cue file (?stream=) for the overlay. */
    suspend fun loadCues() {
        cues = emptyList()
        subtitleText = ""
        val choice = state.subtitles.firstOrNull { it.id == activeSubtitleId && it.available } ?: return
        cues = runCatching { parseSubtitleCues(container.http.get(choice.url).bodyAsText()) }.getOrDefault(emptyList())
    }

    /** Now Playing's artwork: the poster. */
    suspend fun loadArtwork() {
        val url = state.artworkUrl ?: return
        nowPlaying.setArtwork(runCatching { container.http.get(url).readRawBytes() }.getOrNull())
        publishNowPlaying()
    }

    /** The player gives up: the item goes, the panel says why, and one
     *  automatic report is filed — none for a title that is not there. */
    fun giveUp(f: PlayerFailure) {
        if (failure != null) return
        saveProgress()
        if (snapshot.startSeekDone && snapshot.positionMs > 500) resumeAtMs = snapshot.positionMs
        engine.stop()
        failure = f
        if (f.notFound) return
        container.bugReporter.report(
            kind = "player",
            // The heading and the message's first sentence.
            title = "${f.title}: ${f.message.substringBefore(". ").trimEnd('.')}".take(120),
            description = f.tech.take(8 * 1024),
            fingerprint = bugFingerprint(name = f.signature, message = f.message),
            context = mapOf(
                "itemId" to itemId,
                "positionSec" to (resumeAtMs / 1000).toString(),
                "screen" to "player",
                "mode" to (state.info?.mode ?: "unknown"),
                "quality" to quality,
            ),
        )
    }

    /** Try again: a fresh item and a fresh deadline, where the give-up was. */
    fun retry() {
        telemetry.event("retry", itemId = itemId, extra = mapOf("attempt" to (attempt + 1).toString()))
        failure = null
        started = false
        foregroundWaitMs = 0
        playIntent = true
        attempt += 1
        loadKey += 1
    }

    fun prewarmNext(nextId: String) {
        container.appScope.launch { runCatching { api.prewarm(nextId, caps = state.caps.ifEmpty { null }, quality = "high") } }
        telemetry.event("binge_prewarm", itemId = itemId, extra = mapOf("next_item" to nextId))
    }

    private fun refreshAudioChoices() {
        audioChoices = audioChoicesFor(engine.audioRenditions(), state.info?.audioTracks.orEmpty())
    }

    private fun publishNowPlaying() {
        val s = snapshot
        nowPlaying.update(s.positionMs, durationMs, if (engine.wantsPlay && s.playing) speed else 0f)
    }

    private companion object {
        const val STARTUP_TIMEOUT = "StartupTimeout"
    }
}

/** The audio menu: the renditions as chino-web names them ([audioLabels]) —
 *  by the language they are tagged with ("German", "No dialogue" for zxx),
 *  by the track's title or the rendition's NAME where there is none — with
 *  "AAC · Stereo" from /play/info. */
internal fun audioChoicesFor(
    renditions: List<AudioRendition>,
    tracks: List<cloud.nalet.chino.mobile.data.api.TrackInfo>,
): List<AudioChoice> {
    // chino-stream lists the renditions in /play/info's order; by language
    // when the counts differ.
    val matched = renditions.map { r ->
        if (tracks.size == renditions.size) tracks[r.index] else tracks.firstOrNull { normalizeLang(it.language) == normalizeLang(r.language) }
    }
    val details = matched.map { track ->
        listOfNotNull(
            track?.codec?.takeIf { it.isNotBlank() }?.let { if (it.equals("mp4a", ignoreCase = true)) "AAC" else it.uppercase() },
            track?.channels?.takeIf { it > 0 }?.let { channelLabel(it) },
        ).joinToString(" · ").ifEmpty { null }
    }
    val langs = renditions.mapIndexed { i, r -> r.language ?: matched[i]?.language }
    val labels = audioLabels(
        renditions.mapIndexed { i, r ->
            AudioLabelInput(langs[i], name = matched[i]?.title?.trim()?.takeIf { it.isNotEmpty() } ?: r.name, detail = details[i])
        },
    )
    return renditions.mapIndexed { i, r ->
        AudioChoice(index = r.index, label = labels[i], detail = details[i], language = langs[i], selected = r.selected)
    }
}

private fun channelLabel(n: Int): String = when (n) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${n}ch"
}
