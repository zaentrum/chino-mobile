package cloud.nalet.chino.mobile.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Point
import android.provider.Settings
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The player's stand ([PlayerStand]) on the Activity that shows it: what the
 * stand asks of the system goes to the Activity's requested orientation, and
 * what the Activity asked before the player opened comes back as the player
 * is left. Sideways is the sensor's landscape - either way round, whatever
 * the phone's own setting for turning says - and upright is portrait.
 */
internal class PlayerStandHold {
    var stand by mutableStateOf(PlayerStand.WithThePhone)
        private set

    private var activity: Activity? = null

    /** What the Activity asked of the system before the player. */
    private var askedBefore: Int? = null

    /** Whether a title has opened in this player: the titles started from
     *  it keep the way it stands. */
    private var opened = false

    /** Whether the stand goes on with the title started from this player
     *  ([passOn]). */
    private var passedOn = false

    /** The player shows on [activity] (null where there is none: a preview). */
    fun attach(activity: Activity?) {
        this.activity = activity
        passedOn = false
        if (askedBefore == null) askedBefore = activity?.requestedOrientation
        ask(turnAsked(stand))
    }

    /** The player's screen went. The player was left: the app as it was.
     *  Its stand went on with the title started from it ([passOn]): the
     *  screen stays as it stands. */
    fun leave() {
        if (!passedOn) {
            ask(turnAsked(null))
            askedBefore = null
        }
        activity = null
    }

    /** A title opens in the player: the first sideways on a phone, one
     *  started from the player itself as the player stands
     *  ([playerStandAsOpened]). */
    fun titleOpens(phone: Boolean) {
        stands(playerStandAsOpened(phone, keeps = stand.takeIf { opened }))
        opened = true
    }

    /** The stand for the title started from this player - its next episode,
     *  say - which keeps it: this screen leaves the Activity as it stands. */
    fun passOn(): PlayerStandHold {
        passedOn = true
        return this
    }

    /** fullscreen or exit fullscreen was pressed, the player standing
     *  sideways ([standsSideways]) or upright. */
    fun fullscreenPressed(standsSideways: Boolean, phone: Boolean) = stands(stand.afterFullscreenButton(standsSideways, phone))

    /** The phone is held sideways ([heldSideways]) or upright. */
    fun phoneHeld(heldSideways: Boolean, phoneTurnsApps: Boolean, phone: Boolean) =
        stands(stand.afterPhoneHeld(heldSideways, phoneTurnsApps, phone))

    /** The screen is a phone's, or a large one. */
    fun onScreen(phone: Boolean) = stands(stand.onScreen(phone))

    private fun stands(next: PlayerStand) {
        stand = next
        ask(turnAsked(next))
    }

    private fun ask(turn: ScreenTurn) {
        val activity = activity ?: return
        val orientation = when (turn) {
            ScreenTurn.Sideways -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ScreenTurn.Upright -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ScreenTurn.AsTheApp -> askedBefore ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (activity.requestedOrientation != orientation) activity.requestedOrientation = orientation
    }
}

/**
 * The player's stand, kept for as long as the player's screen shows
 * ([PlayerStandHold]): its own, or the one of the player this title was
 * started from ([startedFrom]), which it keeps. Where the title [opens] on
 * this screen it opens with the screen ([PlayerStandHold.titleOpens]); one
 * that goes to a TV first opens here only if it plays here after all. While
 * the player has turned itself sideways or upright, the phone's sensor says
 * how the phone is held, so that the player turns with the phone again once
 * the phone has followed it ([afterPhoneHeld]) - read only then, and only
 * while the app is in front. Whether the phone lets apps turn is its own
 * auto-rotate setting, read as the phone is turned.
 */
@Composable
internal fun rememberPlayerStand(startedFrom: PlayerStandHold? = null, opens: Boolean = true): PlayerStandHold {
    val hold = remember { startedFrom ?: PlayerStandHold() }
    PlayerStandEffect(hold, opens)
    return hold
}

@Composable
private fun PlayerStandEffect(hold: PlayerStandHold, opens: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val phone = isPhoneScreen(LocalConfiguration.current.smallestScreenWidthDp)
    DisposableEffect(context) {
        hold.attach(context.findActivity())
        if (opens) hold.titleOpens(phone)
        onDispose { hold.leave() }
    }
    LaunchedEffect(phone) { hold.onScreen(phone) }
    val turned = turnAsked(hold.stand) != ScreenTurn.AsTheApp
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(turned, phone, lifecycle) {
        if (!turned || !phone) return@DisposableEffect onDispose {}
        val naturallySideways = view.display?.let(::ownPositionIsSideways) ?: false
        var held: Boolean? = null
        val sensor = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                held = phoneHeldSideways(degrees, held, naturallySideways)
                held?.let { hold.phoneHeld(heldSideways = it, phoneTurnsApps = phoneTurnsApps(context), phone = true) }
            }
        }
        val inFront = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> sensor.enable()
                Lifecycle.Event.ON_PAUSE -> sensor.disable()
                else -> Unit
            }
        }
        lifecycle.addObserver(inFront)
        onDispose {
            lifecycle.removeObserver(inFront)
            sensor.disable()
        }
    }
}

/** Whether the device's own position is sideways: its screen is wider than
 *  tall while it is not turned. The sensor's angle counts from there. */
private fun ownPositionIsSideways(display: Display): Boolean {
    val size = Point()
    @Suppress("DEPRECATION")
    display.getRealSize(size)
    val unturned = display.rotation == Surface.ROTATION_0 || display.rotation == Surface.ROTATION_180
    return unturned == (size.x > size.y)
}

/** Whether the phone's own setting lets apps turn with it (auto-rotate). */
private fun phoneTurnsApps(context: Context): Boolean =
    Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

private fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.firstOrNull { it is Activity } as? Activity
