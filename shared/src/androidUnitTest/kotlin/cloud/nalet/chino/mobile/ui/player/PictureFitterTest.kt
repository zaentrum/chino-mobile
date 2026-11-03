package cloud.nalet.chino.mobile.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picture fitted in a real PlayerView (PictureFitter): its content frame
 * laid out by Media3, drawn scaled and moved; the subtitles laid out in what
 * shows of it, a PGS picture in a cropped bar drawn where the screen shows
 * it. A phone at half size: 1200 x 540 held sideways, 540 x 1200 upright.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PictureFitterTest {
    /** A player of nothing but a video size. */
    private class Video(size: VideoSize) : SimpleBasePlayer(Looper.getMainLooper()) {
        var size: VideoSize = size
            set(value) {
                field = value
                invalidateState()
            }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setVideoSize(size)
            .build()
    }

    private val letterbox = Rect(0f, 0.1278f, 1f, 0.1278f + 0.7444f)

    private class Rig(val view: PlayerView, val look: LookSubtitles, val fitter: PictureFitter, val video: Video) {
        val content: View = view.findViewById(androidx.media3.ui.R.id.exo_content_frame)
        /** What holds the look's subtitle view, over the whole player view. */
        val holder: ViewGroup get() = view.overlayFrameLayout!!.getChildAt(0) as ViewGroup
        val subtitles: SubtitleView get() = holder.getChildAt(0) as SubtitleView

        /** Where the subtitle view is laid out: the subtitle region. */
        val region: List<Int> get() = subtitles.let { listOf(it.left, it.top, it.right, it.bottom) }

        fun layOut(width: Int, height: Int) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun rig(width: Int, height: Int, area: Rect?, fill: Boolean = false, side: Float = 0f, video: VideoSize = VideoSize(1920, 1080)): Rig {
        val view = PlayerView(ApplicationProvider.getApplicationContext())
        val look = LookSubtitles(view)
        val fitter = PictureFitter(view, look)
        val player = Video(video)
        fitter.area = area
        fitter.fill = fill
        fitter.side = side
        fitter.bind(player)
        // Media3 sets this from the player's video size; here, from ours.
        view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            .setAspectRatio(if (video.height == 0) 0f else video.width.toFloat() / video.height)
        return Rig(view, look, fitter, player).also { it.layOut(width, height) }
    }

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.002f) = assertEquals(expected, actual, tolerance)

    @Test
    fun heldSidewaysALetterboxedFilmsFrameIsDrawnLargerSoItsPictureIsAsWideAsThePhone() {
        val rig = rig(1200, 540, letterbox)
        assertEquals(960, rig.content.width)
        near(1.25f, rig.content.scaleX)
        near(1.25f, rig.content.scaleY)
        near(0f, rig.content.translationY, 0.05f)
        // The subtitles have the whole screen: what shows of the frame.
        assertEquals(listOf(0, 0, 1200, 540), rig.region)
        assertNotNull(rig.fitter.fit)
        assertTrue(rig.fitter.fit!!.fillApplies)
    }

    @Test
    fun withFillChosenItCoversTheScreenAndAPinchDrivesItBetween() {
        val rig = rig(1200, 540, letterbox, fill = true)
        near(540f / (0.7444f * 540f), rig.content.scaleX)
        rig.fitter.driven = 0f
        near(1.25f, rig.content.scaleX)
        rig.fitter.driven = 0.5f
        assertTrue(rig.content.scaleX > 1.25f && rig.content.scaleX < 1.343f)
        rig.fitter.driven = null
        near(540f / (0.7444f * 540f), rig.content.scaleX)
    }

    @Test
    fun uprightTheFrameIsAsMedia3LaysItOutFillChosenOrNotAndTheSubtitlesHaveTheFrame() {
        val rig = rig(540, 1200, letterbox, fill = true)
        near(1f, rig.content.scaleX)
        near(0f, rig.content.translationY, 0.05f)
        // The 16:9 frame: 540 x 303 in the middle of 1200.
        assertNear(listOf(0, 448, 540, 751), rig.region)
        // Turned sideways, fill is back.
        rig.layOut(1200, 540)
        near(540f / (0.7444f * 540f), rig.content.scaleX)
    }

    @Test
    fun theCutoutsBandsKeepTheFittedPictureAndTheSubtitlesClearOfTheCamera() {
        val rig = rig(1200, 540, letterbox, side = 50f)
        near(1100f / 960f, rig.content.scaleX)
        assertEquals(listOf(50, 0, 1150, 540), rig.region)
        // Without a picture area, a 2.39:1 file is kept out of them too.
        val wide = rig(1200, 540, null, side = 50f, video = VideoSize(1920, 804))
        assertEquals(1200, wide.content.width)
        near(1100f / 1200f, wide.content.scaleX)
    }

    @Test
    fun aFrameNotYetOfTheVideosShapeIsLeftAsMedia3LaysItOut() {
        val rig = rig(1200, 540, letterbox, video = VideoSize.UNKNOWN)
        near(1f, rig.content.scaleX)
        assertNull(rig.fitter.fit)
        // The video's size comes: once its frame is laid out to it, it fits.
        rig.video.size = VideoSize(1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        rig.view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame).setAspectRatio(16f / 9f)
        rig.layOut(1200, 540)
        near(1.25f, rig.content.scaleX)
    }

    @Test
    fun aRenditionOfAnotherSizeFitsTheSame() {
        val rig = rig(1200, 540, letterbox)
        rig.video.size = VideoSize(1280, 720)
        shadowOf(Looper.getMainLooper()).idle()
        rig.layOut(1200, 540)
        near(1.25f, rig.content.scaleX)
    }

    /** Where [view] draws, at [width] x [height]: the box of its opaque pixels. */
    private fun drawn(view: View, width: Int, height: Int): List<Int> {
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(frame))
        val pixels = IntArray(width * height).also { frame.getPixels(it, 0, width, 0, 0, width, height) }
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (Color.alpha(pixels[y * width + x]) > 128) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
        }
        return listOf(left, top, right, bottom)
    }

    private fun assertNear(expected: List<Int>, actual: List<Int>) =
        assertTrue(expected.zip(actual).all { (e, a) -> abs(e - a) <= 1 }, "drawn at $actual, not $expected")

    /** A white box 486 x 78 at (717, 968) of a 1920x1080 plane - in a
     *  letterboxed film's bottom bar - as PgsParser places it. */
    private val inTheBar: Cue = Cue.Builder()
        .setBitmap(Bitmap.createBitmap(486, 78, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
        .setPosition(717f / 1920f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(968f / 1080f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setSize(486f / 1920f)
        .setBitmapHeight(78f / 1080f)
        .build()

    @Test
    fun aPictureSubtitleInACroppedBottomBarIsDrawnOnTheScreenWithTheGapItHadBelowIt() {
        val rig = rig(1200, 540, letterbox)
        rig.look.onCues(CueGroup(listOf(inTheBar), 0))
        // Where the frame's transform puts it, 537.5..586.25, is off the
        // screen's bottom; up by as far, it keeps 21.25 px above the edge.
        assertNear(listOf(448, 470, 752, 519), drawn(rig.holder, 1200, 540))
    }

    @Test
    fun aboveTheControlsTheLineInTheBarRisesAndASignAtTheTopStaysWhereItIs() {
        val sign = Cue.Builder()
            .setBitmap(Bitmap.createBitmap(402, 78, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
            .setPosition(759f / 1920f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine(170f / 1080f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setSize(402f / 1920f)
            .setBitmapHeight(78f / 1080f)
            .build()
        val rig = rig(1200, 540, letterbox)
        rig.look.onCues(CueGroup(listOf(sign, inTheBar), 0))
        // The sign 8 % below the screen's top (it kept more to the frame's),
        // the line up from the cropped bar.
        assertNear(listOf(448, 43, 752, 519), drawn(rig.holder, 1200, 540))
        // The controls show: the line rises 100 px, the sign stays.
        rig.look.lift(100f)
        assertNear(listOf(448, 43, 752, 419), drawn(rig.holder, 1200, 540))
        rig.look.lift(0f)
        assertNear(listOf(448, 43, 752, 519), drawn(rig.holder, 1200, 540))
    }

    @Test
    fun whereNothingIsCroppedThePictureSubtitleIsWhereThePlanePutsIt() {
        // A 16:9 screen: the frame fits as it is, the bar shows.
        val rig = rig(960, 540, letterbox)
        near(1f, rig.content.scaleX)
        rig.look.onCues(CueGroup(listOf(inTheBar), 0))
        assertNear(listOf(359, 484, 602, 523), drawn(rig.holder, 960, 540))
    }

    @Test
    fun aPictureSubtitleFollowsTheFrameUprightTooWhereItsBarShows() {
        val rig = rig(540, 1200, letterbox)
        rig.look.onCues(CueGroup(listOf(inTheBar), 0))
        // The frame 540 x 303.75 from 448.125: 717/1920 of its width, 968/1080 of its height.
        assertNear(listOf(202, 720, 338, 742), drawn(rig.holder, 540, 1200))
    }

    @Test
    fun aLineOfTextIsLaidOutInWhatShowsOfTheFrame() {
        val text = Cue.Builder().setText("A line of text").build()
        // Upright: inside the frame, in its lower part, as before.
        val upright = rig(540, 1200, letterbox)
        upright.look.onCues(CueGroup(listOf(text), 0))
        val (_, top, _, bottom) = drawn(upright.holder, 540, 1200)
        assertTrue(top in (448 + 303 / 2)..bottom && bottom <= 448 + 303, "text at $top..$bottom")
        // Sideways, fitted: the look's margin above the screen's bottom (8 %,
        // 43 px), not above the frame's, which is off the screen (the line
        // would end at 553).
        val sideways = rig(1200, 540, letterbox)
        sideways.look.onCues(CueGroup(listOf(text), 0))
        val (_, _, _, b) = drawn(sideways.holder, 1200, 540)
        assertTrue(b in 450..520, "text ends at $b")
    }
}
