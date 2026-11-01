package cloud.nalet.chino.mobile.ui.player

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The one look of every subtitle: white with a black outline and nothing
 *  behind it, centred at the bottom within 80 % of the picture's width -
 *  what a file brings of its own placed and coloured is not used, its
 *  italics and ruby are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtitleLookTest {
    @Test
    fun theStyleIsWhiteOutlinedWithNoBoxInTheSystemsSansSerif() {
        assertEquals(Color.WHITE, SUBTITLE_STYLE.foregroundColor)
        assertEquals(Color.TRANSPARENT, SUBTITLE_STYLE.backgroundColor)
        assertEquals(Color.TRANSPARENT, SUBTITLE_STYLE.windowColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, SUBTITLE_STYLE.edgeType)
        assertEquals(Color.BLACK, SUBTITLE_STYLE.edgeColor)
        assertEquals(Typeface.SANS_SERIF, SUBTITLE_STYLE.typeface)
    }

    @Test
    fun aCueIsItsTextAloneCentredAtTheBottomWithinEightyPercent() {
        // A file's own: red, bold, at the top left, half the width.
        val text = SpannableString("I told you the harbour\ncloses at nine.").apply {
            setSpan(ForegroundColorSpan(Color.RED), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val styled = Cue.Builder()
            .setText(text)
            .setLine(0.1f, Cue.LINE_TYPE_FRACTION)
            .setPosition(0.1f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setSize(0.5f)
            .setWindowColor(Color.BLACK)
            .build()
        val cue = inTheLook(styled)
        assertEquals("I told you the harbour\ncloses at nine.", cue.text.toString())
        assertFalse(cue.text is Spanned && (cue.text as Spanned).getSpans(0, cue.text!!.length, Any::class.java).isNotEmpty())
        assertEquals(Cue.DIMEN_UNSET, cue.line)
        assertEquals(Cue.DIMEN_UNSET, cue.position)
        assertEquals(SUBTITLE_WIDTH_FRACTION, cue.size)
        assertEquals(android.text.Layout.Alignment.ALIGN_CENTER, cue.textAlignment)
        assertFalse(cue.windowColorSet)
    }

    @Test
    fun italicsAndRubyStayEveryOtherStyleGoes() {
        // "(singing) Over the sea" in italics, bold italic and red; 漢字 with
        // its reading above it.
        val text = SpannableString("Over the sea 漢字").apply {
            setSpan(StyleSpan(Typeface.BOLD_ITALIC), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.ITALIC), 5, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 5, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.RED), 0, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(androidx.media3.common.text.RubySpan("かんじ", androidx.media3.common.text.TextAnnotation.POSITION_BEFORE), 13, 15, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val kept = inTheLook(Cue.Builder().setText(text).build()).text as Spanned
        val styles = kept.getSpans(0, kept.length, StyleSpan::class.java)
        assertEquals(listOf(0 to 4, 5 to 12), styles.map { kept.getSpanStart(it) to kept.getSpanEnd(it) }.sortedBy { it.first })
        // A bold italic is an italic; bold alone goes.
        assertEquals(setOf(Typeface.ITALIC), styles.map { it.style }.toSet())
        assertEquals(0, kept.getSpans(0, kept.length, ForegroundColorSpan::class.java).size)
        val ruby = kept.getSpans(0, kept.length, androidx.media3.common.text.RubySpan::class.java).single()
        assertEquals("かんじ", ruby.rubyText)
        assertEquals(13 to 15, kept.getSpanStart(ruby) to kept.getSpanEnd(ruby))
        // Media3's drawing leaves the reading out: the look draws it, over
        // its characters.
        val reading = kept.getSpans(0, kept.length, RubyReading::class.java).single()
        assertEquals("かんじ", reading.reading)
        assertFalse(reading.under)
        assertEquals(13 to 15, kept.getSpanStart(reading) to kept.getSpanEnd(reading))
    }

    @Test
    fun twoCuesAtOnceAreOneSubtitleOnLinesOfTheirOwnNeverDrawnOverEachOther() {
        // As Media3 hands on a WebVTT file's two cues shown at once: the one
        // that started first at the bottom (line -1), the other above it.
        val first = Cue.Builder().setText("Where is the ferry?").setLine(-1f, Cue.LINE_TYPE_NUMBER).build()
        val second = Cue.Builder().setText("HARBOUR CLOSED").setLine(-2f, Cue.LINE_TYPE_NUMBER).build()
        val laidOut = inTheLook(listOf(first, second))
        assertEquals(1, laidOut.size)
        val cue = laidOut.single()
        assertEquals("HARBOUR CLOSED\nWhere is the ferry?", cue.text.toString())
        // Placed as one subtitle is: 8 % up from the bottom, centred.
        assertEquals(Cue.DIMEN_UNSET, cue.line)
        assertEquals(SUBTITLE_WIDTH_FRACTION, cue.size)
        // A file that placed one at the top and one at the bottom: top first.
        val top = Cue.Builder().setText("A sign").setLine(0.05f, Cue.LINE_TYPE_FRACTION).build()
        val bottom = Cue.Builder().setText("A line").build()
        assertEquals("A sign\nA line", inTheLook(listOf(bottom, top)).single().text.toString())
        // Their italics stay where they were.
        val italic = SpannableString("off screen").apply { setSpan(StyleSpan(Typeface.ITALIC), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val both = inTheLook(listOf(Cue.Builder().setText("Hello").setLine(-2f, Cue.LINE_TYPE_NUMBER).build(), Cue.Builder().setText(italic).setLine(-1f, Cue.LINE_TYPE_NUMBER).build())).single().text as Spanned
        val span = both.getSpans(0, both.length, StyleSpan::class.java).single()
        assertEquals("off", both.subSequence(both.getSpanStart(span), both.getSpanEnd(span)).toString())
        // A picture keeps its own place beside the text.
        val picture = Cue.Builder().setBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)).setPosition(0.2f).build()
        val mixed = inTheLook(listOf(picture, first))
        assertSame(picture, mixed.first())
        assertEquals(2, mixed.size)
        assertEquals(emptyList(), inTheLook(listOf(Cue.Builder().setText(" ").build())))
    }

    @Test
    fun aPictureSubtitleIsDrawnAsItComes() {
        val picture = Cue.Builder().setBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)).setPosition(0.2f).build()
        assertSame(picture, inTheLook(picture))
    }

    /** A PGS picture as Media3's PgsParser places it on a 1920x1080 plane. */
    private fun pgs(width: Int, height: Int, x: Int, y: Int, planeWidth: Int = 1920, planeHeight: Int = 1080) = Cue.Builder()
        .setBitmap(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        .setPosition(x.toFloat() / planeWidth)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(y.toFloat() / planeHeight, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setSize(width.toFloat() / planeWidth)
        .setBitmapHeight(height.toFloat() / planeHeight)
        .build()

    @Test
    fun aPictureOnAnHdPlaneKeepsItsOwnShapeAndItsPlace() {
        // At the bottom: hung from its bottom edge, as high as its width makes it.
        val bottom = inTheLook(pgs(400, 40, 760, 900))
        assertEquals(Cue.DIMEN_UNSET, bottom.bitmapHeight)
        assertEquals(940f / 1080f, bottom.line, 1e-6f)
        assertEquals(Cue.ANCHOR_TYPE_END, bottom.lineAnchor)
        assertEquals(760f / 1920f, bottom.position, 1e-6f)
        assertEquals(400f / 1920f, bottom.size, 1e-6f)
        // At the top: from its top edge.
        val top = inTheLook(pgs(200, 30, 100, 60))
        assertEquals(Cue.DIMEN_UNSET, top.bitmapHeight)
        assertEquals(60f / 1080f, top.line, 1e-6f)
        assertEquals(Cue.ANCHOR_TYPE_START, top.lineAnchor)
    }

    @Test
    fun aPictureOnAPlaneOfAnotherShapeIsDrawnAsMedia3DrawsIt() {
        // A DVD's or a broadcast's plane (720x576), whose pixels are not square.
        val sd = pgs(300, 40, 210, 500, planeWidth = 720, planeHeight = 576)
        assertSame(sd, inTheLook(sd))
    }
}
