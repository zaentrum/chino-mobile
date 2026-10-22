package cloud.nalet.chino.mobile.cast

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Xml
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import cloud.nalet.chino.mobile.shared.R
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.uicontroller.UIController
import com.google.android.gms.cast.framework.media.widget.MiniControllerFragment
import org.xmlpull.v1.XmlPullParser
import com.google.android.gms.cast.framework.R as CastR

/**
 * The bar while casting: Google's mini controller - its thumbnail, the
 * title, play or pause, a tap opening the full controls - in our colours and
 * Inter (Widget.Chino.CastMiniController), with our second line: "playing on
 * {device}", the device's own name. Google's draws the metadata's subtitle
 * there, so that line is put in its place, the same view in the same spot;
 * should the layout ever lack it, Google's line stays.
 *
 * Google's fragment reads its style - its buttons, its colours - as it is
 * inflated from a layout (onInflate). Compose adds it in code (CastBar's
 * AndroidFragment), so it is inflated here first, with no attributes of its
 * own: the theme's castMiniControllerStyle is its look.
 */
class ChinoMiniControllerFragment : MiniControllerFragment() {
    private var inflated = false

    override fun onInflate(context: Context, attrs: AttributeSet, savedInstanceState: Bundle?) {
        inflated = true
        super.onInflate(context, attrs, savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (!inflated) inflateFromTheme(requireContext(), savedInstanceState)
        val root = super.onCreateView(inflater, container, savedInstanceState)
        val subtitle = root.findViewById<TextView>(CastR.id.subtitle_view)
        val parent = subtitle?.parent as? ViewGroup
        val controller = uiMediaController
        if (subtitle != null && parent != null && controller != null) {
            // An AppCompat view: its text appearance's font family (Inter,
            // res/font) applies on every Android version the app runs on.
            val line = AppCompatTextView(root.context).apply {
                id = subtitle.id
                layoutParams = subtitle.layoutParams
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Chino_CastBar_Device)
            }
            val index = parent.indexOfChild(subtitle)
            parent.removeViewAt(index)
            parent.addView(line, index)
            controller.bindViewToUIController(line, DeviceLine(line))
        }
        return root
    }

    /** onInflate with the empty attributes of res/xml/chino_cast_bar.xml. */
    private fun inflateFromTheme(context: Context, savedInstanceState: Bundle?) {
        context.resources.getXml(R.xml.chino_cast_bar).use { parser ->
            while (parser.next() != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) Unit
            onInflate(context, Xml.asAttributeSet(parser), savedInstanceState)
        }
    }
}

/** "playing on {device}" for the session the bar shows. */
private class DeviceLine(private val line: TextView) : UIController() {
    override fun onSessionConnected(castSession: CastSession) {
        super.onSessionConnected(castSession)
        line.text = castDeviceLine(castDeviceName(castSession.castDevice?.friendlyName, castSession.castDevice?.modelName))
    }

    override fun onSessionEnded() {
        super.onSessionEnded()
        line.text = ""
    }
}
