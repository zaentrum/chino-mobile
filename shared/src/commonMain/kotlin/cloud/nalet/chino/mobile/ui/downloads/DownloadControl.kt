package cloud.nalet.chino.mobile.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.api.Episode
import cloud.nalet.chino.mobile.data.downloads.CANT_DOWNLOAD_LABEL
import cloud.nalet.chino.mobile.data.downloads.CANT_DOWNLOAD_SENTENCE
import cloud.nalet.chino.mobile.data.downloads.DownloadEntry
import cloud.nalet.chino.mobile.data.downloads.DownloadFailure
import cloud.nalet.chino.mobile.data.downloads.DownloadOffer
import cloud.nalet.chino.mobile.data.downloads.DownloadStatus
import cloud.nalet.chino.mobile.data.downloads.offerLabel
import cloud.nalet.chino.mobile.data.downloads.sentence
import cloud.nalet.chino.mobile.data.downloads.partLabel
import cloud.nalet.chino.mobile.data.downloads.seasonOfferLabel
import cloud.nalet.chino.mobile.data.downloads.statusLabel
import cloud.nalet.chino.mobile.data.downloads.statusSentence
import cloud.nalet.chino.mobile.data.model.FileEntry
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoDim
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoMono
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/*
 * The download control: one per title, next to "play" on its page, with
 * one status and the one thing that makes sense in it (the design system's
 * downloads and offline pattern). A series has none of its own: its
 * episodes do, each season offers all of them at once, and a series partly
 * on the phone says how far.
 */

/** What a title's control shows. */
private sealed interface ControlState {
    /** Not on the phone: "download · 1.4 GB". [bytes] null while the size
     *  is not known. */
    data class Offer(val bytes: Long?) : ControlState

    /** It plays only as a stream: "can't be downloaded yet". */
    data object CantDownload : ControlState

    /** Asked for, and being worked out: shows as queued. */
    data object Starting : ControlState

    /** It could not start, for [reason]. */
    data class StartFailed(val reason: DownloadFailure) : ControlState

    /** On the phone, or on its way. */
    data class OnPhone(val entry: DownloadEntry) : ControlState
}

/** A title's control state, and what its actions do; null where there is
 *  no control (no downloads on this platform, or the server offers none). */
private class Control(val state: ControlState, val start: () -> Unit, val cancel: () -> Unit, val retry: () -> Unit)

@Composable
private fun rememberControl(itemId: String, durationMs: Long?): Control? {
    val container = LocalAppContainer.current
    val downloads = container.downloads ?: return null
    val starts = container.downloadStarts
    val entries by downloads.entries.collectAsState()
    val prefs by downloads.preferences.collectAsState()
    val features by container.serverReach.features.collectAsState()
    val working by starts.working.collectAsState()
    val failed by starts.failed.collectAsState()
    val entry = entries.firstOrNull { it.itemId == itemId }
    // The server's word: no new downloads where it offers none. What is on
    // the phone stays, with its control.
    if (entry == null && features?.downloads == false) return null
    val offer by produceState<DownloadOffer?>(null, itemId, prefs.quality) {
        value = container.downloadPlanner.offer(itemId, prefs.quality, durationMs)
    }
    val startFailure = failed[itemId]
    val state = when {
        entry != null -> ControlState.OnPhone(entry)
        itemId in working -> ControlState.Starting
        // Found out as it was asked for: it only streams.
        startFailure == DownloadFailure.StreamOnly -> ControlState.CantDownload
        startFailure != null -> ControlState.StartFailed(startFailure)
        offer is DownloadOffer.StreamOnly -> ControlState.CantDownload
        else -> ControlState.Offer((offer as? DownloadOffer.Available)?.bytes)
    }
    // In the app's scope (DownloadStarts): the page may be left before the
    // server has answered.
    val start = {
        downloads.askToNotify()
        starts.start(itemId)
    }
    return Control(
        state = state,
        start = start,
        cancel = { downloads.cancel(itemId) },
        retry = { if (entry != null) downloads.retry(itemId) else start() },
    )
}

/** The control on a film's or an episode's page, next to "play". */
@Composable
fun TitleDownloadControl(itemId: String, durationMs: Long?, modifier: Modifier = Modifier) {
    val control = rememberControl(itemId, durationMs) ?: return
    ControlView(control, compact = false, modifier = modifier)
}

/** The control in an episode's row: the same states, smaller. */
@Composable
fun EpisodeDownloadControl(itemId: String, durationMs: Long?, modifier: Modifier = Modifier) {
    val control = rememberControl(itemId, durationMs) ?: return
    ControlView(control, compact = true, modifier = modifier)
}

@Composable
private fun ControlView(control: Control, compact: Boolean, modifier: Modifier) {
    val device = LocalDeviceKind.current
    val state = control.state
    val status = (state as? ControlState.OnPhone)?.entry?.status
    val label = when (state) {
        is ControlState.Offer -> offerLabel(state.bytes)
        ControlState.CantDownload -> CANT_DOWNLOAD_LABEL
        ControlState.Starting -> statusLabel(DownloadStatus.Queued)
        is ControlState.StartFailed -> statusLabel(DownloadStatus.Failed(state.reason))
        is ControlState.OnPhone -> statusLabel(state.entry.status)
    }
    val sentence = when (state) {
        ControlState.CantDownload -> CANT_DOWNLOAD_SENTENCE
        is ControlState.StartFailed -> state.reason.sentence(device)
        is ControlState.OnPhone -> statusSentence(state.entry.status, device)
        else -> null
    }
    val failed = state is ControlState.StartFailed || status is DownloadStatus.Failed
    val onPhone = status is DownloadStatus.Downloaded || status is DownloadStatus.CheckNeeded
    val icon = when {
        failed -> Lucide.TriangleAlert
        status is DownloadStatus.CheckNeeded -> Lucide.Info
        status is DownloadStatus.Downloaded -> Lucide.Check
        else -> Lucide.Download
    }
    val tint = when {
        failed -> ChinoRed
        status is DownloadStatus.CheckNeeded -> ChinoMuted
        status is DownloadStatus.Downloaded -> ChinoGreen
        state is ControlState.CantDownload -> ChinoDim
        status is DownloadStatus.Downloading -> ChinoCloudBlue
        state is ControlState.Offer -> Color.White
        else -> ChinoMuted
    }
    val percent = (status as? DownloadStatus.Downloading)?.percent
    val onLabel: (() -> Unit)? = if (state is ControlState.Offer) control.start else null
    val cancellable = state is ControlState.OnPhone && !onPhone && status !is DownloadStatus.Failed
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(
                label = label,
                icon = icon,
                tint = tint,
                percent = percent,
                compact = compact,
                dim = state is ControlState.CantDownload,
                onClick = onLabel,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            if (failed) {
                TextAction(
                    text = "try again",
                    compact = compact,
                    onClick = control.retry,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            when {
                cancellable -> IconAction(Lucide.X, "cancel download", compact, control.cancel, Modifier.align(Alignment.CenterVertically))
                onPhone || status is DownloadStatus.Failed ->
                    IconAction(Lucide.Trash2, "delete download", compact, control.cancel, Modifier.align(Alignment.CenterVertically))
                else -> Unit
            }
        }
        sentence?.let {
            Text(
                text = it,
                color = if (failed) ChinoRed else ChinoMuted,
                fontSize = if (compact) 12.sp else 14.sp,
                lineHeight = if (compact) 16.sp else 20.sp,
            )
        }
    }
}

/** The status, in the label face: what the control says, with its icon; a
 *  thin bar along its foot while it downloads. Pressed where it starts a
 *  download. */
@Composable
internal fun StatusPill(
    label: String,
    icon: ImageVector,
    tint: Color,
    percent: Int?,
    compact: Boolean,
    dim: Boolean = false,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val height: Dp = if (compact) 32.dp else 44.dp
    val textSize: TextUnit = if (compact) 12.sp else 15.sp
    Column(
        modifier = modifier
            // As wide as its label: the bar under it no wider.
            .width(IntrinsicSize.Max)
            .clip(RectangleShape)
            .background(Color.White.copy(alpha = if (dim) 0.05f else 0.1f))
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick).semantics { role = Role.Button }
                } else {
                    Modifier
                },
            ),
    ) {
        // At least its height; taller where a narrow row wraps its label,
        // which is never cut short.
        Row(
            modifier = Modifier
                .heightIn(min = if (percent != null) height - 2.dp else height)
                .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (compact) 14.dp else 18.dp))
            Text(
                text = label,
                color = if (dim) ChinoMuted else Color.White,
                fontSize = textSize,
                fontWeight = FontWeight.Medium,
                style = ChinoMono,
            )
        }
        if (percent != null) {
            // The bar: as wide as the pill, filled to the percentage.
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(ChinoBorder)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((percent.coerceIn(0, 100)) / 100f)
                        .height(2.dp)
                        .background(ChinoCloudBlue),
                )
            }
        }
    }
}

/** A text action beside the status, in the label face: "try again". */
@Composable
internal fun TextAction(
    text: String,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The link blue; the destructive red for one that deletes. */
    color: Color = ChinoCloudBlue,
) {
    Box(
        modifier = modifier
            .height(if (compact) 32.dp else 44.dp)
            .clip(RectangleShape)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = if (compact) 8.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = if (compact) 12.sp else 15.sp,
            fontWeight = FontWeight.Medium,
            style = ChinoMono,
        )
    }
}

/** A square icon action, named for screen readers: cancel, delete. */
@Composable
internal fun IconAction(icon: ImageVector, name: String, compact: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(if (compact) 32.dp else 44.dp)
            .clip(RectangleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = name
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (compact) 14.dp else 18.dp))
    }
}

/**
 * A season's control, under its heading: "download season 1 · 8 episodes ·
 * 9.6 GB" while none of the season is on the phone or on its way, else
 * "download 2 more episodes · 724 MB" for the rest; how far the season is
 * once some are ("3 of 8 downloaded"). The offer is the open season's only:
 * a closed one asks the server nothing, and shows how far it is on the
 * phone. None where none of its episodes can be downloaded, or where the
 * server offers no downloads and none is on the phone. [entries] are the
 * season's files, one per thing that plays; [expanded]: its episodes show
 * below.
 */
@Composable
fun SeasonDownloadControl(season: Int, entries: List<FileEntry<Episode>>, expanded: Boolean, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val downloads = container.downloads ?: return
    val starts = container.downloadStarts
    val onPhone by downloads.entries.collectAsState()
    val prefs by downloads.preferences.collectAsState()
    val features by container.serverReach.features.collectAsState()
    val working by starts.working.collectAsState()
    val ids = remember(entries) { entries.map { it.episode.id } }
    val durations = remember(entries) { entries.associate { it.episode.id to fileDurationMs(it) } }
    val byId = onPhone.associateBy { it.itemId }
    val downloaded = ids.count { byId[it]?.status.isOnThePhone() }
    // On the phone, on its way, being worked out or stopped: handled.
    val handled = ids.count { byId[it] != null || it in working }
    val onTheWay = handled - downloaded
    // The server's word: none of it where it offers no downloads - not even
    // the questions - unless part of the season is on the phone already.
    val offering = features?.downloads != false
    if (!offering && downloaded + onTheWay == 0) return
    // The open season's episodes' offers at once: the planner asks the
    // server three at a time and keeps the answers, which the rows below
    // share. A closed season asks nothing.
    val offers by produceState<Map<String, DownloadOffer>?>(null, ids, prefs.quality, offering, expanded) {
        if (!offering || !expanded) return@produceState
        value = coroutineScope {
            ids.map { id -> async { id to container.downloadPlanner.offer(id, prefs.quality, durations[id]) } }.awaitAll().toMap()
        }
    }
    val known = offers
    val canCome = known?.filterValues { it is DownloadOffer.Available }?.keys
    val remaining = ids.filter { byId[it] == null && it !in working && (canCome == null || it in canCome) }
    if (known != null && canCome.isNullOrEmpty() && downloaded + onTheWay == 0) return
    val remainingBytes = known?.let { o ->
        val sizes = remaining.map { (o[it] as? DownloadOffer.Available)?.bytes }
        if (sizes.any { it == null }) null else sizes.sumOf { it ?: 0L }
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // How far, once part of it is on the phone.
        if (downloaded > 0) {
            StatusPill(
                label = partLabel(downloaded, ids.size),
                icon = if (downloaded == ids.size) Lucide.Check else Lucide.Download,
                tint = if (downloaded == ids.size) ChinoGreen else ChinoMuted,
                percent = null,
                compact = true,
                onClick = null,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        if (expanded && remaining.isNotEmpty() && offering) {
            StatusPill(
                label = seasonOfferLabel(season, remaining.size, remainingBytes, noneHandled = handled == 0),
                icon = Lucide.Download,
                tint = Color.White,
                percent = null,
                compact = true,
                onClick = {
                    downloads.askToNotify()
                    // In their order, in the app's scope: the manager runs
                    // them one after the other, and leaving the page stops
                    // none of them.
                    starts.startAll(remaining)
                },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}


/** How far a series is on the phone, where it is partly there: "3 of 8
 *  downloaded". [entries] are all its files, every season's. */
@Composable
fun SeriesDownloadSummary(entries: List<FileEntry<Episode>>, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val downloads = container.downloads ?: return
    val onPhone by downloads.entries.collectAsState()
    val ids = entries.map { it.episode.id }.toSet()
    val downloaded = onPhone.count { it.itemId in ids && it.status.isOnThePhone() }
    if (downloaded == 0) return
    StatusPill(
        label = partLabel(downloaded, ids.size),
        icon = if (downloaded == ids.size) Lucide.Check else Lucide.Download,
        tint = if (downloaded == ids.size) ChinoGreen else ChinoMuted,
        percent = null,
        compact = false,
        onClick = null,
        modifier = modifier,
    )
}

/** What a file of the season plays for: a holder's measured length, else
 *  its episodes' runtimes. */
private fun fileDurationMs(entry: FileEntry<Episode>): Long? =
    if (entry.held.size > 1) {
        entry.episode.fileDurationMs ?: entry.held.sumOf { it.durationMs ?: 0L }.takeIf { it > 0 }
    } else {
        entry.episode.durationMs
    }

/** Whether a status is a title on the phone: downloaded, or waiting for its
 *  check with the server. */
private fun DownloadStatus?.isOnThePhone(): Boolean = this is DownloadStatus.Downloaded || this is DownloadStatus.CheckNeeded
