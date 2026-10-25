package cloud.nalet.chino.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cloud.nalet.chino.mobile.ui.theme.ChinoBg2
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder2
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoMono
import cloud.nalet.chino.mobile.ui.theme.ChinoOnFill
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.ui.theme.ChinoWarning

/*
 * The one question the app asks before an action that deletes something or
 * can't be undone - delete all downloads, sign out, remove account, change
 * server, delete account: the design system's modal, set up as a confirm. A
 * square panel with a thin border, as wide as there is room for up to 480 dp
 * with 16 dp to the screen's edges; its title in monospace, lowercase, a
 * rule under it; the body in the body face, in sentence case, scrolling
 * when it is taller than the screen; a rule, then the actions on the right,
 * "cancel" as the quiet button and the action - the title's own words - as
 * the danger button, last. No avatar: the title says the action and the
 * body names whom it is for ("The 23 downloads of Anna ..."), in every
 * question alike. While the action runs ([busy]) its button shows a spinner
 * and keeps its width, and nothing closes the question - not Back, not a tap
 * outside, not "cancel" - so nobody leaves it halfway.
 */

/** The danger button's fill under a finger: the signal amber itself. */
private val DangerPressed = ChinoWarning

/** A question before an action, its body one or two sentences ([body]). */
@Composable
fun QuestionDialog(
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    dismissLabel: String = "cancel",
) {
    QuestionDialog(title = title, action = action, onAction = onAction, onDismiss = onDismiss, busy = busy, dismissLabel = dismissLabel) {
        QuestionText(body)
    }
}

/**
 * A question before an action: [title] (lowercase, the action's words),
 * what [body] says goes, [dismissLabel] ("cancel"; "close" once nothing is
 * left to do) and [action], the danger button - none where [action] is
 * null. [busy]: the action runs, and the question waits for it.
 */
@Composable
fun QuestionDialog(
    title: String,
    action: String?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    dismissLabel: String = "cancel",
    body: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy, usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = QUESTION_GUTTER)
                .widthIn(max = QUESTION_MAX_WIDTH)
                .fillMaxWidth()
                .background(ChinoSurface)
                .border(1.dp, ChinoBorder2, RectangleShape)
                .semantics { paneTitle = title },
        ) {
            Text(
                text = title,
                color = ChinoFg,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                style = ChinoMono,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
            )
            Rule()
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = body,
            )
            Rule()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuietButton(label = dismissLabel, onClick = onDismiss, enabled = !busy)
                if (action != null) DangerButton(label = action, onClick = onAction, busy = busy)
            }
        }
    }
}

/** One sentence or two of a question's body, in the body face. */
@Composable
fun QuestionText(text: String, color: Color = ChinoFg2) {
    Text(text = text, color = color, fontSize = 14.sp, lineHeight = 22.sp)
}

/** A thin rule across the question, under its title and over its actions. */
@Composable
private fun Rule() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ChinoBorder))
}

/**
 * The design system's danger button, for an action that deletes something
 * or can't be undone: an amber outline and an amber label, filled amber
 * with a dark label while pressed; its label in monospace, lowercase. [busy]:
 * its action runs - a spinner before the label, the width unchanged,
 * further presses ignored.
 */
@Composable
fun DangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    height: Dp = BUTTON_HEIGHT,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && !busy
    val filled = pressed && active
    Box(
        modifier = modifier
            .heightIn(min = height)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .background(if (filled) DangerPressed else Color.Transparent)
            .border(1.dp, ChinoWarning, RectangleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .semantics { if (busy) stateDescription = "busy" }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(14.dp), color = ChinoWarning, strokeWidth = 2.dp)
            Text(
                text = label,
                color = if (filled) ChinoOnFill else ChinoWarning,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                style = ChinoMono,
            )
        }
    }
}

/** The design system's quiet button, the way out beside an action: no fill
 *  and no border, a darker fill while pressed; its label in monospace,
 *  lowercase. */
@Composable
fun QuietButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = BUTTON_HEIGHT,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .heightIn(min = height)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .background(if (pressed && enabled) ChinoBg2 else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (pressed && enabled) ChinoFg else ChinoFg2,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            style = ChinoMono,
        )
    }
}

/** A button's height: a finger's, as the title actions are. */
private val BUTTON_HEIGHT = 48.dp

/** A question's room: 16 dp to the screen's edges, at most 480 dp wide. */
private val QUESTION_GUTTER = 16.dp
private val QUESTION_MAX_WIDTH = 480.dp

/** A button that can't be pressed: dimmed, as the design system's are. */
private const val DISABLED_ALPHA = 0.45f
