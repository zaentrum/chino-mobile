package cloud.nalet.chino.mobile.cast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/*
 * Casting, where the platform has it: Google Cast on Android (androidMain's
 * cast/), nothing on iOS, whose actuals draw nothing. The design system's
 * casting pattern: Google's Cast button, bar and full controls, in our
 * colours.
 */

/**
 * The Cast button - Google's icon, Google's states: outlined, moving while it
 * connects, filled while casting - drawn only while a device that can play is
 * on the network. [modifier] is the screen's frame for it (the player's
 * circle, a title page's square), drawn only with the button.
 */
@Composable
expect fun CastButton(modifier: Modifier = Modifier)

/**
 * The bar while casting - Google's mini controller: the title, "playing on
 * {device}", play or pause; tapped, the full controls - or, where the TV could
 * not play a title, that warning. Part of the screen's layout, above the
 * bottom navigation where there is one: nothing scrolls under it unseen.
 * [padNavigationBar]: the bar is the screen's last row and keeps clear of the
 * system's navigation bar itself.
 */
@Composable
expect fun CastBar(padNavigationBar: Boolean, modifier: Modifier = Modifier)

/** What casting tells the screens. */
object CastEvents {
    private val kept = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** A title whose position the TV played was written back as casting
     *  stopped or the title ended - the position the phone keeps: a page
     *  that shows it reads it again. */
    val positionKept: SharedFlow<String> = kept.asSharedFlow()

    internal fun keptPositionOf(itemId: String) {
        kept.tryEmit(itemId)
    }
}
