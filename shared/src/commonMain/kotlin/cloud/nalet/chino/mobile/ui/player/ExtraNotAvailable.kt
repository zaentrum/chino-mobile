package cloud.nalet.chino.mobile.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.data.model.Trailer
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoHeading
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.trailer.trailerLinkLabel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Youtube

/**
 * The player's extra mode when there is no such extra to play
 * ([ExtraPlayback.NotAvailable], or a master that answers 404 once playing):
 * "Trailer not available" under Back and the title's name, and the title's
 * trailer link ("Watch on YouTube") when it has one, opened outside the app.
 * The same on Android and iOS.
 */
@Composable
fun ExtraNotAvailable(title: String?, link: Trailer?, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        NotAvailableTopBar(title = title, onBack = onBack)
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Trailer not available",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    style = ChinoHeading,
                )
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (link != null) {
                        MessageButton(trailerLinkLabel(link), Lucide.Youtube, primary = true) { uriHandler.openUri(link.url) }
                    }
                    MessageButton("Back", icon = null, primary = false, onClick = onBack)
                }
            }
        }
    }
}

/** Back, then the title's name. */
@Composable
private fun NotAvailableTopBar(title: String?, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RectangleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.ArrowLeft, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
        }
        if (title != null) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ChinoHeading,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }
    }
}

@Composable
private fun MessageButton(label: String, icon: ImageVector?, primary: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RectangleShape)
            .background(if (primary) ChinoCloudBlue else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(text = label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
