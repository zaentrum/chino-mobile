package cloud.nalet.chino.mobile.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.ui.theme.ChinoMonoFamily

/** Chino brand mark — `>c` glyph (cloud-blue chevron + light-grey c) in
 *  JetBrains Mono per the nalet design system §01. The TTF is bundled
 *  in commonMain/composeResources/font/ and loaded via [ChinoMonoFamily].
 *  Used as the header cell on the NavigationRail and on the auth splash.
 *
 *  Weight note: the design system specifies ExtraBold (800) and chino-web
 *  declares `font-weight="800"` in chino_icon.svg. We use Bold (700) here
 *  because the browser renders the web logo by drawing the SVG outline
 *  at viewBox 64 and scaling DOWN to a 48px <img>, which softens stems
 *  by ~1 weight step. Compose draws text at the actual sp size with full
 *  hinting, so an honest 800 reads visibly chunkier than the web glyph
 *  at the same target dp. Bold matches the rendered weight on-screen
 *  without changing the font family. */
@Composable
fun LogoMark(sizeDp: Int = 28) {
    Box(
        modifier = Modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = chinoGlyph(),
            fontFamily = ChinoMonoFamily(),
            fontWeight = FontWeight.Bold,
            fontSize = (sizeDp * 0.72).sp,
        )
    }
}

private fun chinoGlyph(): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = Color(0xFF58A6FF))) { append(">") }
    withStyle(SpanStyle(color = Color(0xFFC9D1D9))) { append("c") }
}
