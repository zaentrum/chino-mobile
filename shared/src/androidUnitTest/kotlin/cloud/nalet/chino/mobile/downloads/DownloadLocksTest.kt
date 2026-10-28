package cloud.nalet.chino.mobile.downloads

import android.content.Context
import android.net.wifi.WifiManager
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadLocksTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun theLocksAreHeldWhileADownloadIsOnItsWayAlone() {
        val downloading = listOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING)
        assertTrue(DownloadLocks.shouldHold(downloading, notMetRequirements = 0, paused = false))
        // queued alone, finished, failed or stopped: nothing on its way
        for (states in listOf(emptyList(), listOf(Download.STATE_QUEUED), listOf(Download.STATE_COMPLETED, Download.STATE_FAILED),
            listOf(Download.STATE_STOPPED), listOf(Download.STATE_RESTARTING, Download.STATE_REMOVING))) {
            assertFalse(DownloadLocks.shouldHold(states, 0, false), "held for $states")
        }
        // paused, or waiting for Wi-Fi or a connection: nothing kept awake
        assertFalse(DownloadLocks.shouldHold(downloading, 0, paused = true))
        assertFalse(DownloadLocks.shouldHold(downloading, Requirements.NETWORK_UNMETERED, false))
        assertFalse(DownloadLocks.shouldHold(downloading, Requirements.NETWORK, false))
    }

    @Test
    fun holdingTakesWifiAndTheProcessorAndLettingGoGivesBothBack() {
        val locks = DownloadLocks(context)
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locks.hold(true)
        assertTrue(locks.held)
        val wake = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(wake)
        assertTrue(wake.isHeld, "the processor's lock")
        assertEquals(1, shadowOf(wifi).activeLockCount, "the Wi-Fi lock")

        // held again (each change while it downloads): still one of each
        locks.hold(true)
        locks.keep()
        assertTrue(wake.isHeld)
        assertEquals(1, shadowOf(wifi).activeLockCount)

        locks.hold(false)
        assertFalse(locks.held)
        assertFalse(wake.isHeld, "the processor's lock after the downloads")
        assertEquals(0, shadowOf(wifi).activeLockCount, "the Wi-Fi lock after the downloads")

        // let go again, or renewed while not held: nothing taken
        locks.hold(false)
        locks.keep()
        assertFalse(wake.isHeld)
        assertEquals(0, shadowOf(wifi).activeLockCount)
    }
}
