package cloud.nalet.chino.mobile.downloads

import android.content.Context
import androidx.media3.common.StreamKey
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.offline.Download
import cloud.nalet.chino.mobile.cast.resumeSecOf
import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.downloads.AvailabilityCheck
import cloud.nalet.chino.mobile.data.downloads.DownloadedTitle
import cloud.nalet.chino.mobile.data.downloads.Reach
import cloud.nalet.chino.mobile.data.downloads.checkDue
import cloud.nalet.chino.mobile.data.downloads.titleOf
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
 * kept of the title.
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

/** What playing a title from the phone comes to. */
sealed interface LocalPlay {
    /** It plays from the phone. */
    data class Ready(val session: DownloadedSession) : LocalPlay

    /** The server said, just now, that the account may no longer watch it:
     *  it was deleted from the phone, and the player says so. */
    data object NoLongerAvailable : LocalPlay

    /** Not checked with the server for 30 days, which cannot be reached: it
     *  plays again once it can. */
    data object CheckNeeded : LocalPlay

    /** Not on the phone for the account signed in now, not wholly, or the
     *  playback goes to another screen: it plays from the server. */
    data object None : LocalPlay
}

/**
 * Playing [itemId] from the phone, for the account signed in now - or why
 * not. Never while playback goes to another screen (a Cast session: it plays
 * from the server there, and nothing on the phone goes to it). With the
 * server, the server says first whether the account may still watch it, in
 * one quick request; without it, a download checked within 30 days plays.
 * Where it starts: from the head when asked ([fromStart]), at a hand-off's
 * second ([resumeSec]); else the server's saved position when it answers
 * within [SERVER_WAIT_MS] and the phone has nothing newer to send it, else
 * the last position played on this phone, else where the server had the
 * account as it downloaded it. The neighbouring episodes are the server's
 * when it answers, else the ones on the phone.
 */
suspend fun downloadedSession(
    context: Context,
    container: AppContainer,
    itemId: String,
    fromStart: Boolean,
    resumeSec: Int,
): LocalPlay {
    // Asked on the caller's thread - the player's, the main one - where the
    // Cast framework answers for itself.
    if (container.playsOnAnotherScreen()) return LocalPlay.None
    return playFromThePhone(context, container, itemId, fromStart, resumeSec)
}

private suspend fun playFromThePhone(
    context: Context,
    container: AppContainer,
    itemId: String,
    fromStart: Boolean,
    resumeSec: Int,
): LocalPlay = withContext(Dispatchers.IO) {
    val owner = container.activeOwner.value ?: return@withContext LocalPlay.None
    val engine = DownloadEngine.get(context)
    engine.titles.awaitReady()
    val found = titleOf(engine.titles.titles.value, owner, itemId) ?: return@withContext LocalPlay.None
    val download = runCatching { engine.manager.downloadIndex.getDownload(found.key) }.getOrNull() ?: return@withContext LocalPlay.None
    if (download.state != Download.STATE_COMPLETED || !found.extrasDone) return@withContext LocalPlay.None
    val online = container.serverReach.state.value != Reach.Unreachable
    if (online) {
        val check = withTimeoutOrNull(PLAY_CHECK_MS) { container.downloads?.checkAvailability(listOf(itemId)) }
        if (check is AvailabilityCheck.Done && check.deleted.any { it.itemId == itemId }) {
            return@withContext LocalPlay.NoLongerAvailable
        }
    }
    // As the check left it: checked just now, or as it was.
    val title = titleOf(engine.titles.titles.value, owner, itemId) ?: return@withContext LocalPlay.None
    val claim = title.claimOf(owner.subject) ?: return@withContext LocalPlay.None
    if (checkDue(claim, currentTimeMillis())) return@withContext LocalPlay.CheckNeeded
    val handoff = !fromStart && resumeSec > 1
    coroutineScope {
        // Online, the server's position - every device's - unless this phone
        // has a newer one still to send. Without its answer, or without the
        // server, what this phone played last, else where the server had the
        // account as it downloaded it - never nothing, so that the session
        // writes its positions (S2).
        val savedAsk = async {
            when {
                fromStart || handoff -> null
                online && !container.progressSync.hasUnsent(itemId) ->
                    withTimeoutOrNull(SERVER_WAIT_MS) { runCatching { resumeSecOf(container.chinoApi.getProgress(itemId)) }.getOrNull() }
                        ?: container.progressSync.lastPlayedSec(itemId) ?: claim.savedPositionSec ?: 0
                else -> container.progressSync.lastPlayedSec(itemId) ?: claim.savedPositionSec ?: 0
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
        val subsDir = engine.titles.subtitlesDir(title.key)
        val sidecars = title.subtitles.mapNotNull { s ->
            val file = File(subsDir, s.file).takeIf { it.isFile } ?: return@mapNotNull null
            SidecarSubtitle(id = s.id, label = s.label, lang = s.lang, url = android.net.Uri.fromFile(file).toString(), format = s.format, forced = s.forced)
        }
        val (prev, next) = neighboursAsk.await()
        LocalPlay.Ready(
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
            ),
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

/** How long it waits for the server to say the account may still watch it. */
private const val PLAY_CHECK_MS = 3_000L
