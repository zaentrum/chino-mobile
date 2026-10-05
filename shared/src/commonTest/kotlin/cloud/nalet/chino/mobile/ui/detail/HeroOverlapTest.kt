package cloud.nalet.chino.mobile.ui.detail

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** The poster starts below the Back / Home row, however short the backdrop. */
class HeroOverlapTest {
    @Test
    fun aPhonesShortBackdropRidesUpOnlyAsFarAsTheButtonsAllow() {
        // A 411dp-wide phone: its 21:9 backdrop is 176dp. Under a 28dp status
        // bar the buttons end at 28 + 16 + 40 = 84dp, so with the 12dp gap the
        // poster starts at 96dp: it rides 80dp up, not web's 128dp.
        assertEquals(80.dp, heroOverlap(backdropHeight = 176.dp, topInset = 28.dp))
    }

    @Test
    fun aTallBackdropKeepsWebsOverlap() {
        assertEquals(128.dp, heroOverlap(backdropHeight = 548.dp, topInset = 24.dp))
    }

    @Test
    fun aBackdropShorterThanTheButtonsDoesNotRideUpAtAll() {
        assertEquals(0.dp, heroOverlap(backdropHeight = 60.dp, topInset = 24.dp))
    }
}
