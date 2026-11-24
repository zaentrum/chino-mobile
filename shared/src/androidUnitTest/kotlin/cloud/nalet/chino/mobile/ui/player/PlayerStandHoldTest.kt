package cloud.nalet.chino.mobile.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * What the player's stand asks of the system on the Activity that shows it
 * (section 11.4.3 of the specification). Change 11.C8: fullscreen asks for
 * sideways, either way round and whatever the phone's own setting says; exit
 * fullscreen for upright; once the player turns with the phone again, and
 * once it is left, the Activity asks what it asked before the player; a
 * large screen is asked nothing. Change 11.C53: a title opens sideways on a
 * phone, the next episode keeps the way the player stands, and a large
 * screen opens as it is held.
 */
@RunWith(RobolectricTestRunner::class)
// A phone held upright.
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlayerStandHoldTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var showing by mutableStateOf(true)
    private lateinit var hold: PlayerStandHold

    /** The stand the title on the screen was started from, where it was
     *  started from the player: the next episode's. */
    private var startedFrom: PlayerStandHold? by mutableStateOf(null)

    private val asked get() = compose.activity.requestedOrientation

    /** The player's screen shown, the Activity having asked [before]; its
     *  title [opens] on this screen, or goes to a TV first. */
    private fun player(before: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, opens: Boolean = false) {
        compose.runOnUiThread { compose.activity.requestedOrientation = before }
        compose.setContent {
            if (showing) {
                // One screen per title, as the navigator replaces them.
                androidx.compose.runtime.key(startedFrom) { hold = rememberPlayerStand(startedFrom = startedFrom, opens = opens) }
            }
        }
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
    fun change11_C53_aTitleOpensSidewaysWhateverThePhonesSettingSays() {
        player(before = ActivityInfo.SCREEN_ORIENTATION_USER, opens = true)

        assertEquals(PlayerStand.Sideways, hold.stand)
        // The sensor's landscape: either way round, the phone's setting for
        // turning not asked.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, asked)
    }

    @Test
    fun change11_C53_onceThePhoneHasBeenTurnedSidewaysThePlayerTurnsWithIt() {
        player(before = ActivityInfo.SCREEN_ORIENTATION_USER, opens = true)

        held(sideways = true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, asked)
        held(sideways = false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, asked)
    }

    @Test
    fun change11_C53_theNextEpisodeKeepsTheWayThePlayerStands() {
        player(before = ActivityInfo.SCREEN_ORIENTATION_USER, opens = true)
        // The player turned upright with exit fullscreen, then next episode.
        press(standsSideways = true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, asked)

        val first = hold
        startedFrom = first.passOn()
        compose.waitForIdle()

        // The next episode's screen: upright as the player stood, not
        // opened sideways again.
        assertEquals(PlayerStand.Upright, hold.stand)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, asked)
        // Leaving it gives the app back as it was before the first title.
        showing = false
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, asked)
    }

    /** An Activity that keeps what it was asked, in turn. */
    class Asking : Activity() {
        val asked = mutableListOf<Int>()

        override fun setRequestedOrientation(requestedOrientation: Int) {
            asked += requestedOrientation
            super.setRequestedOrientation(requestedOrientation)
        }
    }

    @Test
    fun change11_C53_betweenTwoEpisodesTheSystemIsAskedNothing() {
        val activity = Robolectric.buildActivity(Asking::class.java).setup().get()
        val first = PlayerStandHold()
        first.attach(activity)
        first.titleOpens(phone = true)

        // Next episode: the first title's screen goes, the next one's comes.
        val next = first.passOn()
        first.leave()
        next.attach(activity)
        next.titleOpens(phone = true)

        // Sideways once, as the first title opened: never upright or as the
        // app in between, which would turn the screen back and forth.
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE), activity.asked)
        next.leave()
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED), activity.asked)
    }

    @Test
    fun change11_C53_leavingThePlayerGivesTheAppBackAsItWas() {
        player(before = ActivityInfo.SCREEN_ORIENTATION_USER, opens = true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, asked)

        showing = false
        compose.waitForIdle()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, asked)
    }

    @Test
    fun change11_C53_aTitleThatGoesToATvFirstTurnsThePhoneOnlyOnceItPlaysHere() {
        player(opens = false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, asked)

        // The TV could not take it: it plays on the phone after all.
        compose.runOnUiThread { hold.titleOpens(phone = true) }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, asked)
        // Shown again after an error, try again: as the player stands.
        press(standsSideways = true)
        compose.runOnUiThread { hold.titleOpens(phone = true) }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, asked)
    }

    @Test
    // A tablet.
    @Config(sdk = [34], qualifiers = "w800dp-h1280dp")
    fun change11_C53_aLargeScreenOpensAsItIsHeld() {
        player(before = ActivityInfo.SCREEN_ORIENTATION_USER, opens = true)

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
