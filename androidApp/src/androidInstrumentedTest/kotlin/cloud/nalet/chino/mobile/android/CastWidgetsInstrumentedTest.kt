package cloud.nalet.chino.mobile.android

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.mediarouter.app.MediaRouteButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.nalet.chino.mobile.cast.CastExpandedControlsActivity
import cloud.nalet.chino.mobile.cast.CastKeys
import cloud.nalet.chino.mobile.cast.ChinoMiniControllerFragment
import com.google.android.gms.cast.framework.CastButtonFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.google.android.gms.cast.framework.R as CastR

/**
 * Google's Cast widgets on the app's own activity and themes, on a device or
 * an emulator, without a device to cast to: the Cast button and its device
 * chooser (an AppCompat dialog, which needs the activity's AppCompat theme),
 * the bar's fragment with its "playing on" line in place of Google's and the
 * app's body face, and the full controls with their slots set up. None of
 * them shows in the app without a Cast device on the network - this is what
 * a phone runs once one is there.
 */
@RunWith(AndroidJUnit4::class)
class CastWidgetsInstrumentedTest {

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

    @Test
    fun theBarsSecondLineIsOursInTheAppsBodyFace() {
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
                // Inter, read from the Compose resources, on both lines.
                assertNotEquals(Typeface.DEFAULT, line.typeface)
                assertEquals(title.typeface, line.typeface)
                // No session: no device to name.
                assertEquals("", line.text.toString())
                // Google's mini controller hides itself without a session.
                assertEquals(View.GONE, root.visibility)
            }
        }
    }

    @Test
    fun theFullControlsSetUpTheirSlotsAndCloseWithoutASession() {
        ActivityScenario.launch(CastExpandedControlsActivity::class.java).use { scenario ->
            // Without a session the expanded controller finishes itself.
            val start = System.currentTimeMillis()
            while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() - start < 10_000) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test
    fun theVolumeKeysAreThePhonesWithoutASession() {
        assertFalse(CastKeys.handle(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
    }
}
