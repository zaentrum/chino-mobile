package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import chino_mobile.shared.generated.resources.JetBrainsMono_Bold
import chino_mobile.shared.generated.resources.JetBrainsMono_ExtraBold
import chino_mobile.shared.generated.resources.JetBrainsMono_Medium
import chino_mobile.shared.generated.resources.JetBrainsMono_Regular
import chino_mobile.shared.generated.resources.JetBrainsMono_SemiBold
import chino_mobile.shared.generated.resources.Res
import org.jetbrains.compose.resources.Font

/**
 * App-wide font families — mirrors chino-web's Google Fonts config in
 * Chino Logos.html (`--ff-ui: Inter; --ff-mono: JetBrains Mono`).
 *
 * Inter is one variable font, at the weights the design system sets type in,
 * and ships once per platform: on Android the app's font resource
 * (res/font/inter_variable.ttf), which the Cast widgets' themes take too; on
 * iOS a Compose resource of iosMain (composeResources/font/Inter.ttf).
 * JetBrains Mono is a Compose resource of commonMain: Compose Multiplatform
 * generates the `Res.font.*` accessors from the filenames in
 * composeResources/font/ — JetBrainsMono-Regular.ttf becomes
 * Res.font.JetBrainsMono_Regular, etc.
 */
@Composable
expect fun ChinoInterFamily(): FontFamily

/** JetBrains Mono in the weights chino-web's index.html loads (500 to 800),
 *  and Regular. SemiBold is the web's `font-semibold` heading: without it,
 *  a 600 matched the Bold file. */
@Composable
fun ChinoMonoFamily(): FontFamily = FontFamily(
    Font(Res.font.JetBrainsMono_Regular, FontWeight.Normal),
    Font(Res.font.JetBrainsMono_Medium, FontWeight.Medium),
    Font(Res.font.JetBrainsMono_SemiBold, FontWeight.SemiBold),
    Font(Res.font.JetBrainsMono_Bold, FontWeight.Bold),
    Font(Res.font.JetBrainsMono_ExtraBold, FontWeight.ExtraBold),
)
