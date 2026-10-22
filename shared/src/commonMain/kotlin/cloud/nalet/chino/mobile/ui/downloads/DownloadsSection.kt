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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.downloads.DELETE_ALL_TITLE
import cloud.nalet.chino.mobile.data.downloads.DeletedNotice
import cloud.nalet.chino.mobile.data.downloads.DownloadEntry
import cloud.nalet.chino.mobile.data.downloads.DownloadStatus
import cloud.nalet.chino.mobile.data.downloads.Downloads
import cloud.nalet.chino.mobile.data.downloads.EMPTY_SENTENCE
import cloud.nalet.chino.mobile.data.downloads.EMPTY_TITLE
import cloud.nalet.chino.mobile.data.downloads.Reach
import cloud.nalet.chino.mobile.data.downloads.StorageUse
import cloud.nalet.chino.mobile.data.downloads.UNREACHABLE_BANNER
import cloud.nalet.chino.mobile.data.downloads.UNREACHABLE_BANNER_NOTHING
import cloud.nalet.chino.mobile.data.downloads.deleteAllSentence
import cloud.nalet.chino.mobile.data.downloads.deletedManySentence
import cloud.nalet.chino.mobile.data.downloads.deletedOneSentence
import cloud.nalet.chino.mobile.data.downloads.downloadGroups
import cloud.nalet.chino.mobile.data.downloads.formatSize
import cloud.nalet.chino.mobile.data.downloads.statusLabel
import cloud.nalet.chino.mobile.data.downloads.statusSentence
import cloud.nalet.chino.mobile.data.downloads.storageLine
import cloud.nalet.chino.mobile.data.model.episodeCode
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
 * The downloads screen: the account's titles on the phone, newest first, by
 * series and season with the movies in a group of their own; how much room
 * they take and how much is free; each title's status, playing from here.
 * Delete one without a question, all of them after one. While the server
 * cannot be reached, the information banner says the downloads still play -
 * or only that it can't be reached, with none - and it goes once the server
 * answers again. Titles the server took off the phone are named once, in a
 * banner of their own.
 */
@Composable
fun DownloadsSection(onPlay: (itemId: String) -> Unit) {
    val container = LocalAppContainer.current
    val downloads = container.downloads ?: return
    val device = LocalDeviceKind.current
    val entries by downloads.entries.collectAsState()
    val reach by container.serverReach.state.collectAsState()
    val notice by downloads.notice.collectAsState()
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
    // The notice is shown once: the screen keeps what it showed until it
    // goes, and the phone forgets it at once - a later visit, or the app
    // started again, doesn't tell it twice.
    var shownNotice by remember { mutableStateOf<DeletedNotice?>(null) }
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        shownNotice = DeletedNotice((shownNotice?.titles.orEmpty() + n.titles).distinct())
        downloads.noticeSeen(n.titles)
    }
    val groups = remember(entries) { downloadGroups(entries) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(
                text = "downloads",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                style = ChinoHeading,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (reach == Reach.Unreachable) {
            item(key = "banner") { InformationBanner(if (entries.isEmpty()) UNREACHABLE_BANNER_NOTHING else UNREACHABLE_BANNER) }
        }
        shownNotice?.let { n ->
            item(key = "deleted") {
                InformationBanner(
                    text = if (n.titles.size == 1) deletedOneSentence(device) else deletedManySentence(n.titles.size, device),
                    details = n.titles,
                )
            }
        }
        storage?.let { use ->
            item(key = "storage") {
                Text(text = storageLine(use, device), color = ChinoMuted, fontSize = 13.sp)
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty") { EmptyDownloads() }
        } else {
            groups.forEach { group ->
                // The movies by the app's own word; a series by its title as
                // the download saved it - none where none did, never a word
                // that names nothing.
                val heading = if (group.seriesId == null) "movies" else group.seriesTitle
                if (heading != null) {
                    item(key = "group-${group.seriesId ?: "movies"}") {
                        Text(
                            text = heading,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = ChinoHeading,
                            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                        )
                    }
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
        // What it removes from the phone: the titles there, by what they take.
        val there = entries.filter { it.onPhone }
        DownloadsGoQuestion(
            title = DELETE_ALL_TITLE,
            sentence = deleteAllSentence(there.size, there.sumOf { it.size ?: it.bytes }, device),
            confirm = "delete all",
            onConfirm = {
                askDeleteAll = false
                downloads.deleteAll()
            },
            onDismiss = { askDeleteAll = false },
        )
    }
}

/** One title on the phone: its picture, its name, its size and status;
 *  pressed, it plays once it is on the phone. */
@Composable
private fun DownloadRow(entry: DownloadEntry, downloads: Downloads, onPlay: () -> Unit) {
    val device = LocalDeviceKind.current
    val title = entry.title
    val status = entry.status
    // A download waiting for its check with the server does not play.
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
                // On its way: what it will take, as the server said. Not for
                // one that stopped: the reason says it, and a size beside it
                // would read as if that much were kept.
                if (status !is DownloadStatus.Downloaded && status !is DownloadStatus.CheckNeeded && status !is DownloadStatus.Failed) {
                    entry.size?.let { Text(text = formatSize(it), color = ChinoMuted, fontSize = 12.sp) }
                }
            }
            (status as? DownloadStatus.Downloading)?.let { d ->
                Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(ChinoBorder)) {
                    Box(modifier = Modifier.fillMaxWidth(d.percent / 100f).height(2.dp).background(ChinoCloudBlue))
                }
            }
            statusSentence(status, device)?.let { sentence ->
                Text(
                    text = sentence,
                    color = if (status is DownloadStatus.Failed) ChinoRed else ChinoMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            // One that stopped goes on where it stopped, as on its page.
            if (status is DownloadStatus.Failed) {
                TextAction(text = "try again", compact = true, onClick = { downloads.retry(title.itemId) })
            }
        }
        when (status) {
            is DownloadStatus.Downloaded, is DownloadStatus.CheckNeeded, is DownloadStatus.Failed ->
                IconAction(Lucide.Trash2, "delete download", compact = false, onClick = { downloads.delete(title.itemId) })
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
internal fun InformationBanner(text: String, details: List<String> = emptyList()) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // As tall as its text, so the bar down its edge is too.
            .height(IntrinsicSize.Min)
            .background(ChinoCloudBlue.copy(alpha = 0.08f))
            .border(BorderStroke(1.dp, ChinoCloudBlue.copy(alpha = 0.35f)), RectangleShape)
            .clearAndSetSemantics {
                contentDescription = "information: $text" + if (open) " " + details.joinToString(", ") else ""
                liveRegion = LiveRegionMode.Polite
                if (details.isNotEmpty()) {
                    role = Role.Button
                    // The tap's own action is cleared with the rest: this is
                    // the one a screen reader offers.
                    onClick(label = if (open) "hide details" else "details") {
                        open = !open
                        true
                    }
                }
            }
            .then(if (details.isNotEmpty()) Modifier.clickable { open = !open } else Modifier),
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(ChinoCloudBlue))
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(imageVector = Lucide.Info, contentDescription = null, tint = ChinoCloudBlue, modifier = Modifier.size(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = text, color = ChinoFg, fontSize = 14.sp, lineHeight = 20.sp)
                if (details.isNotEmpty()) {
                    Text(text = if (open) "hide details" else "details", color = ChinoCloudBlue, fontSize = 12.sp, style = ChinoMono)
                    if (open) {
                        details.forEach { Text(text = it, color = ChinoFg2, fontSize = 13.sp, lineHeight = 18.sp) }
                    }
                }
            }
        }
    }
}

/** How often the storage line is counted again. */
private const val STORAGE_REFRESH_MS = 3_000L

/** How often the server is asked again while it cannot be reached. */
private const val RECHECK_MS = 15_000L
