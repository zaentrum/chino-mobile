package cloud.nalet.chino.mobile.downloads

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The job that carries waiting downloads on in the background once the
 *  connection is back: user-initiated, for the network the downloads may
 *  use, planned once and forgotten when asked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadResumeJobTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val jobs = context.getSystemService(JobScheduler::class.java)

    @Test
    fun wifiOnlyWaitsForAnUnmeteredNetworkOtherwiseForAny() {
        val wifi = DownloadResumeJob.resumeJobInfo(context, wifiOnly = true)
        assertTrue(wifi.isUserInitiated)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, @Suppress("DEPRECATION") wifi.networkType)
        val any = DownloadResumeJob.resumeJobInfo(context, wifiOnly = false)
        assertTrue(any.isUserInitiated)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, @Suppress("DEPRECATION") any.networkType)
    }

    @Test
    fun plannedOnceForTheNetworkAndForgottenWhenNothingWaits() {
        assertTrue(DownloadResumeJob.plan(context, wifiOnly = true))
        assertTrue(DownloadResumeJob.plan(context, wifiOnly = true))
        assertEquals(1, jobs.allPendingJobs.size)
        // Mobile data allowed now: planned again, for any network.
        assertTrue(DownloadResumeJob.plan(context, wifiOnly = false))
        assertEquals(1, jobs.allPendingJobs.size)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, @Suppress("DEPRECATION") jobs.allPendingJobs.single().networkType)
        DownloadResumeJob.cancel(context)
        assertNull(jobs.allPendingJobs.firstOrNull())
    }
}
