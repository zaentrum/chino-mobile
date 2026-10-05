package cloud.nalet.chino.mobile.ui.slots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.slots.SlotButton
import cloud.nalet.chino.mobile.data.slots.SlotKind
import cloud.nalet.chino.mobile.data.slots.slotButtons
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Clapperboard
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Library
import com.composables.icons.lucide.ListVideo
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.Radar
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Tv
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Wrench
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * The buttons addons contribute to a named slot, drawn as the app's own —
 * chino-web's ExtensionSlot. Read when the slot is shown (GET
 * /v1/extensions?slot=), best effort: no addon, no answer, or no row the app
 * may draw ([slotButtons]), and the slot is nothing at all.
 *
 *  - A link opens its page — on this server only — in the system browser.
 *  - An action is a POST with the person's bearer, as on the web: to the
 *    portal's app proxy here, and nowhere else. The button rests while it is
 *    sent, so a second tap does not send it twice; leaving the screen does
 *    not cancel it.
 *
 * What a button says is the addon's. Whatever a row holds, the worst it can
 * do is not show up.
 */
@Composable
fun ExtensionSlot(slot: String, vars: Map<String, String> = emptyMap(), modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var rows by remember(slot) { mutableStateOf<JsonElement?>(null) }
    LaunchedEffect(slot) {
        rows = try {
            container.chinoApi.extensions(slot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val apiBaseUrl = container.config.apiBaseUrl
    val buttons = remember(rows, vars, apiBaseUrl) {
        runCatching { slotButtons(rows, apiBaseUrl, vars) }.getOrDefault(emptyList())
    }
    if (buttons.isEmpty()) return
    var sending by remember { mutableStateOf(emptySet<String>()) }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        buttons.forEach { button ->
            SlotButtonView(
                button = button,
                resting = button.key in sending,
                onClick = {
                    when (button.kind) {
                        // No browser to open it in is no reason to fail.
                        SlotKind.Link -> runCatching { uriHandler.openUri(button.href) }
                        SlotKind.Action -> scope.launch {
                            sending = sending + button.key
                            try {
                                container.appScope.async {
                                    runCatching { container.chinoApi.sendSlotAction(button.href) }
                                }.await()
                            } finally {
                                sending = sending - button.key
                            }
                        }
                    }
                },
            )
        }
    }
}

/** One row as a button: the accent fill of the app's primary buttons, the
 *  row's icon and its label. */
@Composable
private fun SlotButtonView(button: SlotButton, resting: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .alpha(if (resting) 0.6f else 1f)
            .clip(RectangleShape)
            .background(ChinoCloudBlue)
            .clickable(enabled = !resting, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = slotIcon(button.icon),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Text(text = button.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** A palette name as its glyph — the portal's icons, as chino-web draws
 *  them; any other name is the puzzle. */
private fun slotIcon(name: String): ImageVector = when (name) {
    "library" -> Lucide.Library
    "radar" -> Lucide.Radar
    "download" -> Lucide.Download
    "tv" -> Lucide.Tv
    "music" -> Lucide.Music
    "clapperboard" -> Lucide.Clapperboard
    "settings" -> Lucide.Settings
    "layout-grid" -> Lucide.LayoutGrid
    "server" -> Lucide.Server
    "boxes" -> Lucide.Boxes
    "globe" -> Lucide.Globe
    "wrench" -> Lucide.Wrench
    "file-text" -> Lucide.FileText
    "image" -> Lucide.Image
    "list-video" -> Lucide.ListVideo
    "users" -> Lucide.Users
    "gauge" -> Lucide.Gauge
    "database" -> Lucide.Database
    else -> Lucide.Puzzle
}
