package cloud.nalet.chino.mobile.android

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Paint
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NavUtils
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.mediarouter.app.MediaRouteButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cloud.nalet.chino.mobile.cast.CastExpandedControlsActivity
import cloud.nalet.chino.mobile.cast.CastPlaybackService
import cloud.nalet.chino.mobile.cast.ChinoMiniControllerFragment
import cloud.nalet.chino.mobile.data.model.CatalogDate
import cloud.nalet.chino.mobile.formatDeviceDate
import cloud.nalet.chino.mobile.shared.R
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.google.android.gms.cast.framework.R as CastR

/**
 * Google's Cast widgets on the app's own activity and themes, on a device or
 * an emulator, without a device to cast to: the Cast button and its device
 * chooser (an AppCompat dialog, which needs the activity's AppCompat theme),
 * the bar's fragment with its "playing on" line in place of Google's and
 * Inter from the theme, the full controls with their slots set up, casting's
 * foreground service, and Google's words kept English. None of the widgets
 * shows in the app without a Cast device on the network - this is what a
 * phone runs once one is there.
 */
@RunWith(AndroidJUnit4::class)
class CastWidgetsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theCastButtonAndItsDeviceChooserOpenOnTheAppsTheme() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var shown = false
            scenario.onActivity { activity ->
                val button = MediaRouteButton(activity)
                CastButtonFactory.setUpMediaRouteButton(activity, button)
                (activity.window.decorView as ViewGroup).addView(button, ViewGroup.LayoutParams(144, 144))
                shown = button.showDialog()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue("the chooser opened", shown)
            scenario.onActivity { activity ->
                val chooser = activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>().firstOrNull()
                assertNotNull("Google's device chooser", chooser)
                assertTrue(chooser!!.dialog?.isShowing == true)
                chooser.dismiss()
            }
        }
    }

    /** Whether [view] draws [text] in [font] at its size: the width it takes. */
    private fun drawsIn(view: TextView, font: Int, text: String): Boolean {
        val face = ResourcesCompat.getFont(view.context, font)
        val expected = Paint().apply {
            typeface = face
            textSize = view.textSize
        }.measureText(text)
        val drawn = Paint(view.paint).measureText(text)
        return kotlin.math.abs(expected - drawn) < 0.5f
    }

    @Test
    fun theBarsLinesAreOursInInterFromTheTheme() {
        // Font resources in a theme reach the framework's views from Android 8.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val holder = View.generateViewId()
            scenario.onActivity { activity ->
                val frame = FrameLayout(activity).apply { id = holder }
                (activity.window.decorView as ViewGroup).addView(frame, ViewGroup.LayoutParams(1080, 300))
                activity.supportFragmentManager.beginTransaction().add(holder, ChinoMiniControllerFragment()).commitNow()
            }
            scenario.onActivity { activity ->
                val bar = activity.supportFragmentManager.findFragmentById(holder) as ChinoMiniControllerFragment
                val root = bar.requireView()
                val title = root.findViewById<TextView>(CastR.id.title_view)
                val line = root.findViewById<TextView>(CastR.id.subtitle_view)
                assertNotNull(title)
                assertNotNull(line)
                val sample = "The Quiet Orchard · S01E03 playing on Living Room TV"
                assertTrue("the title in Inter Medium", drawsIn(title, R.font.chino_inter_medium, sample))
                assertTrue("the device line in Inter", drawsIn(line, R.font.chino_inter, sample))
                // No session: no device to name.
                assertEquals("", line.text.toString())
                // Google's mini controller hides itself without a session.
                assertEquals(View.GONE, root.visibility)
            }
        }
    }

    @Test
    fun theFullControlsSlotsAreBackForwardAndStopCastingAndBackLeadsIntoTheApp() {
        var slots: List<Pair<CharSequence?, CharSequence?>>? = null
        var parent: String? = null
        val watch = ActivityLifecycleCallback { activity: Activity, stage: Stage ->
            if (stage == Stage.CREATED && activity is CastExpandedControlsActivity && slots == null) {
                val controls = activity as ExpandedControllerActivity
                slots = (1..3).map { slot ->
                    val view = controls.getButtonImageViewAt(slot)
                    view.contentDescription to view.tooltipTextOrNull()
                }
                parent = NavUtils.getParentActivityName(activity)
            }
        }
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(watch)
        try {
            ActivityScenario.launch(CastExpandedControlsActivity::class.java).use { scenario ->
                // Without a session the expanded controller finishes itself -
                // after it set its slots up.
                val start = SystemClock.uptimeMillis()
                while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() - start < 10_000) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                }
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            }
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(watch)
        }
        assertEquals(
            listOf("back 10 seconds", "forward 10 seconds", "stop casting"),
            slots?.map { it.first?.toString() },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertEquals(listOf("back 10 seconds", "forward 10 seconds", "stop casting"), slots?.map { it.second?.toString() })
        }
        assertEquals(MainActivity::class.java.name, parent)
    }

    private fun View.tooltipTextOrNull(): CharSequence? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText else null

    @Test
    fun castingsForegroundServiceIsOfTheMediaPlaybackKindAndStopsWithNothingPlaying() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, CastPlaybackService::class.java), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, info.foregroundServiceType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK),
            )
        }
        // Nothing plays on a TV: in the foreground for a moment, as a started
        // foreground service must be, then - its grace over, nothing needing
        // it - gone by itself, its notification with it.
        val services = context.getSystemService(ActivityManager::class.java)
        fun running(): Boolean =
            @Suppress("DEPRECATION")
            services.getRunningServices(Int.MAX_VALUE).any { it.service.className == CastPlaybackService::class.java.name }
        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(context, Intent(context, CastPlaybackService::class.java))
            assertTrue("started", running())
            val start = SystemClock.uptimeMillis()
            while (running() && SystemClock.uptimeMillis() - start < 25_000) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                SystemClock.sleep(100)
            }
            assertTrue("stopped by itself", !running())
            val notifications = context.getSystemService(NotificationManager::class.java)
            assertTrue(notifications.activeNotifications.none { it.id == CAST_SERVICE_NOTIFICATION })
        }
    }

    @Test
    fun googlesCastWordsStayEnglishOnAPhoneInAnotherLanguageItsDatesDoNot() {
        val german = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("de-DE")) }
        val de = context.createConfigurationContext(german)
        // Google's own, which ship in some 80 languages: English, ours.
        assertEquals("playing on Living Room TV", de.getString(CastR.string.cast_casting_to_device, "Living Room TV"))
        assertEquals("Stop", de.getString(CastR.string.cast_stop))
        assertEquals("Rewind ten seconds", de.getString(CastR.string.cast_rewind_10))
        // A date is the phone's locale's, from its locale data, not from
        // the app's resources.
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("9. März 2024", formatDeviceDate(CatalogDate(2024, 3, 9)))
            assertEquals("1.234,5", String.format(Locale.getDefault(), "%,.1f", 1234.5))
        } finally {
            Locale.setDefault(before)
        }
    }

    private companion object {
        /** CastPlaybackService's notification. */
        const val CAST_SERVICE_NOTIFICATION = 0x0ca57
    }
}
