package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Brand tokens mirroring chino-web's tailwind 'nalet' tokens
// (chino-web tailwind.config.ts + index.css). Keep names aligned with
// the web tokens (bg, surface, surface-2, border, muted, accent) so
// cross-platform design discussions reference the same vocabulary.
internal val ChinoBg = Color(0xFF0B0F19)         // nalet.bg — primary canvas
internal val ChinoBg2 = Color(0xFF0D1117)        // nalet.bg-2 — recessed / terminal blocks
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
// The design system's warning state (--state-warning, -bg, -border): a strip's
// glyph and left edge, its fill and its frame.
internal val ChinoWarning = Color(0xFFF2B233)
internal val ChinoWarningBg = Color(0xFF24211A)
internal val ChinoWarningBorder = Color(0xFF846B3B)

// A label or an icon on a filled colour - cloud-blue, green, red, amber, an
// account avatar's - is the darkest background colour: the design system's
// rule.
// On blue 7.58:1 (white was 2.53), green 5.68 (3.37), red 5.71 (3.35), amber
// 10.21 (1.88); on the avatars' twelve colours 5.68 to 10.99 (1.74 to 3.37).
internal val ChinoOnFill = ChinoBg

// The design system's switch, off: its dim knob (the design system's fg-dim)
// and its track's edge (border-control), on the dark track (bg-2). On, the
// knob is ChinoOnFill on the cloud-blue track.
internal val SwitchKnobOff = Color(0xFF7B8597)
internal val SwitchEdgeOff = Color(0xFF646C7A)

/** The design system's filled colours, on which a label is [ChinoOnFill]. */
private val Fills = setOf(ChinoCloudBlue, ChinoGreen, ChinoRed, ChinoWarning)

/** The colour of a label or an icon on [fill]: [ChinoOnFill] on one of the
 *  design system's filled colours, else [otherwise] - a neutral button's
 *  light text. */
internal fun contentOn(fill: Color, otherwise: Color = Color.White): Color =
    if (fill in Fills) ChinoOnFill else otherwise

/**
 * The account avatars' twelve colours, picked by a hash of the seed - the
 * account's email, else its name - as the TV app picks them, so an account
 * has the same colour on the phone and the TV. Their initials are
 * [ChinoOnFill]. A catalogue person (cast and crew) has no colour: the
 * neutral surface, as on the TV and the web.
 */
internal fun avatarColor(seed: String): Color {
    val hash = seed.fold(0) { acc, c -> acc * 31 + c.code }
    return AvatarPalette[((hash % AvatarPalette.size) + AvatarPalette.size) % AvatarPalette.size]
}

internal val AvatarPalette = listOf(
    ChinoCloudBlue,     // brand blue
    Color(0xFFFFB454),  // amber
    ChinoGreen,         // emerald
    Color(0xFF9E86FF),  // violet
    Color(0xFFFF6B6B),  // coral
    Color(0xFF14B8A6),  // teal
    Color(0xFFE879F9),  // fuchsia
    Color(0xFFEAB308),  // mustard
    Color(0xFF38BDF8),  // sky
    Color(0xFFF472B6),  // pink
    Color(0xFF4ADE80),  // lime
    Color(0xFFFB923C),  // orange
)
