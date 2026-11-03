package cloud.nalet.chino.mobile.ui.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs

/**
 * Whether the viewer last chose to fill the screen with the picture: kept on
 * the phone, so every title after it - the next episode, a film tomorrow -
 * plays the way they left it, until a pinch in brings fit back. Held upright
 * the picture fits all the same (PictureFit.fillApplies). Outside the
 * backups, as all of the app's preferences (data_extraction_rules.xml).
 */
internal class PictureFillChoice private constructor(private val prefs: SharedPreferences) {
    var fill: Boolean
        get() = prefs.getBoolean(KEY_FILL, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FILL, value).apply()
        }

    companion object {
        private const val PREFS = "chino_player"
        private const val KEY_FILL = "picture_fill"

        fun of(context: Context): PictureFillChoice =
            PictureFillChoice(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}

/** The picture's zoom where a screen reader can change it: whether it fills
 *  the screen now, and what switches it to fit or to fill as a pinch would. */
internal class PictureZoomAction(val filled: Boolean, val toggle: () -> Unit)

/**
 * The picture's zoom for a screen reader: the picture, named [name] (the
 * title playing), with one action - "zoom to fill", or "zoom to fit" while it
 * fills the screen ([PlayerButtons.zoom]) - that does what a pinch does.
 * Without [zoom] (upright, or where fill is fit) the picture is no stop of
 * its own, as before.
 */
internal fun Modifier.pictureZoomAction(name: String, zoom: PictureZoomAction?): Modifier =
    if (zoom == null) {
        this
    } else {
        semantics {
            contentDescription = name
            customActions = listOf(
                CustomAccessibilityAction(PlayerButtons.zoom(zoom.filled).name) {
                    zoom.toggle()
                    true
                },
            )
        }
    }

/**
 * Two fingers spread apart or brought together over the player: a pinch of
 * the picture. It begins once their spread has changed by more than the
 * touch slop, where [mayBegin] lets it - [onBegin] gives the zoom it starts
 * from - and from then on it is the picture's: the fingers' moves are taken
 * before the controls under them see them, so a pinch that started on the
 * seek bar never seeks. [onSpread] hears how many times as far apart the
 * fingers are as they move (less than 1: closer), [onEnd] the same once a
 * finger lifts. One finger, a tap, a drag: never a pinch.
 */
internal suspend fun PointerInputScope.detectPinch(
    mayBegin: () -> Boolean,
    onBegin: () -> Unit,
    onSpread: (Float) -> Unit,
    onEnd: (Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        var spread = 1f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val fingers = event.changes.count { it.pressed }
            if (fingers < 2) {
                // A finger lifted, or never a second one: over.
                if (pinching || fingers == 0) break
                continue
            }
            spread *= event.calculateZoom()
            if (!pinching) {
                val size = event.calculateCentroidSize(useCurrent = false)
                if (size > 0f && abs(1f - spread) * size > viewConfiguration.touchSlop && mayBegin()) {
                    pinching = true
                    onBegin()
                }
            }
            if (pinching) {
                onSpread(spread)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
        if (pinching) onEnd(spread)
    }
}
