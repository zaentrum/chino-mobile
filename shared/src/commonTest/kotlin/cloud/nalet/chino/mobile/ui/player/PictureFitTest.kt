package cloud.nalet.chino.mobile.ui.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import cloud.nalet.chino.mobile.data.api.PictureArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picture fitted by its own part of the frame (PictureFit.kt), with the
 * numbers chino-web's lib/pictureFit.ts tests too: a phone held sideways
 * (2400 x 1080), a 4:3 tablet, a 16:9 screen, upright; letterboxed,
 * pillarboxed and windowboxed pictures in a 16:9 frame.
 */
class PictureFitTest {
    private val letterbox = Rect(0f, 0.1278f, 1f, 0.1278f + 0.7444f)
    private val pillarbox = Rect(0.125f, 0f, 0.875f, 1f)
    private val windowbox = Rect(0.1625f, 0.05f, 0.1625f + 0.675f, 0.95f)
    private val phone = Size(2400f, 1080f)
    private val hdFrameOnPhone = Size(1920f, 1080f)

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.001f) =
        assertEquals(expected, actual, tolerance)

    private fun near(expected: Rect, actual: Rect, tolerance: Float = 0.05f) {
        assertEquals(expected.left, actual.left, tolerance, "left of $actual")
        assertEquals(expected.top, actual.top, tolerance, "top of $actual")
        assertEquals(expected.right, actual.right, tolerance, "right of $actual")
        assertEquals(expected.bottom, actual.bottom, tolerance, "bottom of $actual")
    }

    @Test
    fun aLetterboxedFilmOnAPhoneHeldSidewaysFitsItsPictureNotItsFrame() {
        val fit = PictureFit(phone, hdFrameOnPhone, letterbox)
        // As wide as the phone: its bars cropped, its picture whole.
        near(1.25f, fit.fitScale)
        near(1080f / (0.7444f * 1080f), fit.fillScale)
        val t = fit.transformAt(0f)
        near(0f, t.dx)
        near(0f, t.dy)
        near(Rect(0f, -135f, 2400f, 1215f), fit.frameOnScreen(t))
        near(Rect(0f, 0f, 2400f, 1080f), fit.subtitleRegion(t))
        assertTrue(fit.fillApplies)
    }

    @Test
    fun aPillarboxedPictureOnAPhoneIsAsBeforeAtFitAndFillsItsWidthAtFill() {
        val fit = PictureFit(phone, hdFrameOnPhone, pillarbox)
        near(1f, fit.fitScale)
        near(2400f / 1440f, fit.fillScale, 0.0001f)
        assertTrue(fit.fillApplies)
        // At fill the 4:3 picture is as wide as the phone, in its middle.
        val frame = fit.frameOnScreen(fit.transformAt(1f))
        near(0f, frame.left + 0.125f * frame.width, 0.5f)
        near(2400f, frame.left + 0.875f * frame.width, 0.5f)
    }

    @Test
    fun onA4By3TabletAPillarboxedPictureFillsTheScreenAtFitAndFillOffersNothingMore() {
        val fit = PictureFit(Size(2048f, 1536f), Size(2048f, 1152f), pillarbox)
        near(4f / 3f, fit.fitScale, 0.0001f)
        near(4f / 3f, fit.fillScale, 0.0001f)
        assertFalse(fit.fillApplies)
        // Pinched, it stays as it is.
        near(fit.fitScale, fit.scaleAt(1f))
    }

    @Test
    fun aWindowboxedPictureOnA16By9ScreenLosesItsBarsAboveAndBelow() {
        val fit = PictureFit(Size(1920f, 1080f), Size(1920f, 1080f), windowbox)
        near(1080f / 972f, fit.fitScale, 0.0001f)
        val t = fit.transformAt(0f)
        near(0f, t.dx, 0.01f)
        near(0f, t.dy, 0.01f)
        // Its picture is the screen's height, in the middle.
        val frame = fit.frameOnScreen(t)
        near(0f, frame.top + 0.05f * frame.height, 0.5f)
        near(1080f, frame.top + 0.95f * frame.height, 0.5f)
    }

    @Test
    fun theCutoutsBandsKeepTheFittedPictureClearOfTheCameraAndFillCoversThemToo() {
        val fit = PictureFit(phone, hdFrameOnPhone, letterbox, side = 100f)
        near(2200f / 1920f, fit.fitScale, 0.0001f)
        near(1080f / (0.7444f * 1080f), fit.fillScale)
        val atFit = fit.frameOnScreen(fit.transformAt(0f))
        near(100f, atFit.left, 0.5f)
        near(2300f, atFit.right, 0.5f)
        // The subtitles stay out of the bands at fill too.
        near(Rect(100f, 0f, 2300f, 1080f), fit.subtitleRegion(fit.transformAt(1f)))
    }

    @Test
    fun uprightTheFrameIsWhereItWasAndFillIsNotOffered() {
        val fit = PictureFit(Size(1080f, 2400f), Size(1080f, 607.5f), letterbox)
        near(1f, fit.fitScale)
        assertFalse(fit.fillApplies)
        near(1f, fit.scaleAt(1f))
        near(Rect(0f, 896.25f, 1080f, 1503.75f), fit.subtitleRegion(fit.transformAt(1f)))
    }

    @Test
    fun aPictureOffTheFramesMiddleIsMovedToTheScreensMiddle() {
        val fit = PictureFit(phone, hdFrameOnPhone, Rect(0f, 0f, 1f, 0.8f))
        near(1.25f, fit.fitScale)
        val t = fit.transformAt(0f)
        near(0f, t.dx)
        near(135f, t.dy, 0.01f)
    }

    @Test
    fun withoutAPictureAreaTheWholeFrameFitsAsBeforeAndFillCoversTheScreen() {
        val fit = PictureFit(phone, hdFrameOnPhone, null)
        near(1f, fit.fitScale)
        near(1.25f, fit.fillScale)
        assertEquals(PictureTransform(1f, 0f, 0f), fit.transformAt(0f))
        // A 16:9 frame on a 16:9 screen: fill is fit.
        assertFalse(PictureFit(Size(1920f, 1080f), Size(1920f, 1080f), null).fillApplies)
    }

    @Test
    fun aFrameNotYetLaidOutIsLeftAlone() {
        val fit = PictureFit(phone, Size(0f, 0f), letterbox)
        assertEquals(PictureTransform.None, fit.transformAt(0f))
        assertFalse(fit.fillApplies)
    }

    @Test
    fun zoomRunsFromFitToFillInEvenStepsOfSize() {
        val fit = PictureFit(phone, hdFrameOnPhone, pillarbox)
        near(fit.fitScale, fit.scaleAt(0f))
        near(fit.fillScale, fit.scaleAt(1f))
        near(kotlin.math.sqrt(fit.fitScale * fit.fillScale), fit.scaleAt(0.5f))
        near(0.5f, fit.zoomOf(fit.scaleAt(0.5f)))
    }

    @Test
    fun aLineInACroppedBottomBarComesUpOntoThePictureWithTheGapItHadBelowIt() {
        val fit = PictureFit(phone, hdFrameOnPhone, letterbox)
        val t = fit.transformAt(0f)
        val frame = fit.frameOnScreen(t)
        val region = fit.subtitleRegion(t)
        // 486 x 78 at (717, 968) of the frame, in its bottom bar.
        val rect = Rect(1200f + 1.25f * (717f - 960f), 540f + 1.25f * (968f - 540f), 1200f + 1.25f * (1203f - 960f), 540f + 1.25f * (1046f - 540f))
        near(Rect(896.25f, 1075f, 1503.75f, 1172.5f), rect)
        val placed = placePicture(rect, frame, region)
        // It kept 42.5 px to the frame's bottom: it keeps them to the screen's.
        near(Rect(896.25f, 940f, 1503.75f, 1037.5f), placed)
    }

    @Test
    fun aLineFarAboveTheCroppedEdgeStaysWhereThePictureHasIt() {
        val fit = PictureFit(phone, hdFrameOnPhone, letterbox)
        val t = fit.transformAt(0f)
        // Inside the picture, 860..938 of the frame: on screen 940..1037.5.
        // It kept 177.5 px to the frame's bottom (142 of the frame's own),
        // more than 8 % (86.4 px): it keeps 86.4 to the screen's, so it
        // rises a little.
        val rect = Rect(900f, 540f + 1.25f * (860f - 540f), 1500f, 540f + 1.25f * (938f - 540f))
        val placed = placePicture(rect, fit.frameOnScreen(t), fit.subtitleRegion(t))
        near(1080f - 86.4f, placed.bottom, 0.05f)
        // One in the middle of the picture does not move at all.
        val middle = Rect(900f, 500f, 1500f, 580f)
        assertEquals(middle, placePicture(middle, fit.frameOnScreen(t), fit.subtitleRegion(t)))
    }

    @Test
    fun whereNothingIsCroppedNothingMoves() {
        val fit = PictureFit(Size(1920f, 1080f), Size(1920f, 1080f), letterbox)
        val t = fit.transformAt(0f)
        val frame = fit.frameOnScreen(t)
        val region = fit.subtitleRegion(t)
        for (rect in listOf(Rect(717f, 968f, 1203f, 1046f), Rect(755f, 4f, 1164f, 82f), Rect(33f, 968f, 1886f, 1051f))) {
            assertEquals(rect, placePicture(rect, frame, region))
        }
    }

    @Test
    fun aLineWiderThanWhatShowsIsMadeSmallerInItsOwnShapeByItsBottomEdge() {
        // A 4:3 picture filling a phone held sideways: its pillars, and the
        // line out in them, cropped at the sides.
        val fit = PictureFit(phone, hdFrameOnPhone, pillarbox)
        val t = fit.transformAt(1f)
        val frame = fit.frameOnScreen(t)
        val region = fit.subtitleRegion(t)
        val k = t.scale
        val rect = Rect(frame.left + k * 111f, frame.top + k * 950f, frame.left + k * 1808f, frame.top + k * 1033f)
        val placed = placePicture(rect, frame, region)
        assertTrue(placed.left >= region.left - 0.01f && placed.right <= region.right + 0.01f, "$placed inside $region")
        assertTrue(placed.top >= region.top - 0.01f && placed.bottom <= region.bottom + 0.01f, "$placed inside $region")
        near(rect.width / rect.height, placed.width / placed.height, 0.001f)
        near(rect.center.x, placed.center.x, 0.5f)
    }

    @Test
    fun aSignInACroppedTopBarComesDownIntoWhatShows() {
        val fit = PictureFit(phone, hdFrameOnPhone, letterbox)
        val t = fit.transformAt(1f)
        val frame = fit.frameOnScreen(t)
        val region = fit.subtitleRegion(t)
        val k = t.scale
        val rect = Rect(frame.left + k * 755f, frame.top + k * 44f, frame.left + k * 1164f, frame.top + k * 122f)
        assertTrue(rect.top < 0f)
        val placed = placePicture(rect, frame, region)
        near(k * 44f, placed.top, 0.05f)
        near(rect.height, placed.height, 0.01f)
    }

    @Test
    fun aPictureAreaIsReadOnlyWhereItIsOneToFit() {
        assertNull(fittedArea(null))
        near(letterbox, assertNotNull(fittedArea(PictureArea(0f, 0.1278f, 1f, 0.7444f))), 0.00001f)
        // The whole frame, or near enough: nothing to fit.
        assertNull(fittedArea(PictureArea(0f, 0f, 1f, 1f)))
        assertNull(fittedArea(PictureArea(0.002f, 0.003f, 0.995f, 0.994f)))
        // Outside the frame, empty, too narrow, not a number: none.
        assertNull(fittedArea(PictureArea(0.5f, 0f, 0.6f, 1f)))
        assertNull(fittedArea(PictureArea(-0.1f, 0f, 1f, 0.8f)))
        assertNull(fittedArea(PictureArea(0f, 0.2f, 1f, 0f)))
        assertNull(fittedArea(PictureArea(0f, 0.45f, 1f, 0.1f)))
        assertNull(fittedArea(PictureArea(Float.NaN, 0.1f, 1f, 0.8f)))
        // The server's rounding past an edge is inside it.
        near(Rect(0f, 0.1f, 1f, 1f), assertNotNull(fittedArea(PictureArea(0f, 0.1f, 1f, 0.9005f))), 0.00001f)
    }

    @Test
    fun aPinchGrowsThePictureWithTheFingersAndGivesALittlePastTheEnds() {
        val fit = PictureFit(phone, hdFrameOnPhone, pillarbox)
        near(0f, fit.zoomAfterPinch(0f, 1f))
        // Twice as far apart from fit: the picture twice as large, past fill.
        val out = fit.zoomAfterPinch(0f, 2f)
        near(fit.zoomOf(fit.fitScale * 2f), out)
        assertTrue(out > 1f)
        assertTrue(pinchGive(out) > 1f && pinchGive(out) < 1f + PINCH_GIVE)
        assertTrue(pinchGive(-0.5f) > -PINCH_GIVE)
        assertTrue(pinchGive(-5f) >= -PINCH_GIVE)
        near(0.4f, pinchGive(0.4f))
        // Where fill is fit, a pinch changes nothing.
        val tablet = PictureFit(Size(2048f, 1536f), Size(2048f, 1152f), pillarbox)
        near(0f, tablet.zoomAfterPinch(0f, 2f))
    }

    @Test
    fun aRememberedFillCarriesOverOnlyToATitleWithAPictureAreaItCropsLittle() {
        // A scope film on a phone held sideways: 7 % of its width.
        val scope = PictureFit(phone, hdFrameOnPhone, letterbox)
        near(1f - 2400f / (scope.fillScale * 1920f), scope.fillLossX)
        near(0f, scope.fillLossY)
        assertTrue(scope.fillLossX in 0.06f..0.08f, "${scope.fillLossX}")
        assertTrue(scope.fillCarriesOver)
        // Without a picture area, whatever it would crop.
        assertFalse(PictureFit(phone, hdFrameOnPhone, null).fillCarriesOver)
        // A 4:3 picture on a phone: 40 % of its height.
        val pillared = PictureFit(phone, hdFrameOnPhone, pillarbox)
        near(0.4f, pillared.fillLossY)
        assertFalse(pillared.fillCarriesOver)
        // A scope film on a 16:10 tablet held sideways: a third of its width.
        val tablet = PictureFit(Size(2560f, 1600f), Size(2560f, 1440f), letterbox)
        assertTrue(tablet.fillLossX > 0.3f, "${tablet.fillLossX}")
        assertFalse(tablet.fillCarriesOver)
        // Upright, nothing fills.
        assertFalse(PictureFit(Size(1080f, 2400f), Size(1080f, 607.5f), letterbox).fillCarriesOver)
    }

    @Test
    fun aTitleRestsFilledByItsOwnChoiceElseByARememberedOneThatCarriesOver() {
        val scope = PictureFit(phone, hdFrameOnPhone, letterbox)
        val bare = PictureFit(phone, hdFrameOnPhone, null)
        assertTrue(scope.restsFilled(rememberedFill = true, titleFill = null))
        assertFalse(bare.restsFilled(rememberedFill = true, titleFill = null))
        // A pinch fills any title for itself, and fits one the remembered fill would fill.
        assertTrue(bare.restsFilled(rememberedFill = false, titleFill = true))
        assertFalse(scope.restsFilled(rememberedFill = true, titleFill = false))
        // Never where fill doesn't apply: upright.
        assertFalse(PictureFit(Size(1080f, 2400f), Size(1080f, 607.5f), letterbox).restsFilled(rememberedFill = true, titleFill = true))
    }

    @Test
    fun aPinchSetsTheRememberedFillOnlyWhereItCarriesOverElseThisTitlesOwn() {
        val scope = PictureFit(phone, hdFrameOnPhone, letterbox)
        assertEquals(FillChoice(remembered = true, title = null), scope.choose(fill = true, rememberedFill = false))
        assertEquals(FillChoice(remembered = false, title = null), scope.choose(fill = false, rememberedFill = true))
        val bare = PictureFit(phone, hdFrameOnPhone, null)
        assertEquals(FillChoice(remembered = false, title = true), bare.choose(fill = true, rememberedFill = false))
        assertEquals(FillChoice(remembered = true, title = false), bare.choose(fill = false, rememberedFill = true))
    }

    @Test
    fun aPinchComesToRestAtFillOrFitOrWhereItBegan() {
        near(1f, pinchRest(0f, 0.3f))
        near(0f, pinchRest(1f, 0.7f))
        near(0f, pinchRest(0f, 0.1f))
        near(1f, pinchRest(1f, 0.9f))
        near(1f, pinchRest(1f, 1.3f))
        near(0f, pinchRest(0f, -0.3f))
    }
}
