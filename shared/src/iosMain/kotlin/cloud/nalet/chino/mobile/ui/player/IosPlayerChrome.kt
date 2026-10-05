package cloud.nalet.chino.mobile.ui.player

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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.interop.LocalUIViewController
import cloud.nalet.chino.mobile.data.api.PlayInfo
import cloud.nalet.chino.mobile.data.api.Segment
import cloud.nalet.chino.mobile.ui.theme.ChinoHeading
import coil3.compose.AsyncImage
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
import com.composables.icons.lucide.PictureInPicture2
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.composables.icons.lucide.X
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVKit.AVRoutePickerView
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor

// The chrome of the iOS player: chino-web's PlayerPage and the Android
// player's look, wording and sizes — top bar, scrubber with trickplay, the
// control row, the inline menus, the up-next card, the skip pill, the
// playback info dialog — plus what iOS adds (picture in picture, AirPlay) and
// the web's "won't start" panel.

internal val Accent = Color(0xFF58A6FF)
private val MenuBg = Color(0xFF161B22)
private val MutedText = Color(0xFF8B949E)

internal enum class OpenPopover { NONE, SPEED, AUDIO, CAPTIONS, INFO, VOLUME, QUALITY }

/** An audio rendition as the menu shows it. */
internal data class AudioChoice(
    val index: Int,
    /** Named by language ("German"), or the rendition's own name. */
    val label: String,
    /** "AAC · Stereo", from /play/info, when known. */
    val detail: String?,
    val language: String?,
    val selected: Boolean,
)

@Composable
internal fun PlayerLoading() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent)
    }
}

/** chino-web's PlaybackFailure: what happened, then Try again / Back /
 *  Report a bug. */
@Composable
internal fun PlaybackFailurePanel(
    title: String,
    label: String,
    onRetry: (() -> Unit)?,
    onBack: () -> Unit,
    onReport: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 480.dp),
        ) {
            Icon(Lucide.TriangleAlert, contentDescription = null, tint = Color(0xFFF85149), modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, style = ChinoHeading)
            Spacer(Modifier.height(4.dp))
            Text(label, color = MutedText, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRetry != null) PanelButton("Try again", primary = true, onClick = onRetry)
                PanelButton("Back", primary = false, onClick = onBack)
                PanelButton("Report a bug", primary = false, onClick = onReport)
            }
        }
    }
}

@Composable
private fun PanelButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) Accent else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal)
    }
}

/** Everything the chrome shows and does, so one parameter list feeds both
 *  layouts. */
internal class ChromeModel(
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
    val isPlaying: Boolean,
    val muted: Boolean,
    val volume: Float,
    val speed: Float,
    val fullscreen: Boolean,
    val openPopover: OpenPopover,
    val segments: List<Segment>,
    val trickplayCues: List<TrickplayCue>,
    val trickplayBaseUrl: String,
    val streamToken: String,
    val audio: List<AudioChoice>,
    val subtitles: List<SubtitleChoice>,
    val activeSubtitleId: String?,
    val quality: QualityLadder,
    val currentQuality: String,
    val canPrev: Boolean,
    val canNext: Boolean,
    val pipSupported: Boolean,
    val pipActive: Boolean,
    val unavailableNote: String,
    val info: PlayInfo?,
    /** The chrome's fade, for the native AirPlay button over it. */
    val chromeAlpha: Float = 1f,
)

internal class ChromeActions(
    val onBack: () -> Unit,
    val onHome: () -> Unit,
    val onPlayPause: () -> Unit,
    val onScrubStart: (Long) -> Unit,
    val onScrubUpdate: (Long) -> Unit,
    val onScrubCommit: (Long) -> Unit,
    val onScrubCancel: () -> Unit,
    val onToggleMute: () -> Unit,
    val onVolumeChange: (Float) -> Unit,
    val onVolumeDragStart: () -> Unit,
    val onVolumeDragEnd: () -> Unit,
    val onTogglePopover: (OpenPopover) -> Unit,
    val onSelectSpeed: (Float) -> Unit,
    val onSelectAudio: (AudioChoice) -> Unit,
    val onSelectSubtitle: (SubtitleChoice?) -> Unit,
    val onSelectQuality: (String) -> Unit,
    val onToggleFullscreen: () -> Unit,
    val onTogglePip: () -> Unit,
    val onPrevEpisode: () -> Unit,
    val onNextEpisode: () -> Unit,
    /** Where the controls start (the scrubber's top, px in the window), for
     *  the bottom overlays to sit just above them. */
    val onControlsTop: (Float) -> Unit = {},
)

@Composable
internal fun PlayerChrome(m: ChromeModel, a: ChromeActions) {
    // The chrome draws over the bottom overlays (BottomOverlays), so an open
    // menu covers them; only its bars take taps, the skip pill and the up-next
    // card between them stay tappable.
    Box(modifier = Modifier.fillMaxSize()) {
        TopBar(title = m.title, onBack = a.onBack, onHome = a.onHome)
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().absorbStrayTaps()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.5f to Color(0x99000000),
                            1.0f to Color(0xCC000000),
                        ),
                    ),
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val narrow = maxWidth < 600.dp
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (m.openPopover in setOf(OpenPopover.SPEED, OpenPopover.AUDIO, OpenPopover.CAPTIONS, OpenPopover.QUALITY)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            when (m.openPopover) {
                                OpenPopover.SPEED -> SpeedMenuCard(m.speed, a.onSelectSpeed)
                                OpenPopover.AUDIO -> AudioMenuCard(m.audio, a.onSelectAudio)
                                OpenPopover.CAPTIONS -> CaptionsMenuCard(m.subtitles, m.activeSubtitleId, m.unavailableNote, a.onSelectSubtitle)
                                OpenPopover.QUALITY -> QualityMenuCard(m.quality.options, m.currentQuality, a.onSelectQuality)
                                else -> {}
                            }
                        }
                    }
                    // An open menu grows the column upward; the scrubber
                    // stays put, so the overlays above it do too.
                    Box(modifier = Modifier.fillMaxWidth().onGloballyPositioned { a.onControlsTop(it.boundsInWindow().top) }) {
                        Scrubber(
                            positionMs = m.positionMs,
                            durationMs = m.durationMs,
                            bufferedMs = m.bufferedMs,
                            segments = m.segments,
                            trickplayCues = m.trickplayCues,
                            trickplayBaseUrl = m.trickplayBaseUrl,
                            streamToken = m.streamToken,
                            onScrubStart = a.onScrubStart,
                            onScrubUpdate = a.onScrubUpdate,
                            onScrubCommit = a.onScrubCommit,
                            onScrubCancel = a.onScrubCancel,
                        )
                    }
                    if (narrow) {
                        // A phone held upright has room for half the row:
                        // transport and episode controls first, the rest
                        // below.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TransportCluster(m, a)
                            Spacer(Modifier.weight(1f))
                            EpisodeCluster(m, a)
                            FullscreenButton(m, a)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Spacer(Modifier.weight(1f))
                            MediaCluster(m, a)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TransportCluster(m, a)
                            Spacer(Modifier.weight(1f))
                            MediaCluster(m, a)
                            EpisodeCluster(m, a)
                            FullscreenButton(m, a)
                        }
                    }
                }
            }
        }
        if (m.openPopover == OpenPopover.INFO) {
            PlaybackInfoDialog(
                info = m.info,
                positionMs = m.positionMs,
                durationMs = m.durationMs,
                bufferedMs = m.bufferedMs,
                isPlaying = m.isPlaying,
                onClose = { a.onTogglePopover(OpenPopover.INFO) },
            )
        }
    }
}

@Composable
private fun TransportCluster(m: ChromeModel, a: ChromeActions) {
    ChromeButton(icon = if (m.isPlaying) Lucide.Pause else Lucide.Play, onClick = a.onPlayPause)
    ChromeButton(
        icon = if (m.muted || m.volume == 0f) Lucide.VolumeX else Lucide.Volume2,
        onClick = { a.onTogglePopover(OpenPopover.VOLUME) },
        variant = if (m.openPopover == OpenPopover.VOLUME) ChromeBtnVariant.Accent else ChromeBtnVariant.Neutral,
    )
    AnimatedVisibility(
        visible = m.openPopover == OpenPopover.VOLUME,
        enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
        exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VolumeSlider(
                volume = if (m.muted) 0f else m.volume,
                onVolumeChange = a.onVolumeChange,
                onDragStart = a.onVolumeDragStart,
                onDragEnd = a.onVolumeDragEnd,
            )
            ChromeButton(icon = Lucide.VolumeX, onClick = a.onToggleMute, variant = if (m.muted) ChromeBtnVariant.Accent else ChromeBtnVariant.Neutral)
        }
    }
}

@Composable
private fun MediaCluster(m: ChromeModel, a: ChromeActions) {
    val active = m.audio.firstOrNull { it.selected } ?: m.audio.firstOrNull()
    val chipLang = active?.language ?: m.info?.audioTracks?.firstOrNull { it.default }?.language
        ?: m.info?.audioTracks?.firstOrNull()?.language
    if (!chipLang.isNullOrBlank()) {
        AudioLangChip(
            language = chipLang,
            enabled = m.audio.isNotEmpty(),
            accent = m.openPopover == OpenPopover.AUDIO,
            onClick = { a.onTogglePopover(OpenPopover.AUDIO) },
        )
    }
    ChromeButton(
        icon = Lucide.Captions,
        onClick = { a.onTogglePopover(OpenPopover.CAPTIONS) },
        variant = if (m.activeSubtitleId != null || m.openPopover == OpenPopover.CAPTIONS) ChromeBtnVariant.Accent else ChromeBtnVariant.Neutral,
    )
    ChromeButton(
        icon = Lucide.Gauge,
        onClick = { a.onTogglePopover(OpenPopover.SPEED) },
        variant = if (m.openPopover == OpenPopover.SPEED) ChromeBtnVariant.Accent else ChromeBtnVariant.Neutral,
    )
    if (m.quality.pickable) {
        ChromeButton(
            icon = Lucide.Settings2,
            onClick = { a.onTogglePopover(OpenPopover.QUALITY) },
            variant = if (m.openPopover == OpenPopover.QUALITY) ChromeBtnVariant.Accent else ChromeBtnVariant.Neutral,
        )
    }
    if (m.pipSupported) {
        ChromeButton(
            icon = Lucide.PictureInPicture2,
            onClick = a.onTogglePip,
            variant = if (m.pipActive) ChromeBtnVariant.Accent else ChromeBtnVariant.Neutral,
        )
    }
    AirPlayButton(alpha = m.chromeAlpha)
    ChromeButton(
        icon = Lucide.Info,
        onClick = { a.onTogglePopover(OpenPopover.INFO) },
        variant = if (m.openPopover == OpenPopover.INFO) ChromeBtnVariant.Accent else ChromeBtnVariant.Neutral,
    )
}

@Composable
private fun EpisodeCluster(m: ChromeModel, a: ChromeActions) {
    if (m.canPrev) ChromeButton(icon = Lucide.ChevronLeft, onClick = a.onPrevEpisode)
    if (m.canNext) ChromeButton(icon = Lucide.ChevronRight, onClick = a.onNextEpisode)
}

@Composable
private fun FullscreenButton(m: ChromeModel, a: ChromeActions) {
    ChromeButton(icon = if (m.fullscreen) Lucide.Minimize else Lucide.Maximize, onClick = a.onToggleFullscreen)
}

/**
 * AirPlay: the system route picker (AVRoutePickerView), which only opens on a
 * real tap. A native view placed inside the Compose chrome (UIKitView) sits
 * under the Compose canvas and shows the picture, unscrimmed, through its
 * rectangle; so the picker is laid over the canvas instead, on the host view,
 * kept on this circle's position and the chrome's fade, and removed with it.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun AirPlayButton(alpha: Float) {
    val host = LocalUIViewController.current.view
    val density = LocalDensity.current.density
    val picker = remember {
        AVRoutePickerView(frame = CGRectMake(0.0, 0.0, 36.0, 36.0)).apply {
            tintColor = UIColor.whiteColor
            activeTintColor = UIColor(red = 0x58 / 255.0, green = 0xA6 / 255.0, blue = 1.0, alpha = 1.0)
            backgroundColor = UIColor.clearColor
            prioritizesVideoDevices = true
        }
    }
    DisposableEffect(host) {
        host.addSubview(picker)
        onDispose { picker.removeFromSuperview() }
    }
    SideEffect { picker.alpha = alpha.toDouble() }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .onGloballyPositioned { coordinates ->
                // The picker draws its glyph to its bounds: inset so it
                // matches the 20 dp icons of the buttons beside it.
                val r = coordinates.boundsInWindow()
                val d = density.toDouble()
                val inset = AIRPLAY_GLYPH_INSET_PT
                picker.setFrame(CGRectMake(r.left / d + inset, r.top / d + inset, r.width / d - 2 * inset, r.height / d - 2 * inset))
            },
    )
}

private const val AIRPLAY_GLYPH_INSET_PT = 4.0

@Composable
private fun TopBar(title: String, onBack: () -> Unit, onHome: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().absorbStrayTaps()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0.0f to Color(0xCC000000), 1.0f to Color.Transparent)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChromeButton(icon = Lucide.ArrowLeft, onClick = onBack)
            ChromeButton(icon = Lucide.House, onClick = onHome)
            // The title takes the rest of the row ("Series — S01E02 · Title"
            // needs it on a phone held upright). The web player's h1, in the
            // heading face.
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ChinoHeading,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Drag-capable scrubber, as the Android player's: idle / buffered / played
 *  bars, segment bands, the thumb, and a trickplay tile while scrubbing. */
@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    segments: List<Segment>,
    trickplayCues: List<TrickplayCue>,
    trickplayBaseUrl: String,
    streamToken: String,
    onScrubStart: (Long) -> Unit,
    onScrubUpdate: (Long) -> Unit,
    onScrubCommit: (Long) -> Unit,
    onScrubCancel: () -> Unit,
) {
    val durationSafe = durationMs.coerceAtLeast(1L)
    val density = LocalDensity.current
    var previewMs by remember { mutableStateOf<Long?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = formatTime(positionMs / 1000),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(min = 48.dp),
        )
        BoxWithConstraints(modifier = Modifier.weight(1f).height(28.dp)) {
            val trackWidthPx = with(density) { maxWidth.toPx() }
            val playedFrac = (positionMs.toFloat() / durationSafe).coerceIn(0f, 1f)
            val bufferedFrac = (bufferedMs.toFloat() / durationSafe).coerceIn(0f, 1f)
            val thumbSize = 16.dp
            val thumbOffsetX = with(density) { (trackWidthPx * playedFrac).toDp() } - thumbSize / 2
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val ms = (durationSafe * (offset.x / trackWidthPx).coerceIn(0f, 1f)).toLong()
                                previewMs = ms
                                onScrubStart(ms)
                            },
                            onDragEnd = {
                                previewMs?.let(onScrubCommit)
                                previewMs = null
                            },
                            onDragCancel = {
                                previewMs = null
                                onScrubCancel()
                            },
                        ) { change, _ ->
                            val ms = (durationSafe * (change.position.x / trackWidthPx).coerceIn(0f, 1f)).toLong()
                            previewMs = ms
                            onScrubUpdate(ms)
                            change.consume()
                        }
                    }
                    .pointerInput(durationMs) {
                        detectTapGestures(onTap = { offset ->
                            onScrubCommit((durationSafe * (offset.x / trackWidthPx).coerceIn(0f, 1f)).toLong())
                        })
                    },
            ) {
                Bar(Modifier.fillMaxWidth(), Color.White.copy(alpha = 0.15f))
                Bar(Modifier.width(with(density) { (trackWidthPx * bufferedFrac).toDp() }), Color.White.copy(alpha = 0.3f))
                Bar(Modifier.width(with(density) { (trackWidthPx * playedFrac).toDp() }), Accent)
                segments.forEach { seg ->
                    val startFrac = (seg.startMs.toFloat() / durationSafe).coerceIn(0f, 1f)
                    val endFrac = (seg.endMs.toFloat() / durationSafe).coerceIn(0f, 1f)
                    if (endFrac <= startFrac) return@forEach
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = with(density) { (trackWidthPx * startFrac).toDp() })
                            .width(with(density) { (trackWidthPx * (endFrac - startFrac)).toDp() })
                            .height(4.dp)
                            .background(segmentColor(seg.kind).copy(alpha = 0.85f)),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = thumbOffsetX)
                        .size(thumbSize)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(BorderStroke(2.dp, Accent), CircleShape),
                )
                val pm = previewMs
                if (pm != null && trickplayCues.isNotEmpty()) {
                    val cue = findTrickplayCue(trickplayCues, pm)
                    if (cue != null) {
                        val tileW = with(density) { cue.w.toDp() }
                        val tileH = with(density) { cue.h.toDp() }
                        val half = with(density) { (tileW / 2).toPx() }
                        val centerPx = (trackWidthPx * (pm.toFloat() / durationSafe)).coerceIn(half, (trackWidthPx - half).coerceAtLeast(half))
                        val seg = segments.firstOrNull { pm >= it.startMs && pm < it.endMs }
                        Box(modifier = Modifier.align(Alignment.CenterStart).offset(x = with(density) { centerPx.toDp() } - tileW / 2, y = -(tileH + 36.dp))) {
                            TrickplayPreview(
                                cue = cue,
                                spriteUrl = "$trickplayBaseUrl/${cue.sprite}?stream=$streamToken",
                                caption = seg?.let { "${segmentDisplayLabel(it)} · ${formatTime(pm / 1000)}" } ?: formatTime(pm / 1000),
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

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Bar(modifier: Modifier, color: Color) {
    Box(modifier = modifier.align(Alignment.CenterStart).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

@Composable
private fun TrickplayPreview(cue: TrickplayCue, spriteUrl: String, caption: String) {
    val density = LocalDensity.current
    val tileW = with(density) { cue.w.toDp() }
    val tileH = with(density) { cue.h.toDp() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(tileW, tileH)
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
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                    .offset(x = with(density) { -cue.x.toDp() }, y = with(density) { -cue.y.toDp() }),
            )
        }
        Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(text = caption, color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun VolumeSlider(volume: Float, onVolumeChange: (Float) -> Unit, onDragStart: () -> Unit, onDragEnd: () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.width(88.dp).height(28.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val v = volume.coerceIn(0f, 1f)
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
                .pointerInput(Unit) { detectTapGestures(onTap = { offset -> onVolumeChange((offset.x / widthPx).coerceIn(0f, 1f)) }) },
        ) {
            Bar(Modifier.fillMaxWidth(), Color.White.copy(alpha = 0.2f))
            Bar(Modifier.width(with(density) { (widthPx * v).toDp() }), Accent)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = with(density) { (widthPx * v).toDp() } - 6.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(BorderStroke(2.dp, Accent), CircleShape),
            )
        }
    }
}

internal enum class ChromeBtnVariant { Neutral, Accent }

@Composable
internal fun ChromeButton(icon: ImageVector, onClick: () -> Unit, variant: ChromeBtnVariant = ChromeBtnVariant.Neutral) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (variant == ChromeBtnVariant.Accent) Accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** Taps between a bar's buttons stop at the bar instead of reaching the
 *  picture (where they would only restart the auto-hide timer). */
private fun Modifier.absorbStrayTaps(): Modifier = pointerInput(Unit) { detectTapGestures(onTap = { }) }

/** The playing audio's language as its ISO 639-2/T code ("ENG", "DEU",
 *  "JPN"), "—" for a film without dialogue (zxx), "Audio" for a track in no
 *  language — web's chip ([audioChipLabel]); the whole name to a screen
 *  reader. */
@Composable
private fun AudioLangChip(language: String, enabled: Boolean, accent: Boolean, onClick: () -> Unit) {
    val description = "Audio: ${languageName(language)}"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (accent) Accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f))
            .let {
                if (enabled) it.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick) else it
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = audioChipLabel(language),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

@Composable
private fun MenuCard(width: Dp, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .width(width)
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MenuBg)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
private fun MenuHeader(title: String, trailing: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MutedText, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = MutedText.copy(alpha = 0.7f), fontSize = 10.sp)
    }
}

@Composable
private fun MenuRow(
    text: String,
    active: Boolean,
    detail: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                color = when {
                    !enabled -> Color.White.copy(alpha = 0.35f)
                    active -> Accent
                    else -> Color.White
                },
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                lineHeight = 18.sp,
            )
            if (detail != null) {
                Text(detail, color = MutedText, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun AudioMenuCard(tracks: List<AudioChoice>, onSelect: (AudioChoice) -> Unit) {
    MenuCard(width = 300.dp) {
        MenuHeader("AUDIO")
        if (tracks.isEmpty()) {
            Text("No audio tracks", color = MutedText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        tracks.forEach { t -> MenuRow(text = t.label, active = t.selected, detail = t.detail, onClick = { onSelect(t) }) }
    }
}

/** The captions menu: Off, then every track by language. A track this
 *  player cannot draw (a picture format) is listed, greyed, with why. */
@Composable
private fun CaptionsMenuCard(
    tracks: List<SubtitleChoice>,
    activeId: String?,
    unavailableNote: String,
    onSelect: (SubtitleChoice?) -> Unit,
) {
    MenuCard(width = 300.dp) {
        MenuHeader("SUBTITLES", trailing = "${if (activeId != null) 1 else 0}/1")
        MenuRow(text = "None / Off", active = activeId == null, onClick = { onSelect(null) })
        if (tracks.isEmpty()) {
            Text("No subtitles available", color = MutedText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        tracks.forEach { t ->
            val selected = t.id == activeId
            MenuRow(
                text = t.label,
                active = selected,
                enabled = t.available,
                detail = if (t.available) null else unavailableNote,
                leading = { CheckboxIndicator(selected = selected, enabled = t.available) },
                onClick = { onSelect(t) },
            )
        }
    }
}

@Composable
private fun CheckboxIndicator(selected: Boolean, enabled: Boolean) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (selected) Accent else Color.Transparent)
            .border(
                BorderStroke(1.dp, if (selected) Accent else Color.White.copy(alpha = if (enabled) 0.3f else 0.12f)),
                RoundedCornerShape(2.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Text("1", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SpeedMenuCard(currentSpeed: Float, onSelect: (Float) -> Unit) {
    MenuCard(width = 180.dp) {
        MenuHeader("SPEED")
        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { r ->
            MenuRow(text = if (r == 1.0f) "Normal" else "${r}x", active = r == currentSpeed, onClick = { onSelect(r) })
        }
    }
}

@Composable
private fun QualityMenuCard(options: List<QualityOption>, current: String, onSelect: (String) -> Unit) {
    MenuCard(width = 200.dp) {
        MenuHeader("QUALITY")
        options.forEach { o -> MenuRow(text = o.label, active = o.id.equals(current, ignoreCase = true), onClick = { onSelect(o.id) }) }
    }
}

/**
 * What sits along the bottom of the picture, stacked so that nothing covers
 * the subtitle: the subtitle on top, the up-next card under it, the skip pill
 * at the bottom right; just above the controls while they show
 * ([controlsTopPx], from ChromeActions.onControlsTop). Drawn under the
 * chrome, so an open menu covers them.
 */
@Composable
internal fun BottomOverlays(
    chromeVisible: Boolean,
    controlsTopPx: Float?,
    subtitle: String,
    card: (@Composable () -> Unit)? = null,
    pill: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    var bottomPx by remember { mutableStateOf(0f) }
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val lift = if (chromeVisible && controlsTopPx != null && bottomPx > controlsTopPx) {
        with(density) { (bottomPx - controlsTopPx).toDp() } + 8.dp
    } else {
        safeBottom + 24.dp
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().onGloballyPositioned { bottomPx = it.boundsInWindow().bottom }) {
        val subtitleSize = (maxWidth.value / 24f).coerceIn(16f, 30f).sp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(bottom = lift),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (subtitle.isNotBlank()) SubtitleText(subtitle, subtitleSize)
            card?.invoke()
            if (pill != null) {
                Box(modifier = Modifier.fillMaxWidth().padding(end = 16.dp), contentAlignment = Alignment.CenterEnd) { pill() }
            }
        }
    }
}

/** The subtitle on screen: white on a dark box, sized to the picture. */
@Composable
private fun SubtitleText(text: String, size: TextUnit) {
    Text(
        text = text,
        color = Color.White,
        fontSize = size,
        lineHeight = size * 1.25f,
        textAlign = TextAlign.Center,
        style = TextStyle(shadow = Shadow(color = Color.Black, blurRadius = 4f)),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 900.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** chino-web's manual skip ("Skip Intro"), a white pill. */
@Composable
internal fun SkipSegmentButton(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.SkipForward, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
        Text(label, color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** The credits countdown: "Up next", "Playing next episode in Ns", Play now /
 *  Cancel — the Android player's AutoNextOverlay. */
@Composable
internal fun AutoNextOverlay(secondsLeft: Int, nextTitle: String?, onDismiss: () -> Unit, onPlayNow: () -> Unit) {
    UpNextFrame {
        Text("Up next", color = MutedText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        if (!nextTitle.isNullOrBlank()) {
            Text(nextTitle, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Playing next episode in ${secondsLeft}s", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Accent).clickable(onClick = onPlayNow)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Play now", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.1f)).clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Cancel", color = Color.White, fontSize = 14.sp) }
        }
    }
}

/** Over a post-credits preview: the teaser plays on under "Next episode". */
@Composable
internal fun NextEpisodeCard(secondsLeft: Int?, nextTitle: String?, onDismiss: () -> Unit, onPlayNext: () -> Unit) {
    UpNextFrame(square = true) {
        Text("Up next", color = MutedText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        if (!nextTitle.isNullOrBlank()) {
            Text(nextTitle, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (secondsLeft != null) "Playing next episode in ${secondsLeft}s" else "Preview playing — jump to the next episode",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.background(Accent).clickable(onClick = onPlayNext).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(if (secondsLeft != null) Lucide.SkipForward else Lucide.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("Next episode", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            if (secondsLeft != null) {
                Box(modifier = Modifier.background(Color.White.copy(alpha = 0.1f)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Dismiss", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun UpNextFrame(square: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = if (square) RectangleShape else RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = 420.dp)
            .clip(shape)
            .background(if (square) Color(0xFF161B26) else Color(0xF2161B22))
            .border(BorderStroke(1.dp, if (square) Color(0xFF1F2633) else Color.White.copy(alpha = 0.1f)), shape)
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Full-screen playback info, the Android player's dialog with the iOS
 *  decoders. */
@Composable
private fun PlaybackInfoDialog(info: PlayInfo?, positionMs: Long, durationMs: Long, bufferedMs: Long, isPlaying: Boolean, onClose: () -> Unit) {
    val codecs = remember { iosDecodeProbes() }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color(0xB3000000)).pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp)
                .widthIn(max = 640.dp)
                .heightIn(max = maxHeight * 0.85f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MenuBg)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(12.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = { }) },
        ) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Playback info", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, style = ChinoHeading, modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                        Icon(Lucide.X, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (info != null) {
                        ModeBadge(info.mode, info.reason)
                        InfoSection("Source file") {
                            InfoRow("Container", info.container ?: "—")
                            InfoRow("Video", buildString {
                                append(info.videoCodec ?: "—")
                                if ((info.width ?: 0) > 0 && (info.height ?: 0) > 0) append(" (${info.width}×${info.height})")
                            })
                            InfoRow("Audio", info.audioCodec ?: "—")
                            InfoRow("Duration", info.durationMs?.let { formatTime(it / 1000) } ?: "—")
                        }
                        InfoSection("Live pipeline") {
                            InfoRow("Effective mode", modeLabel(info.mode))
                            if (info.mode.equals("transcode", ignoreCase = true)) {
                                InfoRow("Encoder", info.encoder ?: "libx264")
                                InfoRow("Video target", "H.264")
                                InfoRow("Audio target", "AAC stereo")
                            } else {
                                InfoRow("Quality", "Direct (${info.videoCodec?.uppercase() ?: "?"})")
                                InfoRow("Encoder", "None — source bytes pass through unmodified")
                            }
                            InfoRow("Position", "${formatTime(positionMs / 1000)} / ${formatTime((info.durationMs ?: durationMs) / 1000)}")
                            val ahead = (bufferedMs - positionMs).coerceAtLeast(0L)
                            InfoRow("Buffered ahead", if (ahead > 0) "${ahead / 1000}.${(ahead % 1000) / 100}s" else "—")
                            InfoRow("Element state", if (isPlaying) "PLAYING" else "PAUSED")
                        }
                    } else {
                        Text("Probing source file…", color = MutedText, fontSize = 14.sp)
                    }
                    InfoSection("This device can decode") {
                        codecs.chunked(2).forEach { row ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                row.forEach { (label, ok) ->
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (ok) Color(0xFF34D399) else Color(0xFFFB7185)))
                                        Text(label, color = if (ok) Color(0xFFC9D1D9) else MutedText, fontSize = 13.sp)
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeBadge(mode: String?, reason: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Accent.copy(alpha = 0.2f))
                .border(BorderStroke(1.dp, Accent.copy(alpha = 0.4f)), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) { Text(modeLabel(mode), color = Color(0xFFC9D1D9), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        if (!reason.isNullOrBlank()) Text(reason, color = Color(0xFFC9D1D9), fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, style = ChinoHeading)
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = MutedText, fontSize = 13.sp, modifier = Modifier.width(130.dp))
        Text(value, color = Color(0xFFC9D1D9), fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

private fun modeLabel(mode: String?): String = when (mode?.lowercase()) {
    "passthrough" -> "Passthrough (no transcode)"
    "remux" -> "Remux (stream-copy, repackaged into MP4)"
    "transcode" -> "Transcode (re-encoding required)"
    "packaged" -> "Packaged (pre-segmented CMAF on disk, no ffmpeg)"
    else -> mode?.replaceFirstChar { it.uppercase() } ?: "Unknown"
}

private fun segmentColor(kind: String): Color = when (kind.lowercase()) {
    "intro" -> Color(0xFFE3B341)
    "credits" -> Color(0xFFF59E0B)
    "recap" -> Color(0xFF8B5CF6)
    else -> Color(0xFFFFFFFF)
}
