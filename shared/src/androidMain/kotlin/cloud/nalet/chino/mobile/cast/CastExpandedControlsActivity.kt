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
import com.google.android.gms.cast.framework.media.TracksChooserDialogFragment
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
        // The chooser the system brings back after a rotation is Google's,
        // with the TV's own names: ours again in its place.
        supportFragmentManager.findFragmentByTag(TRACKS_TAG)?.let { restored ->
            supportFragmentManager.beginTransaction().remove(restored).commitNow()
            showTracks()
        }
    }

    /** Widget.Chino.CastExpandedController's buttons: 0 subtitles and audio
     *  (Google's chooser, our names), 1 back 10 s, 2 forward 10 s, 3 stop
     *  casting. */
    private fun bindSlots() {
        val controller = uiMediaController
        for (slot in 0 until buttonSlotCount) {
            val view = getButtonImageViewAt(slot)
            if (getButtonTypeAt(slot) == CastR.id.cast_button_type_closed_caption) {
                // Google's button, there when the TV offers a choice; it
                // opens the chooser with the subtitles named as the app
                // names them.
                view.setOnClickListener { showTracks() }
                continue
            }
            if (getButtonTypeAt(slot) != CastR.id.cast_button_type_custom) continue
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

    /** Google's chooser of the TV's subtitles and audio, its subtitles named
     *  as the phone's menus name them ([withSubtitleNames]): the TV's own
     *  names are the master's ("English SDH"). The chooser turns on what is
     *  picked by the tracks' ids, which the names leave as they are. */
    private fun showTracks() {
        val client = uiMediaController.remoteMediaClient ?: return
        val info = client.mediaInfo ?: return
        if (!client.hasMediaSession() || supportFragmentManager.isStateSaved) return
        supportFragmentManager.findFragmentByTag(TRACKS_TAG)?.let {
            supportFragmentManager.beginTransaction().remove(it).commitNow()
        }
        val active = client.mediaStatus?.activeTrackIds ?: LongArray(0)
        // The one way to hand it names of our own; it reads the TV's tracks
        // itself otherwise.
        @Suppress("DEPRECATION")
        val chooser = TracksChooserDialogFragment.newInstance(info.withSubtitleNames(active), active)
        chooser.show(supportFragmentManager, TRACKS_TAG)
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

        /** The chooser of the TV's tracks, as Google's button tags it. */
        const val TRACKS_TAG = "TRACKS_CHOOSER_DIALOG_TAG"
    }
}
