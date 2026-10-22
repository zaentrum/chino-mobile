package cloud.nalet.chino.mobile.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoMono
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoWarning
import cloud.nalet.chino.mobile.ui.theme.ChinoWarningBg
import cloud.nalet.chino.mobile.ui.theme.ChinoWarningBorder
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X

/**
 * "Living Room TV can't play this title." - the design system's warning strip
 * (nc-strip, warning): its glyph and 3 dp edge in the warning colour on the
 * warning fill, the line in the body face, and the reason behind "details",
 * set in mono. A screen reader hears "warning:" before the line. [onDismiss]
 * puts it away.
 */
@Composable
fun CastFailureStrip(failure: CastFailure, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember(failure) { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(ChinoWarningBg)
            .border(1.dp, ChinoWarningBorder, RectangleShape),
    ) {
        // The strip's left edge: 3 dp in the warning colour.
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(ChinoWarning))
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = WarningGlyph,
                contentDescription = null,
                tint = ChinoWarning,
                modifier = Modifier.padding(top = 3.dp).size(14.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = failure.headline,
                        color = ChinoFg,
                        fontSize = 13.sp,
                        lineHeight = 19.5.sp,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .semantics { contentDescription = "warning: ${failure.headline}" },
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .heightIn(min = 28.dp)
                            .clickable(role = Role.Button) { open = !open }
                            .semantics { stateDescription = if (open) "expanded" else "collapsed" }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = CaretGlyph,
                            contentDescription = null,
                            tint = ChinoMuted,
                            modifier = Modifier.size(10.dp).rotate(if (open) 90f else 0f),
                        )
                        Text(text = "details", color = ChinoMuted, fontSize = 12.sp, style = ChinoMono)
                    }
                }
                if (open) {
                    Row(modifier = Modifier.padding(top = 8.dp).height(IntrinsicSize.Min)) {
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(ChinoWarningBorder))
                        Text(
                            text = failure.details,
                            color = ChinoMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.6.sp,
                            style = ChinoMono,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = "dismiss",
                    tint = ChinoMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** The design system's warning glyph (nc-state-glyph): a triangle with its
 *  mark, drawn in 12 units at 1.25 strokes, square ends. */
private val WarningGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "CastWarningGlyph",
        defaultWidth = 14.dp,
        defaultHeight = 14.dp,
        viewportWidth = 12f,
        viewportHeight = 12f,
    ).apply {
        val stroke = SolidColor(Color.Black)
        path(stroke = stroke, strokeLineWidth = 1.25f, strokeLineCap = StrokeCap.Square, strokeLineJoin = StrokeJoin.Miter) {
            moveTo(6f, 1.5f)
            lineTo(11f, 10.5f)
            horizontalLineTo(1f)
            close()
        }
        path(stroke = stroke, strokeLineWidth = 1.25f, strokeLineCap = StrokeCap.Square) {
            moveTo(6f, 5f)
            verticalLineTo(7f)
        }
        path(stroke = stroke, strokeLineWidth = 1.25f, strokeLineCap = StrokeCap.Square) {
            moveTo(6f, 8.75f)
            verticalLineTo(9f)
        }
    }.build()
}

/** The strip's disclosure caret (nc-strip__caret), turned a quarter while
 *  the details are open. */
private val CaretGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "CastCaretGlyph",
        defaultWidth = 10.dp,
        defaultHeight = 10.dp,
        viewportWidth = 12f,
        viewportHeight = 12f,
    ).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Square) {
            moveTo(4f, 2.5f)
            lineTo(7.5f, 6f)
            lineTo(4f, 9.5f)
        }
    }.build()
}
