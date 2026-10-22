package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import cloud.nalet.chino.mobile.shared.R

/** Inter from the app's font resource, the one copy of it on Android: its
 *  weight axis set to each weight (Font's own variation settings). */
@Composable
actual fun ChinoInterFamily(): FontFamily = InterFamily

private val InterFamily = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal),
    Font(R.font.inter_variable, FontWeight.Medium),
    Font(R.font.inter_variable, FontWeight.SemiBold),
    Font(R.font.inter_variable, FontWeight.Bold),
)
