package cloud.nalet.chino.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

/** A toast's state (the design system's): a plain fact about what happened,
 *  or something that started. */
enum class ToastKind { Neutral, Information }

/** A short confirmation of what just happened, in sentence case. */
data class Toast(val text: String, val kind: ToastKind = ToastKind.Neutral)

/**
 * The design system's toast, over any screen: at the bottom, in the middle,
 * above the bars - a square box with a thin frame and its state's bar down
 * its left edge, the text in the body face. It takes no focus and holds
 * nothing to press; a screen reader reads it at its next pause. It slides up
 * a little as it comes, leaves after [TOAST_MS] without an animation, and
 * the next one shows after it.
 */
@Composable
fun ToastHost(toasts: SharedFlow<Toast>, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(toasts) {
        toasts.collect { t ->
            shown = t
            delay(TOAST_MS)
            shown = null
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = TOAST_BOTTOM),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = shown != null,
            enter = fadeIn() + slideInVertically { it / 4 },
            exit = ExitTransition.None,
        ) {
            val t = shown ?: return@AnimatedVisibility
            val bar = if (t.kind == ToastKind.Information) ChinoCloudBlue else ChinoMuted
            Row(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .height(IntrinsicSize.Min)
                    .background(ChinoSurface)
                    .border(BorderStroke(1.dp, ChinoBorder), RectangleShape)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(bar))
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(imageVector = Lucide.Info, contentDescription = null, tint = bar, modifier = Modifier.size(16.dp))
                    Text(text = t.text, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

/** How long a toast shows. */
const val TOAST_MS = 5_000L

/** How far above the foot of the screen toasts sit: clear of the bottom
 *  navigation and the bar while casting. */
private val TOAST_BOTTOM = 96.dp
