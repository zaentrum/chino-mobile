package cloud.nalet.chino.mobile.ui.components

import androidx.compose.ui.graphics.RectangleShape

import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoBg2
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder2
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoDim
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.md5Hex
import coil3.compose.AsyncImage

/**
 * Round account avatar — Gravatar from email's MD5, with an initial-on-
 * coloured-circle fallback when there's no email or Gravatar returns 404.
 * Ported from chino-androidtv/ui/auth/Avatar.kt so the same visual
 * appears on the mobile + tablet + TV top bars (replacing the generic
 * `Lucide.User` placeholder we used before).
 *
 * Coil's `error` slot can't be a Composable; we always render the
 * fallback layer BEHIND the AsyncImage so the image just covers it when
 * loaded, and the fallback shows through when Gravatar returns 404 (`d=404`).
 */
@Composable
fun Avatar(
    displayName: String,
    email: String,
    size: Dp = 36.dp,
    onClick: (() -> Unit)? = null,
) {
    val seed = email.ifBlank { displayName }
    val bg = avatarColor(seed)
    // Match chino-web's Avatar.tsx initial calc: split on whitespace /
    // dot / underscore / hyphen, take the first two non-empty words,
    // first letter of each uppercased. "Andreas Meinen" → "AM",
    // "andreas.meinen" → "AM", single-word "andreas" → "A". Web fallback
    // is "?" when the name is empty.
    val initial = displayName
        .split(Regex("[\\s._-]+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }
    val gravatar = email.takeIf { it.isNotBlank() }?.let {
        "https://www.gravatar.com/avatar/${md5Hex(it.trim().lowercase())}?s=240&d=404"
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RectangleShape)
            .background(bg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            // CDP-verified: web avatar uses font-weight 600 / 15px / 22.5
            // line-height. Fixed 15sp + 22sp lineHeight reads cleaner
            // than the previous `size * 0.4` (14.4sp at 36dp) and pins
            // baseline metrics regardless of avatar size.
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (gravatar != null) {
            AsyncImage(
                model = gravatar,
                contentDescription = "Avatar for $displayName",
                modifier = Modifier.size(size).clip(RectangleShape),
            )
        }
    }
}

/** Deterministic hue per seed — matches the TV's palette so the same user
 *  shows the same colour everywhere they sign in. */
private fun avatarColor(seed: String): Color {
    val palette = listOf(
        ChinoCloudBlue, // brand blue
        Color(0xFFFFB454), // amber
        ChinoGreen, // emerald
        Color(0xFF9E86FF), // violet
        Color(0xFFFF6B6B), // coral
        Color(0xFF14B8A6), // teal
        Color(0xFFE879F9), // fuchsia
        Color(0xFFEAB308), // mustard
        Color(0xFF38BDF8), // sky
        Color(0xFFF472B6), // pink
        Color(0xFF4ADE80), // lime
        Color(0xFFFB923C), // orange
    )
    val hash = seed.fold(0) { acc, c -> acc * 31 + c.code }
    val idx = ((hash % palette.size) + palette.size) % palette.size
    return palette[idx]
}
