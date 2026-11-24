package cloud.nalet.chino.mobile.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoMono
import com.composables.icons.lucide.FastForward
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Rewind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A double tap's mark as it shows: back or [forward], the [seconds] so far. */
data class SkipMark(val forward: Boolean, val seconds: Int)

/**
 * The taps on the player's picture as they come (section 11.4.4 of the
 * specification): each is handed to [DoubleTapSkips], and what it says is
 * done - a single tap at once, or once the wait for a second one is over; a
 * skip, with its [mark], which goes [SKIP_MARK_MS] after the last tap.
 */
@Stable
class PictureTaps internal constructor(private val scope: CoroutineScope) {
    /** The mark to show; null for none. */
    var mark: SkipMark? by mutableStateOf(null)
        private set

    private val skips = DoubleTapSkips()
    private var waits: Job? = null
    private var markGoes: Job? = null

    /**
     * A tap on the picture at [atMs], in its right half ([forward]) or its
     * left, the title at [positionMs] of [durationMs]; [offered]: whether a
     * double tap is ([DoubleTapSkips.tap]). [onSingleTap] does what a single
     * tap does, [onSkip] takes the title to where a skip goes.
     */
    fun tap(
        atMs: Long,
        forward: Boolean,
        positionMs: Long,
        durationMs: Long,
        offered: Boolean,
        onSingleTap: () -> Unit,
        onSkip: (toMs: Long) -> Unit,
    ) {
        val tap = skips.tap(atMs, forward, positionMs, durationMs, offered)
        waits?.cancel()
        if (tap.earlierWasSingle) onSingleTap()
        when (tap) {
            is PictureTap.Single -> onSingleTap()
            is PictureTap.First -> waits = scope.launch {
                delay(DOUBLE_TAP_WITHIN_MS)
                if (skips.waited(tap.id)) onSingleTap()
            }
            // Forward while the title's length is not known: nothing.
            is PictureTap.Skip -> tap.toMs?.let { toMs ->
                mark = SkipMark(tap.forward, tap.seconds)
                markGoes?.cancel()
                markGoes = scope.launch {
                    delay(SKIP_MARK_MS)
                    mark = null
                }
                onSkip(toMs)
            }
        }
    }
}

/** The picture's taps, for as long as the player shows. */
@Composable
fun rememberPictureTaps(): PictureTaps {
    val scope = rememberCoroutineScope()
    return remember(scope) { PictureTaps(scope) }
}

/**
 * Tells [onTap] of every tap on what it is set on, the whole player with
 * the picture in it: when the last finger lifted ([atMs], by the pointer's
 * own clock), whether in the right half of it - the black beside the picture
 * counts with its half - and whether it was one finger alone from down to up
 * ([oneFinger]): a touch a second finger joined is told as such, and is no
 * tap of a double tap ([doubleTapOffered]). A finger that leaves the player,
 * or whose move something else takes (a pinch), is no tap. A part over the
 * picture that takes its own taps - a bar, a button - keeps them: none of
 * those is told here, so no double tap begins there.
 */
@Composable
fun Modifier.pictureTaps(onTap: (atMs: Long, rightHalf: Boolean, oneFinger: Boolean) -> Unit): Modifier {
    val latest by rememberUpdatedState(onTap)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown().consume()
            var oneFinger = true
            // As waitForUpOrCancellation waits, and counting the fingers.
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) oneFinger = false
                if (event.changes.all { it.changedToUp() }) {
                    val up = event.changes[0]
                    up.consume()
                    latest(up.uptimeMillis, up.position.x >= size.width / 2f, oneFinger)
                    break
                }
                if (event.changes.any { it.isConsumed || it.isOutOfBounds(size, extendedTouchPadding) }) break
                if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) break
            }
        }
    }
}

/**
 * A double tap's mark (figure 11.13 of the specification): rewind or fast
 * forward with the seconds so far under it, in the middle of the half of
 * the player that was tapped. Nothing else of the picture is dimmed, and the
 * mark takes no touch: a further tap that lands on it is the picture's.
 */
@Composable
fun SkipMarkOverlay(mark: SkipMark?, modifier: Modifier = Modifier) {
    if (mark == null) return
    Row(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (!mark.forward) SkipMarkBox(mark)
        }
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (mark.forward) SkipMarkBox(mark)
        }
    }
}

@Composable
private fun SkipMarkBox(mark: SkipMark) {
    Column(
        modifier = Modifier
            .background(Color(0x99000000))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (mark.forward) Lucide.FastForward else Lucide.Rewind,
            contentDescription = null,
            tint = ChinoFg,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = skipMarkText(mark.forward, mark.seconds),
            color = ChinoFg,
            fontSize = 15.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            style = ChinoMono,
        )
    }
}
