package cloud.nalet.chino.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cloud.nalet.chino.mobile.LocalAppContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoOnFill
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.BookmarkFilled
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.HeartFilled
import com.composables.icons.lucide.Lucide

/*
 * The title actions (the design system's title actions, watching): Play,
 * Start over, Trailer, add to list, watched, liked and the download - one
 * size and one look on every screen, their values here alone. Every action
 * is [TITLE_ACTION_HEIGHT] tall, an icon button that square, the controls
 * [TITLE_ACTION_GAP] apart. Off, an action is the surface-2 fill with its
 * icon in fg-2; on, its state's fill with the icon dark on it - one icon
 * per meaning: the bookmark on cloud-blue for the Watchlist, the check on
 * green for watched, the heart on the red for liked.
 * Nothing of it is drawn over artwork.
 */

/** How tall every title action is, and how wide an icon button. */
val TITLE_ACTION_HEIGHT: Dp = 48.dp

/** The space between two title actions. */
val TITLE_ACTION_GAP: Dp = 8.dp

/** How wide add to list's "⌄" is drawn; its touch target is the full square. */
private val CARET_WIDTH = 40.dp

private val ICON_SIZE = 20.dp

/** An action that is off: the surface-2 fill. */
private val OffFill = ChinoSurfaceHi

/** What an action's text and icon are in, off. */
private val OffContent = ChinoFg2

/**
 * A labelled title action - Play, Resume, Start over, Trailer: one action
 * tall, the [primary] one cloud-blue with its label dark on it, the others
 * the surface-2 fill. Dimmed and not pressable where not [enabled].
 */
@Composable
fun TitleActionButton(
    label: String,
    icon: ImageVector?,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val content = if (primary) ChinoOnFill else Color.White
    Row(
        modifier = modifier
            .height(TITLE_ACTION_HEIGHT)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RectangleShape)
            .background(if (primary) ChinoCloudBlue else OffFill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(ICON_SIZE))
        Text(text = label, color = content, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** An icon button of the title actions: square, its [name] read out - one
 *  that says the state - [on]: the [onFill] with the icon dark on it. */
@Composable
fun TitleIconButton(
    icon: ImageVector,
    name: String,
    on: Boolean,
    onFill: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(TITLE_ACTION_HEIGHT)
            .clip(RectangleShape)
            .background(if (on) onFill else OffFill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = if (on) ChinoOnFill else OffContent, modifier = Modifier.size(ICON_SIZE))
    }
}

/** What add to list's main part is called (docs/chino title actions): it
 *  shows and toggles the Watchlist - "in your Watchlist", else "add to
 *  Watchlist". */
fun addToListName(inWatchlist: Boolean): String = if (inWatchlist) "in your Watchlist" else "add to Watchlist"

/**
 * Add to list, one split control in one frame and one fill, never wrapped
 * apart: the main part, square, shows and toggles the Watchlist (the
 * default list) - [onToggle] - cloud-blue with a dark, filled bookmark while
 * the title is in it, an outlined bookmark when not; it never takes the
 * title off another list. "⌄",
 * drawn [CARET_WIDTH] wide at the full height with the full square to press,
 * opens the list picker ([onChoose]), a check against each list that holds
 * the title - where it goes on or off any of them. A 1 dp divider between
 * the two.
 */
@Composable
fun AddToListControl(
    inWatchlist: Boolean,
    onToggle: () -> Unit,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = inWatchlist
    val fill = if (on) ChinoCloudBlue else OffFill
    val content = if (on) ChinoOnFill else OffContent
    Row(modifier = modifier.height(TITLE_ACTION_HEIGHT).clip(RectangleShape).background(fill)) {
        Box(
            modifier = Modifier
                .size(TITLE_ACTION_HEIGHT)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = addToListName(inWatchlist) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = if (on) Lucide.BookmarkFilled else Lucide.Bookmark, contentDescription = null, tint = content, modifier = Modifier.size(ICON_SIZE))
        }
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(if (on) ChinoOnFill.copy(alpha = 0.35f) else ChinoBorder))
        // Drawn narrower; pressed over the full 48 x 48 square - Compose
        // extends a touch target smaller than that to it, past the frame's
        // edge.
        Box(
            modifier = Modifier
                .width(CARET_WIDTH)
                .fillMaxHeight()
                .clickable(role = Role.Button, onClick = onChoose)
                .semantics { contentDescription = "choose lists" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = Lucide.ChevronDown, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        }
    }
}

/** Watched: a dark check on green once watched, else the outlined check -
 *  the check means watched, on buttons and cards alike. */
@Composable
fun WatchedButton(watched: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    TitleIconButton(
        icon = Lucide.Check,
        name = if (watched) "watched" else "mark as watched",
        on = watched,
        onFill = ChinoGreen,
        onClick = onToggle,
        modifier = modifier,
    )
}

/** Liked: a dark, filled heart on the red once liked, else the outlined heart. */
@Composable
fun LikedButton(liked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    TitleIconButton(
        icon = if (liked) Lucide.HeartFilled else Lucide.Heart,
        name = if (liked) "liked" else "like",
        on = liked,
        onFill = ChinoRed,
        onClick = onToggle,
        modifier = modifier,
    )
}

/**
 * A card's status marks over its poster - the only things drawn on artwork
 * (docs/chino title actions): the Watchlist's bookmark, dark on blue, at the
 * top start, while [itemId] is in the viewer's Watchlist, and the watched
 * check, dark on green, at the top end; marks, not buttons. Inside a Box the
 * poster's size.
 */
@Composable
fun BoxScope.CardStatusMarks(itemId: String, watched: Boolean) {
    val watchlist by LocalAppContainer.current.userFlags.watchlist.collectAsState()
    if (itemId in watchlist) {
        CardMark(Lucide.BookmarkFilled, "in your Watchlist", ChinoCloudBlue, Modifier.align(Alignment.TopStart))
    }
    if (watched) {
        CardMark(Lucide.Check, "watched", ChinoGreen, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun CardMark(icon: ImageVector, name: String, fill: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(20.dp)
            .clip(RectangleShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = name, tint = ChinoOnFill, modifier = Modifier.size(12.dp))
    }
}
