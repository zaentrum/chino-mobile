package cloud.nalet.chino.mobile.cast

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import cloud.nalet.chino.mobile.shared.R
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.uicontroller.UIController
import com.google.android.gms.cast.framework.media.widget.MiniControllerFragment
import com.google.android.gms.cast.framework.R as CastR

/**
 * The bar while casting: Google's mini controller - its thumbnail, the
 * title, play or pause, a tap opening the full controls - in our colours
 * (Widget.Chino.CastMiniController), with our second line: "playing on
 * {device}", the device's own name. Google's draws the metadata's subtitle
 * there, so that line is put in its place, the same view in the same spot;
 * should the layout ever lack it, Google's line stays.
 */
class ChinoMiniControllerFragment : MiniControllerFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)
        val inter = CastFonts.inter(root.context)
        root.findViewById<TextView>(CastR.id.title_view)?.let { title -> inter?.let { title.typeface = it } }
        val subtitle = root.findViewById<TextView>(CastR.id.subtitle_view)
        val parent = subtitle?.parent as? ViewGroup
        val controller = uiMediaController
        if (subtitle != null && parent != null && controller != null) {
            val line = TextView(root.context).apply {
                id = subtitle.id
                layoutParams = subtitle.layoutParams
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Chino_CastBar_Device)
                inter?.let { typeface = it }
            }
            val index = parent.indexOfChild(subtitle)
            parent.removeViewAt(index)
            parent.addView(line, index)
            controller.bindViewToUIController(line, DeviceLine(line))
        }
        return root
    }
}

/** "playing on {device}" for the session the bar shows. */
private class DeviceLine(private val line: TextView) : UIController() {
    override fun onSessionConnected(castSession: CastSession) {
        super.onSessionConnected(castSession)
        line.text = castDeviceLine(castSession.castDevice?.friendlyName)
    }

    override fun onSessionEnded() {
        super.onSessionEnded()
        line.text = ""
    }
}
