package cloud.nalet.chino.mobile.ui.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import cloud.nalet.chino.mobile.data.api.PictureArea
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * The picture fitted to the screen by its own part of the frame, not by the
 * frame (chino-web's lib/pictureFit.ts, the same rules): a 2.39:1 film kept
 * in a 16:9 frame with black bars baked in fills a phone held sideways as a
 * 2.39:1 file would, its bars cropped, never its picture. Fill, chosen with a
 * pinch, zooms on until the picture covers the screen, its edges cropped.
 * Pure, so the tests pin the numbers.
 */

/** How far a picture area may go past the frame's edges and still be read as
 *  inside it: the server's rounding to four places. */
internal const val PICTURE_AREA_SLACK = 0.001f

/** The narrowest a picture may be, as a share of its frame's width or
 *  height: a narrower one is a measure gone wrong, never fitted. */
internal const val PICTURE_AREA_MIN_SIDE = 0.2f

/** A picture at least this share of its frame both ways is the whole frame. */
internal const val PICTURE_AREA_WHOLE = 0.99f

/** How much larger than fit fill must be to be offered: less is the same
 *  picture. */
internal const val FILL_GAIN_MIN = 1.01f

/**
 * [area] as the player fits it: its edges in fractions of the frame, inside
 * it. Null - the frame fits as before - where the server gave none, or one
 * outside the frame, narrower than [PICTURE_AREA_MIN_SIDE] or near enough the
 * whole frame ([PICTURE_AREA_WHOLE]).
 */
internal fun fittedArea(area: PictureArea?): Rect? {
    area ?: return null
    if (!area.left.isFinite() || !area.top.isFinite() || !area.width.isFinite() || !area.height.isFinite()) return null
    if (area.left < -PICTURE_AREA_SLACK || area.top < -PICTURE_AREA_SLACK || area.width <= 0f || area.height <= 0f) return null
    if (area.left + area.width > 1f + PICTURE_AREA_SLACK || area.top + area.height > 1f + PICTURE_AREA_SLACK) return null
    val left = area.left.coerceIn(0f, 1f)
    val top = area.top.coerceIn(0f, 1f)
    val right = (area.left + area.width).coerceIn(left, 1f)
    val bottom = (area.top + area.height).coerceIn(top, 1f)
    if (right - left < PICTURE_AREA_MIN_SIDE || bottom - top < PICTURE_AREA_MIN_SIDE) return null
    if (right - left >= PICTURE_AREA_WHOLE && bottom - top >= PICTURE_AREA_WHOLE) return null
    return Rect(left, top, right, bottom)
}

/** The frame scaled by [scale] about its centre, then moved by [dx], [dy]
 *  pixels: where the player draws it. */
internal data class PictureTransform(val scale: Float, val dx: Float, val dy: Float) {
    companion object {
        /** The frame where it is laid out. */
        val None = PictureTransform(1f, 0f, 0f)
    }
}

/**
 * The picture in the player's [box]: the coded frame laid out [frame] large
 * in the middle of it (fitted to the box whole, as a player lays out any
 * video), the picture [area] of it ([fittedArea]; null for the whole frame),
 * and the display cutout's band at each side, [side] pixels ([side] at both,
 * so the picture stays in the middle).
 *
 * - **Fit** ([fitScale]): the picture as large as fits the box less the
 *   cutout's bands, in the middle; the frame's bars outside it are cropped,
 *   the picture never is.
 * - **Fill** ([fillScale]): the picture as large as covers the whole box, the
 *   cutout's bands too; its edges are cropped.
 *
 * Fill is offered ([fillApplies]) only in a box wider than tall - a phone
 * held sideways - and where it is larger than fit by more than
 * [FILL_GAIN_MIN]: upright it would leave a sliver of a film's width.
 */
internal data class PictureFit(val box: Size, val frame: Size, val area: Rect? = null, val side: Float = 0f) {
    private val valid = box.width > 0f && box.height > 0f && frame.width > 0f && frame.height > 0f
    private val pictureWidth = (area?.width ?: 1f) * frame.width
    private val pictureHeight = (area?.height ?: 1f) * frame.height
    private val sideBand = side.coerceIn(0f, box.width / 4f)

    val fitScale: Float = if (!valid) 1f else min((box.width - 2f * sideBand) / pictureWidth, box.height / pictureHeight)
    val fillScale: Float = if (!valid) 1f else max(box.width / pictureWidth, box.height / pictureHeight)
    val fillApplies: Boolean = valid && box.width > box.height && fillScale > fitScale * FILL_GAIN_MIN

    /** The frame's scale at [zoom]: fit at 0, fill at 1, in even steps of
     *  size between them (and on past either, for a pinch that overshoots). */
    fun scaleAt(zoom: Float): Float = if (!fillApplies) fitScale else fitScale * (fillScale / fitScale).pow(zoom)

    /** The zoom of [scale] ([scaleAt] the other way round). */
    fun zoomOf(scale: Float): Float =
        if (!fillApplies || scale <= 0f) 0f else ln(scale / fitScale) / ln(fillScale / fitScale)

    /** The frame's transform at [zoom]: scaled by [scaleAt], moved so that
     *  the picture's centre is the box's. */
    fun transformAt(zoom: Float): PictureTransform {
        if (!valid) return PictureTransform.None
        val scale = scaleAt(zoom)
        val a = area ?: return PictureTransform(scale, 0f, 0f)
        val offsetX = (a.center.x - 0.5f) * frame.width
        val offsetY = (a.center.y - 0.5f) * frame.height
        return PictureTransform(scale, -scale * offsetX, -scale * offsetY)
    }

    /** Where the frame is drawn under [transform], in the box's pixels. */
    fun frameOnScreen(transform: PictureTransform): Rect {
        val width = frame.width * transform.scale
        val height = frame.height * transform.scale
        val left = (box.width - width) / 2f + transform.dx
        val top = (box.height - height) / 2f + transform.dy
        return Rect(left, top, left + width, top + height)
    }

    /** What the subtitles are laid out in under [transform]: the frame as it
     *  shows, inside the box less the cutout's bands. */
    fun subtitleRegion(transform: PictureTransform): Rect {
        val shown = frameOnScreen(transform)
        val safe = Rect(sideBand, 0f, box.width - sideBand, box.height)
        if (!shown.overlaps(safe)) return safe
        return shown.intersect(safe)
    }
}

/** How far a picture subtitle keeps from the top and bottom of what shows, at
 *  most, as a share of its height: a text line's 8 %. */
internal const val PICTURE_SUBTITLE_EDGE_V = 0.08f

/** ... and from its sides, at most, as a share of its width: the 10 % the
 *  text keeps on either side of its 80 %. */
internal const val PICTURE_SUBTITLE_EDGE_H = 0.10f

/**
 * Where a picture subtitle (PGS) is drawn: [rect], where the picture's
 * transform puts it with its frame drawn at [frame], kept whole inside
 * [region] (the subtitle region: what shows of the frame). It keeps the gap
 * the file gave it to each edge of the frame - at most
 * [PICTURE_SUBTITLE_EDGE_V] of the region's height above and below it,
 * [PICTURE_SUBTITLE_EDGE_H] of its width at its sides - to the region's edge
 * there, and moves no further than that takes: a line in a bottom bar that
 * is cropped comes up into the picture, never off the screen. One larger than
 * the room left (a line wider than a cropped picture) is made smaller, in its
 * own shape: in the middle where it was, and by its bottom edge in the lower
 * half of the frame, its top edge in the upper. Where nothing of the frame is
 * cropped, nothing moves.
 */
internal fun placePicture(rect: Rect, frame: Rect, region: Rect): Rect {
    if (rect.width <= 0f || rect.height <= 0f || region.width <= 0f || region.height <= 0f) return rect
    val edgeV = region.height * PICTURE_SUBTITLE_EDGE_V
    val edgeH = region.width * PICTURE_SUBTITLE_EDGE_H
    var room = Rect(
        left = region.left + (rect.left - frame.left).coerceIn(0f, edgeH),
        top = region.top + (rect.top - frame.top).coerceIn(0f, edgeV),
        right = region.right - (frame.right - rect.right).coerceIn(0f, edgeH),
        bottom = region.bottom - (frame.bottom - rect.bottom).coerceIn(0f, edgeV),
    )
    if (room.width <= 0f || room.height <= 0f) room = region
    var placed = rect
    val shrink = min(1f, min(room.width / rect.width, room.height / rect.height))
    if (shrink < 1f) {
        val width = rect.width * shrink
        val height = rect.height * shrink
        val left = rect.center.x - width / 2f
        val top = if (rect.center.y > frame.center.y) rect.bottom - height else rect.top
        placed = Rect(left, top, left + width, top + height)
    }
    val dx = when {
        placed.left < room.left -> room.left - placed.left
        placed.right > room.right -> room.right - placed.right
        else -> 0f
    }
    val dy = when {
        placed.bottom > room.bottom -> room.bottom - placed.bottom
        placed.top < room.top -> room.top - placed.top
        else -> 0f
    }
    return placed.translate(dx, dy)
}

/** How far past fit or fill a pinch takes the picture, at most, in zoom: it
 *  gives a little, so the end is felt. */
internal const val PINCH_GIVE = 0.08f

/** How far a pinch must go, in zoom, to decide: out past this, fill; in past
 *  it, fit; less, back where it was. */
internal const val PINCH_DECIDES = 0.2f

/** The zoom two fingers make that began a pinch at [start] and have spread
 *  [spread] times as far apart since (less than 1: brought together): the
 *  picture grows with them, size for size. */
internal fun PictureFit.zoomAfterPinch(start: Float, spread: Float): Float =
    if (!fillApplies || spread <= 0f || !spread.isFinite()) start else zoomOf(scaleAt(start) * spread)

/** The zoom shown for a pinch's [zoom]: as it is between fit (0) and fill
 *  (1), and past either only [PINCH_GIVE] at most, ever harder to go. */
internal fun pinchGive(zoom: Float): Float = when {
    zoom < 0f -> -PINCH_GIVE * (1f - exp(zoom / PINCH_GIVE))
    zoom > 1f -> 1f + PINCH_GIVE * (1f - exp(-(zoom - 1f) / PINCH_GIVE))
    else -> zoom
}

/** Where a pinch that began at [start] and was let go at [zoom] comes to
 *  rest: fill (1) if it went out by more than [PINCH_DECIDES], fit (0) if it
 *  came in by more, else where it began. */
internal fun pinchRest(start: Float, zoom: Float): Float = when {
    zoom - start > PINCH_DECIDES -> 1f
    start - zoom > PINCH_DECIDES -> 0f
    else -> if (start >= 0.5f) 1f else 0f
}
