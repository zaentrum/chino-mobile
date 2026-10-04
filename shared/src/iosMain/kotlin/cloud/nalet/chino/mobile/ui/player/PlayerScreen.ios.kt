package cloud.nalet.chino.mobile.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.IosSystemChrome
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.AppSettings
import cloud.nalet.chino.mobile.data.api.PlayInfo
import cloud.nalet.chino.mobile.data.api.Segment
import cloud.nalet.chino.mobile.data.api.TrackInfo
import cloud.nalet.chino.mobile.data.api.artworkUrl
import cloud.nalet.chino.mobile.ui.feedback.BugReportDialog
import cloud.nalet.chino.mobile.ui.shell.MainShellScreen
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import platform.UIKit.UIDevice

/**
 * iOS PlayerScreen: AVPlayer on an AVPlayerLayer, under the Android player's
 * Compose chrome. Same master URL (/play/master.m3u8?stream=&caps=&q=, with
 * the iOS caps), same resume (the saved position, never overwritten with one
 * the player did not play), the same up-next countdown, skip pill, audio,
 * subtitle and quality menus. Subtitles are drawn by an overlay from the
 * sidecar / embedded cue files, as AVPlayer cannot side-load WebVTT. Playback
 * keeps going in the background and in picture in picture, with Now Playing
 * and the remote commands.
 */
actual class PlayerScreen actual constructor(
    private val itemId: String,
    private val fromStart: Boolean,
    private val resumeSec: Int,
) : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val container = LocalAppContainer.current
        val nav = LocalNavigator.currentOrThrow
        var loadAttempt by remember { mutableStateOf(0) }
        var state by remember { mutableStateOf<IosPlayState?>(null) }
        var loadError by remember { mutableStateOf<String?>(null) }
        var reportDraft by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(itemId, fromStart, resumeSec, loadAttempt) {
            state = null
            loadError = null
            try {
                state = loadPlayState(container, itemId, fromStart, resumeSec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: e::class.simpleName.orEmpty()
            }
        }

        val ready = state
        when {
            loadError != null -> PlaybackFailurePanel(
                title = "Playback failed",
                label = "The server didn't answer the player. Try again in a moment.",
                onRetry = { loadAttempt += 1 },
                onBack = { nav.pop() },
                onReport = { reportDraft = loadError },
            )
            ready == null -> PlayerLoading()
            else -> IosPlaybackSurface(
                state = ready,
                onBack = { nav.pop() },
                onHome = { nav.replaceAll(MainShellScreen()) },
                // Replace, so back from the next episode does not walk the chain.
                onSwitchItem = { id -> nav.replace(PlayerScreen(itemId = id, fromStart = true)) },
            )
        }
        reportDraft?.let { tech ->
            BugReportDialog(
                screenshot = null,
                context = mapOf("screen" to "player", "itemId" to itemId),
                initialDescription = "Playback failed while watching this item.\n\n--- technical details ---\n$tech",
                onDismiss = { reportDraft = null },
            )
        }
    }
}

/** Everything the surface needs, fetched before it mounts. */
internal data class IosPlayState(
    val itemId: String,
    val apiBase: String,
    val streamToken: String,
    val caps: String,
    val title: String,
    val info: PlayInfo?,
    val quality: QualityLadder,
    /** Where playback starts, whole seconds ([resumeStartSec]). */
    val startSec: Int,
    /** Whether this session may write its position ([mayWriteProgress]). */
    val writable: Boolean,
    val durationMs: Long,
    val segments: List<Segment>,
    val subtitles: List<SubtitleChoice>,
    val defaultSubtitleId: String?,
    /** The audio /play/info lists that should play first (Settings). */
    val preferredAudio: TrackInfo?,
    val prevEpisodeId: String?,
    val nextEpisodeId: String?,
    val nextEpisodeTitle: String?,
    val trickplayCues: List<TrickplayCue>,
    val artworkUrl: String?,
)

private suspend fun loadPlayState(
    container: AppContainer,
    itemId: String,
    fromStart: Boolean,
    resumeSec: Int,
): IosPlayState = coroutineScope {
    val api = container.chinoApi
    val token = container.streamTokenManager.valid()
    val apiBase = container.config.apiBaseUrl
    val caps = CodecCaps.queryParam
    val handoff = !fromStart && resumeSec > 1
    // The saved position matters only when neither "from start" nor a
    // hand-off says where to begin. Null = it could not be read.
    val saved = async { if (fromStart || handoff) null else runCatching { api.getProgress(itemId).positionSec }.getOrNull() }
    val info = async { runCatching { api.playInfo(itemId, caps = caps.ifEmpty { null }) }.getOrNull() }
    val item = async { runCatching { api.getItem(itemId) }.getOrNull() }
    val segments = async { runCatching { api.itemSegments(itemId).segments }.getOrDefault(emptyList()) }
    val sidecars = async { runCatching { api.itemSubtitles(itemId).subtitles }.getOrDefault(emptyList()) }
    val settings = container.settings.flow.first()

    val it = item.await()
    val playInfo = info.await()
    val seriesId = it?.parentId
    val seriesTitle = async { seriesId?.let { sid -> runCatching { api.getItem(sid).title }.getOrNull() } }
    val episodes = async {
        seriesId?.let { sid -> runCatching { api.seriesEpisodes(sid).seasons.flatMap { s -> s.episodes } }.getOrNull() }.orEmpty()
    }
    // Scrub thumbnails exist for packaged titles only (web's gate).
    val trickplay = async {
        if (playInfo?.mode.equals("packaged", ignoreCase = true)) {
            runCatching { parseTrickplayVtt(api.trickplayVtt(itemId, token)) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    val durationMs = playInfo?.durationMs ?: it?.durationMs ?: 0L
    val resume = ResumeInput(
        savedSec = saved.await(),
        durationSec = durationMs / 1000.0,
        startOver = fromStart,
        handoffSec = if (handoff) resumeSec else -1,
    )
    val subtitles = buildSubtitleChoices(itemId, sidecars.await(), playInfo?.subtitleTracks.orEmpty(), apiBase, token)
    val preferredAudio = preferredAudioTrack(playInfo?.audioTracks.orEmpty(), settings.preferredAudioLang)
    val defaultSub = defaultSubtitleChoice(
        subtitles,
        audioLang = preferredAudio?.language,
        subtitlePref = settings.preferredSubLang,
        audioPref = settings.preferredAudioLang,
    )
    val flat = episodes.await()
    val idx = flat.indexOfFirst { e -> e.id == itemId }
    val next = if (idx in 0 until flat.size - 1) flat[idx + 1] else null
    IosPlayState(
        itemId = itemId,
        apiBase = apiBase,
        streamToken = token,
        caps = caps,
        title = composePlayerTitle(it, seriesTitle.await()),
        info = playInfo,
        quality = qualityLadder(playInfo),
        startSec = resumeStartSec(resume),
        writable = mayWriteProgress(resume),
        durationMs = durationMs,
        segments = segments.await(),
        subtitles = subtitles,
        defaultSubtitleId = defaultSub?.id,
        preferredAudio = preferredAudio,
        prevEpisodeId = if (idx > 0) flat[idx - 1].id else null,
        nextEpisodeId = next?.id,
        nextEpisodeTitle = next?.let { e ->
            val se = buildString {
                e.seasonNumber?.let { n -> append("S").append(n.toString().padStart(2, '0')) }
                e.episodeNumber?.let { n -> append("E").append(n.toString().padStart(2, '0')) }
            }
            if (se.isEmpty()) e.title else "$se · ${e.title}"
        },
        trickplayCues = trickplay.await(),
        artworkUrl = artworkUrl(apiBase, it?.posterUrl ?: it?.backdropUrl, token),
    )
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun IosPlaybackSurface(
    state: IosPlayState,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSwitchItem: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val settings by container.settings.flow.collectAsState(initial = AppSettings())
    val c = remember { IosPlaybackController(state, container, onSwitchItem) }

    DisposableEffect(c) {
        c.start()
        onDispose { c.release() }
    }
    // The first item, a quality switch, Try again.
    LaunchedEffect(c.loadKey) { c.load() }
    // The player's clock: 5 looks a second, on the main thread.
    LaunchedEffect(c) {
        while (isActive) {
            c.tick()
            delay(200)
        }
    }
    // Every ~10 s while playing (and on pause, background and exit).
    LaunchedEffect(c) {
        while (isActive) {
            delay(10_000)
            if (c.snapshot.playing) c.saveProgress()
        }
    }
    // No frame within 20 s of an attempt: say so.
    LaunchedEffect(c.attempt) {
        while (isActive && !c.started && c.failure == null) {
            delay(1_000)
            c.checkStartup()
        }
    }
    LaunchedEffect(c.activeSubtitleId) { c.loadCues() }
    LaunchedEffect(c) { c.loadArtwork() }

    // UI-only state.
    var openPopover by remember { mutableStateOf(OpenPopover.NONE) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPreviewMs by remember { mutableStateOf(0L) }
    var volumeDragging by remember { mutableStateOf(false) }
    var reportDraft by remember { mutableStateOf<String?>(null) }
    var controlsTopPx by remember { mutableStateOf<Float?>(null) }

    // Chrome auto-hide: 4 s playing, 8 s paused; never mid-gesture or with a
    // menu open.
    var chromeVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableStateOf(currentTimeMillis()) }
    val noteInteraction: () -> Unit = { lastInteraction = currentTimeMillis() }
    val playing = c.isPlaying
    LaunchedEffect(lastInteraction, playing, scrubbing, volumeDragging, openPopover) {
        chromeVisible = true
        if (scrubbing || volumeDragging || openPopover != OpenPopover.NONE) return@LaunchedEffect
        delay(if (playing) 4_000L else 8_000L)
        chromeVisible = false
    }

    // ---- Binge: prewarm, up-next countdown, preview card (Android's rules) ----
    val positionMs = c.snapshot.positionMs
    val durationMs = c.durationMs
    val nextId = state.nextEpisodeId
    val atEndNow = atEnd(state.segments, positionMs, durationMs)
    val previewSegment = previewSegmentAt(state.segments, positionMs, hasNext = nextId != null)
    val skipSegment = skippableSegmentAt(state.segments, positionMs)
    val prewarmZone = inPrewarmZone(positionMs, creditsStartMs(state.segments), durationMs)

    var prewarmedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(prewarmZone, nextId) {
        if (nextId == null || !prewarmZone || prewarmedFor == nextId) return@LaunchedEffect
        prewarmedFor = nextId
        c.prewarmNext(nextId)
    }

    var autoNextSec by remember { mutableStateOf<Int?>(null) }
    var autoNextDismissed by remember { mutableStateOf(false) }
    var advancing by remember { mutableStateOf(false) }
    val previewActive = previewSegment != null
    LaunchedEffect(atEndNow, previewActive, nextId, settings.autoPlayNext, autoNextDismissed) {
        if ((!atEndNow && !previewActive) || nextId == null || autoNextDismissed || !settings.autoPlayNext) {
            autoNextSec = null
            return@LaunchedEffect
        }
        autoNextSec = settings.countdownSec.coerceAtLeast(1)
        while (true) {
            delay(1_000)
            val n = autoNextSec ?: break
            if (n <= 1) {
                if (!advancing) {
                    advancing = true
                    autoNextSec = 0
                    onSwitchItem(nextId)
                }
                break
            }
            autoNextSec = n - 1
        }
    }
    val advance: () -> Unit = {
        if (!advancing && nextId != null) {
            advancing = true
            onSwitchItem(nextId)
        }
    }
    val dismissNext: () -> Unit = {
        autoNextDismissed = true
        autoNextSec = null
    }

    val unavailableNote = remember { "Not available on ${UIDevice.currentDevice.model}" }
    val failure = c.failure
    // The native AirPlay button follows the chrome's fade; it hides under the
    // info dialog (which Compose draws, so below it).
    val chromeAlpha by animateFloatAsState(if (chromeVisible && failure == null && openPopover != OpenPopover.INFO) 1f else 0f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    chromeVisible = true
                    noteInteraction()
                })
            },
    ) {
        val landscape = maxWidth > maxHeight
        LaunchedEffect(c.fullscreen, landscape) { IosSystemChrome.setImmersive(c.fullscreen || landscape) }

        UIKitView(
            factory = { c.engine.view },
            modifier = Modifier.fillMaxSize(),
            properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
        )
        // Black until the resume seek has landed and a frame is there: the
        // viewer never sees 0:00 flash before the resume point.
        if (!c.started && failure == null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
        } else if (c.snapshot.waiting && failure == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(40.dp))
            }
        }
        if (failure == null) {
            val sec = autoNextSec
            val countdownShowing = sec != null && nextId != null
            BottomOverlays(
                chromeVisible = chromeVisible,
                controlsTopPx = controlsTopPx,
                subtitle = c.subtitleText,
                card = when {
                    previewSegment != null && nextId != null -> {
                        { NextEpisodeCard(secondsLeft = sec.takeIf { settings.autoPlayNext }, nextTitle = state.nextEpisodeTitle, onDismiss = dismissNext, onPlayNext = advance) }
                    }
                    sec != null && nextId != null -> {
                        { AutoNextOverlay(secondsLeft = sec, nextTitle = state.nextEpisodeTitle, onDismiss = dismissNext, onPlayNow = advance) }
                    }
                    else -> null
                },
                // Not for credits while the countdown shows: the card carries
                // the skip forward there.
                pill = skipSegment
                    ?.takeUnless { it.kind.equals("credits", ignoreCase = true) && countdownShowing }
                    ?.let { seg -> { SkipSegmentButton(label = skipSegmentLabel(seg.kind), onClick = { c.skip(seg) }) } },
            )
        }

        AnimatedVisibility(visible = chromeVisible && failure == null, enter = fadeIn(), exit = fadeOut()) {
            PlayerChrome(
                m = ChromeModel(
                    title = state.title,
                    positionMs = if (scrubbing) scrubPreviewMs else positionMs,
                    durationMs = durationMs,
                    bufferedMs = c.snapshot.bufferedMs,
                    isPlaying = playing,
                    muted = c.muted,
                    volume = c.volume,
                    speed = c.speed,
                    fullscreen = c.fullscreen,
                    openPopover = openPopover,
                    segments = state.segments,
                    trickplayCues = state.trickplayCues,
                    trickplayBaseUrl = "${state.apiBase.trimEnd('/')}/v1/items/${state.itemId}/play/trickplay",
                    streamToken = state.streamToken,
                    audio = c.audioChoices,
                    subtitles = state.subtitles,
                    activeSubtitleId = c.activeSubtitleId,
                    quality = state.quality,
                    currentQuality = c.quality,
                    canPrev = state.prevEpisodeId != null,
                    canNext = nextId != null,
                    pipSupported = c.engine.pip != null,
                    pipActive = c.pipActive,
                    unavailableNote = unavailableNote,
                    info = state.info,
                    chromeAlpha = chromeAlpha,
                ),
                a = ChromeActions(
                    onBack = onBack,
                    onHome = onHome,
                    onPlayPause = {
                        c.togglePlayPause()
                        noteInteraction()
                    },
                    onScrubStart = { ms ->
                        scrubbing = true
                        scrubPreviewMs = ms
                    },
                    onScrubUpdate = { ms -> scrubPreviewMs = ms },
                    onScrubCommit = { ms ->
                        c.seek(ms)
                        scrubbing = false
                        noteInteraction()
                    },
                    onScrubCancel = {
                        scrubbing = false
                        noteInteraction()
                    },
                    onToggleMute = {
                        c.toggleMute()
                        noteInteraction()
                    },
                    onVolumeChange = { v -> c.setVolume(v) },
                    onVolumeDragStart = { volumeDragging = true },
                    onVolumeDragEnd = {
                        volumeDragging = false
                        noteInteraction()
                    },
                    onTogglePopover = { p ->
                        openPopover = if (openPopover == p) OpenPopover.NONE else p
                        noteInteraction()
                    },
                    onSelectSpeed = { s ->
                        c.setSpeed(s)
                        openPopover = OpenPopover.NONE
                        noteInteraction()
                    },
                    onSelectAudio = { choice ->
                        c.selectAudio(choice)
                        openPopover = OpenPopover.NONE
                        noteInteraction()
                    },
                    onSelectSubtitle = { choice ->
                        if (choice == null || choice.available) {
                            c.selectSubtitle(choice)
                            openPopover = OpenPopover.NONE
                        }
                        noteInteraction()
                    },
                    onSelectQuality = { q ->
                        c.switchQuality(q)
                        openPopover = OpenPopover.NONE
                        noteInteraction()
                    },
                    onToggleFullscreen = {
                        c.toggleFullscreen()
                        noteInteraction()
                    },
                    onTogglePip = {
                        c.togglePip()
                        noteInteraction()
                    },
                    onPrevEpisode = { state.prevEpisodeId?.let(onSwitchItem) },
                    onNextEpisode = { nextId?.let(onSwitchItem) },
                    onControlsTop = { controlsTopPx = it },
                ),
            )
        }

        if (failure != null) {
            PlaybackFailurePanel(
                title = failure.title,
                label = failure.message,
                onRetry = if (failure.notFound) null else c::retry,
                onBack = onBack,
                onReport = { reportDraft = failure.tech },
            )
        }
        reportDraft?.let { tech ->
            BugReportDialog(
                screenshot = null,
                context = mapOf("screen" to "player", "itemId" to state.itemId),
                initialDescription = "Playback failed while watching this item.\n\n--- technical details ---\n$tech",
                onDismiss = { reportDraft = null },
            )
        }
    }
}
