package cloud.nalet.chino.mobile.downloads

import android.content.Context
import androidx.media3.common.StreamKey
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.offline.Download
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.downloads.DownloadedTitle
import cloud.nalet.chino.mobile.data.downloads.Reach
import cloud.nalet.chino.mobile.ui.player.ResumeInput
import cloud.nalet.chino.mobile.ui.player.composePlayerTitle
import cloud.nalet.chino.mobile.ui.player.mayWriteProgress
import cloud.nalet.chino.mobile.ui.player.resumeStartSec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * A title the player plays from its download: the renditions on the phone
 * (read through [mediaSources], which never asks the server), the
 * subtitles beside them (files, read through [sidecarSources]) and what was
 * kept of the title. Built by [downloadedSession]; null for a title that
 * is not on the phone, or not wholly.
 */
class DownloadedSession(
    val title: DownloadedTitle,
    /** The master the download read, without a token: the media item's URI,
     *  so each piece is found under the key it was kept by. */
    val masterUri: String,
    val streamKeys: List<StreamKey>,
    val mediaSources: DataSource.Factory,
    val sidecarSources: DataSource.Factory,
    /** The subtitles kept, as file URLs. */
    val sidecars: List<SidecarSubtitle>,
    /** Where playback starts, ms. */
    val startMs: Long,
    /** Whether this session may write its position. */
    val writable: Boolean,
    val heading: String,
    val prevEpisodeId: String?,
    val nextEpisodeId: String?,
)

/**
 * The session for playing [itemId] from its download, or null when it has
 * none that is complete. Where it starts: from the head when asked
 * ([fromStart]), at a hand-off's second ([resumeSec]); else the server's
 * saved position when it answers within [SERVER_WAIT_MS] and the phone has
 * nothing newer to send it, else the last position played on this phone.
 * The neighbouring episodes are the server's when it answers, else the
 * ones on the phone.
 */
suspend fun downloadedSession(
    context: Context,
    container: AppContainer,
    itemId: String,
    fromStart: Boolean,
    resumeSec: Int,
): DownloadedSession? = withContext(Dispatchers.IO) {
    val engine = DownloadEngine.get(context)
    val download = runCatching { engine.manager.downloadIndex.getDownload(itemId) }.getOrNull() ?: return@withContext null
    if (download.state != Download.STATE_COMPLETED) return@withContext null
    val title = engine.titles[itemId]?.takeIf { it.extrasDone } ?: return@withContext null
    val online = container.serverReach.state.value != Reach.Unreachable
    val handoff = !fromStart && resumeSec > 1
    coroutineScope {
        // Online, the server's position - every device's - unless this phone
        // has a newer one still to send; when neither is known nothing is
        // written (the players' guard). Without the server, what this phone
        // played last: a title played here first starts at the head, and
        // its positions are kept to send.
        val savedAsk = async {
            when {
                fromStart || handoff -> null
                online && !container.progressSync.hasUnsent(itemId) ->
                    withTimeoutOrNull(SERVER_WAIT_MS) { runCatching { container.chinoApi.getProgress(itemId).positionSec }.getOrNull() }
                        ?: container.progressSync.lastPlayedSec(itemId)
                // Not played here yet: where the server had the viewer when
                // the download was asked for.
                else -> container.progressSync.lastPlayedSec(itemId) ?: title.savedPositionSec ?: 0
            }
        }
        val neighboursAsk = async { neighbours(container, title, online) }
        val saved = savedAsk.await()
        val durationMs = title.info?.durationMs ?: title.item.durationMs
        val resume = ResumeInput(
            savedSec = saved,
            durationSec = (durationMs ?: 0L) / 1000.0,
            startOver = fromStart,
            handoffSec = if (handoff) resumeSec else -1,
        )
        val subsDir = engine.titles.subtitlesDir(itemId)
        val sidecars = title.subtitles.mapNotNull { s ->
            val file = File(subsDir, s.file).takeIf { it.isFile } ?: return@mapNotNull null
            SidecarSubtitle(id = s.id, label = s.label, lang = s.lang, url = android.net.Uri.fromFile(file).toString(), format = s.format, forced = s.forced)
        }
        val (prev, next) = neighboursAsk.await()
        DownloadedSession(
            title = title,
            masterUri = download.request.uri.toString(),
            streamKeys = download.request.streamKeys,
            mediaSources = engine.playbackSources,
            sidecarSources = DefaultDataSource.Factory(context),
            sidecars = sidecars,
            startMs = resumeStartSec(resume) * 1000L,
            writable = mayWriteProgress(resume),
            heading = composePlayerTitle(title.item, title.seriesTitle),
            prevEpisodeId = prev,
            nextEpisodeId = next,
        )
    }
}

/** An episode's previous and next: in the series as the server lists it
 *  when it answers in time, else among the series' episodes on the phone. */
private suspend fun neighbours(container: AppContainer, title: DownloadedTitle, online: Boolean): Pair<String?, String?> {
    val seriesId = title.item.parentId?.takeIf { title.isEpisode } ?: return null to null
    val ids: List<String> = (
        if (online) {
            withTimeoutOrNull(SERVER_WAIT_MS) {
                runCatching { container.chinoApi.seriesEpisodes(seriesId).seasons.flatMap { s -> s.episodes.map { it.id } } }.getOrNull()
            }
        } else {
            null
        }
        ) ?: container.downloads?.entries?.value.orEmpty()
        .map { it.title }
        .filter { it.item.parentId == seriesId }
        .sortedWith(compareBy({ it.item.seasonNumber ?: 0 }, { it.item.episodeNumber ?: 0 }))
        .map { it.itemId }
    val i = ids.indexOf(title.itemId)
    if (i < 0) return null to null
    return ids.getOrNull(i - 1) to ids.getOrNull(i + 1)
}

/** How long a downloaded title's start waits for the server. */
private const val SERVER_WAIT_MS = 2_500L
