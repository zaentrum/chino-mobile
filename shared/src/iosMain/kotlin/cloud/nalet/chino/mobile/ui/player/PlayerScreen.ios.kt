package cloud.nalet.chino.mobile.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey

/**
 * iOS PlayerScreen stub. AVPlayer + AVPlayerLayer wiring lands when iOS
 * ships as a real target. For now this keeps the shared `expect class`
 * declaration satisfied so commonMain compiles for iOS targets.
 */
actual class PlayerScreen actual constructor(
    private val itemId: String,
    private val fromStart: Boolean,
    private val resumeSec: Int,
) : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "iOS player — coming soon", color = Color.White)
        }
    }
}
