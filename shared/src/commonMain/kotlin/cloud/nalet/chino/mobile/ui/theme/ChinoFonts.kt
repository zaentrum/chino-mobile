package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import chino_mobile.shared.generated.resources.Inter_Bold
import chino_mobile.shared.generated.resources.Inter_Medium
import chino_mobile.shared.generated.resources.Inter_Regular
import chino_mobile.shared.generated.resources.Inter_SemiBold
import chino_mobile.shared.generated.resources.JetBrainsMono_Bold
import chino_mobile.shared.generated.resources.JetBrainsMono_ExtraBold
import chino_mobile.shared.generated.resources.JetBrainsMono_Medium
import chino_mobile.shared.generated.resources.JetBrainsMono_Regular
import chino_mobile.shared.generated.resources.Res
import org.jetbrains.compose.resources.Font

/**
 * App-wide font families — mirrors chino-web's Google Fonts config in
 * Chino Logos.html (`--ff-ui: Inter; --ff-mono: JetBrains Mono`). The
 * TTFs are bundled in commonMain/composeResources/font/ so the same
 * family ships on every platform.
 *
 * Compose Multiplatform generates the `Res.font.*` accessors from the
 * filenames in composeResources/font/ — Inter-Regular.ttf becomes
 * Res.font.Inter_Regular, etc.
 */
@Composable
fun ChinoInterFamily(): FontFamily = FontFamily(
    Font(Res.font.Inter_Regular, FontWeight.Normal),
    Font(Res.font.Inter_Medium, FontWeight.Medium),
    Font(Res.font.Inter_SemiBold, FontWeight.SemiBold),
    Font(Res.font.Inter_Bold, FontWeight.Bold),
)

@Composable
fun ChinoMonoFamily(): FontFamily = FontFamily(
    Font(Res.font.JetBrainsMono_Regular, FontWeight.Normal),
    Font(Res.font.JetBrainsMono_Medium, FontWeight.Medium),
    Font(Res.font.JetBrainsMono_Bold, FontWeight.Bold),
    Font(Res.font.JetBrainsMono_ExtraBold, FontWeight.ExtraBold),
)
