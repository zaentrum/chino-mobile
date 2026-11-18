package cloud.nalet.chino.mobile.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.data.AccountSetting
import cloud.nalet.chino.mobile.data.AccountSettings
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.sentence
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The watchlist group's heading, in the case of settings' other groups. */
internal const val WATCHLIST_GROUP = "Watchlist"

/** The switch's name (section 16.4.7). */
internal const val SEPARATE_MOVIES_AND_SHOWS = "Separate movies and shows"

/** The line under it (section 16.4.7). */
internal const val SEPARATE_MOVIES_AND_SHOWS_HINT =
    "Every list shows its movies in one row and its shows in another. " +
        "Your choice goes with your account, to every device you sign in on."

/** What a save that failed says, before the cause's sentence (section 16.8). */
internal const val SETTING_NOT_SAVED = "Couldn't save the setting."

/**
 * Separate movies and shows as the watchlist group shows it (sections 16.4.7
 * and 16.8 of the specification), for [accountId]: [on], the choice on the
 * switch - the account's, the one last known on the device while it is read,
 * or the one being saved; [busy] while it is read or saved, when the switch
 * shows its choice and takes no press; [failure], why the last save failed,
 * until the switch is used again.
 */
data class WatchlistGroupState(
    val accountId: String,
    val on: Boolean,
    val busy: Boolean,
    val failure: LoadFailure? = null,
)

/**
 * The switch of the watchlist group: what it shows of [settings] - the
 * account signed in's - and what a press does. A press saves the choice for
 * the account whose choice the switch shows; a press while it is busy does
 * nothing. The saves run in [settings]' own scope, and what they are doing is
 * held there, so leaving settings stops none, and a switch drawn again shows
 * a save still out, or one that failed meanwhile; [scope] holds what the
 * screen shows.
 */
class WatchlistSwitch(private val settings: AccountSettings, private val scope: CoroutineScope) {
    val state: StateFlow<WatchlistGroupState?> =
        combine(settings.setting, settings.saving, settings.saveFailed) { setting, saving, failed -> stateOf(setting, saving, failed) }
            .stateIn(scope, SharingStarted.Eagerly, stateOf(settings.setting.value, settings.saving.value, settings.saveFailed.value))

    private fun stateOf(setting: AccountSetting?, saving: Map<String, Boolean>, failed: Map<String, LoadFailure>): WatchlistGroupState? {
        setting ?: return null
        // What is being saved, and why a save failed, are the account's they
        // were for: never shown for another.
        val pending = saving[setting.accountId]
        return WatchlistGroupState(
            accountId = setting.accountId,
            on = pending ?: setting.watchlistByKind,
            busy = pending != null || setting.reading,
            failure = failed[setting.accountId]?.takeIf { pending == null },
        )
    }

    /** Settings opened: the account's choice read again, as it may have been
     *  changed on another device. */
    fun opened() {
        scope.launch { settings.read() }
    }

    /** The switch pressed, to [on]: saved for the account whose choice it
     *  shows. While a read or a save runs, nothing. */
    fun press(on: Boolean) {
        val shown = state.value ?: return
        if (shown.busy) return
        settings.press(on, under = shown.accountId)
    }
}

/**
 * The watchlist group (section 16.4.7): its heading, then Separate movies and
 * shows with its line, the account's choice on the switch. Busy, the switch
 * shows its choice and takes no press - the phone's switch has no busy look
 * of its own. A save that failed shows the design system's error banner
 * under it: "Couldn't save the setting." and the cause's sentence, by the
 * answer's status (section 16.8).
 */
@Composable
internal fun WatchlistGroup(state: WatchlistGroupState, onChange: (Boolean) -> Unit) {
    Box(modifier = Modifier.padding(top = 12.dp))
    SectionHeading(WATCHLIST_GROUP)
    ToggleRow(
        label = SEPARATE_MOVIES_AND_SHOWS,
        value = state.on,
        onChange = onChange,
        hint = SEPARATE_MOVIES_AND_SHOWS_HINT,
        enabled = !state.busy,
    )
    state.failure?.let { SettingNotSaved(it) }
}

/** The design system's error banner under a setting that couldn't be saved:
 *  the red frame and bar, its glyph, what failed and the cause's sentence;
 *  read out politely as it appears. */
@Composable
private fun SettingNotSaved(failure: LoadFailure) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // As tall as its text, so the bar down its edge is too.
            .height(IntrinsicSize.Min)
            .background(ChinoRed.copy(alpha = 0.08f))
            .border(BorderStroke(1.dp, ChinoRed.copy(alpha = 0.35f)), RectangleShape)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(ChinoRed))
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(imageVector = Lucide.TriangleAlert, contentDescription = null, tint = ChinoRed, modifier = Modifier.size(18.dp))
            Text(text = settingNotSavedText(failure), color = ChinoFg, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/** "Couldn't save the setting." and the cause's sentence, where the case has
 *  one: never the server's words or its code. */
internal fun settingNotSavedText(failure: LoadFailure): String =
    listOfNotNull(SETTING_NOT_SAVED, failure.sentence()).joinToString(" ")
