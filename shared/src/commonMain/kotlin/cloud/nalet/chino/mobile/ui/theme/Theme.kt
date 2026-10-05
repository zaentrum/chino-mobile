package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

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

/** chino-web's index.css tracks every h1–h6 in to -0.015em, which keeps the
 *  mono glyphs tight. */
private val HeadingTracking = (-0.015).em

/** JetBrains Mono as [ChinoTheme] loaded it, once for the whole app. */
private val LocalMonoFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/**
 * The heading face. chino-web sets every h1–h6 in JetBrains Mono, tracked in
 * to -0.015em, and everything else in Inter (index.css). A raw
 * `Text(fontSize = …)` takes [LocalTextStyle], which is Inter, so a heading
 * names this style, and only its face changes: it keeps the size, weight and
 * line height it gives itself, and the rest of the style around it.
 *
 *     Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, style = ChinoHeading)
 */
val ChinoHeading: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = LocalTextStyle.current.merge(
        TextStyle(fontFamily = LocalMonoFamily.current, letterSpacing = HeadingTracking),
    )

/** chino-web's `font-mono` (tailwind.config.ts) for mono text that is no
 *  heading, such as the bell's count: JetBrains Mono, untracked, as the web
 *  draws it. */
val ChinoMono: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = LocalTextStyle.current.merge(
        TextStyle(fontFamily = LocalMonoFamily.current, letterSpacing = 0.sp),
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
    // Material's own titles (a dialog's) take it from here; a raw Text that
    // is a heading takes [ChinoHeading].
    val mono = ChinoMonoFamily()
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = mono, letterSpacing = HeadingTracking),
        displayMedium = base.displayMedium.copy(fontFamily = mono, letterSpacing = HeadingTracking),
        displaySmall = base.displaySmall.copy(fontFamily = mono, letterSpacing = HeadingTracking),
        headlineLarge = base.headlineLarge.copy(fontFamily = mono, letterSpacing = HeadingTracking),
        headlineMedium = base.headlineMedium.copy(fontFamily = mono, letterSpacing = HeadingTracking),
        headlineSmall = base.headlineSmall.copy(fontFamily = mono, letterSpacing = HeadingTracking),
        titleLarge = base.titleLarge.copy(fontFamily = mono, letterSpacing = HeadingTracking),
        titleMedium = base.titleMedium.copy(fontFamily = mono, letterSpacing = HeadingTracking),
        titleSmall = base.titleSmall.copy(fontFamily = mono, letterSpacing = HeadingTracking),
        bodyLarge = base.bodyLarge.copy(fontFamily = inter, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = inter, letterSpacing = 0.sp),
        bodySmall = base.bodySmall.copy(fontFamily = inter, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontFamily = inter, letterSpacing = 0.sp),
        labelMedium = base.labelMedium.copy(fontFamily = inter, letterSpacing = 0.sp),
        labelSmall = base.labelSmall.copy(fontFamily = inter, letterSpacing = 0.sp),
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
        // The mono family goes down once, for [ChinoHeading] and [ChinoMono].
        CompositionLocalProvider(
            // Untracked, as the web draws Inter: Material's body style
            // spaces it +0.5sp wider.
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = inter, letterSpacing = 0.sp),
            LocalMonoFamily provides mono,
            content = content,
        )
    }
}
