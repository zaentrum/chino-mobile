package cloud.nalet.chino.mobile.ui.zap

import androidx.compose.animation.Crossfade
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
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
import cloud.nalet.chino.mobile.ui.theme.ChinoHeading
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.blur
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
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Zap


/**
 * Mobile Zap discovery — a full-bleed vertical reels pager (web flavour, no
 * remote). One [ZapCard] per page; the active page plays a mid-scene preview
 * via [ZapPreviewPlayer] whole in the middle of the card, over
 * its own ambient light (ZapAmbient), with the title, year and rating and two
 * affordances small at the foot: Watch (as a tap anywhere does) = expand into
 * the full player at the live scene, and Save = the watchlist toggle. It plays
 * with sound: the phone's volume is the mute.
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

        // Reels feed = portrait on a phone, where the card turned sideways is
        // too short for its overlay; a large screen turns freely and the card
        // lays out for it. Locked while Zap is on screen, restored on exit.
        ZapPortraitLock()

        // Saved = in the watchlist, the one every screen shares: a Save shows
        // at once, here and on Detail, and a title saved elsewhere shows saved.
        val watchlist by model.watchlist.collectAsState()

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            when (val s = state) {
                ZapUiState.Loading -> ZapMessage("Tuning in…", spinner = true)
                ZapUiState.Empty -> ZapMessage(
                    "Nothing to zap right now",
                    subtitle = "Add some movies or shows, or come back after the next ingest cycle.",
                )
                is ZapUiState.Active -> ZapPager(
                    cards = s.cards,
                    isSaved = { it in watchlist },
                    onPageSettled = model::onPageSettled,
                    onPositionUpdate = model::onPositionUpdate,
                    onComplete = model::onComplete,
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
    isSaved: (String) -> Boolean,
    onPageSettled: (Int) -> Unit,
    onPositionUpdate: (Int, Int) -> Unit,
    onComplete: (Int) -> Unit,
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
            saved = isSaved(card.item.id),
            onPositionSec = { sec -> onPositionUpdate(page, sec) },
            onComplete = { onComplete(page) },
            onTapExpand = { onExpand(page) },
            onToggleSave = { onSaveToggle(page) },
        )
    }
}

@Composable
private fun ZapCard(
    card: ZapCardState,
    active: Boolean,
    saved: Boolean,
    onPositionSec: (Int) -> Unit,
    onComplete: () -> Unit,
    onTapExpand: () -> Unit,
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
    // The latest small copy of the frame on screen, for the ambient light.
    var ambientFrame by remember(card.masterUrl) { mutableStateOf<ImageBitmap?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Tap anywhere on the card = expand into the full player at the
            // current scene (web's tap-to-expand). The buttons below have
            // their own clickable so they don't bubble up to this.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTapExpand,
            ),
    ) {
        // A card too short for the title on two lines: a short window on a
        // large screen (a split, a desktop window), which the portrait lock
        // does not shape there (ZapPortraitLock).
        val compact = maxHeight < 400.dp
        // The clip whole: full width where the card is taller than 16:9 (a
        // phone, a tablet upright), full height where it is wider.
        val clipSize = if (maxWidth * 9f / 16f <= maxHeight) {
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        } else {
            Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
        }

        ZapAmbient(frame = ambientFrame, backdropUrl = card.backdropUrl, modifier = Modifier.fillMaxSize())

        // The clip, in the middle. Over it until the first frame renders: the
        // backdrop (poster fallback), covering the surface's black pre-frame
        // state during the 1-3s cold start, then fading out. Hidden entirely
        // once faded to keep it from intercepting anything.
        Box(modifier = Modifier.align(Alignment.Center).then(clipSize)) {
            ZapPreviewPlayer(
                masterUrl = card.masterUrl,
                seekSec = card.seekSec,
                muted = false,
                active = active,
                modifier = Modifier.fillMaxSize(),
                onPositionSec = onPositionSec,
                onEnded = onComplete,
                // A dead channel just shows the card; the next swipe moves on
                // (the ScreenModel doesn't auto-advance on mobile — the user
                // controls the pager). Bounded auto-skip is a TV-remote concern.
                onError = {},
                onFirstFrame = { hasFirstFrame = true },
                onAmbientFrame = { ambientFrame = it },
            )
            if (backdropAlpha > 0f) {
                ZapColdStartBackdrop(
                    backdropUrl = card.backdropUrl,
                    posterUrl = card.posterUrl,
                    contentDescription = card.item.title,
                    modifier = Modifier.fillMaxSize().alpha(backdropAlpha),
                )
            }
        }

        // Title, year and rating, Watch and Save: small, at the foot of the
        // card, over a scrim that keeps them legible on the ambient light.
        ZapInfo(
            item = card.item,
            compact = compact,
            saved = saved,
            onWatch = onTapExpand,
            onToggleSave = onToggleSave,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xD9000000)))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 20.dp),
        )
    }
}

/**
 * The card's ambient light: the playing clip's own frame, small, blown up to
 * fill the card, blurred and dimmed behind the clip, so the card reads full
 * screen while the clip plays whole in the middle. Each new frame fades in
 * over the last. Before one is in (and where the platform takes none), the
 * title's backdrop gives the light.
 */
@Composable
private fun ZapAmbient(frame: ImageBitmap?, backdropUrl: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color.Black)) {
        val glow = Modifier.fillMaxSize().blur(48.dp).alpha(0.8f)
        Crossfade(targetState = frame, animationSpec = tween(durationMillis = 600), label = "zapAmbient") { shown ->
            if (shown != null) {
                Image(
                    bitmap = shown,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = glow,
                )
            } else {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = glow,
                )
            }
        }
        // A veil, so the clip stays the brightest thing on the card.
        Box(modifier = Modifier.fillMaxSize().background(Color(0x40000000)))
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

/** Watch from this scene — the primary — and Save, in the detail page's
 *  button style (square corners, the blue primary), a size smaller. Each has
 *  its own clickable, so a tap on one doesn't reach the card's tap-to-watch. */
@Composable
private fun ZapActions(saved: Boolean, onWatch: () -> Unit, onToggleSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(RectangleShape)
                .background(ChinoCloudBlue)
                .clickable(onClick = onWatch)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(imageVector = Lucide.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(text = "Watch", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        ZapIconButton(
            icon = if (saved) Lucide.BookmarkCheck else Lucide.Bookmark,
            contentDescription = if (saved) "Saved" else "Save",
            tint = if (saved) ChinoCloudBlue else Color.White,
            onClick = onToggleSave,
        )
    }
}

@Composable
private fun ZapIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RectangleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** What the card says of its title, small: the title, the year and the
 *  rating, and Watch and Save. [compact]: the card is short — the title on
 *  one line. */
@Composable
private fun ZapInfo(
    item: Item,
    compact: Boolean,
    saved: Boolean,
    onWatch: () -> Unit,
    onToggleSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // A readable measure on a wide card (a tablet) instead of running
        // across it.
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                style = ChinoHeading,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.year?.let { Text(it.toString(), color = ChinoFg2, fontSize = 13.sp) }
                item.rating?.let {
                    Text("★ ${((it * 10).toInt() / 10.0)}", color = ChinoCloudBlue, fontSize = 13.sp)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            ZapActions(saved = saved, onWatch = onWatch, onToggleSave = onToggleSave)
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
            // The web's EmptyState h2.
            Text(text = text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, style = ChinoHeading)
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
