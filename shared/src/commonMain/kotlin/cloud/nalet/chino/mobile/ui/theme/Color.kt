package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Brand tokens mirroring chino-web's tailwind 'nalet' tokens
// (chino-web tailwind.config.ts + index.css). Keep names aligned with
// the web tokens (bg, surface, surface-2, border, muted, accent) so
// cross-platform design discussions reference the same vocabulary.
internal val ChinoBg = Color(0xFF0D1117)         // nalet.bg — primary canvas
internal val ChinoSurface = Color(0xFF161B22)    // nalet.surface
internal val ChinoSurfaceHi = Color(0xFF1C2128)  // nalet.surface-2
internal val ChinoBorder = Color(0xFF21262D)     // nalet.border
internal val ChinoFg = Color(0xFFE6E6E6)         // fg
internal val ChinoFg2 = Color(0xFFC9D1D9)        // nalet.text — wordmark grey
internal val ChinoMuted = Color(0xFF8B949E)      // nalet.muted
internal val ChinoCloudBlue = Color(0xFF58A6FF)  // nalet.accent — chevron + accent
