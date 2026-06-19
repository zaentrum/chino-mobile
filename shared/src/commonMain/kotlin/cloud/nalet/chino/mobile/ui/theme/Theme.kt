package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Brand canvas is dark-first per the nalet design system. No light-mode
 *  variant — the same look ships on every platform. If the host OS is in
 *  light mode we still render dark; matches chino-web + chino-androidtv. */
private val ChinoScheme = darkColorScheme(
    primary = ChinoCloudBlue,
    onPrimary = ChinoBg,
    background = ChinoBg,
    onBackground = ChinoFg2,
    surface = ChinoSurface,
    onSurface = ChinoFg2,
    surfaceVariant = ChinoSurfaceHi,
    onSurfaceVariant = ChinoMuted,
    outline = ChinoBorder,
)

@Composable
fun ChinoTheme(content: @Composable () -> Unit) {
    // Re-flow Material3's default Typography through the Inter family so
    // every Text composable picks up the right typeface without each call
    // site referencing FontFamily explicitly. Matches chino-web's tailwind
    // base config (`font-sans` → Inter).
    val inter = ChinoInterFamily()
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = inter),
        displayMedium = base.displayMedium.copy(fontFamily = inter),
        displaySmall = base.displaySmall.copy(fontFamily = inter),
        headlineLarge = base.headlineLarge.copy(fontFamily = inter),
        headlineMedium = base.headlineMedium.copy(fontFamily = inter),
        headlineSmall = base.headlineSmall.copy(fontFamily = inter),
        titleLarge = base.titleLarge.copy(fontFamily = inter),
        titleMedium = base.titleMedium.copy(fontFamily = inter),
        titleSmall = base.titleSmall.copy(fontFamily = inter),
        bodyLarge = base.bodyLarge.copy(fontFamily = inter),
        bodyMedium = base.bodyMedium.copy(fontFamily = inter),
        bodySmall = base.bodySmall.copy(fontFamily = inter),
        labelLarge = base.labelLarge.copy(fontFamily = inter),
        labelMedium = base.labelMedium.copy(fontFamily = inter),
        labelSmall = base.labelSmall.copy(fontFamily = inter),
    )
    MaterialTheme(
        colorScheme = ChinoScheme,
        typography = typography,
    ) {
        // Push Inter into LocalTextStyle so every Text composable that
        // doesn't reference a specific typography style still inherits
        // the family. Without this the raw Text(fontSize = …) call sites
        // would fall back to Compose's platform default (Roboto on
        // Android / SF on iOS) and we'd see two fonts mixed on screen.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = inter),
            content = content,
        )
    }
}
