package cloud.nalet.chino.mobile.ui.zap

import androidx.compose.ui.graphics.RectangleShape

import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoBg2
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder2
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoDim
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.ui.player.PlayerScreen
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.BookmarkCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.composables.icons.lucide.Zap

/**
 * Whether the muted/sound default for the Zap teaser starts SILENT.
 *
 * The user liked the TV Zap which surfs WITH SOUND, so we default to
 * sound-on (false) — flip this single constant to true for a silent-first
 * teaser. The in-screen toggle still lets the user mute live, and the choice
 * persists across swipes (lifted into the screen, mirroring chino-web's
 * mutedSession).
 */
private const val ZAP_DEFAULT_MUTED = false

/**
 * Mobile Zap discovery — a full-bleed vertical reels pager (web flavour, no
 * remote). One [ZapCard] per page; the active page plays a mid-scene preview
 * via [ZapPreviewPlayer] under an info overlay (title • year • ⭐ rating •
 * genres + overview) with three affordances: tap = expand into the full
 * player at the live scene, a Save/watchlist toggle, and a mute/unmute toggle.
 *
 * Snap + swipe-up = next card is the VerticalPager's native behaviour.
 * Page-settle drives the ZapScreenModel funnel (dwell classification, prefs,
 * telemetry, queue refill, next-card prewarm). The next page is prewarmed and
 * rendered (distance<=1) so it's warm before the user swipes to it.
 */
class ZapScreen : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val container = LocalAppContainer.current
        val nav = LocalNavigator.currentOrThrow
        val model = remember { ZapScreenModel(container) }
        val state by model.state.collectAsState()

        // This ScreenModel is created with remember{} (not rememberScreenModel),
        // so Voyager never calls onDispose — fire session-end + final dwell when
        // the Zap tab leaves composition (tab switch / nav away).
        DisposableEffect(Unit) { onDispose { model.closeSession() } }

        // Reels feed = portrait only; rotating just letterboxes the teaser and
        // breaks the overlay. Locked while Zap is on screen, restored on exit.
        ZapPortraitLock()

        // Lifted mute — unmuting once survives every swipe afterwards
        // (chino-web's mutedSession). Default per ZAP_DEFAULT_MUTED.
        var muted by remember { mutableStateOf(ZAP_DEFAULT_MUTED) }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            when (val s = state) {
                ZapUiState.Loading -> ZapMessage("Tuning in…", spinner = true)
                ZapUiState.Empty -> ZapMessage(
                    "Nothing to zap right now",
                    subtitle = "Add some movies or shows, or come back after the next ingest cycle.",
                )
                is ZapUiState.Active -> ZapPager(
                    cards = s.cards,
                    muted = muted,
                    isSaved = { model.isSaved(it) },
                    onPageSettled = model::onPageSettled,
                    onPositionUpdate = model::onPositionUpdate,
                    onComplete = model::onComplete,
                    onToggleMute = { muted = !muted; model.onMuteToggle(muted) },
                    onSaveToggle = model::onSaveToggle,
                    onExpand = { index ->
                        model.onExpand(index)?.let { (itemId, resumeSec) ->
                            nav.push(PlayerScreen(itemId = itemId, fromStart = false, resumeSec = resumeSec))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ZapPager(
    cards: List<ZapCardState>,
    muted: Boolean,
    isSaved: (String) -> Boolean,
    onPageSettled: (Int) -> Unit,
    onPositionUpdate: (Int, Int) -> Unit,
    onComplete: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onSaveToggle: (Int) -> Unit,
    onExpand: (Int) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { cards.size })

    // Drive the funnel off the SETTLED page (web's IntersectionObserver
    // equivalent): only fire when the pager has come to rest on a page, so a
    // fling through several cards doesn't count each as an impression/dwell.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { onPageSettled(it) }
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1, // keep neighbours warm (distance<=1)
    ) { page ->
        val card = cards[page]
        // Only the settled page plays; neighbours are prepared but paused so
        // we don't run several decoders at once on a phone.
        val active = page == pagerState.settledPage
        ZapCard(
            card = card,
            active = active,
            muted = muted,
            saved = isSaved(card.item.id),
            onPositionSec = { sec -> onPositionUpdate(page, sec) },
            onComplete = { onComplete(page) },
            onTapExpand = { onExpand(page) },
            onToggleMute = onToggleMute,
            onToggleSave = { onSaveToggle(page) },
        )
    }
}

@Composable
private fun ZapCard(
    card: ZapCardState,
    active: Boolean,
    muted: Boolean,
    saved: Boolean,
    onPositionSec: (Int) -> Unit,
    onComplete: () -> Unit,
    onTapExpand: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSave: () -> Unit,
) {
    // Cold-start backdrop gate — true until the preview surface reports its
    // first rendered video frame (web's hasFirstFrame). Reset whenever the
    // channel changes so a re-bound card shows its own backdrop again. The
    // backdrop layer fades out (alpha 1→0) once the frame arrives.
    var hasFirstFrame by remember(card.masterUrl) { mutableStateOf(false) }
    val backdropAlpha by animateFloatAsState(
        targetValue = if (hasFirstFrame) 0f else 1f,
        animationSpec = tween(durationMillis = 350),
        label = "zapBackdropFade",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Tap anywhere on the card = expand into the full player at the
            // current scene (web's tap-to-expand). The action buttons below
            // have their own clickable so they don't bubble up to this.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTapExpand,
            ),
    ) {
        ZapPreviewPlayer(
            masterUrl = card.masterUrl,
            seekSec = card.seekSec,
            muted = muted,
            active = active,
            modifier = Modifier.fillMaxSize(),
            onPositionSec = onPositionSec,
            onEnded = onComplete,
            // A dead channel just shows the overlay; the next swipe moves on
            // (the ScreenModel doesn't auto-advance on mobile — the user
            // controls the pager). Bounded auto-skip is a TV-remote concern.
            onError = {},
            onFirstFrame = { hasFirstFrame = true },
        )

        // Cold-start image: full-bleed backdrop (poster fallback) layered over
        // the player surface, covering its black pre-frame state during the
        // 1-3s cold start, then fading out the moment the first frame renders.
        // Sits below the action rail + info overlay (added after it), so those
        // stay visible throughout. Hidden entirely once faded to keep it from
        // intercepting anything.
        if (backdropAlpha > 0f) {
            ZapColdStartBackdrop(
                backdropUrl = card.backdropUrl,
                posterUrl = card.posterUrl,
                contentDescription = card.item.title,
                modifier = Modifier.fillMaxSize().alpha(backdropAlpha),
            )
        }

        // Right-edge action rail (reels-style): mute + save, vertically
        // stacked above the info overlay. Each has its own clickable.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars))
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ActionButton(
                icon = if (muted) Lucide.VolumeX else Lucide.Volume2,
                label = if (muted) "Unmute" else "Mute",
                onClick = onToggleMute,
            )
            ActionButton(
                icon = if (saved) Lucide.BookmarkCheck else Lucide.Bookmark,
                label = "Save",
                tint = if (saved) ChinoCloudBlue else Color.White,
                onClick = onToggleSave,
            )
            ActionButton(
                icon = Lucide.Maximize,
                label = "Watch",
                onClick = onTapExpand,
            )
        }

        // Bottom info overlay — gradient scrim + metadata, aligned bottom-start.
        ZapInfoOverlay(
            item = card.item,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/**
 * Full-bleed cold-start image for a Zap card: the item's backdrop, cropped to
 * fill, with the poster as a fallback when the backdrop 404s / fails to load
 * (mirrors chino-web's ZapCard `backdrop_url || poster_url`). On a black
 * scrim so a still-loading image doesn't flash the player's surface.
 */
@Composable
private fun ZapColdStartBackdrop(
    backdropUrl: String,
    posterUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var useFallback by remember(backdropUrl, posterUrl) { mutableStateOf(false) }
    Box(modifier = modifier.background(Color.Black)) {
        AsyncImage(
            model = if (useFallback) posterUrl else backdropUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onError = { if (!useFallback) useFallback = true },
        )
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RectangleShape)
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(text = label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ZapInfoOverlay(item: Item, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.6f to Color(0xCC000000),
                    1f to Color(0xF2000000),
                ),
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 88.dp, top = 64.dp, bottom = 24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Small Zap wordmark chip so the discovery mode reads as "Zap".
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector = Lucide.Zap,
                    contentDescription = null,
                    tint = ChinoCloudBlue,
                    modifier = Modifier.size(16.dp),
                )
                Text("Zap", color = ChinoCloudBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item.year?.let { Text(it.toString(), color = ChinoFg2, fontSize = 14.sp) }
                item.rating?.let {
                    Text("★ ${((it * 10).toInt() / 10.0)}", color = ChinoCloudBlue, fontSize = 14.sp)
                }
                item.genres.take(2).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), color = ChinoMuted, fontSize = 13.sp)
                }
            }
            item.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = ChinoFg2,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "Tap to watch from here  ·  Swipe up for next",
                color = ChinoMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ZapMessage(text: String, subtitle: String? = null, spinner: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            if (spinner) {
                CircularProgressIndicator(color = ChinoCloudBlue)
            } else {
                Icon(
                    imageVector = Lucide.Zap,
                    contentDescription = null,
                    tint = ChinoCloudBlue,
                    modifier = Modifier.size(48.dp),
                )
            }
            Text(text = text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(
                    text = it,
                    color = ChinoMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
