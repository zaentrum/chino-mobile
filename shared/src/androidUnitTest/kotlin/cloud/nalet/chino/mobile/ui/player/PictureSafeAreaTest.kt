package cloud.nalet.chino.mobile.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Held sideways, the picture is drawn inside the safe area: never through
 * the display cutout's band, as far from the other edge, so it stays centred.
 * Upright, the cutout at the top changes nothing of its place.
 */
@RunWith(RobolectricTestRunner::class)
// Room for the phone held either way.
@Config(sdk = [34], qualifiers = "w960dp-h960dp")
class PictureSafeAreaTest {
    @get:Rule
    val compose = createComposeRule()

    private fun pictureIn(width: Int, height: Int, cutout: WindowInsets): DpRect {
        compose.setContent {
            Box(modifier = Modifier.size(width.dp, height.dp)) {
                Box(modifier = Modifier.fillMaxSize().pictureInSafeArea(cutout).testTag("picture"))
            }
        }
        return compose.onNodeWithTag("picture").getBoundsInRoot()
    }

    @Test
    fun sidewaysWithTheCutoutAtTheLeftThePictureStartsAfterItsBandAndEndsAsFarFromTheRight() {
        // A 2400 x 1080 phone at 2.625: a 136 px band is about 52 dp.
        val picture = pictureIn(914, 411, WindowInsets(left = 52.dp))
        assertEquals(52f, picture.left.value, 0.5f)
        assertEquals(914f - 52f, picture.right.value, 0.5f)
        assertEquals(0f, picture.top.value, 0.5f)
        assertEquals(411f, picture.bottom.value, 0.5f)
    }

    @Test
    fun turnedTheOtherWayTheCutoutAtTheRightTheSame() {
        val picture = pictureIn(914, 411, WindowInsets(right = 52.dp))
        assertEquals(52f, picture.left.value, 0.5f)
        assertEquals(914f - 52f, picture.right.value, 0.5f)
    }

    @Test
    fun uprightTheCutoutAtTheTopLeavesThePictureWhereItWas() {
        val picture = pictureIn(411, 800, WindowInsets(top = 40.dp))
        assertEquals(0f, picture.left.value, 0.5f)
        assertEquals(411f, picture.right.value, 0.5f)
        assertEquals(0f, picture.top.value, 0.5f)
        assertEquals(800f, picture.bottom.value, 0.5f)
    }

    @Test
    fun withoutACutoutTheWholeScreen() {
        val picture = pictureIn(914, 411, WindowInsets(0, 0, 0, 0))
        assertEquals(0f, picture.left.value, 0.5f)
        assertEquals(914f, picture.right.value, 0.5f)
    }
}
