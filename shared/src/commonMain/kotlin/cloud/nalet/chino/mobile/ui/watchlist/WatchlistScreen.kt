package cloud.nalet.chino.mobile.ui.watchlist

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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.api.Watchlist
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.ui.components.MediaCard
import cloud.nalet.chino.mobile.ui.components.MediaRow
import cloud.nalet.chino.mobile.ui.detail.DetailScreen
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2

/**
 * Watchlist destination — the cross-platform hub design (same UX on web,
 * TV and mobile):
 *
 *   HUB:  a vertical stack of horizontal poster shelves, one per user list,
 *         default list first (server order from GET /me/watchlists). Each
 *         shelf reuses the Home rails' [MediaRow] — first 12 items, newest-
 *         added first, trailing See-all tile. Tapping the shelf header or
 *         the See-all tile opens the MORE view. Empty lists keep their shelf
 *         header + an empty-state hint (so rename/delete stays reachable);
 *         a trailing "+ New list" affordance creates a list.
 *
 *   MORE: the full grid of one list's items (the pre-hub watchlist grid)
 *         with rename / delete header actions (hidden for the default list)
 *         and a back affordance returning to the hub.
 *
 * [WatchlistSection] backs BOTH entry points: the shell's Watchlist nav
 * section (MainShellScreen) and this bell-pushed Voyager screen.
 */
class WatchlistScreen : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        // Pushed over the whole window (TopBar bell) — this path owns the
        // status-bar inset. The section-embedded path sits below the shell
        // TopBar, which already carries the inset, so it passes no modifier.
        WatchlistSection(modifier = Modifier.statusBarsPadding())
    }
}

@Composable
fun WatchlistSection(modifier: Modifier = Modifier) {
    val nav = LocalNavigator.currentOrThrow
    val container = LocalAppContainer.current
    val model = remember { WatchlistScreenModel(container) }

    val lists by model.lists.collectAsState()
    val shelves by model.shelves.collectAsState()
    val loadingShelves by model.loadingShelves.collectAsState()
    val openListId by model.openListId.collectAsState()
    val openItems by model.openItems.collectAsState()
    val loadingOpenItems by model.loadingOpenItems.collectAsState()
    val dialogError by model.dialogError.collectAsState()
    val streamToken by container.streamTokenManager.current.collectAsState()

    // Dialog routing: create / rename / delete a specific list.
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Watchlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Watchlist?>(null) }

    val baseUrl = container.config.apiBaseUrl.trimEnd('/')
    val token = streamToken ?: ""
    val openList = lists.firstOrNull { it.id == openListId }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (openList != null) {
            ListMoreView(
                list = openList,
                items = openItems,
                loading = loadingOpenItems,
                baseUrl = baseUrl,
                token = token,
                onBack = { model.closeList() },
                onItemClick = { id -> nav.push(DetailScreen(id)) },
                onRename = { renameTarget = openList },
                onDelete = { deleteTarget = openList },
            )
        } else {
            WatchlistHub(
                lists = lists,
                shelves = shelves,
                loading = loadingShelves,
                baseUrl = baseUrl,
                token = token,
                onItemClick = { id -> nav.push(DetailScreen(id)) },
                onOpenList = { list -> model.openList(list.id) },
                onNewList = { showCreate = true },
            )
        }
    }

    if (showCreate) {
        NameListDialog(
            title = "New list",
            confirmLabel = "Create",
            initial = "",
            errorText = dialogError,
            onConfirm = { name ->
                model.createList(name) { created ->
                    if (created != null) showCreate = false
                }
            },
            onDismiss = {
                model.clearDialogError()
                showCreate = false
            },
        )
    }

    renameTarget?.let { target ->
        NameListDialog(
            title = "Rename list",
            confirmLabel = "Save",
            initial = target.name,
            errorText = dialogError,
            onConfirm = { name ->
                model.renameList(target.id, name) { ok ->
                    if (ok) renameTarget = null
                }
            },
            onDismiss = {
                model.clearDialogError()
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDeleteListDialog(
            list = target,
            onConfirm = {
                model.deleteList(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/* ─────────────────────────────────  Hub  ───────────────────────────────── */

/** "Weekend Picks · 7" — shelf header text, list name + live item count. */
private fun shelfTitle(list: Watchlist): String = "${list.name} · ${list.itemCount}"

@Composable
private fun WatchlistHub(
    lists: List<Watchlist>,
    shelves: Map<String, List<Item>>,
    loading: Boolean,
    baseUrl: String,
    token: String,
    onItemClick: (String) -> Unit,
    onOpenList: (Watchlist) -> Unit,
    onNewList: () -> Unit,
) {
    if (loading) {
        Center { CircularProgressIndicator() }
        return
    }
    // Same vertical rhythm as HomeSection's shelf stack: 16dp top padding,
    // 32dp between shelves (web `space-y-8`), 16dp bottom spacer.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // Page title — matches BrowseSection's 36sp Bold heading.
        Text(
            text = "Watchlist",
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        lists.forEach { list ->
            val items = shelves[list.id].orEmpty()
            if (items.isEmpty()) {
                // Empty shelf keeps its header (tappable, like a populated
                // shelf's) so the MORE view's rename / delete stays reachable.
                EmptyShelf(list = list, onOpen = { onOpenList(list) })
            } else {
                MediaRow(
                    title = shelfTitle(list),
                    items = items,
                    baseUrl = baseUrl,
                    streamToken = token,
                    onItemClick = onItemClick,
                    onSeeAll = { onOpenList(list) },
                    onTitleClick = { onOpenList(list) },
                    saved = true,
                )
            }
        }
        // Trailing "+ New list" affordance — POST /me/watchlists then the
        // new empty shelf appears above.
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            NewListChip(onClick = onNewList)
        }
        Box(modifier = Modifier.padding(bottom = 16.dp))
    }
}

/** Shelf header + empty-state hint for a list with no items yet. */
@Composable
private fun EmptyShelf(list: Watchlist, onOpen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Same header type as MediaRow's shelf title (24sp / 32 line / 600).
        Text(
            text = shelfTitle(list),
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .clickable(onClick = onOpen),
        )
        Text(
            text = "Save titles with the + button on any movie or show.",
            color = ChinoMuted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun NewListChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RectangleShape)
            .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Lucide.Plus,
            contentDescription = "New list",
            tint = ChinoMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(text = "New list", color = ChinoFg2, fontSize = 14.sp)
    }
}

/* ──────────────────────────  MORE view (per-list)  ─────────────────────── */

@Composable
private fun ListMoreView(
    list: Watchlist,
    items: List<Item>,
    loading: Boolean,
    baseUrl: String,
    token: String,
    onBack: () -> Unit,
    onItemClick: (String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header: back to hub + "name · count" + rename / delete actions
        // (the default list can't be renamed or deleted — actions hidden).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderIconButton(icon = Lucide.ArrowLeft, contentDescription = "Back", onClick = onBack)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = list.name,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = "· ${list.itemCount}",
                    color = ChinoMuted,
                    fontSize = 16.sp,
                )
            }
            if (!list.isDefault) {
                HeaderIconButton(icon = Lucide.Pencil, contentDescription = "Rename list", onClick = onRename)
                HeaderIconButton(icon = Lucide.Trash2, contentDescription = "Delete list", onClick = onDelete)
            }
        }

        when {
            loading -> Center { CircularProgressIndicator() }
            items.isEmpty() -> Center {
                Text(
                    "This list is empty. Add things from a title's page.",
                    color = ChinoMuted,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { item ->
                    MediaCard(
                        item = item,
                        posterUrl = "$baseUrl/v1/items/${item.id}/poster?stream=$token",
                        onClick = { onItemClick(item.id) },
                        cardWidth = 110.dp,
                        // Every tile here is, by definition, in the open
                        // list — surface the "saved" badge (in >=1 list).
                        saved = true,
                    )
                }
            }
        }
    }
}

/** 36dp rounded-8 transparent icon button — the shell TopBar's IconCell
 *  idiom (hover-only bg on web, plain tappable here). */
@Composable
private fun HeaderIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RectangleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/* ────────────────────────────────  Dialogs  ────────────────────────────── */

/**
 * Hand-rolled name dialog (#161B22 card + scrim) matching AccountPicker's
 * dialog idiom rather than a Material AlertDialog. Used for both Create and
 * Rename; the optional [onDelete] surfaces a "Delete list" affordance on the
 * rename path.
 */
@Composable
internal fun NameListDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    errorText: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val valid = trimmed.length in 1..60

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .clip(RectangleShape)
                .background(ChinoSurface)
                .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape)
                .clickable(enabled = false) {}
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            TextField(
                value = name,
                onValueChange = { if (it.length <= 60) name = it },
                singleLine = true,
                placeholder = { Text("List name", color = ChinoMuted) },
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 16.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(trimmed) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = ChinoBg2,
                    unfocusedContainerColor = ChinoBg2,
                    focusedIndicatorColor = ChinoCloudBlue,
                    unfocusedIndicatorColor = ChinoBorder,
                    cursorColor = ChinoCloudBlue,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            errorText?.let {
                Text(text = it, color = ChinoRed, fontSize = 13.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onDelete?.let {
                    DialogTextButton(label = "Delete list", color = ChinoRed, onClick = it)
                }
                Box(modifier = Modifier.weight(1f))
                DialogButton(label = "Cancel", containerColor = ChinoBorder2, onClick = onDismiss)
                DialogButton(
                    label = confirmLabel,
                    containerColor = if (valid) ChinoCloudBlue else ChinoBorder,
                    onClick = { if (valid) onConfirm(trimmed) },
                )
            }
        }
    }
}

@Composable
private fun ConfirmDeleteListDialog(
    list: Watchlist,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .clip(RectangleShape)
                .background(ChinoSurface)
                .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape)
                .clickable(enabled = false) {}
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Delete “${list.name}”?",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "This removes the list and everything in it. Titles stay in your other lists.",
                color = ChinoFg2,
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogButton(label = "Cancel", containerColor = ChinoBorder2, onClick = onDismiss)
                DialogButton(label = "Delete", containerColor = ChinoRed, onClick = onConfirm)
            }
        }
    }
}

@Composable
internal fun DialogButton(label: String, containerColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RectangleShape)
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun DialogTextButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RectangleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = color, fontSize = 14.sp)
    }
}
