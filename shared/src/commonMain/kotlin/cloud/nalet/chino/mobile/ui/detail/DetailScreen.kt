package cloud.nalet.chino.mobile.ui.detail

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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.Trailer
import cloud.nalet.chino.mobile.ui.player.PlayerScreen
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Youtube
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Star

/**
 * Item detail page. Mirrors chino-web's DetailPage.tsx: full-bleed backdrop
 * hero, poster + content row overlapping the backdrop, title +
 * year • runtime • rating • type chip meta row, Resume/Start over +
 * Watchlist/Watched/Like circular toggles, overview, and a Subtitles /
 * Analyzed footer grid.
 */
class DetailScreen(private val itemId: String) : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val nav = LocalNavigator.currentOrThrow
        val container = LocalAppContainer.current
        val model = remember(itemId) { DetailScreenModel(container, itemId) }
        val state by model.state.collectAsState()
        val watchlist by container.userFlags.watchlist.collectAsState()
        val likes by container.userFlags.likes.collectAsState()
        val watchedOverride by model.watchedOverride.collectAsState()
        val episodeWatched by model.episodeWatched.collectAsState()
        // Lists-aware "saved" signal: in the DEFAULT list (back-compat set) OR
        // in any named list (membership cache). Drives the filled watchlist
        // icon + seeds the add-to-list picker checkmarks.
        val lists by container.watchlists.lists.collectAsState()
        val memberships by container.watchlists.memberships.collectAsState()
        val addToListError by model.addToListError.collectAsState()
        val itemMemberships = memberships[itemId] ?: emptySet()
        val inAnyList = itemId in watchlist || itemMemberships.isNotEmpty()
        // Controls the add-to-list picker (long-press / caret on the toggle).
        var showAddToList by remember(itemId) { mutableStateOf(false) }

        Scaffold(containerColor = MaterialTheme.colorScheme.background) { _ ->
            when (val s = state) {
                DetailUiState.Loading -> Center { CircularProgressIndicator() }
                is DetailUiState.Error -> Center { Text(s.message, color = MaterialTheme.colorScheme.error) }
                is DetailUiState.Ready -> {
                    // Effective watched state: the user's optimistic flip this
                    // session wins; otherwise follow the server-stamped
                    // watched_at. Mirrors chino-web's `watchedOverride ?? !!watched_at`.
                    val watched = watchedOverride ?: (s.item.watchedAt != null)
                    ReadyContent(
                        ready = s,
                        inWatchlist = inAnyList,
                        liked = itemId in likes,
                        watched = watched,
                        episodeWatched = episodeWatched,
                        onBack = { nav.pop() },
                        onPlay = { resume ->
                            nav.push(PlayerScreen(itemId = itemId, fromStart = !resume))
                        },
                        // Plain tap: when the item is in NO list, add it to the
                        // default list (casual users never see the picker); when
                        // it's already saved somewhere, a plain tap removes it
                        // from the default list. The caret / long-press opens the
                        // full add-to-list picker.
                        onToggleWatchlist = { model.toggleDefaultWatchlist() },
                        onOpenAddToList = { showAddToList = true },
                        onToggleLike = { container.userFlags.setLike(itemId, itemId !in likes) },
                        onToggleWatched = { model.toggleWatched(watched) },
                        onToggleEpisodeWatched = { epId, epWatched -> model.toggleEpisodeWatched(epId, epWatched) },
                        onItemNavigate = { id -> nav.push(DetailScreen(id)) },
                        onPersonNavigate = { personId ->
                            nav.push(cloud.nalet.chino.mobile.ui.person.PersonScreen(personId))
                        },
                        onEpisodePlay = { episodeId ->
                            nav.push(PlayerScreen(itemId = episodeId, fromStart = false))
                        },
                    )
                    if (showAddToList) {
                        // Seed checkmarks from BOTH the membership cache and the
                        // back-compat default-list set: the default list's id
                        // lives in [lists], and an item the user added via the
                        // plain-tap path is in `watchlist` but might not be in
                        // the membership cache yet.
                        val defaultListId = lists.firstOrNull { it.isDefault }?.id
                        val checked = buildSet {
                            addAll(itemMemberships)
                            if (itemId in watchlist && defaultListId != null) add(defaultListId)
                        }
                        AddToListSheet(
                            lists = lists,
                            checkedListIds = checked,
                            createError = addToListError,
                            onToggle = { listId, isChecked ->
                                model.toggleListMembership(listId, isChecked)
                                // Keep the back-compat default-list flag in sync
                                // when the picker toggles the default list, so
                                // Zap + card badges follow.
                                if (listId == defaultListId) {
                                    container.userFlags.setWatchlist(itemId, isChecked)
                                }
                            },
                            onCreateAndAdd = { name -> model.createListAndAdd(name) },
                            onDismiss = {
                                model.clearAddToListError()
                                showAddToList = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    ready: DetailUiState.Ready,
    inWatchlist: Boolean,
    liked: Boolean,
    watched: Boolean,
    episodeWatched: Map<String, Boolean>,
    onBack: () -> Unit,
    onPlay: (resume: Boolean) -> Unit,
    onToggleWatchlist: () -> Unit,
    onOpenAddToList: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleEpisodeWatched: (episodeId: String, currentlyWatched: Boolean) -> Unit,
    onItemNavigate: ((String) -> Unit)? = null,
    onPersonNavigate: ((String) -> Unit)? = null,
    onEpisodePlay: ((String) -> Unit)? = null,
) {
    val item = ready.item
    val uriHandler = LocalUriHandler.current
    // Resolved trailer URL (null when none) — drives both the pill's
    // visibility and its click. Mirrors web/TV pickTrailer.
    val trailerUrl = pickTrailer(item.trailers)
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(ChinoBg2)) {
        // Backdrop = 21:9 but capped at 60vh (web `max-h-[60vh]`) so in
        // landscape the action row stays near the fold instead of the tall
        // 21:9 image eating the whole screen.
        val backdropH = minOf(maxWidth * 9f / 21f, maxHeight * 0.6f)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // Backdrop hero — web uses `aspect-[21/9] max-h-[60vh]`.
            Box(modifier = Modifier.fillMaxWidth().height(backdropH)) {
                AsyncImage(
                    model = "${ready.baseUrl}/v1/items/${item.id}/backdrop?stream=${ready.streamToken}",
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Web: `bg-gradient-to-t from-[#0d1117] via-[#0d1117]/40
                // to-transparent`. The 70% backdrop opacity is already
                // baked into the AsyncImage by darkening via this overlay.
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.5f to Color(0x66000000),
                            1.0f to ChinoBg2,
                        ),
                    ),
                )
            }
            // Content overlaps the backdrop by -128dp (web: -mt-32 = -128px).
            // Responsive: side-by-side poster | content on wide (tablet /
            // phone-landscape), STACKED poster-then-content on a narrow phone —
            // matching chino-web (CDP-verified at 411px: poster 192x288 top-
            // left, then full-width title/meta/actions below). The old layout
            // forced the Row even on a phone, cramping the content column to
            // ~200dp so the title wrapped mid-word and the rating overflowed.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isWideDetail = maxWidth >= 600.dp
                val poster: @Composable () -> Unit = {
                    AsyncImage(
                        model = "${ready.baseUrl}/v1/items/${item.id}/poster?stream=${ready.streamToken}",
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(192.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RectangleShape)
                            .background(ChinoSurface),
                    )
                }
                val content: @Composable (Modifier, androidx.compose.ui.unit.TextUnit) -> Unit = { mod, titleSize ->
                    Column(modifier = mod, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = titleSize,
                            lineHeight = (titleSize.value * 1.12f).sp,
                            fontWeight = FontWeight.Bold,
                        )
                        item.tagline?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                color = ChinoMuted,
                                fontSize = 16.sp,
                                fontStyle = FontStyle.Italic,
                            )
                        }
                        MetaRow(item)
                        if (item.genres.isNotEmpty()) {
                            GenreChips(item.genres)
                        }
                        ActionRow(
                            resumeSec = ready.resumePositionSec,
                            inWatchlist = inWatchlist,
                            liked = liked,
                            watched = watched,
                            isSeries = item.kind == "series",
                            hasTrailer = trailerUrl != null,
                            onResume = { onPlay(true) },
                            onPlay = { onPlay(false) },
                            onStartOver = { onPlay(false) },
                            onTrailer = {
                                trailerUrl?.let { uriHandler.openUri(it) }
                            },
                            onToggleWatchlist = onToggleWatchlist,
                            onOpenAddToList = onOpenAddToList,
                            onToggleWatched = onToggleWatched,
                            onToggleLike = onToggleLike,
                        )
                        item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                            Text(
                                text = overview,
                                color = ChinoFg2,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                            )
                        }
                        StarringRow(item, onPersonNavigate)
                        DirectorsRow(item, onPersonNavigate)
                        FooterGrid(item)
                    }
                }
                if (isWideDetail) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = (-128).dp)
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        poster()
                        content(Modifier.padding(top = 16.dp).widthIn(max = 768.dp), 36.sp)
                    }
                } else {
                    // Phone: stack. Poster top-left over the backdrop, then the
                    // full-width content below (title text-2xl ~28sp like web).
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = (-128).dp)
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        poster()
                        content(Modifier.fillMaxWidth(), 28.sp)
                    }
                }
            }
            // Sections below the hero — sit OUTSIDE the offset-overlap
            // Row so they don't get clipped. Same horizontal padding so
            // headings line up with the content column above.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .offset(y = (-96).dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                if (ready.seasons.isNotEmpty()) {
                    EpisodesSection(
                        seasons = ready.seasons,
                        baseUrl = ready.baseUrl,
                        streamToken = ready.streamToken,
                        episodeWatched = episodeWatched,
                        onEpisodePlay = { id -> onEpisodePlay?.invoke(id) },
                        onToggleEpisodeWatched = onToggleEpisodeWatched,
                    )
                }
                if (ready.similar.isNotEmpty()) {
                    MoreLikeThisSection(
                        items = ready.similar,
                        baseUrl = ready.baseUrl,
                        streamToken = ready.streamToken,
                        onItemClick = { id -> onItemNavigate?.invoke(id) },
                    )
                }
                Box(modifier = Modifier.height(32.dp))
            }
        }
        // Back button — overlays the backdrop top-left. Web:
        // `absolute top-4 left-4 p-2 rounded-full bg-black/50`. The
        // `windowInsetsPadding(statusBars)` pushes the button below the
        // system status bar (clock + battery) when running edge-to-edge;
        // without it the back chip collides with the system bar on
        // tablets and looks clipped. Same fix as the player's TopBar.
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, top = 16.dp)
                .size(40.dp)
                .clip(RectangleShape)
                .background(Color(0x80000000))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.ArrowLeft,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** year • runtime • ⭐ rating • TYPE chip — matches DetailPage.tsx L136-158. */
@Composable
private fun MetaRow(item: Item) {
    val runtimeMin = item.durationMs?.let { (it / 60_000L).toInt() } ?: 0
    val runtimeText = when {
        runtimeMin <= 0 -> null
        runtimeMin >= 60 -> "${runtimeMin / 60}h ${runtimeMin % 60}m"
        else -> "${runtimeMin}m"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item.year?.let {
            Text(it.toString(), color = ChinoFg2, fontSize = 14.sp)
        }
        runtimeText?.let {
            if (item.year != null) Bullet()
            Text(it, color = ChinoFg2, fontSize = 14.sp)
        }
        item.rating?.let { r ->
            if (item.year != null || runtimeText != null) Bullet()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Lucide.Star,
                    contentDescription = null,
                    tint = ChinoCloudBlue,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = ((r * 10).toInt() / 10.0).toString(),
                    color = ChinoFg2,
                    fontSize = 14.sp,
                )
            }
        }
        item.kind?.takeIf { it.isNotBlank() }?.let {
            Box(
                modifier = Modifier
                    .clip(RectangleShape)
                    .background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = it.uppercase(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun Bullet() {
    Text("•", color = ChinoMuted, fontSize = 14.sp)
}

@Composable
private fun ActionRow(
    resumeSec: Int,
    inWatchlist: Boolean,
    liked: Boolean,
    watched: Boolean,
    isSeries: Boolean = false,
    hasTrailer: Boolean = false,
    onResume: () -> Unit,
    onPlay: () -> Unit,
    onStartOver: () -> Unit,
    onTrailer: () -> Unit = {},
    onToggleWatchlist: () -> Unit,
    onOpenAddToList: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleLike: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            // Series: no series-level Play; show Trailer pill when one
            // exists (web: DetailPage.tsx L199-210 — same gate).
            isSeries -> {
                if (hasTrailer) {
                    PillButton(
                        label = "Trailer",
                        icon = Lucide.Youtube,
                        primary = false,
                        onClick = onTrailer,
                    )
                }
            }
            resumeSec > 30 -> {
                PillButton(
                    label = "Resume ${fmtDur(resumeSec)}",
                    icon = Lucide.Play,
                    primary = true,
                    onClick = onResume,
                )
                PillButton(label = "Start over", icon = null, primary = false, onClick = onStartOver)
            }
            else -> {
                PillButton(label = "Play", icon = Lucide.Play, primary = true, onClick = onPlay)
            }
        }
        // Circular icon-only buttons. Web: `p-2.5 rounded-full bg-white/10`,
        // pressed state recolours to emerald (watchlist/watched) or rose (like).
        // The watchlist button is now an ADD-TO-LIST control: plain tap adds to
        // the default list (filled = in >=1 list), long-press OR the trailing
        // caret opens the per-list picker.
        WatchlistButton(
            inAnyList = inWatchlist,
            onToggle = onToggleWatchlist,
            onOpenPicker = onOpenAddToList,
        )
        // Real watched TOGGLE (web: useWatchedToggle). Filled emerald +
        // Check when watched, neutral Eye outline when not — tap flips it
        // (POST to mark, DELETE to un-mark).
        CircleIconButton(
            icon = if (watched) Lucide.Check else Lucide.Eye,
            tint = Color.White,
            background = if (watched) ChinoGreen else Color.White.copy(alpha = 0.1f),
            onClick = onToggleWatched,
        )
        CircleIconButton(
            icon = Lucide.Heart,
            tint = Color.White,
            background = if (liked) Color(0xFFE11D48) else Color.White.copy(alpha = 0.1f),
            onClick = onToggleLike,
        )
    }
}

@Composable
private fun PillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RectangleShape)
            .background(if (primary) ChinoCloudBlue else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    background: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RectangleShape)
            .background(background)
            .clickable(onClick = onClick),
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

/** Add-to-list control. Plain tap toggles the default list; a long-press on
 *  the circle OR a tap on the trailing caret opens the per-list picker. Filled
 *  emerald + Check when the item is in at least one list, neutral Plus when
 *  not — matching the other action-row toggles. */
@Composable
private fun WatchlistButton(
    inAnyList: Boolean,
    onToggle: () -> Unit,
    onOpenPicker: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RectangleShape)
                .background(if (inAnyList) ChinoGreen else Color.White.copy(alpha = 0.1f))
                .combinedClickable(onClick = onToggle, onLongClick = onOpenPicker),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (inAnyList) Lucide.Check else Lucide.Plus,
                contentDescription = if (inAnyList) "In your lists" else "Add to watchlist",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        // Caret affordance so the picker is discoverable without a long-press.
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RectangleShape)
                .background(Color.White.copy(alpha = 0.1f))
                .clickable(onClick = onOpenPicker),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.ChevronDown,
                contentDescription = "Add to list",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Subtitles / Analyzed two-column footer. Each column only renders when
 *  the underlying data is non-empty, matching web's null-guards. */
@Composable
private fun FooterGrid(item: Item) {
    val subtitleLabel = item.subtitles
        .mapNotNull { it.label?.takeIf { l -> l.isNotBlank() } ?: it.lang.takeIf { it.isNotBlank() } }
        .distinct()
        .joinToString(", ")
        .takeIf { it.isNotBlank() }
    val analyzedLabel = item.segments?.takeIf { it.count > 0 }?.let { seg ->
        listOfNotNull(
            "Intro".takeIf { seg.hasIntro },
            "Credits".takeIf { seg.hasCredits },
            "Recap".takeIf { seg.hasRecap },
        ).joinToString(" · ").ifEmpty { "Segments available" }
    }
    if (subtitleLabel == null && analyzedLabel == null) return
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        subtitleLabel?.let { FooterColumn("Subtitles", it) }
        analyzedLabel?.let { FooterColumn("Analyzed", it) }
    }
}

@Composable
private fun FooterColumn(header: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = header, color = ChinoMuted, fontSize = 14.sp)
        Text(
            text = value,
            color = ChinoFg2,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun fmtDur(s: Int): String {
    if (s < 0) return "0:00"
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** Pill chip strip of genre tags. Web: DetailPage.tsx L160-170 —
 *  `px-3 py-1 rounded-full bg-[#21262d] text-[#c9d1d9] text-xs border
 *  border-[#30363d]`. Wraps when the row overflows. */
@Composable
private fun GenreChips(genres: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        genres.forEach { g ->
            Box(
                modifier = Modifier
                    .clip(RectangleShape)
                    .background(ChinoBorder2)
                    .border(
                        BorderStroke(1.dp, ChinoBorder),
                        RectangleShape,
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(text = g, color = ChinoFg2, fontSize = 12.sp)
            }
        }
    }
}

/** "Starring" section — cast names. Each name with a person_id is a tap
 *  target → the Person / Filmography surface; names without an id render as
 *  plain text. Web: DetailPage.tsx L249-253. Shown above the
 *  Subtitles/Analyzed footer. */
@Composable
private fun StarringRow(item: Item, onPersonNavigate: ((String) -> Unit)?) {
    val actors = item.cast.filter { it.role == null || it.role.equals("actor", true) }
    if (actors.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Starring", color = ChinoMuted, fontSize = 13.sp)
        CastNameFlow(names = actors.take(8), onPersonNavigate = onPersonNavigate)
    }
}

/** Prefer the most-likely "Official Trailer" YouTube entry; fall back to
 *  the first. Mirrors chino-web's pickTrailer (DetailPage.tsx) and the TV
 *  client. Returns the resolved trailer URL, or null when none exists. */
private fun pickTrailer(trailers: List<Trailer>): String? {
    if (trailers.isEmpty()) return null
    val yt = trailers.filter { (it.site ?: "").contains("youtube", ignoreCase = true) }
    val pool = if (yt.isNotEmpty()) yt else trailers
    val picked = pool.firstOrNull {
        val t = it.title.orEmpty()
        t.contains("official", ignoreCase = true) && t.contains("trailer", ignoreCase = true)
    } ?: pool.firstOrNull { it.title.orEmpty().contains("trailer", ignoreCase = true) }
        ?: pool.first()
    return picked.url
}

/** "Director(s)" section — cast entries whose role is "director"
 *  (case-insensitive). Each name with a person_id deep-links to the Person
 *  surface. Label pluralises with the count. Web/TV parity. Rendered only
 *  when at least one director is present. */
@Composable
private fun DirectorsRow(item: Item, onPersonNavigate: ((String) -> Unit)?) {
    val directors = item.cast.filter { it.role.equals("director", true) }
    if (directors.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (directors.size > 1) "Directors" else "Director",
            color = ChinoMuted,
            fontSize = 13.sp,
        )
        CastNameFlow(names = directors, onPersonNavigate = onPersonNavigate)
    }
}

/** Comma-separated cast names where each entry is individually tappable when
 *  it carries a person_id. A trailing comma is appended to every name except
 *  the last so the row reads like the old "A, B, C" join. Names without an
 *  id (or when navigation isn't wired) render as inert text. */
@Composable
private fun CastNameFlow(
    names: List<cloud.nalet.chino.mobile.data.model.CastMember>,
    onPersonNavigate: ((String) -> Unit)?,
) {
    FlowRow(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        names.forEachIndexed { index, member ->
            val label = member.name + if (index < names.lastIndex) ", " else ""
            val pid = member.personId
            val tappable = pid != null && onPersonNavigate != null
            Text(
                text = label,
                color = if (tappable) ChinoCloudBlue else ChinoFg2,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = if (tappable) {
                    Modifier.clickable { onPersonNavigate!!(pid!!) }
                } else {
                    Modifier
                },
            )
        }
    }
}

/** Episodes accordion — one collapsible row per season, expanded into
 *  a vertical list of episodes with poster + title + overview. Mirrors
 *  chino-web's EpisodesList.tsx layout. */
@Composable
private fun EpisodesSection(
    seasons: List<cloud.nalet.chino.mobile.data.api.Season>,
    baseUrl: String,
    streamToken: String,
    episodeWatched: Map<String, Boolean>,
    onEpisodePlay: (String) -> Unit,
    onToggleEpisodeWatched: (episodeId: String, currentlyWatched: Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Episodes",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        seasons.forEach { season ->
            SeasonRow(
                season = season,
                baseUrl = baseUrl,
                streamToken = streamToken,
                episodeWatched = episodeWatched,
                onEpisodePlay = onEpisodePlay,
                onToggleEpisodeWatched = onToggleEpisodeWatched,
            )
        }
    }
}

@Composable
private fun SeasonRow(
    season: cloud.nalet.chino.mobile.data.api.Season,
    baseUrl: String,
    streamToken: String,
    episodeWatched: Map<String, Boolean>,
    onEpisodePlay: (String) -> Unit,
    onToggleEpisodeWatched: (episodeId: String, currentlyWatched: Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(ChinoSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Season ${season.season}",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 16.dp),
            )
            Text(
                text = "${season.episodes.size} episodes",
                color = ChinoMuted,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = ChinoMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                season.episodes.forEachIndexed { index, ep ->
                    // Full-bleed 1dp separator between rows, matching web's
                    // `divide-y divide-[#21262d]` on the episodes card
                    // (EpisodesList.tsx). No divider above the first row.
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(ChinoBorder2),
                        )
                    }
                    // Effective episode watched state: optimistic override
                    // (the user's tap this session) wins over the payload's
                    // watched_at. Mirrors chino-web's EpisodeRow.
                    val epWatched = episodeWatched[ep.id] ?: (ep.watchedAt != null)
                    EpisodeRow(
                        episode = ep,
                        baseUrl = baseUrl,
                        streamToken = streamToken,
                        watched = epWatched,
                        onClick = { onEpisodePlay(ep.id) },
                        onToggleWatched = { onToggleEpisodeWatched(ep.id, epWatched) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: cloud.nalet.chino.mobile.data.api.Episode,
    baseUrl: String,
    streamToken: String,
    watched: Boolean,
    onClick: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(160.dp)
                .aspectRatio(16f / 9f)
                .clip(RectangleShape)
                .background(ChinoBg2),
        ) {
            AsyncImage(
                model = "$baseUrl/v1/items/${episode.id}/backdrop?stream=$streamToken",
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Per-episode watched toggle (web: EpisodesList.tsx watched pip).
            // Always rendered so a watched episode keeps a visible green
            // check; unwatched rows get a subtle dark Eye chip the user can
            // tap to mark watched. The tap is consumed here so it doesn't
            // also fire the row's open-player click.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(RectangleShape)
                    .background(if (watched) ChinoGreen else Color(0x99000000))
                    .clickable(onClick = onToggleWatched),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (watched) Lucide.Check else Lucide.Eye,
                    contentDescription = if (watched) "Mark episode as unwatched" else "Mark episode as watched",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            val epNum = listOfNotNull(
                episode.seasonNumber?.let { "S${it.toString().padStart(2, '0')}" },
                episode.episodeNumber?.let { "E${it.toString().padStart(2, '0')}" },
            ).joinToString("")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (epNum.isNotEmpty()) {
                    Text(text = epNum, color = ChinoCloudBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(text = "·", color = ChinoMuted, fontSize = 13.sp)
                }
                Text(
                    text = episode.title,
                    // Web dims a watched episode's title to #8b949e.
                    color = if (watched) ChinoMuted else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Runtime pinned to the trailing edge (web's `ml-auto`); the
                // title's weight(1f) above eats the slack between them.
                val epRuntimeMin = episode.durationMs?.let { (it / 60_000L).toInt() } ?: 0
                if (epRuntimeMin > 0) {
                    Text(text = "${epRuntimeMin}m", color = ChinoMuted, fontSize = 12.sp)
                }
            }
            episode.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = ChinoFg2,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "More like this" — horizontal poster shelf below the episodes
 *  accordion. Mirrors web's MoreLikeThis section. */
@Composable
private fun MoreLikeThisSection(
    items: List<Item>,
    baseUrl: String,
    streamToken: String,
    onItemClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "More like this",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { entry ->
                SimilarCard(
                    item = entry,
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    onClick = { onItemClick(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun SimilarCard(
    item: Item,
    baseUrl: String,
    streamToken: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(RectangleShape)
            .background(ChinoSurface)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(ChinoBg2),
        )
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = item.title,
                color = ChinoFg2,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // year • rating, rating in blue — matches web MediaCard.tsx
            // L222-230 (the same card "More like this" reuses on web).
            val ratingText = item.rating?.let { ((it * 10).toInt() / 10.0).toString() }
            if (item.year != null || ratingText != null) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.year?.let {
                        Text(it.toString(), color = ChinoMuted, fontSize = 12.sp)
                    }
                    ratingText?.let {
                        if (item.year != null) Text("•", color = ChinoMuted, fontSize = 12.sp)
                        Text(it, color = ChinoCloudBlue, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
