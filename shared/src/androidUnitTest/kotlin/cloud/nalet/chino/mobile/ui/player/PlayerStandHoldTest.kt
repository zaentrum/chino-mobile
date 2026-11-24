package cloud.nalet.chino.mobile.ui.player

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * What the player's stand asks of the system on the Activity that shows it
 * (change 11.C8 of the specification, its section 11.4.3): fullscreen asks
 * for sideways, either way round and whatever the phone's own setting says;
 * exit fullscreen for upright; once the player turns with the phone again,
 * and once it is left, the Activity asks what it asked before the player. A
 * large screen is asked nothing.
 */
@RunWith(RobolectricTestRunner::class)
// A phone held upright.
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlayerStandHoldTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var showing by mutableStateOf(true)
    private lateinit var hold: PlayerStandHold

    private val asked get() = compose.activity.requestedOrientation

    /** The player's screen shown, the Activity having asked [before]. */
    private fun player(before: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
        compose.runOnUiThread { compose.activity.requestedOrientation = before }
        compose.setContent { if (showing) hold = rememberPlayerStand() }
        compose.waitForIdle()
    }

    private fun press(standsSideways: Boolean, phone: Boolean = true) {
        compose.runOnUiThread { hold.fullscreenPressed(standsSideways = standsSideways, phone = phone) }
        compose.waitForIdle()
    }

    private fun held(sideways: Boolean, turnsApps: Boolean = true) {
        compose.runOnUiThread { hold.phoneHeld(heldSideways = sideways, phoneTurnsApps = turnsApps, phone = true) }
        compose.waitForIdle()
    }

    @Test
    fun change11_C8_thePlayerOpenedAsksTheSystemNothing() {
        player(before = ActivityInfo.SCREEN_ORIENTATION_USER)

        assertEquals(PlayerStand.WithThePhone, hold.stand)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, asked)
    }

    @Test
    fun change11_C8_fullscreenAsksForSidewaysAndExitFullscreenForUpright() {
        player()

        press(standsSideways = false)
        // The sensor's landscape: either way round, whatever the phone's
        // own setting for turning says.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, asked)

        press(standsSideways = true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, asked)
    }

    @Test
    fun change11_C8_onceThePlayerTurnsWithThePhoneAgainTheActivityAsksWhatItAskedBefore() {
        player(before = ActivityInfo.SCREEN_ORIENTATION_USER)
        press(standsSideways = false)

        // The phone follows, sideways: still asked for.
        held(sideways = true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, asked)
        // Turned back upright: the phone's to turn again.
        held(sideways = false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, asked)
    }

    @Test
    fun change11_C8_whereThePhoneHoldsAppsUprightThePlayerStaysAsItStands() {
        player()
        press(standsSideways = false)

        held(sideways = true, turnsApps = false)
        held(sideways = false, turnsApps = false)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, asked)
    }

    @Test
    fun change11_C8_leavingThePlayerGivesTheAppBackAsItWas() {
        player(before = ActivityInfo.SCREEN_ORIENTATION_USER)
        press(standsSideways = false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, asked)

        showing = false
        compose.waitForIdle()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, asked)
    }

    @Test
    // A tablet.
    @Config(sdk = [34], qualifiers = "w800dp-h1280dp")
    fun change11_C8_aLargeScreenIsAskedNothing() {
        player()

        press(standsSideways = false, phone = false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, asked)
        press(standsSideways = true, phone = false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, asked)
    }

    @Test
    // A phone unfolded to a tablet's size while the player stands sideways.
    fun change11_C8_aPhoneUnfoldedToATabletsSizeLetsGoOfTheScreen() {
        player()
        press(standsSideways = false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, asked)

        compose.runOnUiThread { hold.onScreen(phone = false) }

        assertEquals(PlayerStand.WithThePhone, hold.stand)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, asked)
    }
}
