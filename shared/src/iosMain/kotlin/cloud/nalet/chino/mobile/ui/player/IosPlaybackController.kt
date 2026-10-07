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
 *
 * In extra mode ([PlayerMode.Extra]) it plays the extra's master and tells
 * the server nothing of the title's: no progress (the session is not
 * writable), no watched mark, none of the player's events — one
 * trailer_play at the first playback. Its subtitles are the master's own,
 * which AVPlayer draws ([masterSubtitleChoices]).
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

    /** The captions menu: a title's sidecar and embedded tracks, an extra's
     *  master renditions once the item knows them. */
    var subtitleChoices by mutableStateOf(state.subtitles)
        private set
    var activeSubtitleId by mutableStateOf(state.defaultSubtitleId)
        private set
    /** The default rule has picked an extra's subtitle (or none). */
    private var masterSubtitleSet = false
    private var cues: List<SubtitleCue> = emptyList()
    var subtitleText by mutableStateOf("")
        private set

    var pipActive by mutableStateOf(false)
        private set

    private var markedWatched = false
    private var trailerPlaySent = false
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
                ?: state.preferredAudio?.displayName?.takeIf { pickedAudioLang == null }
                    ?.let { t -> renditions.firstOrNull { it.name.equals(t, ignoreCase = true) } }
            if (match != null && !match.selected) engine.selectAudio(match.index)
            refreshAudioChoices()
        }
        // An extra's subtitles are its master's renditions (it has no
        // sidecars): listed once the item knows them, the default rule's pick
        // on, and the pick kept across a rebuilt item.
        if (!state.mode.sidecarSubtitles) {
            engine.onLegibleGroupLoaded = {
                val choices = masterSubtitleChoices(engine.subtitleRenditions())
                subtitleChoices = choices
                if (!masterSubtitleSet) {
                    masterSubtitleSet = true
                    activeSubtitleId = defaultSubtitleChoice(
                        choices,
                        audioLang = state.preferredAudio?.language,
                        subtitlePref = state.subtitlePref,
                        audioPref = state.audioPref,
                    )?.id
                }
                engine.selectSubtitleRendition(masterSubtitleIndex(activeSubtitleId))
            }
        }
    }

    /** The player's events, for a title; an extra's telemetry is its one
     *  trailer_play ([PlayerMode]). */
    private fun event(kind: String, extra: Map<String, String>) {
        if (state.mode.playbackEvents) telemetry.event(kind, itemId = itemId, extra = extra)
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
        val url = state.masterUrl(quality)
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
        // An extra's one trailer_play: the extra, and that it played from
        // this server.
        if (state.mode.trailerPlay && !trailerPlaySent && started && s.playing) {
            trailerPlaySent = true
            telemetry.event("trailer_play", itemId = itemId, extra = mapOf("extra_id" to state.extraId.orEmpty(), "local" to "true"))
        }
        subtitleText = if (cues.isEmpty()) "" else cueTextAt(cues, s.positionMs)
        if (failure == null) {
            engine.failure()?.let { f ->
                event("media_error", mapOf("signature" to f.signature, "message" to f.message, "not_found" to f.notFound.toString()))
                giveUp(f)
            }
        }
        val dur = durationMs
        val credits = inCredits(state.segments, s.positionMs)
        if (state.mode.watched && !markedWatched && started && s.playing && reachedWatched(s.positionMs, dur, credits)) {
            markedWatched = true
            container.appScope.launch { runCatching { api.postWatched(itemId) } }
            event("mark_watched", mapOf("at" to (s.positionMs / 1000).toString(), "via" to if (credits) "credits" else "p95"))
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
        event("startup_timeout", mapOf("attempt" to attempt.toString()))
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
        event("skip_segment", mapOf("kind" to seg.kind))
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
        event("quality_switch", mapOf("from" to quality, "to" to q))
        if (snapshot.startSeekDone && snapshot.positionMs > 0) resumeAtMs = snapshot.positionMs
        playIntent = engine.wantsPlay
        quality = q
        loadKey += 1
    }

    fun selectAudio(choice: AudioChoice) {
        val from = audioChoices.firstOrNull { it.selected }
        if (from?.index != choice.index) {
            event(
                "audio_switch",
                mapOf("from" to (from?.language ?: from?.index?.toString().orEmpty()), "to" to (choice.language ?: choice.index.toString())),
            )
        }
        pickedAudioLang = choice.language
        engine.selectAudio(choice.index)
        refreshAudioChoices()
    }

    /** Off (null) or a track the overlay can draw — an extra's, a rendition
     *  of its master that AVPlayer draws. */
    fun selectSubtitle(choice: SubtitleChoice?) {
        if (choice != null && !choice.available) return
        activeSubtitleId = choice?.id
        if (!state.mode.sidecarSubtitles) engine.selectSubtitleRendition(masterSubtitleIndex(choice?.id))
    }

    /** Loads the active track's cue file (?stream=) for the overlay. A
     *  master's rendition has none: AVPlayer draws it. */
    suspend fun loadCues() {
        cues = emptyList()
        subtitleText = ""
        val choice = subtitleChoices.firstOrNull { it.id == activeSubtitleId && it.available && it.url.isNotEmpty() } ?: return
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
            context = buildMap {
                put("itemId", itemId)
                state.extraId?.let { put("extraId", it) }
                put("positionSec", (resumeAtMs / 1000).toString())
                put("screen", "player")
                put("mode", state.info?.mode ?: "unknown")
                put("quality", quality)
            },
        )
    }

    /** Try again: a fresh item and a fresh deadline, where the give-up was. */
    fun retry() {
        event("retry", mapOf("attempt" to (attempt + 1).toString()))
        failure = null
        started = false
        foregroundWaitMs = 0
        playIntent = true
        attempt += 1
        loadKey += 1
    }

    fun prewarmNext(nextId: String) {
        container.appScope.launch { runCatching { api.prewarm(nextId, caps = state.caps.ifEmpty { null }, quality = "high") } }
        event("binge_prewarm", mapOf("next_item" to nextId))
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

/**
 * The audio menu: AVPlayer's options — for a native player one per track,
 * whichever of its groups plays it — each described by the /play/info track
 * of its name ([audioTrackFor]: /play/info names every track as the master
 * names its rendition, the option's title, [AudioRendition.name]). Named as
 * chino-web names a track ([audioLabels]): by the language it is tagged with
 * ("German", "No dialogue" for zxx), by the track's name where there is
 * none; under it its codec and channels, and the 5.1 companion's where one
 * plays it ("AAC · Stereo or E-AC-3 · 5.1"). Which of the two plays is
 * AVPlayer's pick of the group, not the menu's: a 5.1 choice is the track's.
 */
internal fun audioChoicesFor(
    renditions: List<AudioRendition>,
    tracks: List<cloud.nalet.chino.mobile.data.api.TrackInfo>,
): List<AudioChoice> {
    val matched = renditions.mapIndexed { k, r ->
        audioTrackFor(tracks, place = k, count = renditions.size, language = r.language) { it.displayName == r.name }
    }
    val details = matched.map { track -> track?.let(::audioDetail) }
    val langs = renditions.mapIndexed { i, r -> r.language ?: matched[i]?.language }
    val labels = audioLabels(
        renditions.mapIndexed { i, r -> AudioLabelInput(langs[i], name = matched[i]?.displayName ?: r.name, detail = details[i]) },
    )
    return renditions.mapIndexed { i, r ->
        AudioChoice(index = r.index, label = labels[i], detail = details[i], language = langs[i], selected = r.selected)
    }
}

/** The id prefix of a master's subtitle rendition in the captions menu; the
 *  rendition's index follows. */
private const val MASTER_SUBTITLE_ID = "hls-"

/** An extra's captions menu: its master's subtitle renditions, named as a
 *  title's tracks are ([subtitleLabels]) — the language first, then what the
 *  NAME says beyond it, "(forced)" for a forced one. AVPlayer draws the one
 *  picked, so each is available and has no cue file. */
internal fun masterSubtitleChoices(renditions: List<SubtitleRendition>): List<SubtitleChoice> {
    val labels = subtitleLabels(renditions.map { SubtitleLabelInput(it.language, it.name, it.forced) })
    return renditions.mapIndexed { i, r ->
        SubtitleChoice(
            id = MASTER_SUBTITLE_ID + r.index,
            label = labels[i],
            lang = normalizeLang(r.language),
            url = "",
            kind = SubtitleKind.Text,
            forced = r.forced,
            available = true,
        )
    }
}

/** The rendition a [masterSubtitleChoices] row shows, by its id; null for
 *  off, or a row of another kind. */
internal fun masterSubtitleIndex(id: String?): Int? =
    id?.takeIf { it.startsWith(MASTER_SUBTITLE_ID) }?.removePrefix(MASTER_SUBTITLE_ID)?.toIntOrNull()
