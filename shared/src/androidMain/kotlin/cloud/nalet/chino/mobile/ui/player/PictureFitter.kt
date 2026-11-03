package cloud.nalet.chino.mobile.ui.player

import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/** How far the content frame's shape may be from the video's and still be
 *  its layout: Media3 leaves a frame within 1 % of the view's shape as the
 *  view is. */
private const val FRAME_SHAPE_SLACK = 0.02f

/**
 * A title's picture fitted to the screen in a PlayerView (PictureFit.kt):
 * the view's content frame - the video's surface and its shutter - keeps the
 * layout Media3 gives it, fitted whole to the view, and is drawn scaled and
 * moved so that the picture [area] fits the view less the display cutout's
 * bands ([side]), or covers it where the viewer chose [fill] and the view is
 * held sideways; a pinch drives the zoom between them ([driven]). A
 * SurfaceView follows its frame's transform from API 24 on. The subtitles are
 * told where the frame is drawn and what of it shows ([LookSubtitles.place]).
 *
 * The fit follows the view's size (a turn of the phone), the frame's (a
 * rendition of another shape) and the player it is bound to ([bind]); until
 * the video's size is known - and its frame laid out to it - the frame is
 * left where Media3 lays it out.
 */
internal class PictureFitter(private val playerView: PlayerView, private val subtitles: LookSubtitles?) : Player.Listener {
    private val content: View? = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame)
    private var player: Player? = null
    private val relaidOut = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) apply()
    }

    init {
        playerView.addOnLayoutChangeListener(relaidOut)
        content?.addOnLayoutChangeListener(relaidOut)
    }

    /** The picture's part of the frame ([fittedArea]); null for all of it. */
    var area: Rect? = null
        set(value) {
            if (field == value) return
            field = value
            apply()
        }

    /** The display cutout's band at each side, in pixels. */
    var side: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            apply()
        }

    /** Whether the viewer chose fill: the picture covers the view where
     *  fill applies ([PictureFit.fillApplies]). */
    var fill: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            apply()
        }

    /** The zoom a pinch - or the picture settling after it - gives now
     *  (0 fit, 1 fill); null at rest, where [fill] decides. */
    var driven: Float? = null
        set(value) {
            if (field == value) return
            field = value
            apply()
        }

    /** The fit as the view, the frame and the picture are now; null until
     *  the video's size and its frame are known. */
    var fit: PictureFit? = null
        private set

    /** The zoom the picture is drawn at now. */
    val zoom: Float get() = driven ?: if (fill && fit?.fillApplies == true) 1f else 0f

    fun bind(next: Player?) {
        if (player === next) return
        player?.removeListener(this)
        player = next
        next?.addListener(this)
        apply()
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) = apply()

    /** The frame drawn as the fit says, and the subtitles told. */
    fun apply() {
        val frame = content ?: return
        val box = Size(playerView.width.toFloat(), playerView.height.toFloat())
        val laidOut = Size(frame.width.toFloat(), frame.height.toFloat())
        val next = if (frameKnown(laidOut)) PictureFit(box, laidOut, area, side) else null
        fit = next
        val transform = next?.transformAt(zoom) ?: PictureTransform.None
        frame.pivotX = laidOut.width / 2f
        frame.pivotY = laidOut.height / 2f
        frame.scaleX = transform.scale
        frame.scaleY = transform.scale
        frame.translationX = transform.dx
        frame.translationY = transform.dy
        val drawn = next?.frameOnScreen(transform)
            ?: Rect(frame.left.toFloat(), frame.top.toFloat(), frame.right.toFloat(), frame.bottom.toFloat())
        val region = next?.subtitleRegion(transform) ?: PictureFit(box, laidOut, null, side).subtitleRegion(PictureTransform.None)
        subtitles?.place(drawn, region, settled = driven == null)
    }

    /** Whether the frame is laid out to the video's shape: the video's size
     *  is known and the frame has taken it. */
    private fun frameKnown(laidOut: Size): Boolean {
        val video = player?.videoSize ?: return false
        if (video.width <= 0 || video.height <= 0 || laidOut.width <= 0f || laidOut.height <= 0f) return false
        val shape = video.width * video.pixelWidthHeightRatio / video.height
        return abs(laidOut.width / laidOut.height / shape - 1f) < FRAME_SHAPE_SLACK
    }
}
