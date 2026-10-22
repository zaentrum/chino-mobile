package cloud.nalet.chino.mobile.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.downloads.DownloadQuality
import cloud.nalet.chino.mobile.data.downloads.qualityLine
import cloud.nalet.chino.mobile.ui.settings.SectionHeading
import cloud.nalet.chino.mobile.ui.settings.ToggleRow
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.launch

/**
 * Settings' downloads: the quality a download takes - the names the player's
 * quality menu gives a packaged title's renditions, each with what an hour
 * of video takes at it as the server's playlists say - and whether downloads
 * keep to Wi-Fi (on by default). Not where the platform keeps no downloads,
 * nor where the server offers none.
 */
@Composable
fun DownloadSettings() {
    val container = LocalAppContainer.current
    val downloads = container.downloads ?: return
    val features by container.serverReach.features.collectAsState()
    if (features?.downloads == false) return
    val prefs by downloads.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    // The qualities the server's packaged titles come in, with their sizes;
    // without an answer, the usual three, without sizes.
    val offered by produceState<List<DownloadQuality>?>(null) {
        value = runCatching { container.downloadPlanner.qualities() }.getOrDefault(emptyList())
    }
    val qualities = (offered?.takeIf { it.isNotEmpty() } ?: FALLBACK.map { DownloadQuality(it, null) })
        .let { list -> if (list.any { it.sizeClass == prefs.quality }) list else (list + DownloadQuality(prefs.quality, null)).sortedByDescending { it.sizeClass } }
    Box(modifier = Modifier.padding(top = 12.dp))
    SectionHeading("Downloads")
    Text(text = "Download quality", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(ChinoSurface)
            .selectableGroup(),
    ) {
        qualities.forEach { q ->
            val on = q.sizeClass == prefs.quality
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = on, role = Role.RadioButton) { scope.launch { downloads.setQuality(q.sizeClass) } }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = qualityLine(q),
                    color = if (on) ChinoCloudBlue else Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                if (on) Icon(imageVector = Lucide.Check, contentDescription = null, tint = ChinoCloudBlue, modifier = Modifier.size(18.dp))
            }
        }
    }
    ToggleRow(
        label = "Download over Wi-Fi only",
        value = prefs.wifiOnly,
        onChange = { v -> scope.launch { downloads.setWifiOnly(v) } },
    )
}

/** The qualities offered when the server's playlists could not be read. */
private val FALLBACK = listOf(1080, 720, 480)
