package cloud.nalet.chino.mobile.ui.profile

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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
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
import cloud.nalet.chino.mobile.ui.auth.AccountPickerScreen
import cloud.nalet.chino.mobile.ui.auth.AuthScreen
import cloud.nalet.chino.mobile.ui.components.Avatar
import cloud.nalet.chino.mobile.ui.detail.DetailScreen
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Profile screen reached from the TopBar avatar. Mirrors chino-web's
 * ProfilePage (/me): the active account's identity (avatar + name + email
 * from the OIDC userinfo claims persisted on the Account), a Sign out
 * action, and the watch history — newest first — read from the same
 * `GET /v1/me/watched?limit=60` endpoint the web + TV clients call
 * ([ChinoApi.watched]).
 *
 * The history renders as a COMPACT ROW LIST in the spirit of the detail
 * page's episodes list (same card surface + #21262D dividers), but denser:
 * each row is a small thumbnail (landscape still for episodes, poster
 * otherwise), title + meta line, then a right-aligned watched date and an
 * unwatch (EyeOff) action reusing the existing DELETE
 * /me/items/{id}/watched contract ([ChinoApi.deleteWatched]). Tapping a
 * row pushes Detail (same as Watchlist).
 *
 * Episode rows show the SERIES title as the row title with `SxxExx ·
 * episode title` beneath; /me/watched embeds plain catalogue Items (no
 * series_title field), so the parent titles are resolved with follow-up
 * GET /v1/items/{parent_id} calls, deduped per series, after the list
 * paints.
 *
 * Sign out removes the active account from the [AccountStore]. The
 * AppContainer's activeAccountId watcher invalidates the stream token on the
 * resulting active-account change. Where we land after removal depends on how
 * many accounts remain: ≥1 → the "Who's watching?" picker; 0 → Auth. This
 * matches the boot-gate heuristic in App.kt.
 */
class ProfileScreen : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val nav = LocalNavigator.currentOrThrow
        val container = LocalAppContainer.current
        val scope = rememberCoroutineScope()
        val activeAccount by container.accountStore.activeAccount.collectAsState(initial = null)

        // null == loading; emptyList == loaded-but-empty (distinct states so we
        // show a spinner before showing "nothing watched yet", same as web).
        var history by remember { mutableStateOf<List<Item>?>(null) }
        // parent_id -> series title, resolved after the history paints so
        // episode rows can show the series as the row title. Missing entries
        // fall back to the episode's own title.
        var seriesTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        // Mint the (account-scoped) stream token here rather than reading the
        // cached `current` — Profile is reachable directly (TopBar avatar /
        // Settings) and the account-switch path invalidates the token, so
        // `current` is often null here and posters would get `?stream=` (empty).
        var token by remember { mutableStateOf("") }

        LaunchedEffect(activeAccount?.id) {
            history = null
            seriesTitles = emptyMap()
            token = runCatching { container.streamTokenManager.valid() }.getOrDefault("")
            val items = runCatching { container.chinoApi.watched(limit = 60).items }
                .getOrElse { emptyList() }
            history = items
            // Resolve the parent series titles for episode rows (deduped, in
            // parallel, each fetch individually fallible). The list is already
            // on screen at this point; rows upgrade in place as titles land.
            val parentIds = items
                .filter { it.kind == "episode" }
                .mapNotNull { it.parentId }
                .distinct()
            if (parentIds.isNotEmpty()) {
                seriesTitles = coroutineScope {
                    parentIds.map { pid ->
                        async {
                            runCatching { container.chinoApi.getItem(pid) }
                                .getOrNull()
                                ?.let { pid to it.title }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
            }
        }

        val baseUrl = container.config.apiBaseUrl.trimEnd('/')

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val isWide = maxWidth >= 600.dp
            val outerPad = if (isWide) 24.dp else 16.dp

            LazyColumn(
                contentPadding = PaddingValues(
                    start = outerPad,
                    end = outerPad,
                    top = 0.dp,
                    bottom = outerPad,
                ),
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
            ) {
                item {
                    Column {
                        ProfileHeader(onBack = { nav.pop() })
                        IdentityCard(
                            displayName = activeAccount?.displayName ?: "Account",
                            email = activeAccount?.email.orEmpty(),
                            onSignOut = {
                                val id = activeAccount?.id
                                if (id != null) scope.launch {
                                    container.accountStore.remove(id)
                                    // remove() promotes the next most-recent
                                    // account to active (or null when none
                                    // remain); read the post-removal snapshot
                                    // to pick the destination.
                                    val remaining = container.accountStore.snapshotBlocking().accounts
                                    if (remaining.isEmpty()) {
                                        nav.replaceAll(AuthScreen())
                                    } else {
                                        nav.replaceAll(AccountPickerScreen())
                                    }
                                }
                            },
                        )
                        Text(
                            text = "Watch history",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                        )
                    }
                }

                val list = history
                when {
                    list == null -> item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }

                    list.isEmpty() -> item {
                        Text(
                            text = "Nothing watched yet. Watched items appear here once you " +
                                "finish a movie or episode (or mark one watched on a detail page).",
                            color = ChinoMuted,
                            fontSize = 14.sp,
                        )
                    }

                    // Compact row list on one #161B22 card (rounded ends on
                    // the first/last rows, #21262D dividers between — the
                    // detail page's episodes-list idiom, denser).
                    else -> itemsIndexed(list, key = { _, it -> it.id }) { index, item ->
                        val shape = when {
                            list.size == 1 -> RectangleShape
                            index == 0 -> RectangleShape
                            index == list.lastIndex ->
                                RectangleShape
                            else -> RectangleShape
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(ChinoSurface),
                        ) {
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(ChinoBorder2),
                                )
                            }
                            HistoryRow(
                                item = item,
                                seriesTitle = item.parentId?.let { seriesTitles[it] },
                                baseUrl = baseUrl,
                                token = token,
                                onClick = { nav.push(DetailScreen(item.id)) },
                                onUnwatch = {
                                    // Optimistic removal; restore the snapshot
                                    // if the DELETE fails (same revert pattern
                                    // as the detail page's watched toggle).
                                    val snapshot = history
                                    history = snapshot?.filterNot { it.id == item.id }
                                    scope.launch {
                                        runCatching { container.chinoApi.deleteWatched(item.id) }
                                            .onFailure { history = snapshot }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
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
        Text(
            text = "Profile",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun IdentityCard(displayName: String, email: String, onSignOut: () -> Unit) {
    // Mirrors web's identity card: #161B22 fill, #30363D hairline, avatar +
    // name/email + a pill Sign out button. On a phone the row stays single-
    // line; the email truncates rather than wrapping the button off-screen.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(ChinoSurface)
            .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Avatar(displayName = displayName, email = email, size = 64.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (email.isNotBlank() && email != displayName) {
                Text(
                    text = email,
                    color = ChinoMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier
                .clip(RectangleShape)
                .background(Color(0x1AFFFFFF))
                .clickable(onClick = onSignOut)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Lucide.LogOut,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Text(text = "Sign out", color = Color.White, fontSize = 13.sp)
        }
    }
}

/**
 * One watch-history row. The detail page's EpisodeRow idiom, denser: 56dp
 * thumbnail (landscape still for episodes, 2:3 poster otherwise), title +
 * meta line, then a right-aligned watched date and the unwatch (EyeOff)
 * action. The whole row clicks through to Detail; the EyeOff tap is
 * consumed locally so it doesn't also open the row.
 */
@Composable
private fun HistoryRow(
    item: Item,
    seriesTitle: String?,
    baseUrl: String,
    token: String,
    onClick: () -> Unit,
    onUnwatch: () -> Unit,
) {
    val isEpisode = item.kind == "episode"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .height(56.dp)
                .aspectRatio(if (isEpisode) 16f / 9f else 2f / 3f)
                .clip(RectangleShape)
                .background(ChinoBg2),
        ) {
            AsyncImage(
                model = if (isEpisode) {
                    "$baseUrl/v1/items/${item.id}/backdrop?stream=$token"
                } else {
                    "$baseUrl/v1/items/${item.id}/poster?stream=$token"
                },
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                // Episodes lead with the series title; the episode itself
                // moves to the SxxExx meta line below (web CW-card parity).
                text = if (isEpisode) (seriesTitle ?: item.title) else item.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isEpisode) {
                val epNum = listOfNotNull(
                    item.seasonNumber?.let { "S${it.toString().padStart(2, '0')}" },
                    item.episodeNumber?.let { "E${it.toString().padStart(2, '0')}" },
                ).joinToString("")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (epNum.isNotEmpty()) {
                        Text(text = epNum, color = ChinoCloudBlue, fontSize = 12.sp)
                        Text(text = "·", color = ChinoMuted, fontSize = 12.sp)
                    }
                    Text(
                        text = item.title,
                        color = ChinoMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                // year • rating, rating in accent blue — same meta line the
                // poster cards render (MediaCard / SimilarCard).
                val ratingText = item.rating?.let { ((it * 10).toInt() / 10.0).toString() }
                if (item.year != null || ratingText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.year?.let {
                            Text(it.toString(), color = ChinoMuted, fontSize = 12.sp)
                        }
                        ratingText?.let {
                            if (item.year != null) {
                                Text("•", color = ChinoMuted, fontSize = 12.sp)
                            }
                            Text(it, color = ChinoCloudBlue, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        watchedDateLabel(item.watchedAt)?.let {
            Text(text = it, color = ChinoMuted, fontSize = 12.sp, maxLines = 1)
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RectangleShape)
                .clickable(onClick = onUnwatch),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.EyeOff,
                contentDescription = "Mark as unwatched",
                tint = ChinoMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private val MonthAbbrev =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** RFC3339 "2026-06-12T18:23:45Z" -> "Jun 12" (no kotlinx-datetime dep;
 *  the watched stamp's calendar date is all the row needs). */
private fun watchedDateLabel(rfc3339: String?): String? {
    if (rfc3339 == null || rfc3339.length < 10) return null
    val month = rfc3339.substring(5, 7).toIntOrNull() ?: return null
    val day = rfc3339.substring(8, 10).toIntOrNull() ?: return null
    if (month !in 1..12) return null
    return "${MonthAbbrev[month - 1]} $day"
}
