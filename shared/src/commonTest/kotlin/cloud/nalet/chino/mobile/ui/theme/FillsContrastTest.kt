package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A label or an icon on a filled colour is dark (ChinoOnFill): the design
 * system's rule, with its numbers - on blue 7.58:1, green 5.68, red 5.71,
 * amber 10.21 - and the account avatars' twelve colours, which are the TV
 * app's, picked as it picks them. Dim text keeps 4.5:1 on the background and
 * the surfaces.
 */
class FillsContrastTest {
    /** WCAG 2's contrast ratio of two opaque colours. */
    private fun contrast(a: Color, b: Color): Double {
        fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertRatio(expected: Double, fill: Color) =
        assertEquals(expected, contrast(ChinoOnFill, fill), 0.01, "on $fill")

    @Test
    fun aLabelOnAFillIsDarkAtTheDesignSystemsRatios() {
        assertRatio(7.58, ChinoCloudBlue)
        assertRatio(5.68, ChinoGreen)
        assertRatio(5.71, ChinoRed)
        assertRatio(10.21, ChinoWarning)
    }

    @Test
    fun dimTextKeepsFourAndAHalfToOneOnTheBackgroundAndBothSurfaces() {
        // The TV's fg-dim; the off switch's knob is the same token.
        assertEquals(5.15, contrast(ChinoDim, ChinoBg), 0.01)
        assertEquals(4.87, contrast(ChinoDim, ChinoSurface), 0.01)
        assertEquals(4.63, contrast(ChinoDim, ChinoSurfaceHi), 0.01)
        listOf(ChinoBg, ChinoBg2, ChinoSurface, ChinoSurfaceHi).forEach {
            assertTrue(contrast(ChinoDim, it) >= 4.5, "dim on $it: ${contrast(ChinoDim, it)}")
        }
    }

    @Test
    fun onAFillDarkOnANeutralOneLight() {
        listOf(ChinoCloudBlue, ChinoGreen, ChinoRed, ChinoWarning).forEach { assertEquals(ChinoOnFill, contentOn(it)) }
        assertEquals(Color.White, contentOn(ChinoBorder))
        assertEquals(Color.White, contentOn(ChinoBorder2))
        assertEquals(ChinoMuted, contentOn(Color.White.copy(alpha = 0.1f), otherwise = ChinoMuted))
    }

    @Test
    fun everyAvatarColourCarriesDarkInitials() {
        assertEquals(12, AvatarPalette.size)
        AvatarPalette.forEach { assertTrue(contrast(ChinoOnFill, it) >= 5.68 - 0.01, "initials on $it: ${contrast(ChinoOnFill, it)}") }
    }

    @Test
    fun anAccountsAvatarIsTheColourTheTvAppGivesIt() {
        // String.hashCode, floorMod 12, as chino-androidtv's avatarColor.
        assertEquals(ChinoCloudBlue, avatarColor("Demo User"))
        assertEquals(Color(0xFFEAB308), avatarColor("viewer@example.com"))
        assertEquals(Color(0xFF4ADE80), avatarColor("someone@example.org"))
        assertEquals(ChinoCloudBlue, avatarColor(""))
    }
}
