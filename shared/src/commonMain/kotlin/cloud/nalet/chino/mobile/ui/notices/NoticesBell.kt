package cloud.nalet.chino.mobile.ui.notices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.notices.badgeText
import cloud.nalet.chino.mobile.data.notices.bellLabel
import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoMono
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Lucide

/**
 * The bell in the top bar: the person's unread notices counted on it, as the
 * portal's bell counts them, and the list ([NoticesScreen]) a tap away. Only
 * while chino-api says notices are available: a server without portal-api
 * has no bell at all.
 */
@Composable
fun NoticesBell(onClick: () -> Unit) {
    val container = LocalAppContainer.current
    val state by container.notices.state.collectAsState()
    val list = state.list ?: return
    val badge = badgeText(list.unread)
    // The top bar's icon cell: 36dp, a 20dp glyph.
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RectangleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Lucide.Bell,
            contentDescription = bellLabel(list.unread),
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        if (badge.isNotEmpty()) {
            // In the cell's corner, over the bell's: the cell clips what
            // reaches past it. The bell already says how many. The count is
            // in mono, the web's font-mono.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                    .background(ChinoCloudBlue)
                    .padding(horizontal = 3.dp)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(text = badge, color = ChinoBg, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, style = ChinoMono)
            }
        }
    }
}
