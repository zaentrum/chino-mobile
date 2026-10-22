package cloud.nalet.chino.mobile.ui.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.CaptioningManager
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.text.LanguageFeatureSpan
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView

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

/** A cue placed as the look places it: its text, its italics and its ruby
 *  alone ([keptStyle]), centred at the bottom, within
 *  [SUBTITLE_WIDTH_FRACTION] of the picture's width. A picture subtitle
 *  (PGS, VobSub) is drawn as it comes. */
internal fun inTheLook(cue: Cue): Cue {
    if (cue.bitmap != null) return cue
    val text = cue.text ?: return cue
    return Cue.Builder()
        .setText(keptStyle(text))
        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
        .setSize(SUBTITLE_WIDTH_FRACTION)
        .build()
}

/** [text] with the italics and the ruby (and the other language features,
 *  emphasis marks among them) it brings, nothing else: no colour, no size,
 *  no font, no bold, no box. A bold italic is an italic. */
internal fun keptStyle(text: CharSequence): CharSequence {
    if (text !is Spanned) return text.toString()
    val kept = SpannableString(text.toString())
    for (span in text.getSpans(0, text.length, Any::class.java)) {
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        val flags = text.getSpanFlags(span)
        when {
            span is LanguageFeatureSpan -> kept.setSpan(span, start, end, flags)
            span is StyleSpan && (span.style and Typeface.ITALIC) != 0 -> kept.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
        }
    }
    return kept
}

/**
 * The player's subtitles in the look, over the picture: the PlayerView's own
 * subtitle view gives way to one of the same size - the picture's, so the
 * size follows the picture - fed the cues as [inTheLook] places them. Follows
 * the player it is bound to ([bind]); null unbinds. Where the PlayerView has
 * no picture frame, its own view stays, styled the same.
 */
internal class LookSubtitles(playerView: PlayerView) : Player.Listener {
    private val view: SubtitleView?
    private var player: Player? = null

    init {
        val own = playerView.subtitleView
        val frame = playerView.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame)
        view = if (frame != null && own != null) {
            own.visibility = View.GONE
            SubtitleView(playerView.context).also { v ->
                frame.addView(
                    v,
                    frame.indexOfChild(own) + 1,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }
        } else {
            null
        }
        view?.let { applySubtitleLook(it, playerView.context, ownCues = true) }
            ?: own?.let { applySubtitleLook(it, playerView.context) }
    }

    /** How far the line must rise to clear controls that start at
     *  [controlsTopPx] (window pixels), with a small gap: none where the
     *  picture ends above them (a phone held upright). */
    fun clearance(controlsTopPx: Float): Float {
        val frame = view?.parent as? View ?: return 0f
        val at = IntArray(2)
        frame.getLocationInWindow(at)
        val lineBottom = at[1] + frame.height * (1f - SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        val gap = 8f * frame.resources.displayMetrics.density
        return (lineBottom - (controlsTopPx - gap)).coerceAtLeast(0f)
    }

    /** The line [px] higher than the look puts it. */
    fun lift(px: Float) {
        view?.translationY = -px
    }

    fun bind(next: Player?) {
        val v = view ?: return
        if (player === next) return
        player?.removeListener(this)
        player = next
        v.setCues(next?.currentCues?.cues?.map(::inTheLook))
        next?.addListener(this)
    }

    override fun onCues(cueGroup: CueGroup) {
        view?.setCues(cueGroup.cues.map(::inTheLook))
    }
}
