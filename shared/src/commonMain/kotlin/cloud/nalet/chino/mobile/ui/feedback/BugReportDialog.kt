package cloud.nalet.chino.mobile.ui.feedback

import androidx.compose.ui.graphics.RectangleShape

import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoBg2
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder2
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoDim
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.api.FeedbackResponse
import cloud.nalet.chino.mobile.data.api.FeedbackSubmitException
import com.composables.icons.lucide.Bug
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Manual bug-report dialog — full-screen scrim + #161B22 card, the same
 * hand-rolled idiom as AccountPicker's ConfirmRemoveDialog. Extracted from
 * SettingsScreen so the player's terminal-error overlay can open the same
 * flow (web parity: PlayerPage's "Report a bug" next to Reload).
 *
 * The screenshot is captured by the CALLER before this mounts (a dialog over
 * the screen would only ever photograph itself); null just means no preview
 * row + a text-only report. Submit routes through BugReporter.reportManual
 * (errors PROPAGATE here, unlike auto reports): success swaps the card body
 * for the filed-ticket confirmation — "Filed bug #id" for a new ticket,
 * "Added to existing bug #id" when the server deduplicated — while failure
 * shows inline and preserves the input.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun BugReportDialog(
    screenshot: ByteArray?,
    /** Per-call-site report context (e.g. screen:"settings", or
     *  screen:"player" + itemId). BugReporter stamps the static
     *  device/app fields on top. */
    context: Map<String, String>,
    /** Pre-filled description — the player passes the technical error
     *  string; Settings opens empty. */
    initialDescription: String = "",
    onDismiss: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf(initialDescription) }
    var includeShot by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FeedbackResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Decoded once for the thumbnail preview; decode failure (or no
    // screenshot) just hides the preview row.
    val thumb = remember(screenshot) {
        screenshot?.let { bytes -> runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
    }

    val submit: () -> Unit = {
        if (!submitting && description.isNotBlank()) {
            scope.launch {
                submitting = true
                error = null
                try {
                    result = container.bugReporter.reportManual(
                        description = description.trim(),
                        screenshot = if (includeShot) screenshot else null,
                        context = context,
                    )
                } catch (e: Exception) {
                    // Manual reports DO surface failures (auto reports never
                    // do) — inline, with the input preserved for a retry.
                    error = when ((e as? FeedbackSubmitException)?.status) {
                        429 -> "Too many reports right now — try again in a few minutes."
                        503 -> "Bug reporting isn't set up on this server."
                        else -> "Couldn't send the report. Check your connection and try again."
                    }
                } finally {
                    submitting = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(enabled = !submitting, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clip(RectangleShape)
                .background(ChinoSurface)
                .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape)
                .clickable(enabled = false) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val filed = result
            if (filed != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Lucide.Check,
                        contentDescription = null,
                        tint = ChinoGreen,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (filed.duplicate) "Added to existing bug #${filed.id}"
                        else "Filed bug #${filed.id}",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "Thanks — the report landed on the dev backlog.",
                    color = ChinoMuted,
                    fontSize = 13.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    DialogButton(label = "Close", containerColor = ChinoBorder2, onClick = onDismiss)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Lucide.Bug,
                        contentDescription = null,
                        tint = ChinoCloudBlue,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Report a bug",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // Multi-line description input — field colors mirror the
                // Add-Server UrlField (#0D1117 well + #30363D border on the
                // #161B22 card).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RectangleShape)
                        .background(ChinoBg2)
                        .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape),
                ) {
                    BasicTextField(
                        value = description,
                        onValueChange = { description = it },
                        enabled = !submitting,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(ChinoCloudBlue),
                        decorationBox = { inner ->
                            if (description.isEmpty()) {
                                Text(
                                    text = "What went wrong? What did you expect to happen?",
                                    color = ChinoMuted,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                            inner()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp)
                            .padding(12.dp),
                    )
                }
                if (thumb != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Image(
                            bitmap = thumb,
                            contentDescription = "Screenshot preview",
                            modifier = Modifier
                                .height(64.dp)
                                .aspectRatio(thumb.width.toFloat() / thumb.height.toFloat())
                                .clip(RectangleShape)
                                .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape)
                                .alpha(if (includeShot) 1f else 0.35f),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Include screenshot", color = Color.White, fontSize = 14.sp)
                            Text(
                                text = if (includeShot) "Attached to the report" else "Not attached",
                                color = ChinoMuted,
                                fontSize = 12.sp,
                            )
                        }
                        TogglePill(value = includeShot, onChange = { includeShot = it })
                    }
                }
                error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    DialogButton(
                        label = "Cancel",
                        containerColor = ChinoBorder2,
                        enabled = !submitting,
                        onClick = onDismiss,
                    )
                    DialogButton(
                        label = "Submit",
                        containerColor = ChinoCloudBlue,
                        textColor = ChinoBg2,
                        enabled = !submitting && description.isNotBlank(),
                        busy = submitting,
                        onClick = submit,
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    containerColor: Color,
    textColor: Color = Color.White,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RectangleShape)
            .background(if (enabled || busy) containerColor else ChinoBorder)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = textColor,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = label,
                color = if (enabled) textColor else ChinoMuted,
                fontSize = 14.sp,
            )
        }
    }
}

/** Pill toggle for the screenshot row — per-file private copy, same idiom
 *  as the per-file DialogButtons (Settings keeps its own for ToggleRow). */
@Composable
private fun TogglePill(value: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(22.dp)
            .clip(RectangleShape)
            .background(if (value) ChinoCloudBlue else ChinoBorder)
            .clickable { onChange(!value) },
        contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .size(18.dp)
                .clip(RectangleShape)
                .background(Color.White),
        )
    }
}
