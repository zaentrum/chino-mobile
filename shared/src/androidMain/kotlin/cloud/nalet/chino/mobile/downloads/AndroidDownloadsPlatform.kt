package cloud.nalet.chino.mobile.downloads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.downloads.Downloads
import cloud.nalet.chino.mobile.data.downloads.DownloadsPlatform
import cloud.nalet.chino.mobile.data.downloads.ProgressBook
import cloud.nalet.chino.mobile.data.downloads.ProgressBookStore
import cloud.nalet.chino.mobile.data.downloads.Reach
import cloud.nalet.chino.mobile.ui.player.CodecCaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** The app's host: what a worker started by the system reaches the app's
 *  graph through. The Application implements it. */
interface AppContainerHost {
    val container: AppContainer
}

/**
 * Downloads and playing without the server, on Android ([DownloadsPlatform]):
 * Media3's downloads ([AndroidDownloads]), the stereo caps a download asks
 * for, the phone's connection, the positions kept in a file, and a
 * WorkManager job that sends them once a connection is back - with the app
 * open or not. One per process: the Application makes it once and gives it
 * to every graph it builds.
 */
class AndroidDownloadsPlatform(context: Context) : DownloadsPlatform {
    private val context = context.applicationContext
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun downloadCaps(): String = CodecCaps.stereoQueryParam

    override fun networkUp(): Boolean? = runCatching {
        val cm = connectivity ?: return@runCatching null
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrNull()

    override val progressBookStore: ProgressBookStore = FileProgressBookStore(File(this.context.filesDir, "progress-book.json"))

    override fun downloads(container: AppContainer): Downloads = AndroidDownloads(DownloadEngine.get(context), container)

    /** In the place of a send already planned: one on its way may have
     *  counted the queue before the position that asks for this came. Only
     *  the first position waiting asks (ProgressSync), so the plan is not
     *  made again with each save. */
    override fun scheduleProgressFlush() = planFlush(ExistingWorkPolicy.REPLACE)

    private fun planFlush(policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<ProgressFlushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(FLUSH_WORK, policy, request)
        }
    }

    private var pending: Job? = null

    /** Activities started now: the app is in front while there is one. */
    @Volatile
    private var started = 0

    /** Whether the app is in front now. */
    val inFront: Boolean get() = started > 0

    /**
     * Follows the app while its process lives: the phone's connection
     * ([watchNetwork]), and the app coming to the front - downloads that
     * wait or were stopped go on where they can (Android 15 stops them after
     * its daily limit, until the app is opened), the server is asked which
     * downloads the account may still watch, and positions still to send are
     * sent, the sign-in they waited for perhaps just made.
     */
    fun watch(app: Application) {
        val host = app as? AppContainerHost ?: return
        watchNetwork(host)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = cameToFront(host)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                started++
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun cameToFront(host: AppContainerHost) {
        runCatching { DownloadEngine.get(context).resumeIfPending() }
        scope.launch {
            val container = runCatching { host.container }.getOrNull() ?: return@launch
            // One planned already stays as it is: its backoff counts on.
            if (runCatching { container.progressSync.hasPending() }.getOrDefault(false)) planFlush(ExistingWorkPolicy.KEEP)
            // With the server: what the account may still watch, at most
            // every few minutes.
            if (container.serverReach.state.value == Reach.Reachable) {
                runCatching { (container.downloads as? AndroidDownloads)?.checkQuietly() }
            }
        }
    }

    /**
     * Follows the phone's connection while the process lives: each change,
     * a moment later, is a new look at the server - and once it answers,
     * the positions kept while it did not go out.
     */
    private fun watchNetwork(host: AppContainerHost) {
        val cm = connectivity ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = recheck(host)
            override fun onLost(network: Network) = recheck(host)
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    private fun recheck(host: AppContainerHost) {
        pending?.cancel()
        pending = scope.launch {
            // A switch from Wi-Fi to mobile data is a loss, then a gain.
            delay(NETWORK_SETTLE_MS)
            val container = runCatching { host.container }.getOrNull() ?: return@launch
            if (container.serverReach.check() == Reach.Reachable) {
                runCatching { container.progressSync.flush() }
                runCatching { (container.downloads as? AndroidDownloads)?.checkQuietly() }
            }
            // A connection again: downloads that waited for it run, with the
            // service in the foreground where Android lets the app start it -
            // while it is in front. In the background the manager runs them
            // as long as the process does.
            if (inFront) runCatching { DownloadEngine.get(context).resumeIfPending() }
        }
    }

    private companion object {
        const val FLUSH_WORK = "chino-progress-flush"
        const val NETWORK_SETTLE_MS = 1_500L
    }
}

/**
 * Sends the positions kept on the phone, once a connection is back
 * ([AndroidDownloadsPlatform.scheduleProgressFlush]); again later while the
 * server does not take them yet - [MAX_RUNS] times at most. Past them, and
 * while nobody is signed in, what is left waits on the phone: the app
 * sends it as it opens, as the connection changes, or with the sign-in.
 */
class ProgressFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? AppContainerHost ?: return Result.success()
        val result = try {
            host.container.progressSync.flush() ?: return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return when {
            result != null && result.left == 0 -> Result.success()
            runAttemptCount + 1 >= MAX_RUNS -> Result.success()
            else -> Result.retry()
        }
    }

    private companion object {
        /** About 18 hours of exponential backoff from 30 s. */
        const val MAX_RUNS = 12
    }
}

/** The positions played, as JSON in the app's files: written whole through
 *  a temporary file, read once and then kept in memory. */
internal class FileProgressBookStore(private val file: File) : ProgressBookStore {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val lock = Mutex()
    private var cached: ProgressBook? = null

    override suspend fun read(): ProgressBook = lock.withLock {
        cached ?: withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(ProgressBook.serializer(), file.readText()) }.getOrNull() ?: ProgressBook()
        }.also { cached = it }
    }

    override suspend fun write(book: ProgressBook) = lock.withLock {
        cached = book
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ProgressBook.serializer(), book))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
