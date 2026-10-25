package cloud.nalet.chino.mobile.ui.player

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Held sideways, the strip beside the display cutout is the player's black,
 * not the app's navy: while the player shows, its window's background is
 * black, and as it was again once the player closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerWindowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun thePlayersWindowIsBlackBehindThenAsItWas() {
        val window = compose.activity.window
        val navy = 0xFF0B0F19.toInt()
        compose.runOnUiThread { window.setBackgroundDrawable(ColorDrawable(navy)) }
        var showing by mutableStateOf(true)
        compose.setContent { if (showing) PlayerWindowEffect() }
        compose.waitForIdle()
        assertEquals(Color.BLACK, (window.decorView.background as ColorDrawable).color)
        showing = false
        compose.waitForIdle()
        assertEquals(navy, (window.decorView.background as ColorDrawable).color)
    }
}
