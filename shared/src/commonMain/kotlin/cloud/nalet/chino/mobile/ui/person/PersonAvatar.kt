package cloud.nalet.chino.mobile.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import coil3.compose.AsyncImage

/** Faces sit in the upper part of a portrait: keep them in the crop
 *  (chino-web's object-position 50% 20%). */
private val FaceAlignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.6f)

/**
 * A catalogue person's picture: their portrait when the catalog has one, their
 * initials otherwise — chino-web's PersonAvatar. The portrait ([portraitUrl],
 * a profile_url with the stream token on it) is laid over the initials, so
 * they show while it loads and stay when it fails: chino-api answers 404 for
 * a person without a portrait. [portrait] frames it 2:3 like the posters (the
 * person page) instead of square (a search row).
 *
 * Decorative: the person's name is always written beside it.
 */
@Composable
internal fun PersonAvatar(
    name: String,
    portraitUrl: String?,
    width: Dp,
    portrait: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width, if (portrait) width * 1.5f else width)
            .clip(RectangleShape)
            // On the neutral surface, as chino-web and the TV app draw a
            // person (an account's avatar is the one with a colour).
            .background(ChinoSurface)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            color = ChinoFg2,
            fontSize = (width.value * 0.36f).sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (portraitUrl != null) {
            AsyncImage(
                model = portraitUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = FaceAlignment,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** First letter of the first two words, uppercased; "?" for no name. Same
 *  rule as the account avatar and chino-web. */
internal fun initialsOf(name: String): String =
    name.split(Regex("[\\s._-]+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }

/** "1 title", "12 titles". */
internal fun titleCount(count: Int): String = if (count == 1) "1 title" else "$count titles"

