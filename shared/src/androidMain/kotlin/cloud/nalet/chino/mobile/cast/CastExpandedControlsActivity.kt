package cloud.nalet.chino.mobile.cast

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.ImageView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import cloud.nalet.chino.mobile.shared.R
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity
import com.google.android.gms.cast.framework.R as CastR

/**
 * The full controls while casting: Google's expanded controller - the
 * artwork, the title, the seek bar, play or pause - in our colours
 * (Theme.Chino.CastExpandedControls), with its four slots set as the design
 * system's casting pattern lists them: subtitles and audio (Google's, there
 * when the TV offers them), back and forward 10 seconds, and stop casting.
 * The Cast button sits in its toolbar, as everywhere.
 */
class CastExpandedControlsActivity : ExpandedControllerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is edge to edge; so are its full controls, which keep their
        // toolbar and buttons clear of the system's bars.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        findViewById<View>(android.R.id.content)?.let { content ->
            ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }
        bindSlots()
    }

    /** The custom slots of Widget.Chino.CastExpandedController's buttons:
     *  1 back 10 s, 2 forward 10 s, 3 stop casting. */
    private fun bindSlots() {
        val controller = uiMediaController
        for (slot in 0 until buttonSlotCount) {
            if (getButtonTypeAt(slot) != CastR.id.cast_button_type_custom) continue
            val view = getButtonImageViewAt(slot)
            when (slot) {
                1 -> {
                    slotIcon(view, R.drawable.chino_cast_replay_10, getString(R.string.chino_cast_back_10))
                    controller.bindViewToRewind(view, SKIP_MS)
                }
                2 -> {
                    slotIcon(view, R.drawable.chino_cast_forward_10, getString(R.string.chino_cast_forward_10))
                    controller.bindViewToForward(view, SKIP_MS)
                }
                3 -> {
                    slotIcon(view, R.drawable.chino_cast_stop_casting, getString(R.string.chino_cast_stop_casting))
                    view.setOnClickListener { ChinoCast.stopCasting() }
                }
            }
        }
    }

    private fun slotIcon(view: ImageView, icon: Int, name: String) {
        view.setImageResource(icon)
        ImageViewCompat.setImageTintList(view, ContextCompat.getColorStateList(this, R.color.chino_cast_button))
        view.contentDescription = name
        TooltipCompat.setTooltipText(view, name)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.chino_cast_expanded_controls, menu)
        CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.chino_cast_media_route)
        return true
    }

    private companion object {
        /** Back and forward: 10 seconds. */
        const val SKIP_MS = 10_000L
    }
}
