package cloud.nalet.chino.mobile.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.artworkUrl
import cloud.nalet.chino.mobile.data.downloads.DownloadEntry
import cloud.nalet.chino.mobile.data.downloads.DownloadFailure
import cloud.nalet.chino.mobile.data.downloads.DownloadPlan
import cloud.nalet.chino.mobile.data.downloads.DownloadPreferences
import cloud.nalet.chino.mobile.data.downloads.DownloadedSubtitle
import cloud.nalet.chino.mobile.data.downloads.DownloadedTitle
import cloud.nalet.chino.mobile.data.downloads.Downloads
import cloud.nalet.chino.mobile.data.downloads.ManagerState
import cloud.nalet.chino.mobile.data.downloads.PlanResult
import cloud.nalet.chino.mobile.data.downloads.Reach
import cloud.nalet.chino.mobile.data.downloads.StorageUse
import cloud.nalet.chino.mobile.data.downloads.Waiting
import cloud.nalet.chino.mobile.data.downloads.downloadStatus
import cloud.nalet.chino.mobile.data.downloads.failureOf
import cloud.nalet.chino.mobile.data.downloads.qualityName
import cloud.nalet.chino.mobile.data.downloads.resolveUrl
import cloud.nalet.chino.mobile.data.downloads.samePiece
import cloud.nalet.chino.mobile.feedback.CurrentActivity
import cloud.nalet.chino.mobile.ui.player.sidecarForced
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [Downloads] on Android, over the [DownloadEngine]: a plan becomes a Media3
 * download request of the variant and audio renditions it picked (their
 * HlsMultivariantPlaylist stream keys), the title's folder gets its
 * metadata at once, and its sidecar subtitles and artwork once the media
 * starts coming - when the download's requirements hold, Wi-Fi where they
 * say Wi-Fi.
 */
internal class AndroidDownloads(
    private val engine: DownloadEngine,
    private val container: AppContainer,
) : Downloads {
    private val context = engine.context
    private val extrasLock = Mutex()
    private val extrasRunning = HashSet<String>()

    init {
        // Titles whose media came in while their extras could not - the
        // process ended first, or the server did not answer: once the
        // manager has read its index, and again each time the server
        // answers after it did not.
        container.appScope.launch {
            engine.indexRead.first { it }
            container.serverReach.state.collect { reach ->
                if (reach != Reach.Unreachable) catchUpExtras()
            }
        }
    }

    /** A download changed ([DownloadEngine]'s listener): its subtitles and
     *  artwork once its media starts coming. */
    internal fun changed(d: Download) {
        if (d.state == Download.STATE_DOWNLOADING || d.state == Download.STATE_COMPLETED) fetchExtrasLater(d.request.id)
    }

    private fun catchUpExtras() {
        val downloads = engine.downloads.value
        engine.titles.titles.value.values
            .filter { !it.extrasDone && downloads[it.itemId]?.state == Download.STATE_COMPLETED }
            .forEach { fetchExtrasLater(it.itemId) }
    }

    override val entries: StateFlow<List<DownloadEntry>> = combine(
        engine.titles.titles,
        engine.downloads,
        engine.notMet,
        engine.preferences,
        engine.starting,
    ) { titles, downloads, notMet, prefs, starting ->
        val waits = Waiting(
            network = notMet and (Requirements.NETWORK or Requirements.NETWORK_UNMETERED) != 0,
            charging = notMet and Requirements.DEVICE_CHARGING != 0,
        )
        titles.values
            .map { title ->
                val d = downloads[title.itemId]
                val bytes = d?.bytes ?: 0L
                DownloadEntry(
                    title = title,
                    status = downloadStatus(
                        state = d?.let { managerState(it.state) },
                        percent = d?.percent ?: 0f,
                        bytes = bytes,
                        waits = waits,
                        wifiOnly = prefs.wifiOnly,
                        extrasDone = title.extrasDone,
                        failure = title.failure,
                        starting = title.itemId in starting,
                    ),
                    bytes = bytes,
                )
            }
            .sortedByDescending { it.title.addedAtMs }
    }.stateIn(container.appScope, SharingStarted.Eagerly, emptyList())

    override val preferences: StateFlow<DownloadPreferences> get() = engine.preferences

    override suspend fun setQuality(sizeClass: Int) = engine.setQuality(sizeClass)

    override suspend fun setWifiOnly(wifiOnly: Boolean) = engine.setWifiOnly(wifiOnly)

    override suspend fun start(plan: DownloadPlan) = withContext(Dispatchers.IO) {
        val id = plan.item.id
        val title = DownloadedTitle(
            itemId = id,
            item = plan.item,
            seriesTitle = plan.seriesTitle,
            info = plan.info,
            segments = plan.segments,
            quality = qualityName(plan.quality),
            estimatedBytes = plan.estimatedBytes,
            audioLanguages = plan.audios.map { it.language },
            subtitles = plan.subtitles.map { s ->
                DownloadedSubtitle(
                    id = s.id,
                    lang = s.lang,
                    label = s.label,
                    format = s.format,
                    forced = sidecarForced(s),
                    file = s.id.filter { it.isLetterOrDigit() || it == '-' } + ".vtt",
                    source = s.url.ifBlank { "/api/v1/play/subs/${s.id}.vtt" },
                )
            },
            addedAtMs = currentTimeMillis(),
            server = plan.server,
            savedPositionSec = plan.savedPositionSec,
        )
        // Not with less room than it takes - with what the downloads queued
        // before it still take - and a margin: a season that does not fit
        // says so for the episodes that do not, at once, not halfway.
        if (engine.freeBytes() < (plan.estimatedBytes ?: 0L) + pendingBytes(except = id) + SPACE_MARGIN_BYTES) {
            engine.titles.put(title.copy(failure = DownloadFailure.NoSpace))
            engine.starting.value = engine.starting.value - id
            fetchArtwork(id)
            return@withContext
        }
        val keys = streamKeys(plan)
        if (keys == null) {
            engine.titles.put(title.copy(failure = DownloadFailure.Stopped))
            engine.starting.value = engine.starting.value - id
            fetchArtwork(id)
            return@withContext
        }
        engine.titles.put(title)
        engine.starting.value = engine.starting.value + id
        val request = DownloadRequest.Builder(id, Uri.parse(plan.masterUrl))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setStreamKeys(keys)
            // The title, for the notification.
            .setData(plan.item.title.encodeToByteArray())
            .build()
        if (!hand(request)) {
            engine.starting.value = engine.starting.value - id
            engine.titles.update(id) { it.copy(failure = DownloadFailure.Stopped) }
        }
        // Its poster and backdrop now, while the page that asked shows them:
        // the downloads screen has its picture before the media comes.
        fetchArtwork(id)
    }

    /**
     * Hands [request] to the download service. Where the service cannot be
     * started in the foreground now - the app went to the background while
     * the download was worked out (Android 12 and later) - the manager takes
     * it itself: it runs while the process does, and the service picks it
     * up as the app comes to the front. False when neither took it.
     */
    private suspend fun hand(request: DownloadRequest): Boolean {
        val sent = runCatching { DownloadService.sendAddDownload(context, ChinoDownloadService::class.java, request, true) }
        if (sent.isSuccess) return true
        return withContext(Dispatchers.Main) { runCatching { engine.manager.addDownload(request) }.isSuccess }
    }

    /** What the downloads queued or on their way still take, as the
     *  server's playlists said, but [except]'s. */
    private fun pendingBytes(except: String): Long {
        val downloads = engine.downloads.value
        val starting = engine.starting.value
        return engine.titles.titles.value.values
            .filter { it.itemId != except && it.failure == null }
            .sumOf { t ->
                val d = downloads[t.itemId]
                val coming = if (d == null) {
                    t.itemId in starting
                } else {
                    d.state != Download.STATE_COMPLETED && d.state != Download.STATE_FAILED && d.state != Download.STATE_REMOVING
                }
                if (coming) ((t.estimatedBytes ?: 0L) - (d?.bytes ?: 0L)).coerceAtLeast(0L) else 0L
            }
    }

    override fun cancel(itemId: String) = delete(itemId)

    override fun delete(itemId: String) {
        engine.starting.value = engine.starting.value - itemId
        engine.titles.remove(itemId)
        runCatching { DownloadService.sendRemoveDownload(context, ChinoDownloadService::class.java, itemId, false) }
            .onFailure { engine.onMain { engine.manager.removeDownload(itemId) } }
    }

    override fun deleteAll() {
        engine.starting.value = emptySet()
        engine.titles.removeAll()
        runCatching { DownloadService.sendRemoveAllDownloads(context, ChinoDownloadService::class.java, false) }
            .onFailure { engine.onMain { engine.manager.removeAllDownloads() } }
    }

    override fun retry(itemId: String) {
        if (engine.titles[itemId] == null) return
        val d = engine.downloads.value[itemId]
        when {
            d != null && d.state == Download.STATE_COMPLETED -> {
                engine.titles.update(itemId) { it.copy(failure = null) }
                fetchExtrasLater(itemId)
            }
            d != null -> {
                engine.titles.update(itemId) { it.copy(failure = null) }
                container.appScope.launch {
                    if (!hand(d.request)) engine.titles.update(itemId) { it.copy(failure = DownloadFailure.Stopped) }
                }
            }
            else -> {
                // It never reached the manager (no room, a master it could
                // not read): worked out again, as it was asked for - queued
                // meanwhile, not failed.
                engine.starting.value = engine.starting.value + itemId
                engine.titles.update(itemId) { it.copy(failure = null) }
                container.appScope.launch {
                    val failure = try {
                        val settings = container.settings.flow.first()
                        val r = container.downloadPlanner.plan(itemId, engine.preferences.value.quality, settings.preferredAudioLang, settings.preferredSubLang)
                        when {
                            // Deleted while it was worked out: it stays deleted.
                            engine.titles[itemId] == null -> null
                            else -> when (r) {
                                // start() hands it to the manager, or records why not.
                                is PlanResult.Ready -> {
                                    start(r.plan)
                                    null
                                }
                                is PlanResult.NotPossible -> r.reason
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failureOf(e)
                    }
                    if (failure != null) {
                        engine.titles.update(itemId) { it.copy(failure = failure) }
                        engine.starting.value = engine.starting.value - itemId
                    }
                }
            }
        }
    }

    override suspend fun storage(): StorageUse = withContext(Dispatchers.IO) {
        StorageUse(usedBytes = engine.usedBytes(), freeBytes = engine.freeBytes())
    }

    override suspend fun setInLibrary(itemId: String, inLibrary: Boolean) = withContext(Dispatchers.IO) {
        engine.titles.update(itemId) { if (it.goneFromLibrary == !inLibrary) it else it.copy(goneFromLibrary = !inLibrary) }
    }

    override fun askToNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || asked) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val activity = CurrentActivity.get() ?: return
        asked = true
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY_REQUEST)
    }

    @Volatile
    private var asked = false

    override fun fileUrl(itemId: String, file: String?): String? {
        val f = File(engine.titles.dirOf(itemId), file ?: return null)
        return if (f.isFile) Uri.fromFile(f).toString() else null
    }

    /**
     * The stream keys of what [plan] keeps, as Media3 numbers the master:
     * its own parse of the same text, each pick found by its URL, so an
     * index is Media3's whatever the master holds. Null when the master
     * cannot be read.
     */
    private fun streamKeys(plan: DownloadPlan): List<StreamKey>? {
        val parsed = runCatching {
            HlsPlaylistParser().parse(Uri.parse(plan.masterUrl), plan.masterText.byteInputStream()) as? HlsMultivariantPlaylist
        }.getOrNull() ?: return null
        fun indexOf(urls: List<String>, uri: String, fallback: Int): Int {
            val wanted = resolveUrl(plan.masterUrl, uri)
            return urls.indexOfFirst { samePiece(it, wanted) }.takeIf { it >= 0 } ?: fallback
        }
        val variant = indexOf(parsed.variants.map { it.url.toString() }, plan.variant.uri, plan.variant.index)
        if (variant !in parsed.variants.indices) return null
        val audioUrls = parsed.audios.map { it.url?.toString().orEmpty() }
        val audios = plan.audios.map { a -> indexOf(audioUrls, a.uri, a.index) }
            .filter { it in parsed.audios.indices }
        return listOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, variant)) +
            audios.distinct().map { StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, it) }
    }

    private fun fetchExtrasLater(itemId: String) {
        container.appScope.launch(Dispatchers.IO) { fetchExtras(itemId) }
    }

    /**
     * The title's sidecar subtitles and artwork, beside its media. The
     * subtitles must come: a download without them is not on the phone yet,
     * and one whose media is in and whose subtitles fail is a failed one,
     * for its reason. Artwork is best effort: a title without a poster
     * shows its initials, as everywhere in the app.
     */
    private suspend fun fetchExtras(itemId: String) {
        extrasLock.withLock {
            if (!extrasRunning.add(itemId)) return
        }
        try {
            val title = engine.titles[itemId] ?: return
            if (title.extrasDone) return
            val token = container.streamTokenManager.valid()
            val subsDir = engine.titles.subtitlesDir(itemId).apply { mkdirs() }
            val gone = mutableSetOf<String>()
            for (s in title.subtitles) {
                val file = File(subsDir, s.file)
                if (file.isFile && file.length() > 0) continue
                val url = artworkUrl(title.server, s.source ?: "/api/v1/play/subs/${s.id}.vtt", token)
                    ?: throw IOException("no subtitle url")
                try {
                    fetchTo(url, file)
                } catch (e: ApiStatusException) {
                    // One the server no longer has (its subtitles were made
                    // again) is left out; the title plays without it.
                    if (e.status == 404 || e.status == 410) gone += s.id else throw e
                }
            }
            fetchArtwork(itemId, token)
            engine.titles.update(itemId) { t ->
                t.copy(subtitles = t.subtitles.filterNot { it.id in gone }, extrasDone = true, failure = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Waits for the next start of its media, or the server's next
            // answer; once the media is in, it is a failure the control names.
            if (engine.downloads.value[itemId]?.state == Download.STATE_COMPLETED) {
                engine.titles.update(itemId) { it.copy(failure = failureOf(e)) }
            }
        } finally {
            extrasLock.withLock { extrasRunning.remove(itemId) }
        }
    }

    /**
     * The title's poster (an episode's: its series') and its backdrop (an
     * episode's: its still), best effort: a title without one shows its
     * initials, as everywhere in the app. Kept once there.
     */
    private suspend fun fetchArtwork(itemId: String, token: String? = null) = withContext(Dispatchers.IO) {
        val title = engine.titles[itemId] ?: return@withContext
        if (title.poster != null && title.backdrop != null) return@withContext
        val t = token ?: runCatching { container.streamTokenManager.valid() }.getOrNull() ?: return@withContext
        val dir = engine.titles.dirOf(itemId)
        val posterId = title.item.parentId?.takeIf { title.isEpisode } ?: itemId
        val poster = title.poster ?: runCatching {
            fetchTo("${title.server}/v1/items/$posterId/poster?stream=$t", File(dir, POSTER))
            POSTER
        }.getOrNull()
        val backdrop = title.backdrop ?: runCatching {
            fetchTo("${title.server}/v1/items/$itemId/backdrop?stream=$t", File(dir, BACKDROP))
            BACKDROP
        }.getOrNull()
        engine.titles.update(itemId) { it.copy(poster = poster ?: it.poster, backdrop = backdrop ?: it.backdrop) }
    }

    private val http = cloud.nalet.chino.mobile.data.api.SharedConnections.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Fetches [url] into [file], through a temporary file. A refusal is an
     *  [ApiStatusException] of its path - never of its token. */
    private fun fetchTo(url: String, file: File) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiStatusException(resp.code, resp.request.url.encodedPath)
            val body = resp.body ?: throw IOException("empty body")
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private companion object {
        /** Room kept free beside a download's size. */
        const val SPACE_MARGIN_BYTES = 200L * 1_000_000
        const val NOTIFY_REQUEST = 7302
        const val POSTER = "poster.jpg"
        const val BACKDROP = "backdrop.jpg"
    }
}

/** Media3's download state as the shared mapping names it. */
internal fun managerState(state: Int): ManagerState = when (state) {
    Download.STATE_QUEUED -> ManagerState.Queued
    Download.STATE_STOPPED -> ManagerState.Stopped
    Download.STATE_DOWNLOADING -> ManagerState.Downloading
    Download.STATE_COMPLETED -> ManagerState.Completed
    Download.STATE_FAILED -> ManagerState.Failed
    Download.STATE_REMOVING -> ManagerState.Removing
    else -> ManagerState.Restarting
}
