package cloud.nalet.chino.mobile.ui.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/**
 * The picture's room in the player: the screen less the display cutout's
 * band, as much at the other side, so the picture stays centred on the
 * screen. Held sideways, a picture wider than the screen (a 2.39:1 film) is
 * drawn inside the safe area, never through the camera's band, and the strip
 * beside the cutout stays the player's black; a 16:9 one, narrower than the
 * room, is where it was. Upright the cutout is at the top, over the bars:
 * only the sides count, so the picture keeps its place. [cutout] is the
 * window's display cutout ([WindowInsets.displayCutout]), read as it lays
 * out, so turning the phone moves the band.
 */
internal fun Modifier.pictureInSafeArea(cutout: WindowInsets): Modifier = layout { measurable, constraints ->
    val side = maxOf(cutout.getLeft(this, layoutDirection), cutout.getRight(this, layoutDirection), 0)
    val inner = constraints.copy(
        minWidth = (constraints.minWidth - 2 * side).coerceAtLeast(0),
        maxWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth - 2 * side).coerceAtLeast(0) else constraints.maxWidth,
    )
    val placeable = measurable.measure(inner)
    layout(constraints.constrainWidth(placeable.width + 2 * side), constraints.constrainHeight(placeable.height)) {
        placeable.place(side, 0)
    }
}
