package cloud.nalet.chino.mobile.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.SharedConnections
import cloud.nalet.chino.mobile.data.downloads.DEFAULT_DOWNLOAD_QUALITY
import cloud.nalet.chino.mobile.data.downloads.DownloadFailure
import cloud.nalet.chino.mobile.data.downloads.DownloadPreferences
import cloud.nalet.chino.mobile.data.downloads.pieceKey
import cloud.nalet.chino.mobile.data.downloads.signedNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 *     files/downloads/titles/<id>/  each title's metadata, artwork, subtitles
 *     databases/chino_downloads.db  the download manager's index and the
 *                                   cache's own (apart from the Zap cache's)
 *
 * Every piece is kept under its URL without the stream token ([pieceKey]),
 * and every request is signed with the token valid when it goes out
 * ([signedNow], from the app's graph [app]): chino-stream writes the token
 * it was asked with into each URI of its playlists, so a download resumed
 * after the token's 6 h asks with a fresh one, and playback finds each
 * piece by its key whatever token fetched it. A refusal of the token
 * (401/403) forgets it: the manager's next attempt mints another. The graph
 * is the Application's, read at each request - a process the system starts
 * for the service alone, with no screen open, has it as well.
 *
 * Downloads run while their requirements hold: a connection - an unmetered
 * one where downloads are Wi-Fi only (the default) - and, while the battery
 * is low, the charger. Media3 keeps the service in the foreground while it
 * waits (Android 12 and later) and picks up as they come back; below 12 its
 * scheduler restarts it.
 */
internal class DownloadEngine private constructor(context: Context) {
    val context: Context = context.applicationContext
    private val root = File(this.context.filesDir, "downloads")
    val titles = TitleFiles(File(root, "titles"))
    private val database = DownloadsDatabase(this.context)
    val cache = SimpleCache(File(root, "media"), NoOpCacheEvictor(), database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The app's graph as it is now - the Application's, rebuilt when the
     *  server changes - or null before the Application has built it. */
    private val app: AppContainer?
        get() = runCatching { (context as? AppContainerHost)?.container }.getOrNull()

    private val http = SharedConnections.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Signs each request with the token valid now (on the downloader's own
     *  thread: minting one may ask the server). */
    private val signing = ResolvingDataSource.Resolver { spec ->
        val tokens = app?.streamTokenManager
        val minted = tokens?.let { t -> runCatching { runBlocking { withTimeoutOrNull(TOKEN_WAIT_MS) { t.valid() } } }.getOrNull() }
        if (minted == null) spec else spec.withUri(Uri.parse(signedNow(spec.uri.toString(), minted)))
    }

    private val upstream: DataSource.Factory = RefusalAware(
        ResolvingDataSource.Factory(OkHttpDataSource.Factory(http).setUserAgent(USER_AGENT), signing),
    ) { runCatching { app?.streamTokenManager?.invalidate() } }

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

    /** Every download the manager has, by id - its state and progress, at
     *  most a second old while one is on its way. Snapshots: Media3's
     *  Download objects change their progress in place, and a map of the
     *  same objects would never read as changed. */
    val downloads: StateFlow<Map<String, DownloadSnapshot>> = _downloads.asStateFlow()

    private val _notMet = MutableStateFlow(0)

    /** The requirements the manager waits for (Requirements flags). */
    val notMet: StateFlow<Int> = _notMet.asStateFlow()

    /** Ids handed to the service, not in the manager yet. */
    val starting = MutableStateFlow<Set<String>>(emptySet())

    private val _indexRead = MutableStateFlow(false)

    /** The manager has read its index: [downloads] holds every download on
     *  the phone, not only the ones that changed since the process began. */
    val indexRead: StateFlow<Boolean> = _indexRead.asStateFlow()

    private var batteryLow = batteryLowNow()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var ticking = false

    init {
        applyRequirements()
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = readAll()

            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                val id = download.request.id
                starting.value = starting.value - id
                _downloads.value = _downloads.value + (id to download.snapshot())
                if (download.state == Download.STATE_FAILED) {
                    titles.update(id) { it.copy(failure = failureOfDownload(finalException, freeBytes())) }
                }
                if (download.state == Download.STATE_DOWNLOADING) tick()
                // Subtitles and artwork as the media comes: the app graph's
                // downloads, built here if this process has none yet.
                runCatching { (app?.downloads as? AndroidDownloads)?.changed(download) }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                _downloads.value = _downloads.value - download.request.id
            }

            override fun onRequirementsStateChanged(
                downloadManager: DownloadManager,
                requirements: Requirements,
                notMetRequirements: Int,
            ) {
                _notMet.value = notMetRequirements
            }
        })
        watchBattery()
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

    /** What downloads take: their media and their folders. */
    fun usedBytes(): Long = cache.cacheSpace + titles.bytes()

    /** Downloads that wait or were cut off carry on (the app came to the
     *  front): unpaused - after Android 15's daily limit stopped them - and
     *  the service started for them. */
    fun resumeIfPending() = onMain {
        manager.resumeDownloads()
        resumeWanted = true
        // A manager just built reads its index first ([readAll] goes on).
        if (manager.isInitialized) startServiceIfPending()
    }

    @Volatile
    private var resumeWanted = false

    /** The service, for downloads that wait or were cut off, once asked
     *  ([resumeIfPending]) and the index is read. */
    private fun startServiceIfPending() {
        if (!resumeWanted) return
        resumeWanted = false
        val pending = _downloads.value.values.any {
            it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING
        }
        if (pending) {
            runCatching {
                androidx.media3.exoplayer.offline.DownloadService.sendResumeDownloads(context, ChinoDownloadService::class.java, true)
            }
        }
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

    /** While a download is on its way, its progress once a second. */
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
                }
            } finally {
                ticking = false
            }
        }
    }

    /** Wi-Fi only, or any connection; while the battery is low, the charger
     *  as well - the download goes on once the phone charges. */
    private fun applyRequirements() {
        var flags = if (_preferences.value.wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK
        if (batteryLow) flags = flags or Requirements.DEVICE_CHARGING
        manager.requirements = Requirements(flags)
        _notMet.value = manager.notMetRequirements
    }

    private fun watchBattery() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val low = when (intent.action) {
                    Intent.ACTION_BATTERY_LOW -> true
                    Intent.ACTION_BATTERY_OKAY -> false
                    else -> return
                }
                if (low != batteryLow) {
                    batteryLow = low
                    applyRequirements()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    private fun batteryLowNow(): Boolean {
        val status = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return status.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false)
        }
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return level >= 0 && scale > 0 && level * 100 / scale <= LOW_BATTERY_PERCENT
    }

    companion object {
        private const val USER_AGENT = "chino-mobile/0.1 (Android; downloads)"

        /** How long a request waits for its token to be minted. */
        private const val TOKEN_WAIT_MS = 15_000L
        private const val SEGMENT_THREADS = 3
        private const val PREFS = "chino_downloads"
        private const val KEY_QUALITY = "quality"
        private const val KEY_WIFI_ONLY = "wifi_only"

        /** Below API 28 the platform says no "low": its own threshold. */
        private const val LOW_BATTERY_PERCENT = 15

        @Volatile
        private var instance: DownloadEngine? = null

        fun get(context: Context): DownloadEngine =
            instance ?: synchronized(this) { instance ?: DownloadEngine(context).also { instance = it } }
    }
}

/** A download as it is at one moment: its state, how far, what it fetched,
 *  and its request. */
internal data class DownloadSnapshot(
    val state: Int,
    val percent: Float,
    val bytes: Long,
    val request: androidx.media3.exoplayer.offline.DownloadRequest,
)

private fun Download.snapshot() = DownloadSnapshot(state, percentDownloaded, bytesDownloaded, request)

/** Media3's download index and the cache's, in a database file of their
 *  own: the Zap cache keeps its own in Media3's default one. */
private class DownloadsDatabase(context: Context) :
    SQLiteOpenHelper(context, "chino_downloads.db", null, 1), DatabaseProvider {
    override fun onCreate(db: SQLiteDatabase) = Unit
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/** Forgets the stream token when the server refuses one (401/403): the
 *  manager tries the download again, and that asks for a new one. */
private class RefusalAware(
    private val inner: DataSource.Factory,
    private val onRefused: () -> Unit,
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val source = inner.createDataSource()
        return object : DataSource by source {
            override fun open(dataSpec: DataSpec): Long = try {
                source.open(dataSpec)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode == 401 || e.responseCode == 403) onRefused()
                throw e
            }
        }
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

/** Below this, a download that fails is out of room. */
internal const val LOW_SPACE_BYTES = 50L * 1_000_000
