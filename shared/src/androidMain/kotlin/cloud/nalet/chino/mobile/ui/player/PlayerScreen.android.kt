package cloud.nalet.chino.mobile.ui.player

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.api.PlayInfo
import cloud.nalet.chino.mobile.data.api.QualityRung
import cloud.nalet.chino.mobile.data.api.Segment
import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.ui.feedback.BugReportDialog
import cloud.nalet.chino.mobile.ui.shell.MainShellScreen
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Captions
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Minimize
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Android-side PlayerScreen. Media3 ExoPlayer wrapped in PlayerView (with
 * default controls disabled), Compose chrome overlay matching chino-web's
 * PlayerPage.tsx visually. Touch-driven (no DPAD).
 *
 * Chrome structure:
 *   TopBar      : Back ◯ + Title + Mode badge
 *   Scrubber    : drag-capable; inline time labels; segment markers; thumb
 *   BottomBar   : left cluster (Play + Skip± + Mute + Vol slider) +
 *                 right cluster (Captions + Speed + Prev + Next + Info + Fs)
 *
 * Auto-hide: 4 s playing / 8 s paused; suppressed while user is scrubbing,
 * dragging volume, or a popover is open (any → keep visible).
 *
 * Fullscreen: WindowInsetsControllerCompat hide(systemBars); restored
 * on DisposableEffect.onDispose so back-nav doesn't leak the immersive flag.
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

        // Own the system-back explicitly: pop exactly once and CONSUME the
        // event. Voyager 1.1.0-beta03's implicit Navigator BackHandler can
        // re-resolve to a just-popped screen (the loop in bug #151: back from
        // the player landed on Detail, then back from Detail jumped straight
        // back INTO the player). A single deterministic pop here removes the
        // player from the stack once, so the next back on Detail falls through
        // to the shell instead of replaying. `var` so a guard stops a double
        // pop if recomposition re-enters before the screen leaves composition.
        var popped by remember { mutableStateOf(false) }
        BackHandler(enabled = true) {
            if (!popped) {
                popped = true
                nav.pop()
            }
        }

        var ready: PlayState? by remember { mutableStateOf(null) }
        var error: String? by remember { mutableStateOf(null) }
        LaunchedEffect(itemId, fromStart, resumeSec) {
            ready = null
            error = null
            try {
                val token = container.streamTokenManager.valid()
                val base = container.config.apiBaseUrl.trimEnd('/')
                // Resume precedence: fromStart → 0; an explicit Zap-expand
                // resumeSec → that exact second; otherwise the saved progress.
                val resume = when {
                    fromStart -> 0
                    resumeSec >= 0 -> resumeSec
                    else -> runCatching { container.chinoApi.getProgress(itemId).positionSec }.getOrDefault(0)
                }
                val caps = CodecCaps.queryParam
                val info = runCatching { container.chinoApi.playInfo(itemId, caps = caps.ifEmpty { null }) }.getOrNull()
                val item = runCatching { container.chinoApi.getItem(itemId) }.getOrNull()
                val segs = runCatching { container.chinoApi.itemSegments(itemId).segments }.getOrDefault(emptyList())
                val sidecarSubs = runCatching { container.chinoApi.itemSubtitles(itemId).subtitles }
                    .getOrDefault(emptyList())
                    // Sidecar URLs from chino-api are `/api/v1/play/subs/{id}.vtt`.
                    // chino-api's StreamMiddleware (auth/oidc.go L78-87)
                    // accepts `?stream=<signed-token>` minted by
                    // /me/stream-token — same shape as master.m3u8. The
                    // sibling `?token=` param is reserved for raw OIDC
                    // bearers; passing a stream token there 401s with
                    // "oidc: failed to unmarshal claims". Strip `/api`
                    // so we don't double-prefix (apiBaseUrl already
                    // ends in `/api/`).
                    .map { s ->
                        val absUrl = if (s.url.startsWith("http")) {
                            s.url
                        } else {
                            base + s.url.removePrefix("/api")
                        }
                        val sep = if ('?' in absUrl) '&' else '?'
                        s.copy(url = "$absUrl${sep}stream=$token")
                    }
                // Sibling episode resolution (TV pattern, ported). Series id
                // lives on Item.parentId for episodes; for movies parentId is
                // null and the prev/next chevrons stay disabled.
                val seriesId = item?.parentId
                // Series title for the episode player heading. The episode Item
                // doesn't carry the series name, so fetch the parent series item
                // by id. Best-effort — a failed/absent fetch degrades the title
                // to "S01E02 · {episodeTitle}".
                val seriesTitle = seriesId?.let { sid ->
                    runCatching { container.chinoApi.getItem(sid).title }.getOrNull()
                }
                val flatEpisodes = seriesId?.let { sid ->
                    runCatching {
                        container.chinoApi.seriesEpisodes(sid).seasons
                            .flatMap { it.episodes }
                    }.getOrDefault(emptyList())
                } ?: emptyList()
                val idx = flatEpisodes.indexOfFirst { it.id == itemId }
                val prevId = if (idx > 0) flatEpisodes[idx - 1].id else null
                val nextId = if (idx in 0 until flatEpisodes.size - 1) flatEpisodes[idx + 1].id else null
                // Default rung follows the server's recommendation (mirrors TV's
                // `quality ?: info?.defaultQuality ?: "high"`); the quality
                // picker overrides it at runtime via a player rebuild.
                val streamQuality = info?.defaultQuality ?: "high"
                // Trickplay scrub-preview cues — only packaged items have the
                // sprite tree (the analyzer emits it alongside the CMAF). For
                // transcode/passthrough/remux we skip the fetch so we don't log
                // a 404 every play; an empty list degrades to time-only
                // scrubbing. Mirrors chino-web's `info.mode !== 'packaged'`
                // gate before fetching thumbnails.vtt.
                val trickplay = if (info?.mode?.equals("packaged", ignoreCase = true) == true) {
                    runCatching { parseTrickplayVtt(container.chinoApi.trickplayVtt(itemId, token)) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                ready = PlayState(
                    itemId = itemId,
                    base = base,
                    streamToken = token,
                    caps = caps,
                    currentQuality = streamQuality,
                    qualities = info?.qualities ?: emptyList(),
                    resumeMs = resume * 1000L,
                    title = composePlayerTitle(item, seriesTitle),
                    info = info,
                    segments = segs,
                    prevEpisodeId = prevId,
                    nextEpisodeId = nextId,
                    sidecarSubtitles = sidecarSubs,
                    trickplayCues = trickplay,
                )
            } catch (e: Exception) {
                error = "Playback failed: ${e.message ?: e::class.simpleName.orEmpty()}"
            }
        }

        when {
            error != null -> ErrorState(message = error!!, itemId = itemId, onBack = { nav.pop() })
            ready == null -> LoadingState()
            else -> PlaybackSurface(
                state = ready!!,
                itemId = itemId,
                onBack = { nav.pop() },
                // Home: reset the stack to the signed-in shell root. Same idiom
                // the auth/profile flows use (replaceAll(MainShellScreen())),
                // so it lands on Home regardless of how deep the stack is.
                onHome = { nav.replaceAll(MainShellScreen()) },
                onSwitchItem = { newId ->
                    // Replace this player screen with the new one so back
                    // doesn't pile up sibling episodes in the stack.
                    nav.replace(PlayerScreen(itemId = newId, fromStart = true))
                },
                onPlaybackError = { error = it },
            )
        }
    }
}

private data class PlayState(
    /** Item under playback — needed to rebuild the master URL on a quality
     *  switch and to fire the next-episode pre-warm. */
    val itemId: String,
    /** Trailing-slash-trimmed API base (…/api). Master + sprite URLs hang off it. */
    val base: String,
    /** `?stream=` HMAC token — stable across OIDC renews; reused for the
     *  master URL, sidecar subs, trickplay VTT, and sprite JPGs. */
    val streamToken: String,
    /** Decoder caps query value (may be empty). */
    val caps: String,
    /** Currently-selected rung name ("high" / "medium" / "low"). Switching
     *  rebuilds the player at the new ?q= URL while preserving position. */
    val currentQuality: String,
    /** Quality ladder the server can serve (drives the picker menu). */
    val qualities: List<QualityRung>,
    val resumeMs: Long,
    val title: String,
    val info: PlayInfo?,
    val segments: List<Segment>,
    val prevEpisodeId: String?,
    val nextEpisodeId: String?,
    /** Sidecar subtitles fetched from /v1/items/{id}/subtitles. These are
     *  attached to the MediaItem via setSubtitleConfigurations() so
     *  ExoPlayer side-loads them as TEXT tracks — they appear in the
     *  captions menu alongside any embedded text tracks from the HLS
     *  manifest, and the existing applySubtitleSelection logic handles
     *  switching between them. */
    val sidecarSubtitles: List<SidecarSubtitle>,
    /** Scrub-preview thumbnail cues parsed from the trickplay VTT. Empty
     *  for non-packaged items (or when the fetch failed) — the scrubber
     *  then shows time/segment text only. */
    val trickplayCues: List<TrickplayCue>,
)

/** Builds the chino-stream master playlist URL. Mirrors the TV's builder:
 *  `?stream=` first, then `&caps=` (only when non-empty), then `&q=`.
 *  Centralised so the initial prepare and a quality switch produce
 *  byte-identical URLs save for the rung. */
private fun buildMasterUrl(
    base: String,
    itemId: String,
    token: String,
    quality: String,
    caps: String,
): String = buildString {
    append("$base/v1/items/$itemId/play/master.m3u8?stream=$token")
    if (caps.isNotEmpty()) append("&caps=$caps")
    append("&q=$quality")
}

private enum class OpenPopover { NONE, SPEED, AUDIO, CAPTIONS, INFO, VOLUME, QUALITY }

/** Audio track surface for the inline menu. Wraps the Media3 group +
 *  index so the apply step can rebuild the override. Ported verbatim
 *  from chino-androidtv's PlayerScreen. */
private data class AudioTrack(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
    val group: Tracks.Group,
    val trackIndex: Int,
)

private data class SubtitleTrack(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
    val group: Tracks.Group,
    val trackIndex: Int,
)

private fun collectAudioTracks(tracks: Tracks): List<AudioTrack> =
    tracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { g ->
            (0 until g.length).map { i ->
                val fmt = g.getTrackFormat(i)
                val primary = fmt.label
                    ?: fmt.language?.takeIf { it.isNotBlank() && it != "und" }
                    ?: "Track ${i + 1}"
                val parts = buildList {
                    add(primary)
                    if (fmt.channelCount > 0) {
                        add(
                            when (fmt.channelCount) {
                                1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"
                                else -> "${fmt.channelCount}ch"
                            }
                        )
                    }
                    fmt.codecs?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                AudioTrack(
                    id = "${g.mediaTrackGroup.id}#$i",
                    label = parts.joinToString(" • "),
                    language = fmt.language,
                    selected = g.isTrackSelected(i),
                    group = g,
                    trackIndex = i,
                )
            }
        }

private fun collectSubtitleTracks(tracks: Tracks): List<SubtitleTrack> =
    tracks.groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { g ->
            (0 until g.length).map { i ->
                val fmt = g.getTrackFormat(i)
                val label = fmt.label ?: fmt.language ?: "Track ${i + 1}"
                SubtitleTrack(
                    id = "${g.mediaTrackGroup.id}#$i",
                    label = label,
                    language = fmt.language,
                    selected = g.isTrackSelected(i),
                    group = g,
                    trackIndex = i,
                )
            }
        }

private fun applyAudioSelection(player: ExoPlayer, track: AudioTrack) {
    val params = player.trackSelectionParameters
    player.trackSelectionParameters = params.buildUpon()
        .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
        .build()
}

/** When `track` is null, subtitles are disabled. Otherwise the given
 *  track becomes the active text track. */
private fun applySubtitleSelection(player: ExoPlayer, track: SubtitleTrack?) {
    val params = player.trackSelectionParameters
    player.trackSelectionParameters = if (track == null) {
        params.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
    } else {
        params.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
            .build()
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color(0xFF58A6FF))
    }
}

/** Terminal playback failure — both the prepare-time catch and Media3's
 *  onPlayerError land here (the auto bug report has already fired by then).
 *  Web parity (PlayerPage.tsx terminal-error overlay): a secondary "Report
 *  a bug" opens the manual dialog pre-filled with the technical error
 *  string so the user can add what they were doing. Tap anywhere else
 *  still backs out, exactly as before. */
@Composable
private fun ErrorState(message: String, itemId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Same choreography as Settings: capture the screenshot BEFORE the
    // dialog mounts (a dialog over the screen would only photograph
    // itself); non-null draft = dialog open. Plain holder class, not
    // data — ByteArray equality is identity anyway.
    var bugDraft by remember { mutableStateOf<PlayerBugDraft?>(null) }
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(text = message, color = MaterialTheme.colorScheme.error)
            // Secondary action styled like web's bg-white/10 pill.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x1AFFFFFF))
                    .clickable {
                        scope.launch {
                            bugDraft = PlayerBugDraft(
                                screenshot = runCatching {
                                    cloud.nalet.chino.mobile.feedback.captureScreenshot()
                                }.getOrNull(),
                            )
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(text = "Report a bug", color = Color.White, fontSize = 14.sp)
            }
        }
        bugDraft?.let { draft ->
            BugReportDialog(
                screenshot = draft.screenshot,
                context = mapOf("screen" to "player", "itemId" to itemId),
                // Same pre-fill template as web's player → BugReportDialog
                // hand-off (PlayerPage.tsx L3494).
                initialDescription = "Playback failed while watching this item." +
                    "\n\n--- technical details ---\n$message",
                onDismiss = { bugDraft = null },
            )
        }
    }
}

/** Holder for the pre-captured screenshot while the player's bug-report
 *  dialog is open. Plain class on purpose — a data class would lint on
 *  ByteArray equals/hashCode. */
private class PlayerBugDraft(val screenshot: ByteArray?)

/** Hides system bars while mounted; restores on dispose. No-ops if the
 *  host LocalContext isn't an Activity (e.g. Compose preview). */
@Composable
private fun ImmersiveFullscreenEffect(active: Boolean) {
    val view = LocalView.current
    val ctx = LocalContext.current
    DisposableEffect(active, view, ctx) {
        val activity = ctx as? Activity
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            if (active) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun PlaybackSurface(
    state: PlayState,
    itemId: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSwitchItem: (String) -> Unit,
    onPlaybackError: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current

    // Quality switching (mirrors TV's currentQuality + reloadKey). Changing
    // the rung rebuilds the master URL, which re-keys the ExoPlayer
    // `remember` below → teardown + fresh prepare at the new ?q=. The
    // player's resume seek reads `pendingResumeMs`, set just before the
    // switch so the new stream picks up exactly where the old one was
    // instead of jumping back to `state.resumeMs` (the original entry
    // position). reloadKey forces a rebuild even if the user toggles back
    // to the same rung-string after a fallback.
    var currentQuality by remember { mutableStateOf(state.currentQuality) }
    var reloadKey by remember { mutableStateOf(0) }
    // The position to resume at on the NEXT player build. -1 means "use
    // state.resumeMs" (first build / fresh mount). A quality switch stamps
    // the live position here right before bumping reloadKey.
    var pendingResumeMs by remember { mutableStateOf(-1L) }
    val activeMasterUrl = remember(currentQuality, reloadKey) {
        buildMasterUrl(state.base, state.itemId, state.streamToken, currentQuality, state.caps)
    }

    val streamClient = remember {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            // 120s (was 45s): under nas001 NFS read contention a single packaged
            // segment can stall well past 45s; the short timeout ABORTED the fetch
            // (SocketTimeoutException) so the buffer never filled and playback
            // rebuffered. Let the slow segment complete — same fix as chino-androidtv.
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    val player = remember(activeMasterUrl) {
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(streamClient)
            .setUserAgent("chino-mobile/0.1 (Android)")
        // Sidecar subs from /v1/items/{id}/subtitles attached as
        // side-loaded text tracks (TV pattern). ExoPlayer merges them
        // with any embedded text tracks from the HLS manifest, so
        // onTracksChanged sees them all in C.TRACK_TYPE_TEXT and the
        // captions menu lists every option.
        val sideSubs = state.sidecarSubtitles.map { sub ->
            // Format drives MIME so Media3 picks the right decoder: bitmap
            // subs (.sup) route raw bytes into PgsDecoder etc, text subs flow
            // through the legacy VTT/SubRip path. Mirrors chino-androidtv.
            val mime = when (sub.format?.lowercase()) {
                "pgs" -> MimeTypes.APPLICATION_PGS
                "vobsub" -> MimeTypes.APPLICATION_VOBSUB
                "dvb" -> MimeTypes.APPLICATION_DVBSUBS
                "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                else -> MimeTypes.TEXT_VTT
            }
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                .setMimeType(mime)
                .setLanguage(sub.lang.takeIf { it.isNotBlank() })
                .setLabel(sub.label.takeIf { it.isNotBlank() })
                .setSelectionFlags(if (sub.default == true) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        // A 404 on the manifest (.m3u8) means chino-stream has no playback
        // asset for the item — permanent, so fail fast instead of running the
        // default 3 retries. Segment 404s keep the default (transient) policy.
        val retryPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(
                info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
            ): Long {
                val ex = info.exception
                if (ex is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException &&
                    ex.responseCode == 404 && ex.dataSpec.uri.toString().contains(".m3u8")
                ) {
                    return C.TIME_UNSET
                }
                return super.getRetryDelayMsFor(info)
            }
        }
        val hlsSource = HlsMediaSource.Factory(httpFactory)
            .setLoadErrorHandlingPolicy(retryPolicy)
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(activeMasterUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
            )
        // Side-load each VTT as its own SingleSampleMediaSource and merge.
        // Putting the SubtitleConfiguration directly on the HLS MediaItem
        // ended up classifying the side-loaded track as METADATA (not TEXT)
        // in Media3 1.5.1 — onTracksChanged showed groups [VIDEO, AUDIO,
        // OTHER(5)] with no TEXT group, so the captions menu stayed empty.
        // MergingMediaSource with explicit SingleSampleMediaSource sources
        // ensures each VTT shows up under TRACK_TYPE_TEXT with the
        // SubtitleConfiguration's MIME/lang/label intact.
        val mediaSource = if (sideSubs.isEmpty()) {
            hlsSource
        } else {
            // Media3 1.5+ moved subtitle parsing to extraction time and
            // disabled the legacy text renderer path by default. The
            // SingleSampleMediaSource.Factory API in 1.5.1 doesn't
            // expose a SubtitleParser.Factory setter, so we re-enable
            // legacy decoding on the renderers factory below (see the
            // `ExoPlayer.Builder` call) and feed the raw VTT sample
            // through the legacy decoder. Without that flag the player
            // crashes mid-prepare with
            //   "Legacy decoding is disabled, can't handle text/vtt
            //    samples (expected application/x-media3-cues)".
            val sideSources = sideSubs.map { cfg ->
                androidx.media3.exoplayer.source.SingleSampleMediaSource.Factory(httpFactory)
                    .createMediaSource(cfg, C.TIME_UNSET)
            }
            androidx.media3.exoplayer.source.MergingMediaSource(
                hlsSource,
                *sideSources.toTypedArray(),
            )
        }
        // Custom RenderersFactory that enables legacy subtitle decoding
        // on every TextRenderer it creates. Media3 1.5 disabled this
        // path by default — but SingleSampleMediaSource.Factory has no
        // SubtitleParser.Factory setter in 1.5.1, so the side-loaded
        // VTT samples can't be parsed during extraction either. The
        // legacy decoder path is the only working option for raw text/
        // vtt sidecars in this Media3 version.
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            init {
                // Fall back to the next decoder (e.g. the software
                // c2.android.avc.decoder) when the preferred hardware decoder
                // fails to initialize. Some device decoders advertise support
                // for H.264 High@4.0 1080p (format_supported=YES) yet throw on
                // MediaCodec init -> ERROR_CODE_DECODER_INIT_FAILED. Fallback
                // keeps playback alive on those devices (seen on SM-T500).
                setEnableDecoderFallback(true)
            }
            override fun buildTextRenderers(
                ctx: android.content.Context,
                output: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                val before = out.size
                super.buildTextRenderers(ctx, output, outputLooper, extensionRendererMode, out)
                for (i in before until out.size) {
                    (out[i] as? androidx.media3.exoplayer.text.TextRenderer)
                        ?.experimentalSetLegacyDecodingEnabled(true)
                }
            }
        }
        // On a quality switch `pendingResumeMs` holds the live position so
        // the rebuilt player resumes there; on first mount it's -1 and we
        // fall back to the entry resume position.
        val seekTarget = if (pendingResumeMs >= 0) pendingResumeMs else state.resumeMs
        ExoPlayer.Builder(context, renderersFactory).build().also {
            it.setMediaSource(mediaSource)
            it.prepare()
            if (seekTarget > 0) it.seekTo(seekTarget)
            it.playWhenReady = true
        }
    }

    var positionMs by remember { mutableStateOf(state.resumeMs) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var bufferedMs by remember { mutableStateOf(0L) }
    var muted by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1f) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    var fullscreen by remember { mutableStateOf(false) }

    // Re-apply the user's playback prefs to the freshly built player after a
    // quality switch. The player instance is rebuilt (remember(activeMasterUrl))
    // but mute/volume/speed are only applied by the imperative chrome handlers
    // on user action, so the new instance would otherwise start at defaults
    // while the chips still show the prior selection.
    LaunchedEffect(player) {
        player.volume = if (muted) 0f else volume
        player.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    // Hoisted "user is interacting" flags. ANY of these → suppress auto-hide.
    var scrubbing by remember { mutableStateOf(false) }
    var volumeDragging by remember { mutableStateOf(false) }
    var openPopover by remember { mutableStateOf(OpenPopover.NONE) }

    // While scrubbing, this is the preview position (so the scrubber visual
    // doesn't fight the player's currentPosition while the user drags).
    var scrubPreviewMs by remember { mutableStateOf(0L) }

    // Track lists, refreshed from Player.Listener.onTracksChanged. The
    // selected flag flips when the user picks a track AND when the
    // server starts a new adaptation period — both paths feed the same
    // override-rebuilding logic in apply{Audio,Subtitle}Selection.
    var audioTracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<SubtitleTrack>>(emptyList()) }
    var subtitlesEnabled by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onTracksChanged(t: Tracks) {
                audioTracks = collectAudioTracks(t)
                subtitleTracks = collectSubtitleTracks(t)
                subtitlesEnabled = subtitleTracks.any { it.selected }
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                // Auto bug report — fire-and-forget + silent (BugReporter
                // swallows every failure and session-throttles repeat
                // fingerprints). Position is read here on the player's
                // looper, BEFORE the async hop; the screenshot is
                // best-effort — PlayerView's SurfaceView video plane may
                // come out black, which is accepted (the chrome + error
                // text are the useful pixels).
                val positionSec = (player.currentPosition / 1000L).toString()
                container.appScope.launch {
                    val shot = runCatching {
                        cloud.nalet.chino.mobile.feedback.captureScreenshot()
                    }.getOrNull()
                    container.bugReporter.report(
                        kind = "player",
                        description = buildString {
                            append(e.errorCodeName)
                            e.message?.let { append(" — ").append(it) }
                            append('\n')
                            append(e.stackTraceToString())
                        }.take(8 * 1024),
                        // codeName+message only (no frames): the same decoder
                        // failure should dedupe regardless of which call path
                        // tripped it.
                        fingerprint = cloud.nalet.chino.mobile.feedback.bugFingerprint(
                            name = e.errorCodeName,
                            message = e.message,
                        ),
                        context = mapOf(
                            "itemId" to itemId,
                            "positionSec" to positionSec,
                            "screen" to "player",
                        ),
                        screenshot = shot,
                    )
                }
                // A 404 on the manifest = the item has no playable asset on
                // chino-stream; show a plain-language message. Anything else
                // keeps the engine error code for diagnostics.
                val http = e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                val manifestMissing = http?.responseCode == 404 &&
                    (http.dataSpec.uri.toString().contains(".m3u8"))
                onPlaybackError(
                    if (manifestMissing) "This title isn't available to stream yet."
                    else "Playback failed: ${e.errorCodeName}${e.message?.let { " — $it" } ?: ""}",
                )
            }
        }
        player.addListener(listener)
        onDispose {
            val pos = (player.currentPosition / 1000L).toInt()
            val dur = (player.duration.takeIf { it != C.TIME_UNSET } ?: 0L).let { (it / 1000L).toInt() }
            if (pos > 0) {
                container.appScope.launch {
                    runCatching {
                        container.chinoApi.postProgress(
                            itemId,
                            cloud.nalet.chino.mobile.data.api.ProgressBody(positionSec = pos, durationSec = dur),
                        )
                    }
                }
            }
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) positionMs = player.currentPosition
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            bufferedMs = player.bufferedPosition
            delay(250)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(10_000)
            val pos = (player.currentPosition / 1000L).toInt()
            val dur = (player.duration.takeIf { it != C.TIME_UNSET } ?: 0L).let { (it / 1000L).toInt() }
            if (pos > 0) {
                runCatching {
                    container.chinoApi.postProgress(
                        itemId,
                        cloud.nalet.chino.mobile.data.api.ProgressBody(positionSec = pos, durationSec = dur),
                    )
                }
            }
        }
    }

    ImmersiveFullscreenEffect(active = fullscreen)

    // Auto-hide chrome — bumped on any interaction, suppressed while user
    // is dragging anything or a popover is open.
    var chromeVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableStateOf(cloud.nalet.chino.mobile.currentTimeMillis()) }
    val noteInteraction: () -> Unit = { lastInteraction = cloud.nalet.chino.mobile.currentTimeMillis() }
    LaunchedEffect(lastInteraction, isPlaying, scrubbing, volumeDragging, openPopover) {
        chromeVisible = true
        if (scrubbing || volumeDragging || openPopover != OpenPopover.NONE) return@LaunchedEffect
        val grace = if (isPlaying) 4_000L else 8_000L
        delay(grace)
        chromeVisible = false
    }

    val displayPositionMs = if (scrubbing) scrubPreviewMs else positionMs

    // ---- Binge: pre-warm + auto-play-next countdown (TV/web parity) ----
    // Settings drive whether the countdown auto-fires; the next/prev
    // chevrons stay manual regardless (they're rendered from
    // state.prevEpisodeId / nextEpisodeId, resolved at prepare time).
    val settings by container.settings.flow.collectAsState(
        initial = cloud.nalet.chino.mobile.data.AppSettings(),
    )

    // Once-per-player guard so the preferred-language auto-apply doesn't
    // stomp a manual track pick. A manual switch (onSelectAudio /
    // onSelectSubtitle below) flips these, and applyPreferredLanguages
    // re-keys on `player` so a quality switch / episode change re-applies
    // the saved preference on the fresh instance (manual picks are
    // session-only by design — they don't survive a rebuild).
    var userPickedAudio by remember(player) { mutableStateOf(false) }
    var userPickedSub by remember(player) { mutableStateOf(false) }

    // Auto-apply the user's preferred AUDIO + SUBTITLE language on first
    // load — mirrors chino-androidtv's setPreferredTextLanguage /
    // setPreferredAudioLanguage and chino-web's auto-pick on load. We feed
    // ExoPlayer's preferred*Language so its automatic track selector picks
    // the closest matching track (track.language is an ISO code; Media3
    // fuzzy-matches 2-/3-letter forms). Skipped once the user has manually
    // switched that track type this session. Audio "orig" leaves the
    // source default; subtitle "off"/blank disables the text type.
    LaunchedEffect(player, settings.preferredAudioLang, settings.preferredSubLang) {
        val audioPref = settings.preferredAudioLang
        val subPref = settings.preferredSubLang
        val params = player.trackSelectionParameters.buildUpon()
        if (!userPickedAudio && audioPref.isNotBlank() && !audioPref.equals("orig", ignoreCase = true)) {
            params.setPreferredAudioLanguage(audioPref)
        }
        if (!userPickedSub) {
            if (subPref.isBlank() || subPref.equals("off", ignoreCase = true)) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            } else {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                params.setPreferredTextLanguage(subPref)
            }
        }
        player.trackSelectionParameters = params.build()
    }

    // Active segment under the *played* head (not the scrub preview) —
    // the credits gate must follow real playback, otherwise scrubbing into
    // credits would arm the auto-next while the user is just inspecting.
    val inCredits = remember(positionMs, state.segments) {
        val ms = positionMs
        state.segments.any { it.kind.equals("credits", ignoreCase = true) && ms >= it.startMs && ms < it.endMs }
    }
    // Fallback end gate: within ~20s of a known duration and no credits
    // segment exists. Mirrors web's "credits OR near-end" trigger so movies
    // / unsegmented episodes still chain.
    val hasCredits = remember(state.segments) {
        state.segments.any { it.kind.equals("credits", ignoreCase = true) }
    }
    // Later of (20s-from-end) and 95% so short clips don't arm in their first
    // half (a fixed 20s window covers half of a <40s clip). Mirrors web's
    // credits-or-95% trigger.
    val nearEnd = remember(positionMs, durationMs) {
        durationMs > 0 && positionMs > 0 &&
            positionMs >= maxOf(durationMs - 20_000L, (durationMs * 0.95).toLong())
    }
    val atEnd = inCredits || (!hasCredits && nearEnd)

    // Manual Skip button — web parity (PlayerPage.tsx skipSegment). Shown
    // whenever the *played* head sits inside an intro / recap / credits
    // segment; tapping seeks to that segment's end. INDEPENDENT of the
    // binge auto-skip settings, so a user who turned auto-skip OFF can
    // still skip manually. Follows the played head (not the scrub preview)
    // so it doesn't pop up while the user is just inspecting via the
    // scrubber. The credits button is suppressed while the auto-play-next
    // countdown card is on screen (web does the same: the countdown card
    // replaces the bare "Skip Credits" pill when a next episode exists).
    val activeSkipSegment = remember(positionMs, state.segments) {
        val ms = positionMs
        state.segments.firstOrNull { seg ->
            ms >= seg.startMs && ms < seg.endMs &&
                seg.kind.lowercase() in SKIPPABLE_KINDS &&
                // A post-credits preview (a next-episode teaser mislabelled as a
                // recap, or an explicit `preview`) is NOT skippable — the teaser
                // plays under a "Next episode ▶" card instead. Excluding it here
                // stops a post-credits recap from showing "Skip Recap".
                !isPostCreditsPreview(seg, state.segments)
        }
    }

    // Active post-credits preview under the played head — only meaningful when a
    // next episode exists. Drives the "Next episode ▶" card (and, with
    // autoPlayNext on, the auto-next countdown below). An explicit `preview`
    // segment, or a `recap` starting at/after the credits, both qualify.
    val activePreviewSegment = remember(positionMs, state.segments, state.nextEpisodeId) {
        if (state.nextEpisodeId == null) return@remember null
        val ms = positionMs
        state.segments.firstOrNull { seg ->
            ms >= seg.startMs && ms < seg.endMs && isPostCreditsPreview(seg, state.segments)
        }
    }

    // Pre-warm lead window: fire ~30s BEFORE the cut-over (credits start, or
    // 30s-from-end when unsegmented) so chino-stream has head start before the
    // auto-next countdown — matches TV's PREWARM_WINDOW_MS. Separate from [atEnd]
    // (which arms the countdown) so the warm genuinely leads.
    val creditsStartMs = remember(state.segments) {
        state.segments.firstOrNull { it.kind.equals("credits", ignoreCase = true) }?.startMs
    }
    val prewarmZone = remember(positionMs, creditsStartMs, durationMs) {
        positionMs > 0 && when {
            creditsStartMs != null -> positionMs >= creditsStartMs - 30_000L
            durationMs > 0 -> positionMs >= durationMs - 30_000L
            else -> false
        }
    }

    // Binge pre-warm — fire ChinoApi.prewarm(nextId) once per nextId when we
    // hit the credits/near-end zone so chino-stream warms the next stream
    // before the countdown elapses. Deduped via prewarmedFor. Mirrors
    // TV.prewarmMaster / web's prewarmedNextRef. q="high" matches the
    // master URL the next player will request from a binge nav (fromStart).
    var prewarmedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(prewarmZone, state.nextEpisodeId) {
        val nextId = state.nextEpisodeId ?: return@LaunchedEffect
        if (!prewarmZone || prewarmedFor == nextId) return@LaunchedEffect
        prewarmedFor = nextId
        container.appScope.launch {
            runCatching {
                container.chinoApi.prewarm(nextId, caps = state.caps.ifEmpty { null }, quality = "high")
            }
        }
        container.telemetry.event("binge_prewarm", itemId = itemId, extra = mapOf("next_item" to nextId))
    }

    // Auto-play-next countdown: arms when we're in credits/near-end OR inside a
    // post-credits preview teaser, AND a next episode exists AND the setting is
    // on AND the user hasn't dismissed it. Counts down `countdownSec` then
    // advances. autoNextSec == null → no overlay. A single in-flight guard
    // (`advancing`) stops the tick loop from firing the nav twice if
    // recomposition re-enters at 0. The preview reuses this same countdown +
    // next-episode id + navigation the credits auto-advance already uses; the
    // "Next episode ▶" card just renders the seconds instead of AutoNextOverlay.
    var autoNextSec by remember { mutableStateOf<Int?>(null) }
    var autoNextDismissed by remember { mutableStateOf(false) }
    var advancing by remember { mutableStateOf(false) }
    val previewActive = activePreviewSegment != null
    LaunchedEffect(atEnd, previewActive, state.nextEpisodeId, settings.autoPlayNext, autoNextDismissed) {
        val nextId = state.nextEpisodeId
        val wantAuto = settings.autoPlayNext
        if ((!atEnd && !previewActive) || nextId == null || autoNextDismissed || !wantAuto) {
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

    // Quality switch: stamp the live position so the rebuilt player resumes
    // there, set the new rung, and bump reloadKey to force a teardown even
    // when the rung-string is unchanged after a fallback. The player
    // `remember` re-keys on activeMasterUrl (currentQuality + reloadKey).
    val switchQuality: (String) -> Unit = remember(player) {
        { rung ->
            if (rung != currentQuality) {
                container.telemetry.event(
                    "quality_switch",
                    itemId = itemId,
                    extra = mapOf("from" to currentQuality, "to" to rung),
                )
                pendingResumeMs = player.currentPosition.coerceAtLeast(0L)
                currentQuality = rung
                reloadKey += 1
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    // Tap-to-toggle is ONLY a wake gesture. When chrome
                    // is hidden, tap brings it back. When visible, the
                    // auto-hide timer handles dismissal — don't toggle
                    // off on tap, because empty-space taps between
                    // buttons (or near chrome backgrounds) were
                    // accidentally dismissing the chrome mid-interaction,
                    // making popover buttons appear to do nothing.
                    if (!chromeVisible) {
                        chromeVisible = true
                        noteInteraction()
                    } else {
                        // Bump the timer so any chrome interaction resets
                        // the auto-hide clock.
                        noteInteraction()
                    }
                })
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = true
                }
            },
            // A quality switch rebuilds the ExoPlayer instance (player is
            // remember(activeMasterUrl)); the factory only runs once, so without
            // this the view keeps the old, released player and the surface goes
            // black after switching. Re-bind whenever the player instance changes.
            update = { view -> if (view.player !== player) view.player = player },
        )
        AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
            Chrome(
                state = state,
                positionMs = displayPositionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                isPlaying = isPlaying,
                muted = muted,
                volume = volume,
                captionsOn = subtitlesEnabled,
                fullscreen = fullscreen,
                playbackSpeed = playbackSpeed,
                openPopover = openPopover,
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                onPlayPause = {
                    if (player.isPlaying) player.pause() else player.play()
                    noteInteraction()
                },
                onSkipBack = {
                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                    noteInteraction()
                },
                onSkipForward = {
                    val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 10_000L).coerceAtMost(dur))
                    noteInteraction()
                },
                onScrubStart = { ms ->
                    scrubbing = true
                    scrubPreviewMs = ms
                },
                onScrubUpdate = { ms ->
                    scrubPreviewMs = ms
                },
                onScrubCommit = { ms ->
                    player.seekTo(ms)
                    scrubbing = false
                    noteInteraction()
                },
                onToggleMute = {
                    muted = !muted
                    player.volume = if (muted) 0f else volume
                    noteInteraction()
                },
                onVolumeChange = { v ->
                    volume = v.coerceIn(0f, 1f)
                    muted = false
                    player.volume = volume
                },
                onVolumeDragStart = { volumeDragging = true },
                onVolumeDragEnd = {
                    volumeDragging = false
                    noteInteraction()
                },
                onToggleCaptions = {
                    // Captions button now opens the inline captions menu;
                    // a tap when the menu is already open closes it. The
                    // actual on/off is per-track via the menu — there's
                    // no separate visible flag. (Inlined toggle instead
                    // of routing through onTogglePopover because named
                    // arguments can't reference each other in the same
                    // call.)
                    openPopover = if (openPopover == OpenPopover.CAPTIONS) {
                        OpenPopover.NONE
                    } else {
                        OpenPopover.CAPTIONS
                    }
                    noteInteraction()
                },
                onSelectAudio = { t -> userPickedAudio = true; applyAudioSelection(player, t); openPopover = OpenPopover.NONE; noteInteraction() },
                onSelectSubtitle = { t -> userPickedSub = true; applySubtitleSelection(player, t); openPopover = OpenPopover.NONE; noteInteraction() },
                onTogglePopover = { p ->
                    openPopover = if (openPopover == p) OpenPopover.NONE else p
                    noteInteraction()
                },
                onSelectSpeed = { s ->
                    playbackSpeed = s
                    player.playbackParameters = PlaybackParameters(s)
                    openPopover = OpenPopover.NONE
                    noteInteraction()
                },
                currentQuality = currentQuality,
                onSelectQuality = { q ->
                    switchQuality(q)
                    openPopover = OpenPopover.NONE
                    noteInteraction()
                },
                onToggleFullscreen = {
                    fullscreen = !fullscreen
                    noteInteraction()
                },
                onPrevEpisode = state.prevEpisodeId?.let { id -> { onSwitchItem(id) } },
                onNextEpisode = state.nextEpisodeId?.let { id -> { onSwitchItem(id) } },
                onBack = onBack,
                onHome = onHome,
            )
        }
        // Auto-play-next affordance — rendered OUTSIDE
        // AnimatedVisibility(chromeVisible) so it stays on screen even after
        // the chrome auto-hides during the credits / preview. Sits at the
        // bottom above the controls. Dismiss cancels the auto-fire for the
        // rest of this episode; "Play now" advances immediately.
        val nextId = state.nextEpisodeId
        val sec = autoNextSec
        when {
            // Post-credits preview teaser: the teaser keeps playing under a
            // "Next episode ▶" card. With autoPlayNext on it counts down
            // (autoNextSec, reusing the credits countdown) and auto-advances at
            // 0; with it off, it's a plain manual button. Either way it reuses
            // state.nextEpisodeId + onSwitchItem — the SAME nav the credits
            // auto-advance uses. Takes priority over AutoNextOverlay so the two
            // don't stack while a preview is active.
            activePreviewSegment != null && nextId != null -> {
                NextEpisodeCard(
                    secondsLeft = sec.takeIf { settings.autoPlayNext },
                    onDismiss = {
                        autoNextDismissed = true
                        autoNextSec = null
                    },
                    onPlayNext = {
                        if (!advancing) {
                            advancing = true
                            onSwitchItem(nextId)
                        }
                    },
                )
            }
            sec != null && nextId != null -> {
                AutoNextOverlay(
                    secondsLeft = sec,
                    onDismiss = {
                        autoNextDismissed = true
                        autoNextSec = null
                    },
                    onPlayNow = {
                        if (!advancing) {
                            advancing = true
                            onSwitchItem(nextId)
                        }
                    },
                )
            }
        }
        // Manual Skip pill — Lucide SkipForward + web wording ("Skip Intro"
        // / "Skip Recap" / "Skip Credits"), bottom-right, seeking to the
        // segment end. Rendered OUTSIDE the chrome's AnimatedVisibility so
        // it stays reachable even after the chrome auto-hides inside the
        // segment. Suppressed for credits while the auto-play-next card is
        // up (the card carries the skip-forward intent there). Reuses the
        // same seek path as the ±10s controls; touches neither auto-skip
        // nor the auto-next countdown.
        val skipSeg = activeSkipSegment
        val countdownShowing = autoNextSec != null && state.nextEpisodeId != null
        if (skipSeg != null && !(skipSeg.kind.equals("credits", ignoreCase = true) && countdownShowing)) {
            SkipSegmentButton(
                label = skipSegmentLabel(skipSeg.kind),
                onClick = {
                    player.seekTo(skipSeg.endMs)
                    container.telemetry.event(
                        "skip_segment",
                        itemId = itemId,
                        extra = mapOf("kind" to skipSeg.kind),
                    )
                },
            )
        }
    }
}

/** Segment kinds that get a manual "Skip …" pill. Matches web's
 *  skipSegment('intro' | 'credits' | 'recap'). Post-credits previews are
 *  excluded — they get the "Next episode ▶" card instead (see
 *  [isPostCreditsPreview]). */
private val SKIPPABLE_KINDS = setOf("intro", "recap", "credits")

/**
 * Position-based reclassification of a next-episode preview. The analyzer
 * mislabels post-credits "next time on…" teasers as `recap` (a "Previously
 * on…" opener) — hundreds of them in the catalog — so a recap that STARTS at
 * or after the credits is really a post-roll preview, and an explicit `preview`
 * kind always is. Such segments get the "Next episode ▶" card treatment (play
 * the teaser, offer to jump) instead of the skip-recap treatment (seek past it,
 * which robbed the viewer of the teaser). A genuine opening recap starts near
 * 0:00 and keeps the skip behaviour. Mirrors the TV impl exactly.
 */
private fun isPostCreditsPreview(seg: Segment, all: List<Segment>): Boolean {
    return when (seg.kind.lowercase()) {
        "preview" -> true
        "recap" -> {
            val creditsStart = all
                .filter { it.kind.equals("credits", ignoreCase = true) }
                .minByOrNull { it.startMs }?.startMs ?: return false
            seg.startMs >= creditsStart
        }
        else -> false
    }
}

/** Web wording for the manual skip pill (PlayerPage.tsx: "Skip Intro" /
 *  "Skip Recap" / "Skip Credits"). Unknown kinds fall back to a
 *  capitalised "Skip <Kind>". */
private fun skipSegmentLabel(kind: String): String = when (kind.lowercase()) {
    "intro" -> "Skip Intro"
    "recap" -> "Skip Recap"
    "credits" -> "Skip Credits"
    else -> "Skip ${kind.replaceFirstChar { it.uppercase() }}"
}

/** Bottom-right white pill matching chino-web's manual skip button
 *  (`bg-white text-black rounded-full shadow`). Lucide SkipForward glyph
 *  + label. Sits above the bottom chrome and clears the nav bar. */
@Composable
private fun SkipSegmentButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 96.dp, end = 16.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Lucide.SkipForward,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                color = Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Chrome(
    state: PlayState,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    isPlaying: Boolean,
    muted: Boolean,
    volume: Float,
    captionsOn: Boolean,
    fullscreen: Boolean,
    playbackSpeed: Float,
    openPopover: OpenPopover,
    audioTracks: List<AudioTrack>,
    subtitleTracks: List<SubtitleTrack>,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onScrubStart: (Long) -> Unit,
    onScrubUpdate: (Long) -> Unit,
    onScrubCommit: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeDragStart: () -> Unit,
    onVolumeDragEnd: () -> Unit,
    onToggleCaptions: () -> Unit,
    onTogglePopover: (OpenPopover) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectAudio: (AudioTrack) -> Unit,
    onSelectSubtitle: (SubtitleTrack?) -> Unit,
    currentQuality: String,
    onSelectQuality: (String) -> Unit,
    onToggleFullscreen: () -> Unit,
    onPrevEpisode: (() -> Unit)?,
    onNextEpisode: (() -> Unit)?,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    // System bar insets are applied to TopBar (statusBars) and the
    // bottom-strip wrapper (navigationBars) individually — NOT to the
    // outer Box, because the Playback info dialog backdrop must still
    // fill the full screen, including under the bars. When fullscreen
    // is on, ImmersiveFullscreenEffect hides the bars so the insets
    // resolve to 0 and chrome rides edge-to-edge.
    // Stray-tap absorber: when Chrome is visible, taps on empty chrome
    // backdrop (between buttons, gradient regions) used to fall through
    // to the video-surface tap handler and toggle chrome OFF, making
    // popover buttons appear inert. This intercepts taps on the Chrome
    // overlay so only intentional button taps (which have their own
    // clickable) propagate further.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { /* absorb */ })
            },
    ) {
        TopBar(
            title = state.title,
            onBack = onBack,
            onHome = onHome,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            // Gradient sits BEHIND the controls so taps on the empty
            // area still toggle chrome.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.6f to Color(0x99000000),
                            1.0f to Color(0xCC000000),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Push controls above the gesture pill / nav bar.
                    // Gradient backdrop stays edge-to-edge below.
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Inline popover row — sits ABOVE the scrubber+controls
                // inside the same bottom strip. End-aligned so it appears
                // near the speed button on the right. Rendering here
                // (rather than as an offset child of the speed button)
                // dodges Compose's input-bounds-clipping behaviour where
                // a popover rendered outside its parent's bounds is
                // visible but untappable, AND avoids Popup-window
                // composition pitfalls inside AnimatedVisibility.
                if (openPopover == OpenPopover.SPEED ||
                    openPopover == OpenPopover.AUDIO ||
                    openPopover == OpenPopover.CAPTIONS ||
                    openPopover == OpenPopover.QUALITY
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        // Pad-right so the menu lands roughly under its
                        // anchor button. Each right-cluster slot is ~40dp
                        // (36 button + 4 gap); we walk back from the
                        // Fullscreen edge by the anchor's index. Quality is
                        // the new 3rd-from-right (Settings gear), pushing
                        // Speed/Captions/Audio one slot further left.
                        val endPad = when (openPopover) {
                            OpenPopover.QUALITY -> 120.dp   // Quality gear = 3rd from right
                            OpenPopover.SPEED -> 160.dp      // Speed = 4th from right
                            OpenPopover.CAPTIONS -> 200.dp   // Captions = 5th from right
                            OpenPopover.AUDIO -> 240.dp      // JAP chip = 6th from right
                            else -> 0.dp
                        }
                        Box(modifier = Modifier.padding(end = endPad)) {
                            when (openPopover) {
                                OpenPopover.SPEED -> SpeedMenuCard(
                                    currentSpeed = playbackSpeed,
                                    onSelect = onSelectSpeed,
                                )
                                OpenPopover.AUDIO -> AudioMenuCard(
                                    tracks = audioTracks,
                                    onSelect = onSelectAudio,
                                )
                                OpenPopover.CAPTIONS -> CaptionsMenuCard(
                                    tracks = subtitleTracks,
                                    onSelect = onSelectSubtitle,
                                )
                                OpenPopover.QUALITY -> QualityMenuCard(
                                    qualities = state.qualities,
                                    currentQuality = currentQuality,
                                    onSelect = onSelectQuality,
                                )
                                else -> {}
                            }
                        }
                    }
                }
                Scrubber(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    segments = state.segments,
                    trickplayCues = state.trickplayCues,
                    trickplayBaseUrl = "${state.base}/v1/items/${state.itemId}/play/trickplay",
                    streamToken = state.streamToken,
                    onScrubStart = onScrubStart,
                    onScrubUpdate = onScrubUpdate,
                    onScrubCommit = onScrubCommit,
                )
                BottomControls(
                    isPlaying = isPlaying,
                    muted = muted,
                    volume = volume,
                    captionsOn = captionsOn,
                    fullscreen = fullscreen,
                    playbackSpeed = playbackSpeed,
                    openPopover = openPopover,
                    canPrev = onPrevEpisode != null,
                    canNext = onNextEpisode != null,
                    hasQualities = state.qualities.size > 1,
                    info = state.info,
                    audioTracks = audioTracks,
                    onPlayPause = onPlayPause,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onToggleMute = onToggleMute,
                    onVolumeChange = onVolumeChange,
                    onVolumeDragStart = onVolumeDragStart,
                    onVolumeDragEnd = onVolumeDragEnd,
                    onToggleCaptions = onToggleCaptions,
                    onTogglePopover = onTogglePopover,
                    onSelectSpeed = onSelectSpeed,
                    onToggleFullscreen = onToggleFullscreen,
                    onPrevEpisode = onPrevEpisode,
                    onNextEpisode = onNextEpisode,
                )
            }
        }
        // Playback info dialog — rendered LAST so it z-orders ABOVE both
        // TopBar and bottom strip. Without this, the bottom controls
        // bled through the dim backdrop because they painted after the
        // dialog in tree order. The dialog also has its own backdrop
        // tap-to-close handler, which takes precedence over the Chrome
        // root's tap-absorber for taps in the dim area.
        if (openPopover == OpenPopover.INFO) {
            PlaybackInfoDialog(
                info = state.info,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                isPlaying = isPlaying,
                onClose = { onTogglePopover(OpenPopover.INFO) },
            )
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, onHome: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0xCC000000),
                        1.0f to Color.Transparent,
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Push the row content below the status bar. The gradient
                // backdrop stays edge-to-edge above so the fade is
                // continuous up to the screen top.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChromeButton(
                icon = Lucide.ArrowLeft,
                onClick = onBack,
                variant = ChromeBtnVariant.Neutral,
            )
            // Home — resets to the app root (chino-web parity). Sits next to
            // Back, same neutral chrome styling.
            ChromeButton(
                icon = Lucide.House,
                onClick = onHome,
                variant = ChromeBtnVariant.Neutral,
            )
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // The playback mode is no longer surfaced on the always-visible
            // chrome — it lives in the Playback info dialog (Info button).
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Drag-capable scrubber. Layout matches web's PlayerPage scrubber Row:
 *   [time-left 64dp]  [track expands]  [time-right 64dp]
 *
 * Track is a 40dp tall hit area centered around a 4dp bar at the bottom
 * (web does the same — bar sits low so hover-tooltip / thumb room is above).
 *
 * Stacking order (back→front):
 *   1. Idle track (white/15)
 *   2. Buffered overlay (white/30, width = bufferedFrac)
 *   3. Played overlay (#58A6FF, width = playedFrac)
 *   4. Segment markers (per-segment colored band over the played track)
 *   5. Thumb (white circle with #58A6FF border, follows playhead)
 *
 * Positions computed in absolute px from `BoxWithConstraints.maxWidth` so
 * the layout doesn't drift when the parent resizes.
 *
 * Gestures: combined detectHorizontalDragGestures + detectTapGestures.
 * Drag commits seek on release so the user can preview; tap commits
 * immediately. Both call `change.consume()` implicitly via the detectors
 * so the parent's tap-to-toggle-chrome doesn't fire mid-drag.
 */
@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    segments: List<Segment>,
    trickplayCues: List<TrickplayCue>,
    /** `…/play/trickplay` — sprite filenames from the cues hang off this. */
    trickplayBaseUrl: String,
    streamToken: String,
    onScrubStart: (Long) -> Unit,
    onScrubUpdate: (Long) -> Unit,
    onScrubCommit: (Long) -> Unit,
) {
    val durationSafe = durationMs.coerceAtLeast(1L)
    val density = LocalDensity.current
    // Tracks whether the user is actively dragging/pressing the bar so the
    // trickplay preview only shows during a scrub (matches web's hover gate;
    // on touch there's no hover, so press/drag is the analogue). Reset on
    // release/commit.
    var previewMs by remember { mutableStateOf<Long?>(null) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = formatTime(positionMs / 1000),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(min = 48.dp),
        )
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(20.dp),
        ) {
            val trackWidthPx = with(density) { maxWidth.toPx() }

            val playedFrac = (positionMs.toFloat() / durationSafe).coerceIn(0f, 1f)
            val bufferedFrac = (bufferedMs.toFloat() / durationSafe).coerceIn(0f, 1f)

            val playedWidthDp = with(density) { (trackWidthPx * playedFrac).toDp() }
            val bufferedWidthDp = with(density) { (trackWidthPx * bufferedFrac).toDp() }
            // Thumb center sits at the playhead x. We render the thumb with
            // CenterStart alignment so it Y-centers automatically on the
            // 20dp container — the same as the bar — and offset by
            // `playedX - thumbRadius` so the thumb's *center* lands on the
            // playhead (not its left edge). 16dp thumb (white) + 2dp blue
            // ring → effective 18dp diameter on screen.
            val thumbSize = 16.dp
            val thumbCenterX = with(density) { (trackWidthPx * playedFrac).toDp() }
            val thumbOffsetX = thumbCenterX - thumbSize / 2

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val pct = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                                val ms = (durationSafe * pct).toLong()
                                previewMs = ms
                                onScrubStart(ms)
                            },
                            onDragEnd = { previewMs = null },
                            onDragCancel = { previewMs = null },
                        ) { change, _ ->
                            val pct = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                            val ms = (durationSafe * pct).toLong()
                            previewMs = ms
                            onScrubUpdate(ms)
                            change.consume()
                        }
                    }
                    .pointerInput(durationMs) {
                        detectTapGestures(
                            onTap = { offset ->
                                val pct = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                                onScrubCommit((durationSafe * pct).toLong())
                            },
                            onPress = {
                                val pct0 = (it.x / trackWidthPx).coerceIn(0f, 1f)
                                previewMs = (durationSafe * pct0).toLong()
                                tryAwaitRelease()
                                previewMs = null
                                val pct = (it.x / trackWidthPx).coerceIn(0f, 1f)
                                onScrubCommit((durationSafe * pct).toLong())
                            },
                        )
                    },
            ) {
                // All four layers use Alignment.CenterStart so they share
                // the same Y midline — that's what places the thumb
                // perfectly centered on the bar (previous bug: bar at
                // BottomStart + thumb at BottomStart with -5dp offset
                // produced a vertical mismatch that read as a floating
                // dot).
                // Idle track
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.15f)),
                )
                // Buffered
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(bufferedWidthDp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f)),
                )
                // Played
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(playedWidthDp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF58A6FF)),
                )
                // Segments — colored bands over the bar.
                segments.forEach { seg ->
                    if (seg.endMs <= seg.startMs) return@forEach
                    val startFrac = (seg.startMs.toFloat() / durationSafe).coerceIn(0f, 1f)
                    val endFrac = (seg.endMs.toFloat() / durationSafe).coerceIn(0f, 1f)
                    if (endFrac <= startFrac) return@forEach
                    val startDp = with(density) { (trackWidthPx * startFrac).toDp() }
                    val widthDp = with(density) { (trackWidthPx * (endFrac - startFrac)).toDp() }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = startDp)
                            .width(widthDp)
                            .height(4.dp)
                            .background(segmentColor(seg.kind).copy(alpha = 0.85f)),
                    )
                }
                // Thumb — white fill + blue ring, centered on the played
                // position. Same Y center as the bar.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = thumbOffsetX)
                        .size(thumbSize)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(BorderStroke(2.dp, Color(0xFF58A6FF)), CircleShape),
                )
                // Trickplay preview — a sprite-cropped thumbnail floating
                // above the bar at the scrub position. Only while the user
                // is actively scrubbing AND we have cues for this item;
                // otherwise the bar shows time/segment text only (graceful
                // degrade). The cue lookup is O(log n); the sprite is a
                // single Coil load reused across cue tiles in the same sheet.
                val pm = previewMs
                if (pm != null && trickplayCues.isNotEmpty()) {
                    val cue = findTrickplayCue(trickplayCues, pm)
                    if (cue != null) {
                        // Clamp the preview's center so it stays inside the
                        // track (web does the same: half-tile margins).
                        val tileWDp = with(density) { cue.w.toDp() }
                        val tileHDp = with(density) { cue.h.toDp() }
                        val halfTilePx = with(density) { (tileWDp / 2).toPx() }
                        val centerPx = (trackWidthPx * (pm.toFloat() / durationSafe))
                            .coerceIn(halfTilePx, trackWidthPx - halfTilePx)
                        val leftDp = with(density) { centerPx.toDp() } - tileWDp / 2
                        // Segment label under the time, like web's hover card.
                        val seg = segments.firstOrNull { pm >= it.startMs && pm < it.endMs }
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = leftDp, y = -(tileHDp + 36.dp)),
                        ) {
                            TrickplayPreview(
                                cue = cue,
                                spriteUrl = "$trickplayBaseUrl/${cue.sprite}?stream=$streamToken",
                                timeLabel = formatTime(pm / 1000),
                                segmentLabel = seg?.let { segmentDisplayLabel(it) },
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = formatTime(durationMs / 1000),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.widthIn(min = 48.dp),
        )
    }
}

/**
 * Sprite-cropped trickplay thumbnail + time/segment caption. Mirrors web's
 * hover preview (PlayerPage.tsx L2916): a fixed `w×h` window with the full
 * sprite sheet placed at `-x, -y`, so only the cue's tile shows. Compose
 * has no `background-position`, so we render the full sprite via Coil's
 * AsyncImage at its intrinsic pixel size (wrapContentSize unbounded,
 * ContentScale.None, TopStart) inside a `clipToBounds` window and offset
 * it by `(-x, -y)`. Sprite px → dp via the local density so the crop
 * window and the offset share one scale (keeps the tile aligned on any
 * dpi). Coil caches the sheet, so dragging across tiles in the same sheet
 * reuses the decoded bitmap.
 */
@Composable
private fun TrickplayPreview(
    cue: TrickplayCue,
    spriteUrl: String,
    timeLabel: String,
    segmentLabel: String?,
) {
    val density = LocalDensity.current
    val tileWDp = with(density) { cue.w.toDp() }
    val tileHDp = with(density) { cue.h.toDp() }
    val offX = with(density) { -cue.x.toDp() }
    val offY = with(density) { -cue.y.toDp() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(tileWDp, tileHDp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)), RoundedCornerShape(6.dp))
                .clipToBounds(),
        ) {
            AsyncImage(
                model = spriteUrl,
                contentDescription = null,
                contentScale = ContentScale.None,
                alignment = Alignment.TopStart,
                modifier = Modifier
                    // Lay out at intrinsic sprite size (unbounded) so the
                    // negative offset can shift the right tile into the
                    // clip window. Without `unbounded` the image is bounded
                    // to the tile and the offset clips to black.
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                    .offset(x = offX, y = offY),
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xCC000000))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = if (segmentLabel.isNullOrBlank()) timeLabel else "$segmentLabel · $timeLabel",
                color = Color.White,
                fontSize = 11.sp,
            )
        }
    }
}

/** Friendly scrub-preview label for a segment — mirrors web's
 *  segmentDisplayLabel: prefer a human chapter label, else the kind
 *  capitalised. Auto-detector labels (numeric tags, dash-joined ranges)
 *  fall back to the kind so the preview doesn't surface raw analyzer
 *  noise. */
private fun segmentDisplayLabel(seg: Segment): String {
    val friendlyKind = when (seg.kind.lowercase()) {
        "intro" -> "Intro"
        "credits" -> "Credits"
        "recap" -> "Recap"
        "chapter" -> "Chapter"
        else -> seg.kind.replaceFirstChar { it.uppercase() }
    }
    val raw = seg.label?.trim().orEmpty()
    if (seg.kind.equals("chapter", ignoreCase = true) && raw.isNotEmpty()) return raw
    if (raw.isEmpty()) return friendlyKind
    // Dash-joined ("00:00-01:20") or numeric-tag ("seg_12") detector noise.
    if (raw.contains('-')) return friendlyKind
    if (Regex("""^\d""").containsMatchIn(raw)) return friendlyKind
    return raw
}

@Composable
private fun BottomControls(
    isPlaying: Boolean,
    muted: Boolean,
    volume: Float,
    captionsOn: Boolean,
    fullscreen: Boolean,
    playbackSpeed: Float,
    openPopover: OpenPopover,
    canPrev: Boolean,
    canNext: Boolean,
    /** Whether the server offers >1 rung — gates the quality gear (a single
     *  rung has nothing to switch to, matching TV/web). */
    hasQualities: Boolean,
    info: PlayInfo?,
    audioTracks: List<AudioTrack>,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onToggleMute: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeDragStart: () -> Unit,
    onVolumeDragEnd: () -> Unit,
    onToggleCaptions: () -> Unit,
    onTogglePopover: (OpenPopover) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onToggleFullscreen: () -> Unit,
    onPrevEpisode: (() -> Unit)?,
    onNextEpisode: (() -> Unit)?,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // LEFT cluster — web parity: just Play + Mute + Volume slider.
            // Skip ±10s buttons removed: web doesn't have them (it uses
            // scrubber drag/click for seek). On a touch tablet the
            // scrubber is already large enough to seek precisely.
            ChromeButton(
                icon = if (isPlaying) Lucide.Pause else Lucide.Play,
                onClick = onPlayPause,
                variant = ChromeBtnVariant.Neutral,
            )
            // Compact volume: just the icon by default; tapping it reveals
            // the slider inline (and keeps the chrome from auto-hiding because
            // a popover is "open"). Drag the slider to 0 to mute. This frees
            // the horizontal space the always-on slider used to take.
            ChromeButton(
                icon = if (muted || volume == 0f) Lucide.VolumeX else Lucide.Volume2,
                onClick = { onTogglePopover(OpenPopover.VOLUME) },
                variant = if (openPopover == OpenPopover.VOLUME) ChromeBtnVariant.Accent else ChromeBtnVariant.Neutral,
            )
            AnimatedVisibility(
                visible = openPopover == OpenPopover.VOLUME,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
            ) {
                VolumeSlider(
                    volume = if (muted) 0f else volume,
                    onVolumeChange = onVolumeChange,
                    onDragStart = onVolumeDragStart,
                    onDragEnd = onVolumeDragEnd,
                )
            }
            Spacer(Modifier.weight(1f))
            // RIGHT cluster: Audio-language chip, Captions, Speed, Prev/Next
            // (only when this is an episode with siblings), Info, Fullscreen.
            // Per web's PlayerPage.tsx L2944: chip text is the currently
            // SELECTED audio track's language code (3-letter uppercase).
            // Tap → opens AudioMenuCard (only renders when >1 track,
            // matching web's `info.audio_tracks.length > 1` gate at L2937).
            val activeAudio = audioTracks.firstOrNull { it.selected }
                ?: audioTracks.firstOrNull()
            val audioChipText = activeAudio?.language
                ?: info?.audioTracks?.firstOrNull { it.default }?.language
                ?: info?.audioTracks?.firstOrNull()?.language
            if (!audioChipText.isNullOrBlank()) {
                // Chip is always tappable when any audio track exists —
                // single-track files still get a menu (just one entry).
                // This is more discoverable than web's >1-track gate and
                // lets the user confirm "the only audio is JAP".
                AudioLangChip(
                    language = audioChipText,
                    enabled = audioTracks.isNotEmpty(),
                    onClick = { onTogglePopover(OpenPopover.AUDIO) },
                    accent = openPopover == OpenPopover.AUDIO,
                )
            }
            ChromeButton(
                icon = Lucide.Captions,
                onClick = onToggleCaptions,
                variant = if (captionsOn || openPopover == OpenPopover.CAPTIONS) {
                    ChromeBtnVariant.Accent
                } else {
                    ChromeBtnVariant.Neutral
                },
            )
            ChromeButton(
                icon = Lucide.Gauge,
                onClick = { onTogglePopover(OpenPopover.SPEED) },
                variant = if (openPopover == OpenPopover.SPEED) ChromeBtnVariant.Accent else ChromeBtnVariant.Neutral,
            )
            // Quality gear — only when the ladder has >1 rung (a single rung
            // has nothing to switch to). Opens QualityMenuCard via the shared
            // popover state. Mirrors TV's onQuality gate + Settings2 icon.
            if (hasQualities) {
                ChromeButton(
                    icon = Lucide.Settings2,
                    onClick = { onTogglePopover(OpenPopover.QUALITY) },
                    variant = if (openPopover == OpenPopover.QUALITY) ChromeBtnVariant.Accent else ChromeBtnVariant.Neutral,
                )
            }
            // Prev/Next chevrons HIDE entirely when no sibling exists
            // (movies, first/last episode). Web does the same — the
            // chevrons only render when `siblingEpisodes` resolves a
            // prev/next neighbour.
            if (canPrev && onPrevEpisode != null) {
                ChromeButton(
                    icon = Lucide.ChevronLeft,
                    onClick = onPrevEpisode,
                    variant = ChromeBtnVariant.Neutral,
                )
            }
            if (canNext && onNextEpisode != null) {
                ChromeButton(
                    icon = Lucide.ChevronRight,
                    onClick = onNextEpisode,
                    variant = ChromeBtnVariant.Neutral,
                )
            }
            // Info button — the dialog itself is rendered at the Chrome
            // root (above the bottom strip) so it can fill the screen
            // and stack above everything else. No popover anchored here.
            ChromeButton(
                icon = Lucide.Info,
                onClick = { onTogglePopover(OpenPopover.INFO) },
                variant = if (openPopover == OpenPopover.INFO) ChromeBtnVariant.Accent else ChromeBtnVariant.Neutral,
            )
            ChromeButton(
                icon = if (fullscreen) Lucide.Minimize else Lucide.Maximize,
                onClick = onToggleFullscreen,
                variant = ChromeBtnVariant.Neutral,
            )
        }
    }
}

/**
 * Horizontal volume slider — 96dp wide, 4dp bar. Drag with
 * detectHorizontalDragGestures (same pattern as scrubber so consumption
 * semantics match) + tap-to-set. Hoists isDragging up so auto-hide
 * suspends while the user drags.
 */
@Composable
private fun VolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier
            .width(88.dp)
            .height(20.dp),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val filledDp = with(density) { (widthPx * volume.coerceIn(0f, 1f)).toDp() }
        // Smaller thumb than the scrubber's — 12dp white + 2dp blue ring.
        val thumbSize = 12.dp
        val thumbCenterX = with(density) { (widthPx * volume.coerceIn(0f, 1f)).toDp() }
        val thumbOffsetX = thumbCenterX - thumbSize / 2
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            onDragStart()
                            onVolumeChange((offset.x / widthPx).coerceIn(0f, 1f))
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    ) { change, _ ->
                        onVolumeChange((change.position.x / widthPx).coerceIn(0f, 1f))
                        change.consume()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { offset ->
                        onVolumeChange((offset.x / widthPx).coerceIn(0f, 1f))
                    })
                },
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.2f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(filledDp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF58A6FF)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbOffsetX)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(BorderStroke(2.dp, Color(0xFF58A6FF)), CircleShape),
            )
        }
    }
}

private enum class ChromeBtnVariant { Neutral, Primary, Accent }

@Composable
private fun ChromeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    variant: ChromeBtnVariant = ChromeBtnVariant.Neutral,
    enabled: Boolean = true,
) {
    val bg = when (variant) {
        ChromeBtnVariant.Primary -> Color(0xFF58A6FF)
        ChromeBtnVariant.Accent -> Color(0xFF58A6FF).copy(alpha = 0.3f)
        ChromeBtnVariant.Neutral -> Color.White.copy(alpha = 0.1f)
    }
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (enabled) bg else Color.White.copy(alpha = 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Compact pill chip showing the current audio track's 3-letter language
 *  code (e.g. "JAP", "ENG"). Mirrors chino-web's audio chip at
 *  PlayerPage.tsx L2939-2945. When `enabled` (multi-track file), tapping
 *  the chip opens the AudioMenuCard via the shared OpenPopover state.
 *  Single-track files render a non-tappable chip (no menu, same look). */
@Composable
private fun AudioLangChip(
    language: String,
    enabled: Boolean,
    accent: Boolean,
    onClick: () -> Unit,
) {
    val label = langLabel(language).take(3).uppercase()
    val bg = if (accent) Color(0xFF58A6FF).copy(alpha = 0.3f)
    else Color.White.copy(alpha = 0.1f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .let {
                if (enabled) it.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ) else it
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Inline audio-track selector — mirrors chino-web's audio menu at
 *  PlayerPage.tsx L2946-2962. Header is uppercase "AUDIO"; each row
 *  renders the primary track title and a dimmed format suffix
 *  (`AAC · 1ch`). Labels wrap freely so long titles like "Original
 *  Japanese theatrical mono (unfiltered)" stay legible. */
@Composable
private fun AudioMenuCard(tracks: List<AudioTrack>, onSelect: (AudioTrack) -> Unit) {
    Box(
        modifier = Modifier
            .width(320.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161B22))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MenuHeader(title = "AUDIO", trailing = null)
            if (tracks.isEmpty()) {
                Text(
                    text = "No audio tracks",
                    color = Color(0xFF8B949E),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                tracks.forEach { t ->
                    val (primary, suffix) = splitAudioLabel(t.label)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(t) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = primary,
                                color = if (t.selected) Color(0xFF58A6FF) else Color.White,
                                fontSize = 14.sp,
                                fontWeight = if (t.selected) FontWeight.SemiBold else FontWeight.Normal,
                                lineHeight = 18.sp,
                            )
                            if (suffix != null) {
                                Text(
                                    text = suffix,
                                    color = Color(0xFF8B949E),
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Splits "Original Japanese mono • Stereo • aac" into ("Original Japanese
 *  mono", "AAC · Stereo"). The TV's collectAudioTracks emits the primary
 *  label first then channels and codec joined by " • " — we re-render
 *  them so the format suffix matches web's `AAC · 1ch` styling. */
private fun splitAudioLabel(label: String): Pair<String, String?> {
    val parts = label.split(" • ")
    if (parts.size <= 1) return label to null
    val primary = parts[0]
    // TV order is [primary, channels, codec]. Web shows [codec · channels].
    val rest = parts.drop(1)
    val codec = rest.lastOrNull { !it.isChannelLabel() }?.uppercase()
    val channels = rest.firstOrNull { it.isChannelLabel() }
    val suffix = listOfNotNull(codec, channels).joinToString(" · ")
    return primary to suffix.ifBlank { null }
}

private fun String.isChannelLabel() =
    this in setOf("Mono", "Stereo", "5.1", "7.1") || endsWith("ch")

/** Inline subtitle-track selector — mirrors chino-web's captions menu
 *  at PlayerPage.tsx L2975-3050. Layout:
 *    - Header: "SUBTITLES" + "0/1" count on the right
 *    - First row: "None / Off" — blue when no track is active
 *    - Each track row: checkbox indicator + track label
 *  Web supports up to 2 simultaneous subs; Media3 natively renders only
 *  one, so the count is hard-capped at /1 and selecting a track always
 *  replaces the previous one. Web's TIMING OFFSET section is deferred
 *  (needs per-cue retiming). */
@Composable
private fun CaptionsMenuCard(tracks: List<SubtitleTrack>, onSelect: (SubtitleTrack?) -> Unit) {
    val anySelected = tracks.any { it.selected }
    val activeCount = if (anySelected) 1 else 0
    Box(
        modifier = Modifier
            .width(320.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161B22))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MenuHeader(title = "SUBTITLES", trailing = "$activeCount/1")
            // None / Off entry — always present; clears the override and
            // disables the TEXT track type.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(null) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "None / Off",
                    color = if (!anySelected) Color(0xFF58A6FF) else Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (!anySelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            if (tracks.isEmpty()) {
                Text(
                    text = "No subtitles available",
                    color = Color(0xFF8B949E),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                tracks.forEach { t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(t) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CheckboxIndicator(selected = t.selected)
                        Text(
                            text = t.label,
                            color = if (t.selected) Color(0xFF58A6FF) else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (t.selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Small square checkbox indicator — checked = blue filled, unchecked =
 *  hollow with thin white-30% border. Matches web's
 *  `inline-flex items-center justify-center w-4 h-4 rounded-sm border`. */
@Composable
private fun CheckboxIndicator(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (selected) Color(0xFF58A6FF) else Color.Transparent)
            .border(
                BorderStroke(1.dp, if (selected) Color(0xFF58A6FF) else Color.White.copy(alpha = 0.3f)),
                RoundedCornerShape(2.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text(text = "1", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Shared menu header — uppercase, gray, optional right-aligned count. */
@Composable
private fun MenuHeader(title: String, trailing: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = Color(0xFF8B949E),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                color = Color(0xFF8B949E).copy(alpha = 0.7f),
                fontSize = 10.sp,
            )
        }
    }
}

/** Best-effort ISO 639 → English name lookup matching chino-web's
 *  LANG_NAMES table at PlayerPage.tsx L76-84. We only need it for the
 *  chip's 3-letter cap — passing through the raw code if unknown still
 *  reads correctly (e.g. an unmapped "fre" → "FRE"). */
private fun langLabel(code: String): String {
    val map = mapOf(
        "en" to "English", "eng" to "English",
        "ja" to "Japanese", "jpn" to "Japanese", "jap" to "Japanese",
        "de" to "German", "ger" to "German", "deu" to "German",
        "fr" to "French", "fre" to "French", "fra" to "French",
        "es" to "Spanish", "spa" to "Spanish",
        "it" to "Italian", "ita" to "Italian",
        "pt" to "Portuguese", "por" to "Portuguese",
        "ru" to "Russian", "rus" to "Russian",
        "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese",
        "ko" to "Korean", "kor" to "Korean",
        "ar" to "Arabic", "ara" to "Arabic",
        "hi" to "Hindi", "hin" to "Hindi",
        "tr" to "Turkish", "tur" to "Turkish",
        "pl" to "Polish", "pol" to "Polish",
        "nl" to "Dutch", "dut" to "Dutch", "nld" to "Dutch",
        "sv" to "Swedish", "swe" to "Swedish",
        "und" to "Unknown",
    )
    return map[code.lowercase()] ?: code
}

/** Inline speed-rate selector. Rendered as a child of the bottom strip
 *  Column above the controls row, end-aligned to land under the Gauge
 *  button. Tappable rate entries fire `onSelect` then close via the
 *  shared `openPopover` state machine.
 *
 *  Width is FIXED at 180dp — without this, the inner clickable Row's
 *  `fillMaxWidth` would grow the Column to whatever the parent Row
 *  offers, defeating Arrangement.End and stretching the menu across
 *  the whole screen. */
@Composable
private fun SpeedMenuCard(currentSpeed: Float, onSelect: (Float) -> Unit) {
    val rates = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    Box(
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161B22))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MenuHeader(title = "SPEED", trailing = null)
            rates.forEach { r ->
                val active = (r == currentSpeed)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(r) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (r == 1.0f) "Normal" else "${r}x",
                        color = if (active) Color(0xFF58A6FF) else Color.White,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Inline quality-rung selector — mirrors chino-web's quality menu
 *  (high/medium/low) + TV's PlayerControls quality submenu. Each row is
 *  the rung label (e.g. "1080p"); the active rung is blue. Selecting a
 *  rung fires [onSelect] which rebuilds the player at the new ?q= while
 *  preserving the live position. Fixed width like SpeedMenuCard so
 *  Arrangement.End places it under the gear instead of stretching. */
@Composable
private fun QualityMenuCard(
    qualities: List<QualityRung>,
    currentQuality: String,
    onSelect: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161B22))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MenuHeader(title = "QUALITY", trailing = null)
            qualities.forEach { rung ->
                val active = rung.name.equals(currentQuality, ignoreCase = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(rung.name) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // Prefer the server's label; fall back to the
                        // name→resolution map so a bare "high" still reads
                        // "1080p" like web/TV.
                        text = rung.label.takeIf { it.isNotBlank() } ?: labelForQuality(rung.name),
                        color = if (active) Color(0xFF58A6FF) else Color.White,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Auto-play-next countdown overlay. Bottom-center card with the
 *  remaining seconds, a "Play now" primary action, and a "Cancel" that
 *  suppresses the auto-fire for the rest of the episode. Mirrors web's
 *  Next-Episode countdown card; stays visible even when the chrome
 *  auto-hides (rendered outside the chrome's AnimatedVisibility). */
@Composable
private fun AutoNextOverlay(
    secondsLeft: Int,
    onDismiss: () -> Unit,
    onPlayNow: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 96.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF2161B22))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Up next",
                    color = Color(0xFF8B949E),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Playing next episode in ${secondsLeft}s",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFF58A6FF))
                            .clickable(onClick = onPlayNow)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(text = "Play now", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(text = "Cancel", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** "Next episode ▶" card shown over a still-playing post-credits preview (a
 *  next-episode teaser the analyzer mislabelled as a recap, or an explicit
 *  `preview`). Unlike [AutoNextOverlay] the teaser is NOT skipped — it plays
 *  underneath. When [secondsLeft] is non-null (autoPlayNext on) the card
 *  counts down and auto-advances at 0; Dismiss keeps the teaser playing.
 *  When null (autoPlayNext off) it's a plain manual button: tap advances,
 *  ignore lets the teaser play. Styled as the brand-blue counterpart of
 *  [SkipSegmentButton] with SQUARE corners per the nalet design system, so it
 *  reads as the same "there's an action here" affordance family as the TV. */
@Composable
private fun NextEpisodeCard(
    secondsLeft: Int?,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 96.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 420.dp)
                // Square corners (surface-2 fill, 1px border) per the DS —
                // never rounded.
                .background(Color(0xFF161B26))
                .border(BorderStroke(1.dp, Color(0xFF1F2633)), RectangleShape)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Up next",
                    color = Color(0xFF8B949E),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    // With a countdown, echo the credits card's wording; without
                    // it, invite the tap while the teaser keeps playing.
                    text = if (secondsLeft != null) {
                        "Playing next episode in ${secondsLeft}s"
                    } else {
                        "Preview playing — jump to the next episode"
                    },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            // Brand-blue primary action, square corners.
                            .background(Color(0xFF58A6FF))
                            .clickable(onClick = onPlayNext)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            // SkipForward while counting down (auto-advance
                            // intent), Play for the plain manual button.
                            imageVector = if (secondsLeft != null) Lucide.SkipForward else Lucide.Play,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Next episode",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // Dismiss only when a countdown is armed — nothing to cancel
                    // otherwise (the manual button already leaves the teaser
                    // playing when ignored).
                    if (secondsLeft != null) {
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.1f))
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(text = "Dismiss", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-screen Playback info dialog — mirrors chino-web's InfoOverlay at
 * PlayerPage.tsx L3230-3354. Sections, in order:
 *
 *   1. Mode badge ("Remux (stream-copy, repackaged into MP4)") + reason
 *      paragraph from PlayInfo.reason.
 *   2. Source file — Container / Video (codec + WxH) / Audio / Duration.
 *   3. Live pipeline — Effective mode badge / Quality / Encoder /
 *      Position / Buffered ahead / Element state.
 *   4. This device can decode — list of common codecs with green/red
 *      status dots, probed via [probeDeviceCodecs].
 *
 * Pipeline-switches timeline is omitted: we don't yet track the switch
 * log on mobile (no quality auto-downgrade is wired). When that lands,
 * port the TV's switchHistory state and add the section here.
 */
@Composable
private fun PlaybackInfoDialog(
    info: PlayInfo?,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    isPlaying: Boolean,
    onClose: () -> Unit,
) {
    val codecs = remember { probeDeviceCodecs() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClose() })
            },
        contentAlignment = Alignment.Center,
    ) {
        // Cap dialog at 85% viewport height (web: `max-h-[85vh] overflow-
        // y-auto`). The inner body Column owns the scroll, so overflowing
        // sections (long decode list, mode reason) scroll inside the card
        // instead of running past the bottom controls.
        val maxDialogHeight = maxHeight * 0.85f
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 640.dp)
                .heightIn(max = maxDialogHeight)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF161B22))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { /* swallow taps inside dialog */ })
                },
        ) {
            Column {
                // Header: title + close X
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Playback info",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.X,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.1f)),
                )
                // Body — scrollable so even the densest probe list fits on
                // shorter screens.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (info != null) {
                        ModeBadgeBlock(mode = info.mode, reason = info.reason)
                        SourceFileSection(info = info)
                        LivePipelineSection(
                            info = info,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            bufferedMs = bufferedMs,
                            isPlaying = isPlaying,
                        )
                    } else {
                        Text(
                            text = "Probing source file…",
                            color = Color(0xFF8B949E),
                            fontSize = 14.sp,
                        )
                    }
                    DecodeCapabilitiesSection(codecs = codecs)
                }
            }
        }
    }
}

/** Tinted pill matching chino-web's `modeColor`/`modeLabel` mapping. */
@Composable
private fun ModeBadgeBlock(mode: String?, reason: String?) {
    val (bg, border, fg) = modeColors(mode)
    val label = modeLabel(mode)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(bg)
                .border(BorderStroke(1.dp, border), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(text = label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        if (!reason.isNullOrBlank()) {
            Text(
                text = reason,
                color = Color(0xFFC9D1D9),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun SourceFileSection(info: PlayInfo) {
    InfoSection(title = "Source file") {
        InfoDLRow("Container", info.container ?: "—")
        val videoLine = buildString {
            append(info.videoCodec ?: "—")
            if ((info.width ?: 0) > 0 && (info.height ?: 0) > 0) {
                append(" (${info.width}×${info.height})")
            }
        }
        InfoDLRow("Video", videoLine)
        InfoDLRow("Audio", info.audioCodec ?: "—")
        InfoDLRow("Duration", info.durationMs?.let { formatTime(it / 1000) } ?: "—")
    }
}

@Composable
private fun LivePipelineSection(
    info: PlayInfo,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    isPlaying: Boolean,
) {
    val (bg, border, fg) = modeColors(info.mode)
    val bufferedAheadSec = ((bufferedMs - positionMs).coerceAtLeast(0L)) / 1000.0
    val elementState = if (isPlaying) "PLAYING" else "PAUSED"
    InfoSection(title = "Live pipeline") {
        // "Effective mode" row uses a tinted pill instead of plain text.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Effective mode",
                color = Color(0xFF8B949E),
                fontSize = 13.sp,
                modifier = Modifier.width(140.dp),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(bg)
                    .border(BorderStroke(1.dp, border), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(text = modeLabel(info.mode), color = fg, fontSize = 11.sp)
            }
        }
        when (info.mode?.lowercase()) {
            "transcode" -> {
                InfoDLRow("Encoder", info.encoder ?: "libx264")
                InfoDLRow("Video target", "H.264")
                InfoDLRow("Audio target", "AAC stereo")
            }
            else -> {
                InfoDLRow("Quality", "Direct (${info.videoCodec?.uppercase() ?: "?"})")
                InfoDLRow("Encoder", "None — source bytes pass through unmodified")
            }
        }
        val position = "${formatTime(positionMs / 1000)} / " +
            (info.durationMs?.let { formatTime(it / 1000) }
                ?: formatTime(durationMs / 1000))
        InfoDLRow("Position", position)
        InfoDLRow(
            "Buffered ahead",
            if (bufferedAheadSec > 0) "${"%.1f".format(bufferedAheadSec)}s" else "—",
        )
        InfoDLRow("Element state", elementState)
    }
}

@Composable
private fun DecodeCapabilitiesSection(codecs: List<DeviceCodecProbe>) {
    InfoSection(title = "This device can decode") {
        // Two-column grid — pair up codecs by index.
        val pairs = codecs.chunked(2)
        pairs.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                row.forEach { c ->
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (c.supported) Color(0xFF34D399) else Color(0xFFFB7185)),
                        )
                        Text(
                            text = c.label,
                            color = if (c.supported) Color(0xFFC9D1D9) else Color(0xFF8B949E),
                            fontSize = 13.sp,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "When the source file uses a codec the device can't decode, " +
                "katalog-stream re-encodes it on the fly to H.264 + AAC inside a " +
                "fragmented MP4.",
            color = Color(0xFF8B949E),
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

@Composable
private fun InfoDLRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(text = label, color = Color(0xFF8B949E), fontSize = 13.sp, modifier = Modifier.width(140.dp))
        Text(text = value, color = Color(0xFFC9D1D9), fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/** Web's modeLabel table — used both by the badge above the dialog body
 *  and the Live pipeline "Effective mode" pill. */
private fun modeLabel(mode: String?): String = when (mode?.lowercase()) {
    "passthrough" -> "Passthrough (no transcode)"
    "remux" -> "Remux (stream-copy, repackaged into MP4)"
    "transcode" -> "Transcode (re-encoding required)"
    "packaged" -> "Packaged (pre-segmented CMAF on disk, no ffmpeg)"
    else -> mode?.replaceFirstChar { it.uppercase() } ?: "Unknown"
}

/** Web's modeColor table → Compose (bg, border, fg). */
private fun modeColors(mode: String?): Triple<Color, Color, Color> = when (mode?.lowercase()) {
    "passthrough", "packaged" -> Triple(
        Color(0x3334D399), Color(0x6634D399), Color(0xFF6EE7B7),
    )
    "remux" -> Triple(
        Color(0x33F59E0B), Color(0x66F59E0B), Color(0xFFFCD34D),
    )
    "transcode" -> Triple(
        Color(0x33F43F5E), Color(0x66F43F5E), Color(0xFFFDA4AF),
    )
    else -> Triple(
        Color(0x33FFFFFF), Color(0x66FFFFFF), Color(0xFFC9D1D9),
    )
}

/** Mirrors chino-web's CODEC_PROBES → probeClientCodecs. On Android we
 *  consult MediaCodecList (REGULAR_CODECS includes platform default
 *  decoders) instead of MSE.isTypeSupported. */
private data class DeviceCodecProbe(val label: String, val supported: Boolean)

private fun probeDeviceCodecs(): List<DeviceCodecProbe> {
    val probes = listOf(
        "H.264 (AVC)" to "video/avc",
        "H.265 (HEVC)" to "video/hevc",
        "VP9" to "video/x-vnd.on2.vp9",
        "AV1" to "video/av01",
        "AAC" to "audio/mp4a-latm",
        "MP3" to "audio/mpeg",
        "Opus" to "audio/opus",
        "AC-3" to "audio/ac3",
        "E-AC-3" to "audio/eac3",
        "DTS" to "audio/vnd.dts",
    )
    return runCatching {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        val supportedMimes = list.codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.toList() }
            .map { it.lowercase() }
            .toSet()
        probes.map { (label, mime) -> DeviceCodecProbe(label, mime.lowercase() in supportedMimes) }
    }.getOrElse { probes.map { (label, _) -> DeviceCodecProbe(label, false) } }
}

/** Mirrors chino-androidtv segment palette (kept similar so the TV and
 *  mobile read as the same app). */
private fun segmentColor(kind: String): Color = when (kind.lowercase()) {
    "intro" -> Color(0xFFE3B341)
    "credits" -> Color(0xFFF59E0B)
    "recap" -> Color(0xFF8B5CF6)
    "chapter" -> Color(0xFFFFFFFF)
    else -> Color(0xFFFFFFFF)
}

/**
 * Player heading. For an EPISODE (Item.kind == "episode", or any item that
 * carries a season/episode number) the title reads
 * `{seriesTitle} — S01E02 · {episodeTitle}` (2-digit zero-padded). When the
 * series title isn't resolved yet it degrades to `S01E02 · {episodeTitle}`.
 * Movies / series-level items keep their plain title. Mirrors chino-web's
 * player heading composition.
 */
private fun composePlayerTitle(item: Item?, seriesTitle: String?): String {
    val episodeTitle = item?.title ?: "Playing"
    val isEpisode = item?.kind.equals("episode", ignoreCase = true) ||
        item?.seasonNumber != null || item?.episodeNumber != null
    if (item == null || !isEpisode) return episodeTitle
    val se = buildString {
        item.seasonNumber?.let { append("S").append(it.toString().padStart(2, '0')) }
        item.episodeNumber?.let { append("E").append(it.toString().padStart(2, '0')) }
    }
    val tail = if (se.isNotEmpty()) "$se · $episodeTitle" else episodeTitle
    return if (!seriesTitle.isNullOrBlank()) "$seriesTitle — $tail" else tail
}

private fun labelForQuality(q: String): String = when (q.lowercase()) {
    "high" -> "1080p"
    "medium" -> "720p"
    "low" -> "480p"
    "source" -> "Source"
    else -> q
}

private fun formatTime(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
