package cloud.nalet.chino.mobile.ui.detail

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A title's page has a top bar of its own, from the server as from the
 * phone - Back / Home never over the page's text - and from the phone its
 * banner on top, under the bar, never scrolled under Back / Home; Back /
 * Home sit on one surface, with nothing showing between them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class TitlePageFrameTest {
    @get:Rule
    val compose = createComposeRule()

    private val banner = "Can't reach the server. Your downloads still play."

    private fun page(banner: String?, scroll: ScrollState) {
        compose.setContent {
            TitlePageFrame(banner = banner, onBack = {}, onHome = {}, scroll = scroll, cast = {}) {
                Box(modifier = Modifier.fillMaxWidth().height(3000.dp)) { Text("The title") }
            }
        }
    }

    @Test
    fun fromThePhoneTheBannerStaysOnTopUnderBackAndHomeWhileThePageScrolls() {
        val scroll = ScrollState(0)
        page(banner, scroll)
        val back = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
        val before = compose.onNodeWithContentDescription("information: $banner").getBoundsInRoot()
        // Under the top bar, not under the buttons.
        assertTrue(before.top >= back.bottom, "$before under $back")
        compose.runOnIdle { runBlocking { scroll.scrollTo(1200) } }
        compose.waitForIdle()
        assertTrue(scroll.value > 0)
        // Neither moved: the page scrolls under them.
        assertEquals(before, compose.onNodeWithContentDescription("information: $banner").getBoundsInRoot())
        assertEquals(back, compose.onNodeWithContentDescription("Back").getBoundsInRoot())
    }

    @Test
    fun fromTheServerTheSameTopBarBackAndHomeNeverOverThePagesText() {
        val scroll = ScrollState(0)
        page(null, scroll)
        val back = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
        val text = compose.onNodeWithText("The title").getBoundsInRoot()
        // The page starts under the bar, not under the buttons.
        assertTrue(text.top >= back.bottom, "$text under $back")
        compose.runOnIdle { runBlocking { scroll.scrollTo(1200) } }
        compose.waitForIdle()
        assertTrue(scroll.value > 0)
        // The bar stays; the page scrolls under it.
        assertEquals(back, compose.onNodeWithContentDescription("Back").getBoundsInRoot())
        assertEquals(back.top, compose.onNodeWithContentDescription("Home").getBoundsInRoot().top)
    }

    @Test
    fun theServersPageAndThePhonesHaveBackAndHomeInOnePlace() {
        val scroll = ScrollState(0)
        var banner by mutableStateOf<String?>(null)
        compose.setContent {
            TitlePageFrame(banner = banner, onBack = {}, onHome = {}, scroll = scroll, cast = {}) {
                Box(modifier = Modifier.fillMaxWidth().height(3000.dp)) { Text("The title") }
            }
        }
        val fromTheServer = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
        banner = this.banner
        compose.waitForIdle()
        assertEquals(fromTheServer, compose.onNodeWithContentDescription("Back").getBoundsInRoot())
    }

    private fun assertOneSurface() {
        val back = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
        val home = compose.onNodeWithContentDescription("Home").getBoundsInRoot()
        // Side by side, the 1 dp divider between them and nothing else.
        assertEquals(back.top, home.top)
        assertEquals(back.bottom, home.bottom)
        assertEquals(1f, (home.left - back.right).value, 0.5f)
    }

    @Test
    fun fromThePhoneBackAndHomeSitOnOneSurfaceNoGapBetweenThem() {
        page(banner, ScrollState(0))
        assertOneSurface()
    }

    @Test
    fun overTheBackdropBackAndHomeSitOnOneSurfaceNoGapBetweenThem() {
        page(null, ScrollState(0))
        assertOneSurface()
    }
}
