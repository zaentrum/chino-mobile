package cloud.nalet.chino.mobile.downloads

import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Downloads that waited for a connection carry on once it is back with the
 * app in the background (Android 14 and later): a user-initiated data
 * transfer job, planned as they start to wait - while the download service
 * still runs, as Android asks - for the network they need ([plan]). Android
 * starts it once that network is there; the job shows how far the downloads
 * are and lasts while they run. From Android 15 an app in the background
 * has no network at all without a job or a service in the foreground. In
 * front, the service takes the downloads on and the plan goes ([cancel]).
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class DownloadResumeJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val engine = DownloadEngine.get(this)
        watching = scope.launch {
            // The app in front starts the service for them itself as the
            // connection comes back: the job waits a moment for it, with no
            // notification of its own - and carries them where it doesn't.
            if (inFront()) {
                engine.resumeIfPending()
                repeat(FRONT_WAIT_TICKS) {
                    if (ChinoDownloadService.running) {
                        jobFinished(params, false)
                        return@launch
                    }
                    delay(TICK_MS)
                }
            }
            // The notification first: Android asks for it at once.
            setNotification(params, NOTIFICATION_ID, engine.notification(), JOB_END_NOTIFICATION_POLICY_REMOVE)
            var waitedMs = 0L
            while (true) {
                delay(TICK_MS)
                // The service in the foreground has them now, or nothing is
                // left that runs.
                if (ChinoDownloadService.running) break
                val current = runCatching { engine.manager.currentDownloads }.getOrDefault(emptyList())
                if (current.none { it.runs() }) break
                // Android saw the network; the downloads don't, yet: a while,
                // then planned again for later.
                if (engine.manager.notMetRequirements != 0) {
                    waitedMs += TICK_MS
                    if (waitedMs >= NOT_MET_MS) {
                        jobFinished(params, true)
                        return@launch
                    }
                } else {
                    waitedMs = 0L
                }
                setNotification(params, NOTIFICATION_ID, engine.notification(), JOB_END_NOTIFICATION_POLICY_REMOVE)
            }
            jobFinished(params, false)
        }
        return true
    }

    /** Stopped by Android - the connection went again - or by [cancel]:
     *  planned again while downloads still wait. */
    override fun onStopJob(params: JobParameters): Boolean {
        watching?.cancel()
        if (params.stopReason == JobParameters.STOP_REASON_CANCELLED_BY_APP) return false
        val current = runCatching { DownloadEngine.get(this).manager.currentDownloads }.getOrDefault(emptyList())
        return current.any { it.runs() }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 7302
        private const val NOTIFICATION_ID = 7302
        private const val TICK_MS = 1_000L
        private const val NOT_MET_MS = 60_000L

        /** How long the job waits for the app in front to start the
         *  service: well within the time Android gives a user-initiated
         *  job to show its notification. */
        private const val FRONT_WAIT_TICKS = 6

        /** Plans the job for [wifiOnly]'s network; false where Android does
         *  not take it (the app may not start work in the foreground now). */
        fun plan(context: Context, wifiOnly: Boolean): Boolean {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return false
            val info = resumeJobInfo(context, wifiOnly)
            // Planned already for the same network: kept - planning it again
            // would stop one that runs.
            val planned = runCatching { jobs.getPendingJob(JOB_ID) }.getOrNull()
            @Suppress("DEPRECATION")
            if (planned != null && planned.networkType == info.networkType) return true
            return runCatching { jobs.schedule(info) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
        }

        fun cancel(context: Context) {
            runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
        }

        /** The job: user-initiated, for an unmetered network when downloads
         *  are for Wi-Fi only, for any otherwise. */
        internal fun resumeJobInfo(context: Context, wifiOnly: Boolean): JobInfo =
            JobInfo.Builder(JOB_ID, ComponentName(context, DownloadResumeJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .build()
    }
}

/** Whether the app is what the person sees now. */
private fun inFront(): Boolean = runCatching {
    val me = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(me)
    me.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}.getOrDefault(false)

/** A download the manager runs, or will once it can: not stopped for a
 *  reason of the app's, not done, not failed. */
internal fun Download.runs(): Boolean =
    stopReason == Download.STOP_REASON_NONE &&
        (state == Download.STATE_QUEUED || state == Download.STATE_DOWNLOADING || state == Download.STATE_RESTARTING)
