package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Brand tokens mirroring chino-web's tailwind 'nalet' tokens
// (chino-web tailwind.config.ts + index.css). Keep names aligned with
// the web tokens (bg, surface, surface-2, border, muted, accent) so
// cross-platform design discussions reference the same vocabulary.
internal val ChinoBg = Color(0xFF0B0F19)         // nalet.bg — primary canvas
internal val ChinoBg2 = Color(0xFF0D1117)        // nalet.bg-2 — recessed / terminal blocks; dark text on accent
internal val ChinoSurface = Color(0xFF11161F)    // nalet.surface
internal val ChinoSurfaceHi = Color(0xFF161B26)  // nalet.surface-2
internal val ChinoBorder = Color(0xFF1F2633)     // nalet.border
internal val ChinoBorder2 = Color(0xFF2A3142)    // nalet.border-2
internal val ChinoFg = Color(0xFFE6E6E6)         // fg
internal val ChinoFg2 = Color(0xFFC9D1D9)        // nalet.text — wordmark grey / fg-2
internal val ChinoMuted = Color(0xFFAEB8C2)      // nalet.fg-muted
internal val ChinoDim = Color(0xFF6E7787)        // nalet.fg-dim — hint text
internal val ChinoCloudBlue = Color(0xFF58A6FF)  // nalet.accent — chevron + accent
internal val ChinoGreen = Color(0xFF2EA043)      // signal green — watched / success
internal val ChinoRed = Color(0xFFF85149)        // signal red — destructive / error
