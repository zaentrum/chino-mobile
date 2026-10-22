package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import chino_mobile.shared.generated.resources.Inter
import chino_mobile.shared.generated.resources.Res
import org.jetbrains.compose.resources.Font

/** Inter from iosMain's Compose resources, one variable font: its weight
 *  axis set to each weight (Font's own variation settings). */
@Composable
actual fun ChinoInterFamily(): FontFamily = FontFamily(
    Font(Res.font.Inter, FontWeight.Normal),
    Font(Res.font.Inter, FontWeight.Medium),
    Font(Res.font.Inter, FontWeight.SemiBold),
    Font(Res.font.Inter, FontWeight.Bold),
)
