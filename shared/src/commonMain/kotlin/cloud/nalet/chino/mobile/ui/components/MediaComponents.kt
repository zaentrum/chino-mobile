package cloud.nalet.chino.mobile.ui.components

import androidx.compose.ui.graphics.RectangleShape
import cloud.nalet.chino.mobile.data.model.episodeCode

import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoOnFill
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
import cloud.nalet.chino.mobile.ui.theme.PosterImage

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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import com.composables.icons.lucide.BookmarkFilled
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.ui.home.HeroPlay
import cloud.nalet.chino.mobile.ui.trailer.TrailerChoice
import cloud.nalet.chino.mobile.ui.trailer.trailerChoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hero banner — chino-web's HeroSection.tsx. On a tablet the backdrop is
 * right-anchored with a left-edge mask that fades to the canvas; title +
 * year + rating chip + overview paragraph + play / see details / trailer
 * left of it, on the dark portion. On a phone the picture is the hero's
 * width and ends above the buttons, which sit on the hero's black: no button
 * is drawn over a still (docs/chino title actions). The buttons are the
 * title actions, 48 dp and solid. Rotates through the pool every ~12s. No
 * trailer plays on the hero by itself (web's TRAILERS_ON_HERO is off too):
 * the title's trailer button opens it, as its page's does.
 */
@Composable
fun HeroBanner(
    pool: List<Item>,
    baseUrl: String,
    streamToken: String,
    rotateEveryMs: Long = 12_000L,
    /** Each title's first button, by its id: "resume 0:58" with "start
     *  over", "play S01E01"; "play" for one not in it. */
    plays: Map<String, HeroPlay> = emptyMap(),
    onMoreInfo: ((String) -> Unit)? = null,
    /** The first button ([startOver] false) or "start over" of the title
     *  with that id. */
    onPlay: ((itemId: String, startOver: Boolean) -> Unit)? = null,
    /** The Trailer of the title with that id: its [TrailerChoice]. */
    onTrailer: ((String, TrailerChoice) -> Unit)? = null,
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
        val dots = pool.size > 1
        // CDP-verified: web hero image is 500px tall (chino-web's
        // HeroSection has h-[500px] on md+). Tablet's wide hero was
        // previously 420 — too short, made the hero feel cramped. A phone's
        // is as tall as its picture and its buttons under it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isWide) Modifier.height(500.dp) else Modifier)
                // CDP-verified: web hero is `rounded-xl` = 12px, not 16.
                .clip(RectangleShape)
                // CDP-verified: web hero card uses `bg-black` = pure
                // black (rgb 0,0,0), not the canvas color. The mask on
                // the backdrop image fades to this black, which is the
                // intentional darker shade behind the title column, and
                // the buttons sit on it.
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = if (isWide) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) { page ->
                HeroContent(
                    item = pool[page],
                    play = plays[pool[page].id],
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    isWide = isWide,
                    dots = dots,
                    onMoreInfo = onMoreInfo,
                    onPlay = onPlay,
                    onTrailer = onTrailer,
                )
            }
            if (dots) {
                // CDP-verified pagination dots: ALL dots are 8×8 circles
                // (`w-2 h-2 rounded-full`). Active = opaque white
                // (`bg-white`), inactive = 30% white (`bg-white/30`).
                // Web uses `flex gap-1.5` = 6px between dots (not 8px /
                // gap-2). Position: start = 48dp aligns under the Play
                // button (Play sits at hero left padding 16 + text-
                // column padding 32 = 48dp). Bottom inset 48dp matches
                // web `bottom-12`. On a phone, a row of their own under
                // the buttons.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = if (isWide) 48.dp else 16.dp, bottom = if (isWide) 48.dp else 16.dp)
                        .height(if (isWide) 8.dp else HERO_DOTS_ROW),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pool.indices.forEach { i ->
                        val isActive = i == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RectangleShape)
                                .background(
                                    // Solid over the artwork, as nothing
                                    // translucent is.
                                    if (isActive) Color.White else cloud.nalet.chino.mobile.ui.theme.ChinoBorderControl,
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
    play: HeroPlay?,
    baseUrl: String,
    streamToken: String,
    isWide: Boolean,
    dots: Boolean,
    onMoreInfo: ((String) -> Unit)?,
    onPlay: ((String, Boolean) -> Unit)?,
    onTrailer: ((String, TrailerChoice) -> Unit)?,
) {
    if (isWide) HeroContentWide(item, play, baseUrl, streamToken, onMoreInfo, onPlay, onTrailer)
    else HeroContentNarrow(item, play, baseUrl, streamToken, dots, onMoreInfo, onPlay, onTrailer)
}

/** Between a phone's hero picture and the buttons under it. */
internal val HERO_PICTURE_GAP = 16.dp

/** The rotation dots' row under a phone's hero buttons. */
private val HERO_DOTS_ROW = 24.dp

/** Where a tablet's hero picture starts, as a share of the hero's width:
 *  the buttons' column ends before it (chino-web's md:w-[55%]). */
internal const val HERO_PICTURE_START = 0.45f

/** The room at the sides of the hero's labelled buttons on a phone, where
 *  three share a line. */
private val HERO_BUTTON_PADDING_NARROW = 12.dp

@Composable
private fun HeroContentNarrow(
    item: Item,
    play: HeroPlay?,
    baseUrl: String,
    streamToken: String,
    /** The rotation dots take a row under the buttons. */
    dots: Boolean,
    onMoreInfo: ((String) -> Unit)?,
    onPlay: ((String, Boolean) -> Unit)?,
    onTrailer: ((String, TrailerChoice) -> Unit)?,
) {
    // Phone hero after chino-web's mobile hero: the backdrop the hero's
    // width with the title over its top-left, the buttons UNDER it on the
    // hero's black - never on the picture (docs/chino title actions) - and
    // the rotation dots under them.
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = "$baseUrl/v1/items/${item.id}/backdrop?stream=$streamToken",
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Dark at the top for the title, into the hero's black at the
            // bottom.
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.0f to Color(0xCC000000),
                        0.32f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1.0f to Color.Black,
                    ),
                ),
            )
            // Title + chip overlaid top-left (web: x=32, y=96, text-2xl=24px,
            // an h1 clamped to two lines with an ellipsis).
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
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = ChinoHeading,
                )
                YearRatingChipRow(item)
            }
        }
        // play · see details · trailer: the title actions, 48 dp and 8 dp
        // apart, in one line on a phone - the trailer its icon alone, as the
        // web's is below sm - wrapping between buttons where even so they
        // don't fit (a large font).
        FlowRow(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = HERO_PICTURE_GAP),
            horizontalArrangement = Arrangement.spacedBy(TITLE_ACTION_GAP),
            verticalArrangement = Arrangement.spacedBy(TITLE_ACTION_GAP),
        ) {
            HeroButtons(item, play, narrow = true, onMoreInfo = onMoreInfo, onPlay = onPlay, onTrailer = onTrailer)
        }
        Box(modifier = Modifier.height(16.dp + if (dots) HERO_DOTS_ROW else 0.dp))
    }
}

@Composable
private fun HeroContentWide(
    item: Item,
    play: HeroPlay?,
    baseUrl: String,
    streamToken: String,
    onMoreInfo: ((String) -> Unit)?,
    onPlay: ((String, Boolean) -> Unit)?,
    onTrailer: ((String, TrailerChoice) -> Unit)?,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // CDP-verified: web backdrop image is `md:w-[55%] md:max-w-[1100px]
        // h-full object-cover object-center` — anchored right, full-height,
        // *cropped* to fill the box (no letterboxing). The mask
        // `linear-gradient(to_left, black 0% 60%, transparent 100%)`
        // fades the image into the canvas on its left edge — Compose
        // can't cheaply apply mask-image so a left-side overlay
        // gradient achieves the same visual.
        AsyncImage(
            model = "$baseUrl/v1/items/${item.id}/backdrop?stream=$streamToken",
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(1f - HERO_PICTURE_START)
                .align(Alignment.CenterEnd),
        )
        // Left-to-right BLACK-to-transparent overlay: opaque black where
        // the text column and the buttons are, then the picture's left
        // edge fades in.
        //   0 → 45 %   : opaque black (the dark text-column inset)
        //   45 → 67 %  : fade from black to transparent (image emerges)
        //   67 → 100 % : transparent (image fully visible)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color.Black,
                        HERO_PICTURE_START to Color.Black,
                        0.67f to Color.Transparent,
                        1.0f to Color.Transparent,
                    ),
                ),
        )
        HeroTextAndButtons(
            item = item,
            play = play,
            buttonsWidth = (maxWidth * HERO_PICTURE_START - HERO_WIDE_START - 16.dp).coerceAtLeast(TITLE_ACTION_HEIGHT),
            onMoreInfo = onMoreInfo,
            onPlay = onPlay,
            onTrailer = onTrailer,
        )
    }
}

/** The wide hero's text column's start and top, and its buttons' room
 *  under them (web: the text at y=146, the buttons ending at y=460 of a
 *  500-tall hero). */
private val HERO_WIDE_START = 48.dp
private val HERO_WIDE_TOP = 48.dp
private val HERO_WIDE_BOTTOM = 72.dp

/** At least this between the text column's last line and the buttons. */
internal val HERO_TEXT_BUTTONS_GAP = 24.dp

/** The wide hero's overview: at most this many lines, 28 sp each. */
private const val HERO_OVERVIEW_LINES = 3
private val HERO_OVERVIEW_LINE = 28.sp

/**
 * The wide hero's text column - title, year and rating, overview - from the
 * top (max 476 dp, web's 475.5 px), and its buttons pinned to the column's
 * foot, left of the picture: their column ends before it starts, and they
 * wrap inside it, never onto the still. Where they wrap into more rows than
 * the column has room for under the text - a narrow tablet, a large font -
 * the overview gives up whole lines, so nothing is ever drawn over it.
 */
@Composable
private fun HeroTextAndButtons(
    item: Item,
    play: HeroPlay?,
    buttonsWidth: Dp,
    onMoreInfo: ((String) -> Unit)?,
    onPlay: ((String, Boolean) -> Unit)?,
    onTrailer: ((String, TrailerChoice) -> Unit)?,
) {
    SubcomposeLayout(modifier = Modifier.fillMaxSize()) { constraints ->
        val start = HERO_WIDE_START.roundToPx()
        val top = HERO_WIDE_TOP.roundToPx()
        val textWidth = (constraints.maxWidth - start - 16.dp.roundToPx()).coerceIn(0, 476.dp.roundToPx())
        val buttons = subcompose("buttons") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TITLE_ACTION_GAP),
                verticalArrangement = Arrangement.spacedBy(TITLE_ACTION_GAP),
            ) {
                HeroButtons(item, play, narrow = false, onMoreInfo = onMoreInfo, onPlay = onPlay, onTrailer = onTrailer)
            }
        }.map { it.measure(Constraints(maxWidth = buttonsWidth.roundToPx())) }
        val head = subcompose("head") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // h1.text-5xl = 48px/48 line-height/700, clamped to two
                // lines. The web's face, JetBrains Mono Bold, is bundled: no
                // heavier weight to make up for another font.
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 48.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = ChinoHeading,
                )
                YearRatingChipRow(item)
            }
        }.map { it.measure(Constraints(maxWidth = textWidth)) }
        val buttonsHeight = buttons.maxOfOrNull { it.height } ?: 0
        val buttonsTop = constraints.maxHeight - HERO_WIDE_BOTTOM.roundToPx() - buttonsHeight
        val overviewTop = top + (head.maxOfOrNull { it.height } ?: 0) + 12.dp.roundToPx()
        val room = buttonsTop - HERO_TEXT_BUTTONS_GAP.roundToPx() - overviewTop
        // As many of its three lines as fit above the buttons.
        val lines = (room / HERO_OVERVIEW_LINE.toPx()).toInt().coerceIn(0, HERO_OVERVIEW_LINES)
        // text-lg = 18px/28 line-height on tablet (lg+). #C9D1D9 matches
        // text-[#c9d1d9]. Hidden when the API returns none (the catalog
        // has no description for the title).
        val overview = item.overview?.takeIf { lines > 0 }?.let { text ->
            subcompose("overview") {
                Text(
                    text = text,
                    color = ChinoFg2,
                    fontSize = 18.sp,
                    lineHeight = HERO_OVERVIEW_LINE,
                    maxLines = lines,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }.map { it.measure(Constraints(maxWidth = textWidth)) }
        }.orEmpty()
        layout(constraints.maxWidth, constraints.maxHeight) {
            head.forEach { it.place(start, top) }
            overview.forEach { it.place(start, overviewTop) }
            buttons.forEach { it.place(start, buttonsTop) }
        }
    }
}

/**
 * The hero's buttons, the title actions as on a title's page: "play" - or
 * "resume 0:58" and "start over" for a title partly watched, a series'
 * episode by its code ([play]) - dark on the accent; "see details" and
 * "trailer" on the plain surface - 48 dp, solid, the labels in the mono
 * face. The trailer by its page's rule (trailerChoice), with the trailer's
 * one icon: this server's trailer plays, a link opens; without either, no
 * button. On a phone ([narrow]) the labelled ones have less room at their
 * sides and the trailer is its icon alone, named "trailer", so play, see
 * details and trailer share one line; with "start over" they wrap between
 * buttons.
 */
@Composable
private fun HeroButtons(
    item: Item,
    play: HeroPlay?,
    narrow: Boolean,
    onMoreInfo: ((String) -> Unit)?,
    onPlay: ((String, Boolean) -> Unit)?,
    onTrailer: ((String, TrailerChoice) -> Unit)?,
) {
    val padding = if (narrow) HERO_BUTTON_PADDING_NARROW else TITLE_ACTION_PADDING
    TitleActionButton(
        label = play?.label ?: "play",
        icon = Lucide.Play,
        primary = true,
        onClick = { onPlay?.invoke(item.id, false) },
        horizontalPadding = padding,
    )
    if (play?.resumes == true) {
        TitleActionButton(
            label = "start over",
            icon = null,
            primary = false,
            onClick = { onPlay?.invoke(item.id, true) },
            horizontalPadding = padding,
        )
    }
    TitleActionButton(
        label = "see details",
        icon = null,
        primary = false,
        onClick = { onMoreInfo?.invoke(item.id) },
        horizontalPadding = padding,
    )
    val choice = trailerChoice(item) ?: return
    if (narrow) {
        TitleIconButton(
            icon = TRAILER_ICON,
            name = "trailer",
            on = false,
            onFill = ChinoSurfaceHi,
            onClick = { onTrailer?.invoke(item.id, choice) },
        )
    } else {
        TitleActionButton(
            label = "trailer",
            icon = TRAILER_ICON,
            primary = false,
            onClick = { onTrailer?.invoke(item.id, choice) },
            horizontalPadding = padding,
        )
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
                    .clip(RectangleShape)
                    .background(ChinoCloudBlue)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = ((r * 10).toInt() / 10.0).toString(),
                    color = ChinoOnFill,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
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
    // Watchlist hub: list shelves can contain EPISODES; when true, episode
    // items swap the year•rating subtitle for the SxxEyy badge (same idiom
    // as the Continue Watching cards). Home rails leave this off.
    episodeBadges: Boolean = false,
    // #188: per-card watched toggle. When set, each card's overflow menu gains
    // a Mark-watched/Mark-unwatched item; the callback receives the item id and
    // owns the optimistic update (Home rails drop the card; Browse flips the
    // badge). null leaves the toggle off the menu.
    onToggleWatched: ((String) -> Unit)? = null,
) {
    // CDP-verified: title → LazyRow gap = 16dp (web `mb-4`).
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // CDP-verified shelf title: `h2.text-2xl font-semibold` =
        // 24px / 32 line / 600, in the heading face. The watchlist hub's
        // opens its list and is a <button> on the web, so Inter there.
        Text(
            text = title,
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            style = if (onTitleClick == null) ChinoHeading else LocalTextStyle.current,
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
                        episodeBadge = if (episodeBadges) episodeBadgeFor(item) else null,
                        cardWidth = cardWidth,
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
            .clip(RectangleShape)
            .background(ChinoSurface)
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
                        .clip(RectangleShape)
                        .background(ChinoBorder2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.ChevronRight,
                        contentDescription = null,
                        tint = ChinoCloudBlue,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = "See all",
                    color = ChinoCloudBlue,
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
data class EpisodeBadge(
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    /** The last episode number of a file that holds several: S03E18–21. */
    val episodeEnd: Int? = null,
)

/** SxxEyy coordinates for an EPISODE catalogue item, or null for anything
 *  else (or an episode missing its numbers). Used by surfaces whose item
 *  lists can contain episodes — the watchlist hub shelves + per-list grid —
 *  so saved episodes carry the same badge the Continue Watching rail shows.
 *  The card title stays the episode's own title, so no episodeTitle here
 *  (it would just duplicate the line above). */
fun episodeBadgeFor(item: Item): EpisodeBadge? =
    if (item.kind == "episode" && item.seasonNumber != null && item.episodeNumber != null) {
        EpisodeBadge(season = item.seasonNumber, episode = item.episodeNumber, episodeTitle = null, episodeEnd = item.episodeEnd)
    } else null

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
    // When set, the card surfaces a "Remove from Continue Watching" action
    // in its menu, on a long-press of the card body. Only the in-progress Continue Watching rail wires this (web:
    // MediaCard.tsx `onRemoveFromContinueWatching`); other rails leave it
    // null and the affordance never appears.
    onRemoveFromContinueWatching: (() -> Unit)? = null,
    // #188: when set, the card's overflow menu gains a watched toggle. The
    // label flips on `item.watchedAt`: "Mark as unwatched" / "Mark as
    // watched", by the check that means watched. The callback reuses the
    // watched POST/DELETE of the title page's watched button; the caller owns
    // the optimistic update (drop from a Home rail / flip the Browse badge).
    // Available on ALL card surfaces, not just Continue Watching — wiring this
    // is what gives a rail that doesn't dismiss its long-press menu.
    onToggleWatched: (() -> Unit)? = null,
    // A line under the year/rating naming someone's credit on the title — a
    // person's roles on a filmography card ("Director · Writer"). chino-web
    // MediaCard's `credit`. Two lines are held whatever it says, so a grid
    // row of filmography cards stays one height.
    credit: String? = null,
) {
    // Either action wires the overflow affordance. When ONLY the watched
    // toggle is wired (Home rails / Browse grid), the menu still appears.
    val hasMenu = onRemoveFromContinueWatching != null || onToggleWatched != null
    // Drives the card's menu, opened by a long-press on the card (no button
    // drawn over the artwork).
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
            // Dp.Unspecified fills the cell instead (a fixed-column grid).
            .then(if (cardWidth.isSpecified) Modifier.width(cardWidth) else Modifier.fillMaxWidth())
            .clip(RectangleShape)
            .background(ChinoSurface)
            .let { base ->
                // Long-press opens the overflow menu when any menu action is
                // wired (phone-appropriate affordance, web parity for the
                // ⋮ menu). Falls back to a plain clickable otherwise.
                when {
                    onClick != null && hasMenu ->
                        base.combinedClickable(
                            onClick = onClick,
                            onLongClick = { menuOpen = true },
                            onLongClickLabel = "More options",
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
            PosterImage(
                model = posterUrl,
                title = item.title,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Its status marks: in the Watchlist, watched.
            CardStatusMarks(itemId = item.id, watched = item.watchedAt != null)
            // The card's menu - mark watched, take off Continue Watching - on
            // a long-press of the card: nothing is drawn over the artwork but
            // its status marks, the saved bookmark, the watched check and the
            // progress bar (the design system's title actions).
            if (hasMenu) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp)) {
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
                                        imageVector = Lucide.Check,
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
                        .background(ChinoBorder),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0f, 100f) / 100f)
                            .background(ChinoCloudBlue),
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
            // The web's h3: the heading face, one line.
            Text(
                text = item.title,
                color = ChinoFg2,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = ChinoHeading,
            )
            if (episodeBadge != null) {
                // Web: SxxExx in #58a6ff, · separator + episode title in
                // #8b949e, 14px/20. MediaCard.tsx L205-215.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = episodeCode(episodeBadge.season, episodeBadge.episode, episodeBadge.episodeEnd),
                        color = ChinoCloudBlue,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    episodeBadge.episodeTitle?.let { epTitle ->
                        Text("·", color = ChinoMuted, fontSize = 14.sp, lineHeight = 20.sp)
                        Text(
                            text = epTitle,
                            color = ChinoMuted,
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
                            Text(it.toString(), color = ChinoMuted, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                        item.rating?.let { r ->
                            if (item.year != null) {
                                Text("•", color = ChinoMuted, fontSize = 14.sp, lineHeight = 20.sp)
                            }
                            Text(
                                text = ((r * 10).toInt() / 10.0).toString(),
                                color = ChinoCloudBlue,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            )
                        }
                    }
                } else if (credit != null) {
                    // Hold the meta line's height so the credit lines align.
                    Box(modifier = Modifier.height(20.dp))
                }
            }
            if (credit != null) {
                Text(
                    text = credit,
                    color = ChinoMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    minLines = 2,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
