package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage

/**
 * Poster / card artwork with a graceful design-system placeholder.
 *
 * Un-enriched catalog items 404 for their poster (and empty items carry no
 * URL at all), which would otherwise show a broken/empty box on a card. This
 * wraps Coil's [SubcomposeAsyncImage] so that BOTH the blank-URL case and a
 * load Error render [ArtworkPlaceholder] filling the SAME slot — no layout
 * shift, since the caller already sizes the surrounding Box (2:3 aspect ratio
 * / fixed height) exactly as it does for a successful image.
 *
 * The placeholder is intentional, not an error state: DS surface fill, a 1px
 * border, square corners, and the item's title initial(s) in JetBrains Mono
 * (fg-dim) — or the `>c` brand mark when there is no title. No broken-image
 * glyph. Callers pass [Modifier.fillMaxSize] just as they did for the raw
 * AsyncImage; the surrounding card/detail layout is untouched.
 */
@Composable
fun PosterImage(
    model: String?,
    title: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (model.isNullOrBlank()) {
        ArtworkPlaceholder(title = title, modifier = modifier)
        return
    }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        // Un-enriched items 404 here — swap in the DS placeholder rather than
        // Coil's empty box. loading/success fall through to the normal image.
        error = { ArtworkPlaceholder(title = title, modifier = Modifier.fillMaxSize()) },
    )
}

/**
 * The square-cornered DS placeholder tile. Fills whatever slot the caller
 * gives it (poster 2:3, etc.) so it drops in for a missing image with zero
 * layout shift. Centered content is the title's initial(s) in mono / fg-dim,
 * falling back to the `>c` brand mark for generic or untitled entries.
 */
@Composable
fun ArtworkPlaceholder(
    title: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // surface-2 over surface for a subtle raised fill, 1px border,
            // square corners — the nalet card idiom.
            .background(ChinoSurfaceHi)
            .border(1.dp, ChinoBorder, RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        val initials = posterInitialsOf(title)
        if (initials == null) {
            // Generic / untitled — `>c` brand mark (cloud-blue chevron + grey c).
            Text(
                text = chinoGlyph(),
                fontFamily = ChinoMonoFamily(),
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
            )
        } else {
            Text(
                text = initials,
                fontFamily = ChinoMonoFamily(),
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                color = ChinoDim,
            )
        }
    }
}

/** `>c` brand mark — cloud-blue chevron + wordmark-grey c, JetBrains Mono.
 *  Mirrors the shell [cloud.nalet.chino.mobile.ui.shell.LogoMark] glyph so the
 *  generic-poster fallback carries the same mark. */
private fun chinoGlyph(): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = ChinoCloudBlue)) { append(">") }
    withStyle(SpanStyle(color = ChinoFg2)) { append("c") }
}

/**
 * Up to two uppercase initials from a title, e.g. "Blue Eye Samurai" -> "BS".
 * Returns null when there is no usable title so the caller shows the `>c`
 * brand mark instead.
 */
private fun posterInitialsOf(title: String?): String? {
    val parts = title?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
    if (parts.isEmpty()) return null
    val first = parts.first().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    val last = if (parts.size > 1) parts.last().firstOrNull()?.uppercaseChar()?.toString().orEmpty() else ""
    return (first + last).ifBlank { null }
}
