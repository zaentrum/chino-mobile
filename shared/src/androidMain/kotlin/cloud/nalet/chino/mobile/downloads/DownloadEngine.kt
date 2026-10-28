package cloud.nalet.chino.mobile.downloads

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.media3.common.C
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.SharedConnections
import cloud.nalet.chino.mobile.data.auth.withoutStreamToken
import cloud.nalet.chino.mobile.data.downloads.DEFAULT_DOWNLOAD_QUALITY
import cloud.nalet.chino.mobile.data.downloads.DownloadFailure
import cloud.nalet.chino.mobile.data.downloads.DownloadPreferences
import cloud.nalet.chino.mobile.data.downloads.downloadKey
import cloud.nalet.chino.mobile.data.downloads.pieceKey
import cloud.nalet.chino.mobile.data.downloads.withoutStreamTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Downloads on Android: Media3's download manager, fetching a title's HLS
 * renditions into a cache of its own in app-specific storage (no storage
 * permission), with [TitleFiles] beside it. One per process: the
 * [ChinoDownloadService] and the app's screens share it.
 *
 *     files/downloads/media/        the renditions' playlists and segments
 *                                   (Media3's SimpleCache, never evicted)
 *     files/downloads/titles/<key>/ each title's metadata, artwork, subtitles
 *     databases/chino_downloads.db  the download manager's index and the
 *                                   cache's own (apart from the Zap cache's)
 *
 * No stream token is ever on the disk. chino-stream writes the token it was
 * asked with into every URI of its playlists; a download keeps each playlist
 * without them ([withoutStreamTokens]), each piece under its URL without one
 * ([pieceKey]), and the index holds the master's URL without one. Each
 * request is signed as it goes out ([Signing]), with the token valid then -
 * only a request to the connected server, for a title of the account signed
 * in now: any other goes without one, and the downloads of other accounts
 * and servers wait, stopped. A refusal of the token (401/403) forgets it: the
 * manager's next attempt mints another. Playback reads the phone alone.
 *
 * Downloads run while there is a connection - an unmetered one where
 * downloads are Wi-Fi only (the default) - and nothing keeps the service in
 * the foreground while they wait for one: it stops, and they go on when the
 * connection comes back, while the app runs (Media3 restarts the service
 * where Android lets it, the app's network watch where Media3 does not),
 * and below Android 12 by its scheduler with the app closed.
 */
internal class DownloadEngine private constructor(context: Context) {
    val context: Context = context.applicationContext
    private val root = File(this.context.filesDir, "downloads")
    val titles = TitleFiles(File(root, "titles"))
    private val database = DownloadsDatabase(this.context)
    val cache = SimpleCache(File(root, "media"), NoOpCacheEvictor(), database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The app's graph as it is now - the Application's, rebuilt when the
     *  server changes - or null before the Application has built it. Read
     *  at each request: a process the system starts for the service alone
     *  has it as well. */
    private val app: AppContainer?
        get() = runCatching { (context as? AppContainerHost)?.container }.getOrNull()

    /** The client the downloads fetch with: the app's connection pool, and
     *  every request signed as it goes out. */
    private val http: OkHttpClient = SharedConnections.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(SigningInterceptor(::tokenFor))
        .build()

    /** The client the downloads' artwork and subtitles are fetched with -
     *  signed by their caller ([AndroidDownloads]), which asks the same of
     *  them: the connected server, the account signed in now. */
    val extrasClient: OkHttpClient = SharedConnections.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val upstream: DataSource.Factory = downloadUpstream(http) { runCatching { app?.streamTokenManager?.invalidate() } }

    /** A piece's key: its URL without the stream token. */
    val keys = CacheKeyFactory { spec -> spec.key ?: pieceKey(spec.uri.toString()) }

    private val downloadSources = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory(keys)
        .setUpstreamDataSourceFactory(upstream)

    /** What playback of a finished download reads: the pieces on the phone
     *  and nothing else. No upstream, no writes - it never asks the server,
     *  and a piece that is not there fails instead of streaming. */
    val playbackSources: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory(keys)
        .setCacheWriteDataSinkFactory(null)

    /** Media3's manager: one title at a time, three of its segments at
     *  once. */
    val manager = DownloadManager(
        this.context,
        DefaultDownloadIndex(database),
        DefaultDownloaderFactory(downloadSources, Executors.newFixedThreadPool(SEGMENT_THREADS)),
    ).apply {
        maxParallelDownloads = 1
    }

    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _preferences = MutableStateFlow(
        DownloadPreferences(
            quality = prefs.getInt(KEY_QUALITY, DEFAULT_DOWNLOAD_QUALITY),
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
        ),
    )
    val preferences: StateFlow<DownloadPreferences> = _preferences.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadSnapshot>>(emptyMap())

    /** Every download the manager has, by key - its state and progress, at
     *  most a second old while one is on its way. Snapshots: Media3's
     *  Download objects change their progress in place, and a map of the
     *  same objects would never read as changed. */
    val downloads: StateFlow<Map<String, DownloadSnapshot>> = _downloads.asStateFlow()

    /**
     * What each title takes on the phone now, by key: its media as the
     * manager counts it, and its folder - metadata, artwork, subtitles. The
     * one figure the downloads screen's line and rows, "delete all" and every
     * question before a sign-out or a change of server count. Only for a
     * title whose media is there or on its way, partly fetched while it
     * waits; one that failed keeps nothing (AndroidDownloads frees its media)
     * and has none.
     */
    val onPhone: StateFlow<Map<String, Long>> = combine(_downloads, titles.folderBytes) { downloads, folders ->
        onPhoneBytes(downloads, folders)
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _notMet = MutableStateFlow(0)

    /** The requirements the manager waits for (Requirements flags). */
    val notMet: StateFlow<Int> = _notMet.asStateFlow()

    /** Keys handed to the manager, not in its index yet. */
    val starting = MutableStateFlow<Set<String>>(emptySet())

    private val _indexRead = MutableStateFlow(false)

    /** The manager has read its index: [downloads] holds every download on
     *  the phone, not only the ones that changed since the process began. */
    val indexRead: StateFlow<Boolean> = _indexRead.asStateFlow()

    /** Called while a download is on its way and the phone is about to run
     *  out of room ([LOW_SPACE_BYTES]): [AndroidDownloads] stops them before
     *  the disk is full. */
    @Volatile
    var onLowSpace: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var ticking = false

    /** Wi-Fi and the processor kept awake while a download is on its way. */
    private val locks = DownloadLocks(this.context)

    /** Holds the locks while the manager downloads, lets them go when it
     *  does not (DownloadLocks.shouldHold). On the manager's thread. */
    private fun updateLocks() {
        val states = runCatching { manager.currentDownloads.map { it.state } }.getOrDefault(emptyList())
        locks.hold(DownloadLocks.shouldHold(states, manager.notMetRequirements, manager.downloadsPaused))
    }

    init {
        applyRequirements()
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                readAll()
                updateLocks()
            }

            override fun onDownloadsPausedChanged(downloadManager: DownloadManager, downloadsPaused: Boolean) = updateLocks()

            override fun onIdle(downloadManager: DownloadManager) = updateLocks()

            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                val key = download.request.id
                updateLocks()
                starting.value = starting.value - key
                _downloads.value = _downloads.value + (key to download.snapshot())
                if (download.state == Download.STATE_FAILED) {
                    // In memory at once, on the disk off the main thread
                    // (TitleFiles): a full disk fails a write, never the app.
                    titles.update(key) { it.copy(failure = failureOfDownload(finalException, freeBytes())) }
                }
                if (download.state == Download.STATE_DOWNLOADING) tick()
                // One that stopped as the connection went: nothing runs, so
                // nothing keeps the service in the foreground.
                if (download.state == Download.STATE_QUEUED && manager.notMetRequirements != 0) stopServiceWhileWaiting()
                if (!download.runs()) forgetResumeWhenNothingWaits()
                // Subtitles and artwork as the media comes: the app graph's
                // downloads, built here if this process has none yet.
                runCatching { (app?.downloads as? AndroidDownloads)?.changed(download) }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                _downloads.value = _downloads.value - download.request.id
                updateLocks()
                forgetResumeWhenNothingWaits()
            }

            override fun onRequirementsStateChanged(
                downloadManager: DownloadManager,
                requirements: Requirements,
                notMetRequirements: Int,
            ) {
                _notMet.value = notMetRequirements
                updateLocks()
                // Waiting for a connection: no service in the foreground for
                // it, and no notification for hours.
                if (notMetRequirements != 0) stopServiceWhileWaiting()
            }
        })
        if (manager.isInitialized) readAll()
    }

    suspend fun setQuality(sizeClass: Int) {
        prefs.edit().putInt(KEY_QUALITY, sizeClass).apply()
        _preferences.value = _preferences.value.copy(quality = sizeClass)
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply()
        _preferences.value = _preferences.value.copy(wifiOnly = wifiOnly)
        onMain { applyRequirements() }
    }

    /** Runs [block] on the main thread, the manager's: Media3 expects its
     *  calls there. */
    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** What is free in the app's storage now. */
    fun freeBytes(): Long = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L)

    /** Downloads that wait or were cut off carry on (the app came to the
     *  front): unpaused - after Android 15's daily limit stopped them - and
     *  the service started for them, where they can run now. */
    fun resumeIfPending() = onMain {
        manager.resumeDownloads()
        resumeWanted = true
        // A manager just built reads its index first ([readAll] goes on).
        if (manager.isInitialized) startServiceIfPending()
    }

    @Volatile
    private var resumeWanted = false

    /** The service, for downloads that can run now (a connection there,
     *  none stopped), once asked ([resumeIfPending]) and the index is read.
     *  Not for downloads that wait: they need no service until they run. */
    private fun startServiceIfPending() {
        if (!resumeWanted) return
        resumeWanted = false
        if (manager.notMetRequirements != 0) return
        val runnable = _downloads.value.values.any {
            it.stopReason == Download.STOP_REASON_NONE &&
                (it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING)
        }
        if (runnable) {
            runCatching {
                androidx.media3.exoplayer.offline.DownloadService.sendResumeDownloads(context, ChinoDownloadService::class.java, true)
            }
            // The service has them: no job for them in the background.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadResumeJob.cancel(context)
        }
    }

    /** No service in the foreground while the downloads wait for a
     *  connection: it is stopped, and started again as they run - by the app
     *  in front, or, in the background from Android 14, by the job planned
     *  here for when the connection is back ([DownloadResumeJob]); planned
     *  first, while the service still runs, as Android asks. Only a service
     *  that runs already (in the foreground) is stopped. */
    fun stopServiceWhileWaiting() = onMain {
        if (manager.notMetRequirements == 0) return@onMain
        if (_downloads.value.values.any { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_REMOVING }) return@onMain
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !manager.downloadsPaused &&
            manager.currentDownloads.any { it.runs() }
        ) {
            DownloadResumeJob.plan(context, _preferences.value.wifiOnly)
        }
        if (ChinoDownloadService.running) {
            runCatching { context.stopService(Intent(context, ChinoDownloadService::class.java)) }
        }
    }

    /** Nothing left that waits to run: no job for it. */
    private fun forgetResumeWhenNothingWaits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (runCatching { manager.currentDownloads.any { it.runs() } }.getOrDefault(true)) return
        DownloadResumeJob.cancel(context)
    }

    /** The downloads' notification as it stands: the service's, and the
     *  job's while it carries them in the background. */
    fun notification(): android.app.Notification =
        downloadNotification(context, manager.currentDownloads, manager.notMetRequirements)

    /** Whether the downloads wait for a connection now. */
    val waitingForConnection: Boolean get() = manager.notMetRequirements != 0

    /** Hands [request] to the manager directly, with [stopReason]: for a
     *  download that waits (no service for it), or where the service cannot
     *  be started now. */
    fun addDirectly(request: DownloadRequest, stopReason: Int): Boolean {
        var ok = true
        val add = { ok = runCatching { manager.addDownload(request, stopReason) }.isSuccess }
        if (Looper.myLooper() == Looper.getMainLooper()) add() else runBlocking(Dispatchers.Main) { add() }
        return ok
    }

    private fun readAll() {
        val all = HashMap<String, DownloadSnapshot>()
        runCatching {
            manager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) cursor.download.let { all[it.request.id] = it.snapshot() }
            }
        }
        _downloads.value = all
        _notMet.value = manager.notMetRequirements
        _indexRead.value = true
        if (all.values.any { it.state == Download.STATE_DOWNLOADING }) tick()
        startServiceIfPending()
    }

    /** While a download is on its way, its progress once a second - and the
     *  room left on the phone: below [LOW_SPACE_BYTES] the downloads stop
     *  before the disk is full. */
    private fun tick() {
        if (ticking) return
        ticking = true
        scope.launch(Dispatchers.Main) {
            try {
                while (true) {
                    delay(1_000)
                    val current = runCatching { manager.currentDownloads }.getOrDefault(emptyList())
                    if (current.isNotEmpty()) {
                        _downloads.value = _downloads.value + current.associate { it.request.id to it.snapshot() }
                    }
                    if (current.none { it.state == Download.STATE_DOWNLOADING }) break
                    // Still on its way: the processor's lock runs on.
                    locks.keep()
                    val free = kotlinx.coroutines.withContext(Dispatchers.IO) { freeBytes() }
                    if (free < LOW_SPACE_BYTES) onLowSpace?.invoke()
                }
            } finally {
                ticking = false
            }
        }
    }

    /** Wi-Fi only, or any connection. */
    private fun applyRequirements() {
        val flags = if (_preferences.value.wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK
        manager.requirements = Requirements(flags)
        _notMet.value = manager.notMetRequirements
    }

    /**
     * The stream token a request gets: one only for the connected server's
     * media of a title of the account signed in now - a request to another
     * origin, or for a title of another account or server, goes without one.
     */
    private fun tokenFor(url: HttpUrl): String? {
        val graph = app ?: return null
        return streamTokenFor(url, graph.serverBase, graph.activeOwner.value, titles.titles.value) {
            runCatching { runBlocking { withTimeoutOrNull(TOKEN_WAIT_MS) { graph.streamTokenManager.valid() } } }.getOrNull()
        }
    }

    companion object {
        private const val SEGMENT_THREADS = 3
        private const val PREFS = "chino_downloads"
        private const val KEY_QUALITY = "quality"
        private const val KEY_WIFI_ONLY = "wifi_only"

        /** How long a request waits for its token to be minted. */
        private const val TOKEN_WAIT_MS = 15_000L

        @Volatile
        private var instance: DownloadEngine? = null

        fun get(context: Context): DownloadEngine =
            instance ?: synchronized(this) { instance ?: DownloadEngine(context).also { instance = it } }
    }
}

/**
 * The stream token a download's request gets: one only for the connected
 * server's media ([serverBase]'s origin) of a title of the account signed in
 * now ([owner]) - its path names the item (/items/{id}/...) - minted by
 * [mint] then. A request to another origin, or for a title of another
 * account or server, gets none.
 */
internal fun streamTokenFor(
    url: HttpUrl,
    serverBase: String,
    owner: cloud.nalet.chino.mobile.data.downloads.Owner?,
    titles: Map<String, cloud.nalet.chino.mobile.data.downloads.DownloadedTitle>,
    mint: () -> String?,
): String? {
    val base = serverBase.toHttpUrlOrNull() ?: return null
    if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return null
    if (owner == null || owner.server.trimEnd('/') != serverBase.trimEnd('/')) return null
    val segments = url.pathSegments
    val at = segments.indexOf("items")
    if (at < 0) return null
    val itemId = segments.getOrNull(at + 1) ?: return null
    val title = titles[downloadKey(owner.server, itemId)] ?: return null
    if (!title.ownedBy(owner)) return null
    return mint()
}

/** Signs a download's request as it goes out ([tokenFor]), in place of any
 *  token it carries; one that gets none goes without. */
internal class SigningInterceptor(private val tokenFor: (HttpUrl) -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val bare = request.url.newBuilder().removeAllQueryParameters(STREAM_PARAM).build()
        val token = tokenFor(bare)
        val url = if (token != null) bare.newBuilder().addQueryParameter(STREAM_PARAM, token).build() else bare
        return chain.proceed(request.newBuilder().url(url).build())
    }

    private companion object {
        const val STREAM_PARAM = "stream"
    }
}

/** What a download reads from the server: [http] (which signs each
 *  request), its playlists without the tokens in them ([UnsignedPlaylists]),
 *  and a refused token forgotten ([onRefused]). */
internal fun downloadUpstream(http: OkHttpClient, onRefused: () -> Unit): DataSource.Factory = DataSource.Factory {
    RefusalAware(UnsignedPlaylists(OkHttpDataSource.Factory(http).setUserAgent(USER_AGENT).createDataSource()), onRefused)
}

private const val USER_AGENT = "chino-mobile/0.2 (Android; downloads)"

/** A download as it is at one moment: its state, how far, what it fetched,
 *  its stop reason, and its request. */
internal data class DownloadSnapshot(
    val state: Int,
    val percent: Float,
    val bytes: Long,
    val request: DownloadRequest,
    val stopReason: Int = Download.STOP_REASON_NONE,
)

private fun Download.snapshot() = DownloadSnapshot(state, percentDownloaded, bytesDownloaded, request, stopReason)

/** What each title takes on the phone ([DownloadEngine.onPhone]): the media
 *  of a download there or on its way and its title's folder; none for one
 *  that failed or goes. */
internal fun onPhoneBytes(downloads: Map<String, DownloadSnapshot>, folders: Map<String, Long>): Map<String, Long> =
    downloads
        .filterValues { it.state != Download.STATE_FAILED && it.state != Download.STATE_REMOVING }
        .mapValues { (key, d) -> d.bytes.coerceAtLeast(0L) + (folders[key] ?: 0L) }

/** What of a download's media is on the phone now, without its title's
 *  folder: none for one that failed or goes, or has none ([d] null). */
internal fun mediaBytesOf(d: DownloadSnapshot?): Long =
    if (d == null || d.state == Download.STATE_FAILED || d.state == Download.STATE_REMOVING) 0L else d.bytes.coerceAtLeast(0L)

/** Whether a title counts as on the phone for the storage line, "delete
 *  all" and the questions before a sign-out: some of its media is there, or
 *  it is complete - a title queued with only its artwork is not. */
internal fun countsAsOnThePhone(d: DownloadSnapshot?): Boolean =
    mediaBytesOf(d) > 0 || d?.state == Download.STATE_COMPLETED

/** Media3's download index and the cache's, in a database file of their
 *  own: the Zap cache keeps its own in Media3's default one. */
private class DownloadsDatabase(context: Context) :
    SQLiteOpenHelper(context, "chino_downloads.db", null, 1), DatabaseProvider {
    override fun onCreate(db: SQLiteDatabase) = Unit
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/**
 * Serves an HLS playlist without the stream tokens chino-stream wrote into
 * it ([withoutStreamTokens]): read whole, and the range asked for served of
 * that, so a playlist is kept on the phone, and played, without one. Any
 * other piece passes as it is. The URI it reports is without its token
 * either way: the cache keeps no URL that carries one.
 */
internal class UnsignedPlaylists(private val inner: DataSource) : DataSource {
    private var data: ByteArray? = null
    private var readAt = 0
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        uri = unsigned(dataSpec.uri)
        if (dataSpec.uri.path?.endsWith(".m3u8") != true) {
            data = null
            return inner.open(dataSpec)
        }
        val whole = dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        val body = ByteArrayOutputStream()
        try {
            inner.open(whole)
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = inner.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                body.write(buf, 0, n)
            }
        } finally {
            inner.close()
        }
        val clean = withoutStreamTokens(body.toByteArray().decodeToString()).encodeToByteArray()
        val start = dataSpec.position.coerceIn(0L, clean.size.toLong()).toInt()
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) clean.size else (start + dataSpec.length).coerceAtMost(clean.size.toLong()).toInt()
        data = clean.copyOfRange(start, end)
        readAt = 0
        return (end - start).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val d = data ?: return inner.read(buffer, offset, length)
        if (length == 0) return 0
        if (readAt >= d.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, d.size - readAt)
        System.arraycopy(d, readAt, buffer, offset, n)
        readAt += n
        return n
    }

    override fun getUri(): Uri? = if (data != null) uri else inner.uri?.let(::unsigned) ?: uri

    override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

    override fun close() {
        if (data == null) inner.close()
        data = null
    }

    private fun unsigned(u: Uri): Uri = Uri.parse(withoutStreamToken(u.toString()))
}

/** Forgets the stream token when the server refuses one (401/403): the
 *  manager tries the download again, and that asks for a new one. */
private class RefusalAware(
    private val inner: DataSource,
    private val onRefused: () -> Unit,
) : DataSource by inner {
    override fun open(dataSpec: DataSpec): Long = try {
        inner.open(dataSpec)
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode == 401 || e.responseCode == 403) onRefused()
        throw e
    }
}

/** Why a download stopped, from the manager's last exception and the room
 *  left on the phone. */
internal fun failureOfDownload(e: Throwable?, freeBytes: Long): DownloadFailure {
    val causes = generateSequence(e) { it.cause }.take(8).toList()
    val noSpace = freeBytes < LOW_SPACE_BYTES || causes.any {
        it is IOException && (it.message.orEmpty().contains("ENOSPC") || it.message.orEmpty().contains("No space left", ignoreCase = true))
    }
    if (noSpace) return DownloadFailure.NoSpace
    causes.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.let { http ->
        return when (http.responseCode) {
            404, 410 -> DownloadFailure.Gone
            in 500..599 -> DownloadFailure.ServerProblem
            else -> DownloadFailure.Refused
        }
    }
    if (causes.any { it is HttpDataSource.HttpDataSourceException || it is UnknownHostException || it is SocketTimeoutException || it is ConnectException }) {
        return DownloadFailure.NoAnswer
    }
    return DownloadFailure.Stopped
}

/** Below this, the downloads stop before the disk is full, and a download
 *  that fails is out of room. */
internal const val LOW_SPACE_BYTES = 150L * 1_000_000
