package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

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
    // Headings/titles/display use JetBrains Mono (terminal aesthetic per the
    // nalet design system); body/label stay Inter. Terminal headings carry a
    // slightly negative tracking (~ -0.015em) to keep the mono glyphs tight.
    val mono = ChinoMonoFamily()
    val headingTracking = (-0.015).em
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = mono, letterSpacing = headingTracking),
        displayMedium = base.displayMedium.copy(fontFamily = mono, letterSpacing = headingTracking),
        displaySmall = base.displaySmall.copy(fontFamily = mono, letterSpacing = headingTracking),
        headlineLarge = base.headlineLarge.copy(fontFamily = mono, letterSpacing = headingTracking),
        headlineMedium = base.headlineMedium.copy(fontFamily = mono, letterSpacing = headingTracking),
        headlineSmall = base.headlineSmall.copy(fontFamily = mono, letterSpacing = headingTracking),
        titleLarge = base.titleLarge.copy(fontFamily = mono, letterSpacing = headingTracking),
        titleMedium = base.titleMedium.copy(fontFamily = mono, letterSpacing = headingTracking),
        titleSmall = base.titleSmall.copy(fontFamily = mono, letterSpacing = headingTracking),
        bodyLarge = base.bodyLarge.copy(fontFamily = inter),
        bodyMedium = base.bodyMedium.copy(fontFamily = inter),
        bodySmall = base.bodySmall.copy(fontFamily = inter),
        labelLarge = base.labelLarge.copy(fontFamily = inter),
        labelMedium = base.labelMedium.copy(fontFamily = inter),
        labelSmall = base.labelSmall.copy(fontFamily = inter),
    )
    // Square everything — the design system uses zero corner radius (no
    // rounded corners, no pills) across all Material3 shape slots.
    val squareShapes = Shapes(
        extraSmall = RoundedCornerShape(0.dp),
        small = RoundedCornerShape(0.dp),
        medium = RoundedCornerShape(0.dp),
        large = RoundedCornerShape(0.dp),
        extraLarge = RoundedCornerShape(0.dp),
    )
    MaterialTheme(
        colorScheme = ChinoScheme,
        typography = typography,
        shapes = squareShapes,
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
