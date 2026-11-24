package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which way the phone app's player stands after each thing that happens to
 * it (section 11.4.3 of the specification): fullscreen and exit fullscreen
 * (change 11.C8), the phone turned, the player left - on a phone and on a
 * large screen, with the phone's own setting for turning on and off.
 */
class PlayerStandTest {
    private val withThePhone = PlayerStand.WithThePhone

    /** The phone held sideways or upright, on a phone whose setting lets
     *  apps turn unless [turnsApps] says otherwise. */
    private fun PlayerStand.held(sideways: Boolean, turnsApps: Boolean = true) =
        afterPhoneHeld(heldSideways = sideways, phoneTurnsApps = turnsApps, phone = true)

    private fun PlayerStand.fullscreen() = afterFullscreenButton(standsSideways = false, phone = true)

    private fun PlayerStand.exitFullscreen() = afterFullscreenButton(standsSideways = true, phone = true)

    @Test
    fun change11_C8_uprightFullscreenTurnsThePlayerSideways() {
        val stand = withThePhone.fullscreen()

        assertEquals(PlayerStand.Sideways, stand)
        assertEquals(ScreenTurn.Sideways, turnAsked(stand))
        // Upright, the button offers fullscreen.
        assertFalse(playerOffersExitFullscreen(phone = true, standsSideways = false, barsHidden = false))
    }

    @Test
    fun change11_C8_thePlayerStaysSidewaysUntilThePhoneHasFollowedIt() {
        // The phone is still upright: the person has not turned it yet.
        val stand = withThePhone.fullscreen().held(sideways = false)

        assertEquals(ScreenTurn.Sideways, turnAsked(stand))
    }

    @Test
    fun change11_C8_onceThePhoneHasFollowedThePlayerTurnsWithItAgain() {
        val followed = withThePhone.fullscreen().held(sideways = true)
        // Sideways still, as the phone is.
        assertEquals(ScreenTurn.Sideways, turnAsked(followed))

        // Turned back upright, it leaves full screen: the phone's to turn.
        val upright = followed.held(sideways = false)
        assertEquals(withThePhone, upright)
        assertEquals(ScreenTurn.AsTheApp, turnAsked(upright))
    }

    @Test
    fun change11_C8_heldSidewaysHoweverItGotThereTheButtonIsExitFullscreen() {
        // By the button, or by the phone turned by hand: sideways is sideways.
        assertTrue(playerOffersExitFullscreen(phone = true, standsSideways = true, barsHidden = false))
        PlayerStand.entries.forEach { stand ->
            val upright = stand.exitFullscreen()
            assertEquals(PlayerStand.Upright, upright, "exit fullscreen from $stand")
            assertEquals(ScreenTurn.Upright, turnAsked(upright))
        }
    }

    @Test
    fun change11_C8_afterExitFullscreenThePlayerStaysUprightUntilThePhoneIsTurnedSidewaysAgain() {
        // exit fullscreen pressed with the phone held sideways.
        val turned = withThePhone.exitFullscreen()
        // Still held sideways: upright it stays.
        assertEquals(ScreenTurn.Upright, turnAsked(turned.held(sideways = true)))
        // The phone follows, upright: upright it stays.
        val followed = turned.held(sideways = true).held(sideways = false)
        assertEquals(ScreenTurn.Upright, turnAsked(followed))
        // Turned sideways again: the player turns with it.
        assertEquals(withThePhone, followed.held(sideways = true))
    }

    @Test
    fun change11_C8_afterExitFullscreenFullscreenTurnsThePlayerSidewaysAgain() {
        assertEquals(PlayerStand.Sideways, withThePhone.exitFullscreen().fullscreen())
        assertEquals(PlayerStand.Sideways, withThePhone.exitFullscreen().held(sideways = false).fullscreen())
    }

    @Test
    fun change11_C8_whereThePhonesSettingHoldsAppsUprightTurningThePhoneChangesNothing() {
        // fullscreen, the phone turned to follow, and turned back upright.
        val sideways = withThePhone.fullscreen().held(sideways = true, turnsApps = false).held(sideways = false, turnsApps = false)
        assertEquals(ScreenTurn.Sideways, turnAsked(sideways))
        // exit fullscreen, the phone upright, and turned sideways again.
        val upright = sideways.exitFullscreen().held(sideways = false, turnsApps = false).held(sideways = true, turnsApps = false)
        assertEquals(ScreenTurn.Upright, turnAsked(upright))
        // The buttons are the way.
        assertEquals(ScreenTurn.Sideways, turnAsked(upright.fullscreen()))
    }

    @Test
    fun change11_C8_thePhonesSettingTurnedOnLetsThePlayerTurnWithThePhoneAgain() {
        // Held upright with the setting off: sideways it stays. The setting
        // turned on: the player turns with the phone.
        val sideways = withThePhone.fullscreen().held(sideways = true, turnsApps = false).held(sideways = false, turnsApps = false)

        assertEquals(withThePhone, sideways.held(sideways = false, turnsApps = true))
    }

    @Test
    fun change11_C8_aLargeScreenIsNotTurned() {
        assertTrue(isPhoneScreen(smallestWidthDp = 599))
        assertFalse(isPhoneScreen(smallestWidthDp = 600))
        PlayerStand.entries.forEach { stand ->
            assertEquals(withThePhone, stand.afterFullscreenButton(standsSideways = false, phone = false), "fullscreen from $stand")
            assertEquals(withThePhone, stand.afterFullscreenButton(standsSideways = true, phone = false), "exit fullscreen from $stand")
            assertEquals(withThePhone, stand.afterPhoneHeld(heldSideways = true, phoneTurnsApps = true, phone = false))
            assertEquals(withThePhone, stand.afterPhoneHeld(heldSideways = false, phoneTurnsApps = false, phone = false))
            // A phone unfolded to a tablet's size lets go of the screen.
            assertEquals(withThePhone, stand.onScreen(phone = false))
            assertEquals(stand, stand.onScreen(phone = true))
        }
        // Its button is as before: exit fullscreen while it has hidden the
        // system bars, whichever way the tablet is held.
        assertFalse(playerOffersExitFullscreen(phone = false, standsSideways = true, barsHidden = false))
        assertTrue(playerOffersExitFullscreen(phone = false, standsSideways = false, barsHidden = true))
    }

    @Test
    fun change11_C8_leavingThePlayerGivesTheAppBackAsItWas() {
        assertEquals(ScreenTurn.AsTheApp, turnAsked(null))
    }

    @Test
    fun change11_C8_howThePhoneIsHeldIsReadOffItsAngle() {
        assertEquals(false, phoneHeldSideways(degrees = 0, before = null))
        assertEquals(true, phoneHeldSideways(degrees = 90, before = null))
        assertEquals(false, phoneHeldSideways(degrees = 180, before = null))
        assertEquals(true, phoneHeldSideways(degrees = 270, before = null))
        assertEquals(false, phoneHeldSideways(degrees = 359, before = true))
        assertEquals(true, phoneHeldSideways(degrees = 62, before = false))
        assertEquals(true, phoneHeldSideways(degrees = 118, before = false))
        assertEquals(false, phoneHeldSideways(degrees = 28, before = true))
    }

    @Test
    fun change11_C8_atASlantOrLyingFlatThePhoneIsHeldAsItWas() {
        // Between upright and sideways: as it was, so it does not flip there.
        assertEquals(true, phoneHeldSideways(degrees = 45, before = true))
        assertEquals(false, phoneHeldSideways(degrees = 45, before = false))
        assertEquals(false, phoneHeldSideways(degrees = 310, before = false))
        assertNull(phoneHeldSideways(degrees = 135, before = null))
        // Flat on a table, the sensor can't tell.
        assertEquals(true, phoneHeldSideways(degrees = -1, before = true))
        assertNull(phoneHeldSideways(degrees = -1, before = null))
    }

    @Test
    fun change11_C8_aDeviceWhoseOwnPositionIsSidewaysCountsFromThere() {
        assertEquals(true, phoneHeldSideways(degrees = 0, before = null, naturallySideways = true))
        assertEquals(false, phoneHeldSideways(degrees = 90, before = null, naturallySideways = true))
    }
}
