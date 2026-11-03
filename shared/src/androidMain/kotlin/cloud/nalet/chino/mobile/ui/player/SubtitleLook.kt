package cloud.nalet.chino.mobile.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.CaptioningManager
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Rect
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.text.LanguageFeatureSpan
import androidx.media3.common.text.RubySpan
import androidx.media3.common.text.TextAnnotation
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlin.math.roundToInt

/*
 * The one look of every subtitle (the design system's subtitles pattern):
 * white text with a black outline, no background and no window, 5.33 % of
 * the picture's height in the system's sans-serif - every language has its
 * letters in it - centred at the bottom, wrapping within 80 % of the
 * picture's width. Of what a file brings of its own, italics (a voice off
 * screen, a song, a thought) and ruby stay; colours, fonts, sizes, boxes and
 * positions are not used. Where the person switched the device's caption
 * settings on (accessibility), theirs are used instead, box and size
 * included. The line rises above the player's controls while they show.
 * "The picture" is what shows of it: where the player fits a title's picture
 * and crops its frame's bars (PictureFit.kt), the size and the margin go by
 * the frame as it shows on the screen.
 */

/** The look's caption style: white, a black outline, nothing behind it. */
internal val SUBTITLE_STYLE = CaptionStyleCompat(
    Color.WHITE,
    Color.TRANSPARENT,
    Color.TRANSPARENT,
    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
    Color.BLACK,
    Typeface.SANS_SERIF,
)

/** How much of the picture's width a line may take before it wraps. */
internal const val SUBTITLE_WIDTH_FRACTION = 0.8f

/** [view] in the look - or in the person's own caption settings where they
 *  switched them on: their style and their text size. [ownCues]: the view
 *  is fed cues already in the look ([inTheLook]), their italics and ruby
 *  kept; else Media3 drops every style a file brings, italics too. */
internal fun applySubtitleLook(view: SubtitleView, context: Context, ownCues: Boolean = false) {
    view.setApplyEmbeddedStyles(ownCues)
    view.setApplyEmbeddedFontSizes(false)
    val captioning = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
    if (captioning?.isEnabled == true) {
        view.setUserDefaultStyle()
        view.setUserDefaultTextSize()
    } else {
        view.setStyle(SUBTITLE_STYLE)
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
    }
}

/**
 * The cues shown at once, as the look draws them (chino-androidtv's
 * layOutCues): the text ones as one subtitle, centred at the bottom within
 * [SUBTITLE_WIDTH_FRACTION] of the picture's width - each cue on lines of its
 * own, top to bottom as the file placed them, so two cues at once (a sign and
 * a line, two speakers) stack and never draw over each other, the newer above
 * as Media3 stacks them. A file's places, box and sizes go; of its styles only
 * italics and ruby stay ([keptStyle]). A picture subtitle (PGS, VobSub) keeps
 * its own place: a picture cannot be re-flowed. It keeps its own shape too
 * ([inItsShape]).
 */
internal fun inTheLook(cues: List<Cue>): List<Cue> {
    val pictures = cues.filter { it.bitmap != null }.map(::inItsShape)
    val texts = cues.filter { it.bitmap == null && !it.text.isNullOrBlank() }
    if (texts.isEmpty()) return pictures
    val text = SpannableStringBuilder()
    texts.withIndex()
        .sortedWith(compareBy<IndexedValue<Cue>> { heightOnScreen(it.value) }.thenBy { it.index })
        .forEachIndexed { i, (_, cue) ->
            if (i > 0) text.append('\n')
            text.append(cue.text)
        }
    val subtitle = Cue.Builder()
        .setText(keptStyle(text))
        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
        .setSize(SUBTITLE_WIDTH_FRACTION)
        .build()
    return pictures + subtitle
}

/** One cue as the look draws it ([inTheLook]). */
internal fun inTheLook(cue: Cue): Cue = inTheLook(listOf(cue)).singleOrNull() ?: cue

/** How far from 16:9 a picture subtitle's plane may be and still be in an HD
 *  picture's shape. */
private const val HD_PLANE_TOLERANCE = 0.02f

/**
 * A picture subtitle (PGS) in its own shape. Media3 draws a picture over a
 * share of the picture's width and a share of its height, the shares of the
 * plane it was made on - stretched wherever the picture played is of
 * another shape than that plane: a film cropped to 2.39:1 that kept its
 * Blu-ray's subtitles, made on a 16:9 plane, gets them squashed. A plane in
 * an HD picture's shape (16:9, square pixels) keeps the picture's own shape
 * instead: as wide as Media3 draws it, as high as that makes it, and one in
 * the lower half of its plane hangs from its bottom edge there, so the frame
 * never cuts it off. Where the picture is of the plane's shape this is
 * exactly Media3's drawing. A plane of another shape (a DVD's or a
 * broadcast's, whose pixels are not square) is drawn as Media3 draws it.
 */
internal fun inItsShape(cue: Cue): Cue {
    val bitmap = cue.bitmap ?: return cue
    if (cue.size <= 0f || cue.bitmapHeight <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return cue
    if (cue.lineType != Cue.LINE_TYPE_FRACTION || cue.lineAnchor != Cue.ANCHOR_TYPE_START || cue.line == Cue.DIMEN_UNSET) return cue
    val plane = (bitmap.width / cue.size) / (bitmap.height / cue.bitmapHeight)
    if (kotlin.math.abs(plane - 16f / 9f) > 16f / 9f * HD_PLANE_TOLERANCE) return cue
    val shaped = cue.buildUpon().setBitmapHeight(Cue.DIMEN_UNSET)
    if (cue.line + cue.bitmapHeight / 2f > 0.5f) {
        shaped.setLine(cue.line + cue.bitmapHeight, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END)
    }
    return shaped.build()
}

/** How high a file placed a cue, from 0 (the top) to 1 (the bottom): what
 *  orders cues shown at once. One with no line is at the bottom; Media3
 *  numbers a WebVTT file's cues without one -1, -2, ... from the bottom up. */
private fun heightOnScreen(cue: Cue): Float {
    if (cue.line == Cue.DIMEN_UNSET) return 1f
    return when (cue.lineType) {
        Cue.LINE_TYPE_FRACTION -> cue.line.coerceIn(0f, 1f)
        Cue.LINE_TYPE_NUMBER -> if (cue.line >= 0) (cue.line / 15f).coerceIn(0f, 1f) else (1f + (cue.line + 1f) / 15f).coerceIn(0f, 1f)
        else -> 1f
    }
}

/** [text] with the italics and the ruby (and the other language features,
 *  emphasis marks among them) it brings, nothing else: no colour, no size,
 *  no font, no bold, no box. A bold italic is an italic. Each ruby reading
 *  gets a [RubyReading] that draws it: Media3's own drawing leaves it out. */
internal fun keptStyle(text: CharSequence): CharSequence {
    if (text !is Spanned) return text.toString()
    val kept = SpannableString(text.toString())
    for (span in text.getSpans(0, text.length, Any::class.java)) {
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        val flags = text.getSpanFlags(span)
        when {
            span is RubySpan -> {
                kept.setSpan(span, start, end, flags)
                kept.setSpan(RubyReading(span.rubyText, under = span.position == TextAnnotation.POSITION_AFTER), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            span is LanguageFeatureSpan -> kept.setSpan(span, start, end, flags)
            span is StyleSpan && (span.style and Typeface.ITALIC) != 0 -> kept.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
        }
    }
    return kept
}

/** A ruby reading's size, as a share of the text's. */
internal const val RUBY_SIZE = 0.5f

/**
 * A ruby reading drawn with its characters, as the TV draws it: the reading
 * at half their size, centred over them (under them where the file puts it
 * after), in the subtitle's own paint, so the outline goes round it as round
 * the text. The line grows by the reading's height.
 */
internal class RubyReading(val reading: String, val under: Boolean) : ReplacementSpan() {
    private fun small(paint: Paint) = TextPaint(paint).apply { textSize = paint.textSize * RUBY_SIZE }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val small = small(paint)
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            val extra = kotlin.math.ceil(small.descent() - small.ascent()).toInt()
            if (under) {
                fm.descent += extra
                fm.bottom += extra
            } else {
                fm.ascent -= extra
                fm.top -= extra
            }
        }
        return kotlin.math.ceil(maxOf(paint.measureText(text, start, end), small.measureText(reading))).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val small = small(paint)
        val base = paint.measureText(text, start, end)
        val above = small.measureText(reading)
        val width = maxOf(base, above)
        canvas.drawText(text, start, end, x + (width - base) / 2f, y.toFloat(), paint)
        val readingY = if (under) y + paint.descent() - small.ascent() else y + paint.ascent() - small.descent()
        canvas.drawText(reading, x + (width - above) / 2f, readingY, small)
    }
}

/**
 * The player's subtitles in the look, over the picture: the PlayerView's own
 * subtitle view gives way to one over the whole view, above the picture's
 * frame and outside it - the frame is scaled and moved to fit its picture
 * (PictureFitter), the subtitles are not - fed the cues as [inTheLook] shapes
 * them. Text is laid out in the subtitle region ([place]: what shows of the
 * frame, clear of the display cutout), so its size and margin follow what
 * shows of the picture; a picture subtitle (PGS) is drawn where the frame's
 * transform puts it, kept whole inside that region ([placePicture]). Follows
 * the player it is bound to ([bind]); null unbinds. One subtitle at a time:
 * the cues shown at once are one ([inTheLook]). Where the PlayerView has no
 * overlay, its own view stays, styled the same.
 */
internal class LookSubtitles(playerView: PlayerView) : Player.Listener {
    private val view: SubtitleView?
    private val holder: RegionFrame?
    private var player: Player? = null

    init {
        val own = playerView.subtitleView
        val overlay = playerView.overlayFrameLayout
        if (overlay != null && own != null) {
            own.visibility = View.GONE
            val v = SubtitleView(playerView.context)
            holder = RegionFrame(playerView.context).also { frame ->
                frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                overlay.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            view = v
        } else {
            holder = null
            view = null
        }
        view?.let { applySubtitleLook(it, playerView.context, ownCues = true) }
            ?: own?.let { applySubtitleLook(it, playerView.context) }
    }

    // The cues on now, as the look shapes them, before they are placed; the
    // cues on screen, or the last ones while none are - of the subtitle on
    // now, placed: what the rise above the controls goes by. The controls'
    // top edge, once known.
    private var cues: List<Cue>? = null
    private var shown: List<Cue> = emptyList()
    private var subtitleOn: Format? = null
    private var controlsTop: Float? = null

    // Where the frame is drawn, in the player view's pixels, and the subtitle
    // region, in whole ones (place); null until the fitter has laid them out.
    private var frame: Rect? = null
    private var region: Rect? = null

    // A rise asked for while the frame moves (a pinch), told once it rests.
    private var settled = true
    private var clearanceDue = false

    /** Hears the rise [clearance] asks for again as what is on screen
     *  changes, the controls where they were. */
    var onClearance: ((Float) -> Unit)? = null

    /**
     * Where the frame is drawn ([frameOnScreen]) and the subtitle region
     * ([region]: text is laid out in it, pictures kept inside it), in the
     * player view's pixels. [settled]: the frame is at rest, not moved by a
     * pinch; the rise above the controls follows only once it is.
     */
    fun place(frameOnScreen: Rect, region: Rect, settled: Boolean = true) {
        val v = view ?: return
        this.settled = settled
        val whole = Rect(
            region.left.roundToInt().toFloat(),
            region.top.roundToInt().toFloat(),
            region.right.roundToInt().toFloat(),
            region.bottom.roundToInt().toFloat(),
        )
        if (frameOnScreen != frame || whole != this.region) {
            frame = frameOnScreen
            this.region = whole
            holder?.region = android.graphics.Rect(whole.left.toInt(), whole.top.toInt(), whole.right.toInt(), whole.bottom.toInt())
            show(v, cues)
        }
        if (settled && clearanceDue) {
            clearanceDue = false
            controlsTop?.let { onClearance?.invoke(clearance(it)) }
        }
    }

    /** How far the subtitle must rise to clear controls that start at
     *  [controlsTopPx] (window pixels), with a small gap: none where what it
     *  shows ends above them (a phone held upright). A line of text ends at
     *  the look's bottom margin of the subtitle region, a picture (PGS) at
     *  its own bottom edge ([subtitleClearance]). */
    fun clearance(controlsTopPx: Float): Float {
        controlsTop = controlsTopPx
        val overlay = holder ?: return 0f
        val at = IntArray(2)
        overlay.getLocationInWindow(at)
        val r = region ?: Rect(0f, 0f, overlay.width.toFloat(), overlay.height.toFloat())
        val top = at[1] + r.top
        val textBottom = top + r.height * (1f - SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        val gap = 8f * overlay.resources.displayMetrics.density
        val width = r.width.roundToInt()
        val height = r.height.roundToInt()
        // Never so far that a picture that rises leaves what shows at its top.
        return minOf(
            subtitleClearance(shown, width, height, top, textBottom, controlsTopPx, gap),
            subtitleRiseRoom(shown, width, height),
        )
    }

    // How far the subtitle is raised now ([lift]).
    private var lifted = 0f

    /** The line [px] higher than the look puts it: text and the pictures in
     *  the lower half of what shows; one in the upper half - a sign - stays
     *  where it is ([heldInPlace]). */
    fun lift(px: Float) {
        val v = view ?: return
        v.translationY = -px
        if (px == lifted) return
        lifted = px
        val now = placedNow ?: return
        if (now.any { it.bitmap != null }) v.setCues(held(now))
    }

    // The cues placed now, before they are held against the rise.
    private var placedNow: List<Cue>? = null

    private fun held(cues: List<Cue>): List<Cue> {
        val r = region ?: return cues
        return heldInPlace(cues, lifted, r.width.roundToInt(), r.height.roundToInt())
    }

    fun bind(next: Player?) {
        val v = view ?: return
        if (player === next) return
        player?.removeListener(this)
        player = next
        subtitleOn = next?.let { selectedSubtitle(it.currentTracks) }
        show(v, next?.currentCues?.cues?.let(::inTheLook))
        next?.addListener(this)
    }

    override fun onCues(cueGroup: CueGroup) {
        view?.let { show(it, inTheLook(cueGroup.cues)) }
    }

    override fun onTracksChanged(tracks: Tracks) {
        val subtitle = selectedSubtitle(tracks)
        if (subtitle == subtitleOn) return
        // Another subtitle: the rise no longer goes by the last one's cues.
        subtitleOn = subtitle
        shown = emptyList()
        val top = controlsTop ?: return
        onClearance?.invoke(clearance(top))
    }

    private fun show(v: SubtitleView, next: List<Cue>?) {
        cues = next
        val placed = next?.map(::placed)
        placedNow = placed
        v.setCues(placed?.let(::held))
        if (placed.isNullOrEmpty() || placed == shown) return
        shown = placed
        val top = controlsTop ?: return
        if (settled) onClearance?.invoke(clearance(top)) else clearanceDue = true
    }

    /** [cue] where it is drawn: a picture as the frame's transform puts it,
     *  kept whole in the region ([placePicture]), in fractions of the region;
     *  text as it is - the view lays it out in the region. */
    private fun placed(cue: Cue): Cue {
        val f = frame ?: return cue
        val r = region ?: return cue
        if (r.width <= 0f || r.height <= 0f) return cue
        val drawn = pictureRect(cue, f) ?: return cue
        val at = placePicture(drawn, f, r)
        return cue.buildUpon()
            .setPosition((at.left - r.left) / r.width)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine((at.top - r.top) / r.height, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setSize(at.width / r.width)
            .setBitmapHeight(at.height / r.height)
            .build()
    }
}

/**
 * Where a picture cue is drawn over a frame drawn at [parent] - its box, in
 * the same pixels - as Media3's SubtitlePainter places a bitmap: its
 * position and line anchored as the cue says, as wide as its size, as high as
 * its bitmap height or, without one, as its bitmap's shape makes it; null for
 * a text cue or one without a place.
 */
internal fun pictureRect(cue: Cue, parent: Rect): Rect? {
    val bitmap = cue.bitmap ?: return null
    if (parent.width <= 0f || parent.height <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return null
    if (cue.line == Cue.DIMEN_UNSET || cue.lineType != Cue.LINE_TYPE_FRACTION) return null
    if (cue.size == Cue.DIMEN_UNSET || cue.position == Cue.DIMEN_UNSET) return null
    val width = parent.width * cue.size
    val height = if (cue.bitmapHeight != Cue.DIMEN_UNSET) parent.height * cue.bitmapHeight else width * bitmap.height / bitmap.width
    val anchorX = parent.left + parent.width * cue.position
    val anchorY = parent.top + parent.height * cue.line
    val left = when (cue.positionAnchor) {
        Cue.ANCHOR_TYPE_END -> anchorX - width
        Cue.ANCHOR_TYPE_MIDDLE -> anchorX - width / 2f
        else -> anchorX
    }
    val top = when (cue.lineAnchor) {
        Cue.ANCHOR_TYPE_END -> anchorY - height
        Cue.ANCHOR_TYPE_MIDDLE -> anchorY - height / 2f
        else -> anchorY
    }
    return Rect(left, top, left + width, top + height)
}

/**
 * The look's subtitle view held at the subtitle [region] (its own pixels;
 * null: all of it): measured and laid out there as soon as the region
 * changes - in the middle of a layout pass too, where the fitter hears the
 * frame laid out, and on every frame of a pinch - without asking for a
 * layout of the screen; and again whenever its own layout runs.
 */
internal class RegionFrame(context: Context) : FrameLayout(context) {
    var region: android.graphics.Rect? = null
        set(value) {
            if (field == value) return
            field = value
            placeChild()
        }

    private fun placeChild() {
        val child = getChildAt(0) ?: return
        // Not laid out yet: its layout places it.
        if (width == 0 && height == 0) return
        val r = region ?: android.graphics.Rect(0, 0, width, height)
        val w = r.width().coerceAtLeast(0)
        val h = r.height().coerceAtLeast(0)
        if (child.measuredWidth != w || child.measuredHeight != h || child.isLayoutRequested) {
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        child.layout(r.left, r.top, r.left + w, r.top + h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = placeChild()
}

/**
 * Where a picture cue is drawn - its top and bottom edges, in pixels from the
 * top of a box [width] x [height] - as Media3's SubtitlePainter places a
 * bitmap; null for a text cue.
 */
internal fun pictureEdges(cue: Cue, width: Int, height: Int): Pair<Float, Float>? {
    val bitmap = cue.bitmap ?: return null
    if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return null
    if (cue.line == Cue.DIMEN_UNSET || cue.lineType != Cue.LINE_TYPE_FRACTION || cue.size == Cue.DIMEN_UNSET) return null
    val drawnHeight = if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
        height * cue.bitmapHeight
    } else {
        width * cue.size * bitmap.height / bitmap.width
    }
    val anchor = height * cue.line
    val top = when (cue.lineAnchor) {
        Cue.ANCHOR_TYPE_END -> anchor - drawnHeight
        Cue.ANCHOR_TYPE_MIDDLE -> anchor - drawnHeight / 2f
        else -> anchor
    }
    return top to top + drawnHeight
}

/** Where a picture cue's bottom edge is drawn ([pictureEdges]); null for a
 *  text cue. */
internal fun pictureBottom(cue: Cue, width: Int, height: Int): Float? = pictureEdges(cue, width, height)?.second

/** Whether a picture cue is in the upper half of its box [width] x
 *  [height] - a sign at the top: it stays where it is while the subtitle
 *  rises above the controls ([heldInPlace]). False for text. */
internal fun inUpperHalf(cue: Cue, width: Int, height: Int): Boolean {
    val (top, bottom) = pictureEdges(cue, width, height) ?: return false
    return top + bottom < height
}

/**
 * How far the subtitle must rise, in pixels, so that what it shows - [shown],
 * the cues on screen or the last ones - ends [gap] above [edge] (window
 * pixels): the controls' top. A line of text ends at [textBottom]
 * (window pixels), a picture at its own bottom edge in the box [width] x
 * [height] whose top is at [boxTop] ([pictureBottom]); one in the upper half
 * stays where it is ([inUpperHalf]) and counts for nothing. Nothing shown
 * yet: as a line of text would.
 */
internal fun subtitleClearance(shown: List<Cue>, width: Int, height: Int, boxTop: Float, textBottom: Float, edge: Float, gap: Float): Float {
    var lowest = if (shown.isEmpty() || shown.any { it.bitmap == null }) textBottom else Float.NEGATIVE_INFINITY
    for (cue in shown) {
        if (inUpperHalf(cue, width, height)) continue
        pictureBottom(cue, width, height)?.let { lowest = maxOf(lowest, boxTop + it) }
    }
    if (lowest == Float.NEGATIVE_INFINITY) return 0f
    return (lowest - (edge - gap)).coerceAtLeast(0f)
}

/**
 * How far the subtitle may rise and keep whole a picture that rises - one in
 * the lower half of its box [width] x [height]: that picture's top edge, in
 * pixels from the box's top. A sign in the upper half bounds nothing: it
 * stays where it is ([heldInPlace]). No bound where none shows.
 */
internal fun subtitleRiseRoom(shown: List<Cue>, width: Int, height: Int): Float {
    var room = Float.POSITIVE_INFINITY
    for (cue in shown) {
        val (top, bottom) = pictureEdges(cue, width, height) ?: continue
        if (top + bottom >= height) room = minOf(room, top.coerceAtLeast(0f))
    }
    return room
}

/**
 * [cues] as a subtitle view raised [lift] pixels draws them, its box [width]
 * x [height]: a picture in the upper half ([inUpperHalf]) lowered in it as
 * far, so that on the screen it stays where it was - a sign stays on what it
 * translates while the line under it rises above the controls. Text and the
 * lower half's pictures rise with the view.
 */
internal fun heldInPlace(cues: List<Cue>, lift: Float, width: Int, height: Int): List<Cue> {
    if (lift == 0f || height <= 0) return cues
    return cues.map { cue ->
        if (!inUpperHalf(cue, width, height)) cue else cue.buildUpon().setLine(cue.line + lift / height, cue.lineType).build()
    }
}

/** The subtitle [tracks] play: the format of the selected text track, or null. */
internal fun selectedSubtitle(tracks: Tracks): Format? {
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_TEXT || !group.isSelected) continue
        for (i in 0 until group.length) if (group.isTrackSelected(i)) return group.getTrackFormat(i)
    }
    return null
}
