package cloud.nalet.chino.mobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import com.composables.icons.lucide.BookmarkCheck
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hero banner — clone of chino-web's HeroSection.tsx for the tablet/desktop
 * layout. Backdrop image right-anchored with a left-edge mask that fades to
 * the canvas; title + year + rating chip + overview paragraph + Play/More-
 * Info buttons left-anchored on the dark portion. Rotates through the pool
 * every ~12s with a crossfade. Skips the YouTube trailer embed (native
 * YouTube player is platform-specific and not load-bearing here).
 */
@Composable
fun HeroBanner(
    pool: List<Item>,
    baseUrl: String,
    streamToken: String,
    rotateEveryMs: Long = 12_000L,
    onMoreInfo: ((String) -> Unit)? = null,
    onPlay: ((String) -> Unit)? = null,
) {
    if (pool.isEmpty()) return
    // Swipeable carousel. HorizontalPager gives native touch-drag + snapping;
    // the ticker auto-advances but yields while the user is dragging so it
    // doesn't fight the swipe.
    val pagerState = rememberPagerState(
        initialPage = if (pool.size >= 2) (0 until pool.size).random() else 0,
        pageCount = { pool.size },
    )
    val scope = rememberCoroutineScope()
    LaunchedEffect(pool, pagerState) {
        if (pool.size < 2) return@LaunchedEffect
        while (true) {
            delay(rotateEveryMs)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % pool.size)
            }
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        val isWide = maxWidth >= 600.dp
        // CDP-verified: web hero image is 500px tall (chino-web's
        // HeroSection has h-[500px] on md+). Tablet's wide hero was
        // previously 420 — too short, made the hero feel cramped.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isWide) 500.dp else 280.dp)
                // CDP-verified: web hero is `rounded-xl` = 12px, not 16.
                .clip(RoundedCornerShape(12.dp))
                // CDP-verified: web hero card uses `bg-black` = pure
                // black (rgb 0,0,0), not the canvas color. The mask on
                // the backdrop image fades to this black, which is the
                // intentional darker shade behind the title column.
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                HeroContent(
                    item = pool[page],
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    isWide = isWide,
                    onMoreInfo = onMoreInfo,
                    onPlay = onPlay,
                )
            }
            if (pool.size > 1) {
                // CDP-verified pagination dots: ALL dots are 8×8 circles
                // (`w-2 h-2 rounded-full`). Active = opaque white
                // (`bg-white`), inactive = 30% white (`bg-white/30`).
                // Web uses `flex gap-1.5` = 6px between dots (not 8px /
                // gap-2). Position: start = 48dp aligns under the Play
                // button (Play sits at hero left padding 16 + text-
                // column padding 32 = 48dp). Bottom inset 48dp matches
                // web `bottom-12`.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = if (isWide) 48.dp else 16.dp, bottom = if (isWide) 48.dp else 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pool.indices.forEach { i ->
                        val isActive = i == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) Color.White else Color.White.copy(alpha = 0.3f),
                                )
                                .clickable { scope.launch { pagerState.animateScrollToPage(i) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroContent(
    item: Item,
    baseUrl: String,
    streamToken: String,
    isWide: Boolean,
    onMoreInfo: ((String) -> Unit)?,
    onPlay: ((String) -> Unit)?,
) {
    if (isWide) HeroContentWide(item, baseUrl, streamToken, onMoreInfo, onPlay)
    else HeroContentNarrow(item, baseUrl, streamToken, onMoreInfo, onPlay)
}

@Composable
private fun HeroContentNarrow(item: Item, baseUrl: String, streamToken: String, onMoreInfo: ((String) -> Unit)?, onPlay: ((String) -> Unit)?) {
    // Phone hero mirrors chino-web's mobile hero (CDP-verified at 411px: a
    // 379x280 object-cover backdrop with the title OVERLAID top-left and the
    // Play / More Info buttons OVERLAID bottom-left — NOT a stacked image-then-
    // text block, which also let the pagination dots collide with the buttons).
    // Full-bleed crop + a top+bottom dark gradient keeps the overlaid text legible.
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = "$baseUrl/v1/items/${item.id}/backdrop?stream=$streamToken",
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to Color(0xCC000000),
                    0.28f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1.0f to Color(0xF2000000),
                ),
            ),
        )
        // Title + chip overlaid top-left (web: x=32, y=96, text-2xl=24px).
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
            YearRatingChipRow(item)
        }
        // Play / More Info overlaid bottom-left, sitting above the pagination
        // dots (which the parent pins at bottom=16). Web: Play at y=284 (~40dp
        // from the 280-tall image bottom), 8dp button gap.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroButton(
                label = "Play",
                icon = Lucide.Play,
                background = Color(0xFF58A6FF),
                contentColor = Color.White,
                onClick = { onPlay?.invoke(item.id) },
            )
            HeroButton(
                label = "More Info",
                icon = Lucide.Info,
                background = Color.White.copy(alpha = 0.2f),
                contentColor = Color.White,
                onClick = { onMoreInfo?.invoke(item.id) },
            )
        }
    }
}

@Composable
private fun HeroContentWide(item: Item, baseUrl: String, streamToken: String, onMoreInfo: ((String) -> Unit)?, onPlay: ((String) -> Unit)?) {
    Box(modifier = Modifier.fillMaxSize()) {
        // CDP-verified: web backdrop image is `md:w-[60%] md:max-w-[1100px]
        // h-full object-cover object-center` — 60% wide of the hero
        // container, anchored right, full-height, *cropped* to fill the
        // box (no letterboxing). The mask
        // `linear-gradient(to_left, black 0% 60%, transparent 100%)`
        // fades the image into the canvas on its left edge — Compose
        // can't cheaply apply mask-image so a left-side overlay
        // gradient achieves the same visual.
        //
        // Previous chain `.fillMaxWidth(0.6f).fillMaxSize()` was a bug —
        // fillMaxSize implicitly fillMaxWidth(1f) which overrode the
        // 0.6 fraction; AsyncImage also defaults to ContentScale.Fit
        // which left empty bands above + below the backdrop on items
        // with non-matching aspect ratios.
        AsyncImage(
            model = "$baseUrl/v1/items/${item.id}/backdrop?stream=$streamToken",
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.6f)
                .align(Alignment.CenterEnd),
        )
        // Left-to-right BLACK-to-transparent overlay over the backdrop,
        // matching web's `mask-image: linear-gradient(to_left, black 0%,
        // black 60%, transparent 100%)`. With image anchored right
        // 60 % of hero, the mask says the image's right 60 % is fully
        // visible and the left 40 % fades. In hero coordinates that's:
        //   0 → 40 %   : opaque black (the dark text-column inset)
        //   40 → 64 %  : fade from black to transparent (image emerges)
        //   64 → 100 % : transparent (image fully visible)
        // Using fraction-based colour stops makes this responsive — the
        // previous `endX = 900f` was a fixed-pixel cap that landed the
        // fade BEFORE the image started, so no actual fade appeared on
        // the backdrop's left edge.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color.Black,
                        0.4f to Color.Black,
                        0.64f to Color.Transparent,
                        1.0f to Color.Transparent,
                    ),
                ),
        )
        // Title + year-rating chip + overview anchored at TopStart with
        // 48dp top padding (matches web's text column at y=146 in a
        // 500-tall hero, ~16% from top). max-width 476dp matches web's
        // 475.5px text column width.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 48.dp, end = 16.dp, top = 48.dp)
                .widthIn(max = 476.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // h1.text-5xl = 48px/48 line-height/700. ExtraBold (800)
            // compensates for Roboto rendering slightly thinner than
            // web's macOS system-ui Bold at the same weight token.
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 48.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
            )
            YearRatingChipRow(item)
            // text-lg = 18px/28 line-height on tablet (lg+). #C9D1D9
            // matches text-[#c9d1d9]. Hidden when API returns null
            // (e.g. catalog hasn't curated a description for the
            // current hero rotation item).
            item.overview?.let { overview ->
                Text(
                    text = overview,
                    color = Color(0xFFC9D1D9),
                    fontSize = 18.sp,
                    lineHeight = 28.sp,
                    maxLines = 3,
                )
            }
        }
        // CDP-verified: buttons are pinned to the BOTTOM of the hero
        // text column at y=460 in a 500-tall hero (= 72dp from hero
        // bottom: 92dp - 48 button height = 44 + 28 = 72). Web's
        // <main> uses flex-col with the title group at the top and
        // the button row pinned via mt-auto / bottom positioning, so
        // when item.overview is null the buttons DON'T move up — they
        // stay at the same Y. Mobile previously collapsed the column
        // with the buttons sitting right under the chip row when
        // overview was null. BottomStart-aligned Row with padding(
        // bottom=72dp, start=48dp) pins them to web's exact position
        // regardless of overview presence.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 72.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroButton(
                label = "Play",
                icon = Lucide.Play,
                background = Color(0xFF58A6FF),
                contentColor = Color.White,
                onClick = { onPlay?.invoke(item.id) },
            )
            HeroButton(
                label = "More Info",
                icon = Lucide.Info,
                background = Color.White.copy(alpha = 0.2f),
                contentColor = Color.White,
                onClick = { onMoreInfo?.invoke(item.id) },
            )
        }
    }
}

/** Year + bullet + blue rating chip — shared by narrow and wide hero
 *  layouts. Mirrors chino-web's `<year> • <chip>` block. */
@Composable
private fun YearRatingChipRow(item: Item) {
    if (item.year == null && item.rating == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // CDP-verified row gap = 6dp (web `gap-1.5`). Bullet is the
        // brighter white drop-shadow on web, not muted grey.
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // CDP-verified hero year + rating: year text matches the
        // ambient 14px / 400 / 20-line paragraph font, rating chip is
        // a `px-2 py-0.5 bg-[#58a6ff] rounded` span with 14px / 400
        // white text.
        item.year?.let {
            Text(it.toString(), color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
        }
        item.rating?.let { r ->
            if (item.year != null) {
                Text("•", color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF58A6FF))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = ((r * 10).toInt() / 10.0).toString(),
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
private fun HeroButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    // CDP-verified: web hero buttons use
    //   `flex items-center gap-2 px-4 py-2 md:px-6 md:py-3 bg-[#58a6ff]
    //    text-white rounded-lg text-sm md:text-base`
    // On md+ viewports that's padding 12 24, gap 8, rounded-lg = 8px,
    // text-base = 16px, icon SVG 20×20. At tablet's 1280dp we're md+.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        // CDP-verified: web button label is font-weight 400 (Normal),
        // 16px text-base, line-height 24. Previous SemiBold was a tick
        // too heavy.
        Text(
            text = label,
            color = contentColor,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

/**
 * Horizontal carousel of [MediaCard] tiles. Mirrors chino-web's MediaRow.
 * Heading + LazyRow; touch-scrollable (no prev/next chevrons because mobile
 * has fling-scroll natively).
 */
@Composable
fun MediaRow(
    title: String,
    items: List<Item>,
    baseUrl: String,
    streamToken: String,
    onItemClick: ((String) -> Unit)? = null,
    // #150: when set, render a trailing "See all" tile at the end of the
    // row that jumps to the full Movies/Shows overview (chino-web parity:
    // MediaRow.tsx onSeeAll). The mobile LazyRow never loops, so this is
    // the only terminal affordance the row needs.
    onSeeAll: (() -> Unit)? = null,
    // Watchlist hub: tapping the shelf header opens the same per-list MORE
    // view the trailing See-all tile does. Home rails leave this null and
    // the title stays inert.
    onTitleClick: (() -> Unit)? = null,
    // Watchlist hub: every tile on a list shelf is by definition saved, so
    // the hub turns the bookmark badge on for the whole row.
    saved: Boolean = false,
    // #188: per-card watched toggle. When set, each card's overflow menu gains
    // a Mark-watched/Mark-unwatched item; the callback receives the item id and
    // owns the optimistic update (Home rails drop the card; Browse flips the
    // badge). null leaves the toggle off the menu.
    onToggleWatched: ((String) -> Unit)? = null,
) {
    // CDP-verified: title → LazyRow gap = 16dp (web `mb-4`).
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // CDP-verified shelf title: `h2.text-2xl font-semibold` =
        // 24px / 32 line / 600.
        Text(
            text = title,
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .let { m -> if (onTitleClick != null) m.clickable(onClick = onTitleClick) else m },
        )
        // CDP-verified: gap-4 (16dp) between cards; pb-2 (8dp) bottom
        // contentPadding so card bottom edges don't kiss the next shelf.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // Web mobile poster is 128dp (3-across w/ peek); tablet/wide 208dp.
            val cardWidth = if (maxWidth < 600.dp) 128.dp else 208.dp
            LazyRow(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    MediaCard(
                        item = item,
                        posterUrl = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                        onClick = onItemClick?.let { cb -> { cb(item.id) } },
                        cardWidth = cardWidth,
                        saved = saved,
                        onToggleWatched = onToggleWatched?.let { cb -> { cb(item.id) } },
                    )
                }
                // #150: end-of-row "See all" tile — only when the row opts
                // into a finite overview link AND has items. Matches chino-
                // web's trailing See-all tile (MediaRow.tsx L292).
                if (onSeeAll != null && items.isNotEmpty()) {
                    item(key = "__see_all__") {
                        SeeAllTile(cardWidth = cardWidth, onClick = onSeeAll)
                    }
                }
            }
        }
    }
}

/** Trailing "See all" tile for a [MediaRow]. A poster-sized button that
 *  jumps to the full Movies/Shows overview. Mirrors chino-web's end-of-
 *  row See-all tile (MediaRow.tsx). */
@Composable
private fun SeeAllTile(cardWidth: Dp, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161B22))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF21262D)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFF58A6FF),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = "See all",
                    color = Color(0xFF58A6FF),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        // Spacer block to match MediaCard's info-block height so the tile
        // is the same total height as the poster cards beside it.
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp))
    }
}

/** Episode coordinates used by Continue Watching cards. Mirrors chino-
 *  web's MediaCard `episode` prop: SxxExx badge + optional episode title. */
data class EpisodeBadge(val season: Int, val episode: Int, val episodeTitle: String?)

/**
 * Single poster tile. 2:3 portrait poster + title beneath. Mirrors chino-
 * web's MediaCard (without the hover-overlay actions — those land when we
 * add the Detail page tap).
 *
 * Continue Watching mode: pass [progress] (0..100) to overlay a 1dp blue
 * progress bar at the bottom of the poster (web's `absolute bottom-0 …
 * h-1 bg-[#30363d]` track with `bg-[#58a6ff]` fill), and pass
 * [episodeBadge] to swap the year•rating subtitle for `SxxExx · episode
 * title` — also web parity (MediaCard.tsx L200-215).
 */
@Composable
fun MediaCard(
    item: Item,
    posterUrl: String,
    onClick: (() -> Unit)? = null,
    progress: Float? = null,
    episodeBadge: EpisodeBadge? = null,
    cardWidth: Dp = 208.dp,
    // When true, overlays a small bookmark badge on the poster — the "saved"
    // signal, now meaning "in the default list OR any named list" (#110).
    // Sits top-LEFT so it never collides with the top-right watched badge.
    saved: Boolean = false,
    // When set, the card surfaces a "Remove from Continue Watching" action
    // via a small ⋮ overflow button on the poster + a long-press on the card
    // body. Only the in-progress Continue Watching rail wires this (web:
    // MediaCard.tsx `onRemoveFromContinueWatching`); other rails leave it
    // null and the affordance never appears.
    onRemoveFromContinueWatching: (() -> Unit)? = null,
    // #188: when set, the card's overflow menu gains a watched toggle. The
    // label/icon flip on `item.watchedAt`: set → "Mark as unwatched" (EyeOff),
    // else → "Mark as watched" (Eye). The callback reuses the same watched
    // POST/DELETE the detail-page eye uses; the caller owns the optimistic
    // update (drop from a Home rail / flip the Browse badge). Available on ALL
    // card surfaces, not just Continue Watching — so wiring this is what makes
    // the ⋮ / long-press menu appear on rails that don't dismiss.
    onToggleWatched: (() -> Unit)? = null,
) {
    // Either action wires the overflow affordance. When ONLY the watched
    // toggle is wired (Home rails / Browse grid), the menu still appears.
    val hasMenu = onRemoveFromContinueWatching != null || onToggleWatched != null
    // Drives the overflow dropdown. Opened by either the ⋮ button or a
    // long-press on the card body — both feed the same single-item menu.
    var menuOpen by remember { mutableStateOf(false) }
    // CDP-verified: web's MediaCard is `relative rounded-lg overflow-
    // hidden bg-[#161B22]`. The OUTER card carries the 8dp radius +
    // surface bg (so the info area below the poster sits on the dark
    // card surface, not bleeding through to canvas). Poster fills the
    // top 208×312 (2:3), then a 12dp-padded info block holds title +
    // meta with 4dp gap. CDP-verified fonts: title 16px/24 / 500 /
    // #C9D1D9, meta 14px/20 / 400. Card 208×384.
    Column(
        modifier = Modifier
            // Width is responsive (caller passes ~128dp on a phone, 208dp on a
            // tablet/wide). Height wraps: 2:3 poster + info block, so cards stay
            // uniform (title/meta are single-line) and scale with the width.
            .width(cardWidth)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161B22))
            .let { base ->
                // Long-press opens the overflow menu when any menu action is
                // wired (phone-appropriate affordance, web parity for the
                // ⋮ menu). Falls back to a plain clickable otherwise.
                when {
                    onClick != null && hasMenu ->
                        base.combinedClickable(
                            onClick = onClick,
                            onLongClick = { menuOpen = true },
                        )
                    onClick != null -> base.clickable(onClick = onClick)
                    else -> base
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Watched badge — top-end emerald circle with a white check.
            // Mirrors the TV PosterCard badge (CircleShape, #2EA043). Shown
            // when chino-api reports a watched_at timestamp for the item.
            if (item.watchedAt != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2EA043)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.Check,
                        contentDescription = "Watched",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            // Saved badge — top-LEFT bookmark, shown when the item is in at
            // least one watchlist. Mirrors the TV/web "saved" poster badge.
            if (saved) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC161B22)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.BookmarkCheck,
                        contentDescription = "Saved",
                        tint = Color(0xFF58A6FF),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            // ⋮ overflow + dropdown — top-right of the poster. Present on ANY
            // card that wires a menu action (#188: the watched toggle makes
            // this appear on Home rails + Browse, not just Continue Watching).
            // Tap opens the same menu a long-press does. Web: MediaCard.tsx
            // 3-dot menu.
            if (hasMenu) {
                // Offset the ⋮ below the watched badge when both want the
                // top-right corner (web: the menu button sits clear of the
                // watched badge). The badge is 6dp inset + 20dp tall, so a
                // 32dp top inset clears it; otherwise hug the corner.
                val menuTopInset = if (item.watchedAt != null) 32.dp else 6.dp
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = menuTopInset, end = 6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0x99000000))
                            .clickable { menuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.EllipsisVertical,
                            contentDescription = "More options",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        // #188: watched toggle — label/icon flip on the
                        // server-stamped watchedAt. Reuses the same watched
                        // POST/DELETE as the detail-page eye via onToggleWatched.
                        if (onToggleWatched != null) {
                            val isWatched = item.watchedAt != null
                            DropdownMenuItem(
                                text = {
                                    Text(if (isWatched) "Mark as unwatched" else "Mark as watched")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isWatched) Lucide.EyeOff else Lucide.Eye,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onToggleWatched()
                                },
                            )
                        }
                        if (onRemoveFromContinueWatching != null) {
                            DropdownMenuItem(
                                text = { Text("Remove from Continue Watching") },
                                onClick = {
                                    menuOpen = false
                                    onRemoveFromContinueWatching()
                                },
                            )
                        }
                    }
                }
            }
            if (progress != null && progress > 0f) {
                // Web: `absolute bottom-0 left-0 right-0 h-1 bg-[#30363d]`
                // + fill `bg-[#58a6ff]`. 4dp track height reads cleaner
                // than 1dp at tablet density.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0xFF30363D)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0f, 100f) / 100f)
                            .background(Color(0xFF58A6FF)),
                    )
                }
            }
        }
        Column(
            // Reserve a fixed info-block height (title line + one meta line)
            // so every card in a row is the SAME total height regardless of
            // whether it has a subtitle. Without this, Continue-Watching movies
            // (title only) render shorter than episodes (title + SxxExx line).
            // 12dp top + 24 title + 4 gap + 20 meta + 12 bottom = 72dp.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                color = Color(0xFFC9D1D9),
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (episodeBadge != null) {
                // Web: SxxExx in #58a6ff, · separator + episode title in
                // #8b949e, 14px/20. MediaCard.tsx L205-215.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "S${episodeBadge.season.toString().padStart(2, '0')}" +
                            "E${episodeBadge.episode.toString().padStart(2, '0')}",
                        color = Color(0xFF58A6FF),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    episodeBadge.episodeTitle?.let { epTitle ->
                        Text("·", color = Color(0xFF8B949E), fontSize = 14.sp, lineHeight = 20.sp)
                        Text(
                            text = epTitle,
                            color = Color(0xFF8B949E),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                val hasMeta = item.year != null || item.rating != null
                if (hasMeta) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item.year?.let {
                            Text(it.toString(), color = Color(0xFF8B949E), fontSize = 14.sp, lineHeight = 20.sp)
                        }
                        item.rating?.let { r ->
                            if (item.year != null) {
                                Text("•", color = Color(0xFF8B949E), fontSize = 14.sp, lineHeight = 20.sp)
                            }
                            Text(
                                text = ((r * 10).toInt() / 10.0).toString(),
                                color = Color(0xFF58A6FF),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
