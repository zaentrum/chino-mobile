package cloud.nalet.chino.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoHeading
import cloud.nalet.chino.mobile.ui.theme.ChinoMono
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted

/**
 * The design system's "couldn't load" screen, in the place of what did not
 * load: what couldn't load ([title], in the screen's own words), why as far
 * as the app can tell ([sentence]), and the one way out ([action]: "try
 * again", or "back" for what was removed).
 */
@Composable
fun CouldntLoad(
    title: String,
    sentence: String?,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    extra: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                style = ChinoHeading,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            sentence?.let { Text(text = it, color = ChinoMuted, fontSize = 14.sp, textAlign = TextAlign.Center) }
            if (action != null) {
                Button(
                    onClick = onAction,
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = ChinoCloudBlue, contentColor = ChinoBg),
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text(text = action, style = ChinoMono, fontWeight = FontWeight.SemiBold) }
            }
            extra?.invoke()
        }
    }
}
