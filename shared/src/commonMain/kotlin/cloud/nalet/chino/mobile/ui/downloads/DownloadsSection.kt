package cloud.nalet.chino.mobile.ui.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.downloads.DELETE_ALL_TITLE
import cloud.nalet.chino.mobile.data.downloads.DownloadEntry
import cloud.nalet.chino.mobile.data.downloads.DownloadStatus
import cloud.nalet.chino.mobile.data.downloads.Downloads
import cloud.nalet.chino.mobile.data.downloads.EMPTY_SENTENCE
import cloud.nalet.chino.mobile.data.downloads.EMPTY_TITLE
import cloud.nalet.chino.mobile.data.downloads.Reach
import cloud.nalet.chino.mobile.data.downloads.StorageUse
import cloud.nalet.chino.mobile.data.downloads.UNREACHABLE_BANNER
import cloud.nalet.chino.mobile.data.downloads.deleteAllSentence
import cloud.nalet.chino.mobile.data.downloads.downloadGroups
import cloud.nalet.chino.mobile.data.downloads.formatSize
import cloud.nalet.chino.mobile.data.downloads.statusLabel
import cloud.nalet.chino.mobile.data.downloads.storageLine
import cloud.nalet.chino.mobile.data.model.episodeCode
import cloud.nalet.chino.mobile.ui.auth.AuthScreen
import cloud.nalet.chino.mobile.ui.player.PlayerScreen
import cloud.nalet.chino.mobile.ui.shell.LogoMark
import cloud.nalet.chino.mobile.ui.theme.ChinoBg2
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoHeading
import cloud.nalet.chino.mobile.ui.theme.ChinoMono
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.ui.theme.PosterImage
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay

/**
 * The downloads screen: everything on the phone, newest first, by series and
 * season with the films in a group of their own; how much room downloads
 * take and how much is free; each title's size and status, playing from
 * here. Delete one without a question, all of them after one. While the
 * server cannot be reached, the information banner says the downloads still
 * play - and it goes once the server answers again.
 */
@Composable
fun DownloadsSection(
    onPlay: (itemId: String) -> Unit,
    /** Beside the title: the signed-out screen's way to sign in. */
    action: (@Composable () -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val downloads = container.downloads ?: return
    val entries by downloads.entries.collectAsState()
    val reach by container.serverReach.state.collectAsState()
    var askDeleteAll by remember { mutableStateOf(false) }
    // The room taken and left, as the phone counts it - off the main
    // thread: at once as a title comes, goes or is done, and every few
    // seconds besides, while one is removed or on its way.
    val storageKey = remember(entries) { entries.map { it.itemId to (it.status is DownloadStatus.Downloaded) } }
    val storage by produceState<StorageUse?>(null, storageKey) {
        while (true) {
            value = downloads.storage()
            delay(STORAGE_REFRESH_MS)
        }
    }
    // While the server cannot be reached, a look at it now and then: once
    // it answers, the banner goes and what was played here goes out.
    LaunchedEffect(reach) {
        if (reach != Reach.Unreachable) return@LaunchedEffect
        while (true) {
            delay(RECHECK_MS)
            if (container.serverReach.check() == Reach.Reachable) {
                runCatching { container.progressSync.flush() }
                break
            }
        }
    }
    // With the server, whether each title is still in its library.
    LaunchedEffect(reach == Reach.Reachable, entries.size) {
        if (reach == Reach.Reachable) checkLibrary(container, downloads, entries)
    }
    val groups = remember(entries) { downloadGroups(entries) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "downloads",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    style = ChinoHeading,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                action?.invoke()
            }
        }
        if (reach == Reach.Unreachable) {
            item(key = "banner") { InformationBanner(UNREACHABLE_BANNER) }
        }
        storage?.let { use ->
            item(key = "storage") {
                Text(text = storageLine(use), color = ChinoMuted, fontSize = 13.sp)
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty") { EmptyDownloads() }
        } else {
            groups.forEach { group ->
                item(key = "group-${group.seriesId ?: "films"}") {
                    Text(
                        text = group.seriesTitle ?: if (group.seriesId == null) "films" else "series",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = ChinoHeading,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                }
                group.seasons.forEach { season ->
                    if (group.seriesId != null && season.season != null) {
                        item(key = "season-${group.seriesId}-${season.season}") {
                            Text(
                                text = "season ${season.season}",
                                color = ChinoFg2,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                style = ChinoMono,
                            )
                        }
                    }
                    season.entries.forEach { entry ->
                        item(key = "title-${entry.itemId}") {
                            DownloadRow(
                                entry = entry,
                                downloads = downloads,
                                onPlay = { onPlay(entry.itemId) },
                            )
                        }
                    }
                }
            }
            item(key = "delete-all") {
                TextAction(
                    text = "delete all",
                    compact = false,
                    onClick = { askDeleteAll = true },
                    color = ChinoRed,
                )
            }
        }
    }
    if (askDeleteAll) {
        AlertDialog(
            onDismissRequest = { askDeleteAll = false },
            shape = RectangleShape,
            containerColor = ChinoSurface,
            title = { Text(DELETE_ALL_TITLE, color = Color.White, style = ChinoHeading, fontSize = 18.sp) },
            text = {
                val used = storage?.usedBytes ?: entries.sumOf { it.size ?: 0L }
                Text(deleteAllSentence(entries.size, used), color = ChinoFg2, fontSize = 14.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    askDeleteAll = false
                    downloads.deleteAll()
                }) { Text("delete all", color = ChinoRed, style = ChinoMono) }
            },
            dismissButton = {
                TextButton(onClick = { askDeleteAll = false }) { Text("cancel", color = Color.White, style = ChinoMono) }
            },
        )
    }
}

/** One title on the phone: its picture, its name, its size and status;
 *  pressed, it plays once it is on the phone. */
@Composable
private fun DownloadRow(entry: DownloadEntry, downloads: Downloads, onPlay: () -> Unit) {
    val title = entry.title
    val status = entry.status
    val playable = status is DownloadStatus.Downloaded
    val episode = title.isEpisode
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(ChinoSurface)
            .then(if (playable) Modifier.clickable(onClick = onPlay).semantics { role = Role.Button } else Modifier)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val picture = downloads.fileUrl(title.itemId, if (episode) title.backdrop else title.poster)
            ?: downloads.fileUrl(title.itemId, title.poster)
        PosterImage(
            model = picture,
            title = title.item.title,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(if (episode) 112.dp else 56.dp)
                .aspectRatio(if (episode) 16f / 9f else 2f / 3f)
                .background(ChinoBg2),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val name = if (episode) {
                val code = episodeCode(title.item.seasonNumber, title.item.episodeNumber, title.item.episodeEnd)
                if (code.isNotEmpty()) "$code · ${title.item.title}" else title.item.title
            } else {
                title.item.title
            }
            Text(text = name, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusLabel(status),
                    color = when (status) {
                        is DownloadStatus.Failed -> ChinoRed
                        is DownloadStatus.Downloaded -> ChinoGreen
                        is DownloadStatus.Downloading -> ChinoCloudBlue
                        else -> ChinoMuted
                    },
                    fontSize = 12.sp,
                    style = ChinoMono,
                )
                // On its way: what it will take, as the server said.
                if (status !is DownloadStatus.Downloaded) {
                    entry.size?.let { Text(text = formatSize(it), color = ChinoMuted, fontSize = 12.sp) }
                }
            }
            (status as? DownloadStatus.Downloading)?.let { d ->
                Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(ChinoBorder)) {
                    Box(modifier = Modifier.fillMaxWidth(d.percent / 100f).height(2.dp).background(ChinoCloudBlue))
                }
            }
            (status as? DownloadStatus.Failed)?.let {
                Text(text = it.reason.sentence, color = ChinoRed, fontSize = 12.sp, lineHeight = 16.sp)
            }
            if (title.goneFromLibrary) {
                Text(text = "This title isn't in the library anymore.", color = ChinoMuted, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        when (status) {
            is DownloadStatus.Downloaded, is DownloadStatus.Failed ->
                IconAction(Lucide.Trash2, "delete download", compact = false, onClick = { downloads.delete(title.itemId) })
            DownloadStatus.Removing -> Unit
            else -> IconAction(Lucide.X, "cancel download", compact = false, onClick = { downloads.cancel(title.itemId) })
        }
    }
}

/** The empty downloads screen: what is true, and what to expect. */
@Composable
private fun EmptyDownloads() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(56.dp).border(BorderStroke(1.dp, ChinoBorder), RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = Lucide.Download, contentDescription = null, tint = ChinoMuted, modifier = Modifier.size(24.dp))
        }
        Text(
            text = EMPTY_TITLE,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            style = ChinoHeading,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = EMPTY_SENTENCE, color = ChinoMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/**
 * The information banner (the design system's): a square box tinted and
 * framed in the information blue, a bar of it down its left edge, its glyph
 * and one or two sentences. No close button: it says what stays true until
 * the server answers. Read out politely, as "information: …".
 */
@Composable
internal fun InformationBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // As tall as its text, so the bar down its edge is too.
            .height(IntrinsicSize.Min)
            .background(ChinoCloudBlue.copy(alpha = 0.08f))
            .border(BorderStroke(1.dp, ChinoCloudBlue.copy(alpha = 0.35f)), RectangleShape)
            .clearAndSetSemantics {
                contentDescription = "information: $text"
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(ChinoCloudBlue))
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(imageVector = Lucide.Info, contentDescription = null, tint = ChinoCloudBlue, modifier = Modifier.size(18.dp))
            Text(text = text, color = ChinoFg, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/** Whether each title on the phone is still in the server's library: one
 *  the server answers 404 or 410 for says so, and keeps its download. */
private suspend fun checkLibrary(container: AppContainer, downloads: Downloads, entries: List<DownloadEntry>) {
    for (e in entries) {
        if (e.title.server != container.serverBase) continue
        val inLibrary = try {
            container.chinoApi.getItem(e.itemId)
            true
        } catch (x: ApiStatusException) {
            if (x.status == 404 || x.status == 410) false else continue
        } catch (x: Exception) {
            return
        }
        if (inLibrary == e.title.goneFromLibrary) downloads.setInLibrary(e.itemId, inLibrary)
    }
}

/**
 * The downloads alone, for nobody signed in, when the server cannot be
 * reached: they play without it, and a sign-in has to wait for it. Once it
 * answers, "sign in" is the way on.
 */
class DownloadsScreen : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val container = LocalAppContainer.current
        val nav = LocalNavigator.currentOrThrow
        val reach by container.serverReach.state.collectAsState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogoMark(sizeDp = 32)
            }
            DownloadsSection(
                onPlay = { id -> nav.push(PlayerScreen(itemId = id)) },
                action = if (reach == Reach.Reachable) {
                    { TextAction(text = "sign in", compact = false, onClick = { nav.replaceAll(AuthScreen()) }) }
                } else {
                    null
                },
            )
        }
    }
}

/** How often the storage line is counted again. */
private const val STORAGE_REFRESH_MS = 3_000L

/** How often the server is asked again while it cannot be reached. */
private const val RECHECK_MS = 15_000L
