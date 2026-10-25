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
import androidx.compose.ui.layout.layout
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
import cloud.nalet.chino.mobile.ui.theme.ChinoMono
import cloud.nalet.chino.mobile.ui.theme.ChinoOnFill
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.BookmarkFilled
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Clapperboard
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

/** The room at a labelled action's sides. */
val TITLE_ACTION_PADDING: Dp = 20.dp

/** The trailer's one icon, wherever a trailer is offered - the title page,
 *  Home's hero, a trailer's link: one meaning, one icon, whether this
 *  server plays the trailer or a link opens it. */
val TRAILER_ICON: ImageVector get() = Lucide.Clapperboard

/** An action that is off: the surface-2 fill. */
private val OffFill = ChinoSurfaceHi

/** What an action's text and icon are in, off. */
private val OffContent = ChinoFg2

/**
 * A labelled title action - "play", "resume 1:43", "start over", "trailer":
 * one action tall, the [primary] one cloud-blue with its label dark on it,
 * the others the surface-2 fill. The label is given as docs/chino writes it,
 * lowercase but for an episode's code ("play S01E01"), and drawn in the mono
 * face, as the design system's buttons are - the download control beside it
 * too. Dimmed and not pressable where not [enabled]. [horizontalPadding]:
 * the room at its sides, less where a phone's row has three in a line.
 */
@Composable
fun TitleActionButton(
    label: String,
    icon: ImageVector?,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontalPadding: Dp = TITLE_ACTION_PADDING,
) {
    val content = if (primary) ChinoOnFill else Color.White
    Row(
        modifier = modifier
            .height(TITLE_ACTION_HEIGHT)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RectangleShape)
            .background(if (primary) ChinoCloudBlue else OffFill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(ICON_SIZE))
        Text(text = label, color = content, fontSize = 16.sp, fontWeight = FontWeight.Medium, style = ChinoMono, maxLines = 1, softWrap = false)
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
 * title off another list. "⌄", drawn [CARET_WIDTH] wide at the full height,
 * pressed over a full [TITLE_ACTION_HEIGHT] square - the 8 dp past the
 * frame's edge are in the gap to the next control - opens the list picker
 * ([onChoose]), a check against each list that holds the title - where it
 * goes on or off any of them. A 1 dp divider between the two. The frame is
 * not clipped, so that square takes the presses past its edge.
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
    Row(modifier = modifier.height(TITLE_ACTION_HEIGHT).background(fill)) {
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
        // Drawn [CARET_WIDTH] wide - the frame's fill ends there - and laid
        // out as wide, so the next control is 8 dp after what is drawn; what
        // is pressed is the full square, from the divider on. Compose would
        // not stretch a 40 dp target to 48 here: the row is wider than that.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .pressedWiderThanDrawn(drawn = CARET_WIDTH, pressed = TITLE_ACTION_HEIGHT)
                .clickable(role = Role.Button, onClick = onChoose)
                .semantics { contentDescription = "choose lists" },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(modifier = Modifier.width(CARET_WIDTH).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Icon(imageVector = Lucide.ChevronDown, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Lays a control out [drawn] wide, as its row places it and as its frame
 *  draws it, while what it takes presses on is [pressed] wide, from its
 *  start: the rest lies past the frame's edge, in the gap after it. */
private fun Modifier.pressedWiderThanDrawn(drawn: Dp, pressed: Dp): Modifier = layout { measurable, constraints ->
    val width = pressed.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(drawn.roundToPx().coerceAtMost(width), placeable.height) { placeable.place(0, 0) }
}

/** The watched toggle's name, as a screen reader says it and a card's menu
 *  writes it: "mark as watched", and "watched" once it is. */
fun watchedName(watched: Boolean): String = if (watched) "watched" else "mark as watched"

/** A card's menu: "play", or "resume" where the title was stopped part way -
 *  without the position, which the card's bar shows - and its way off
 *  Continue watching. Lowercase, for the mono face (chino-web's
 *  titleActions). */
const val CARD_PLAY = "play"
const val CARD_RESUME = "resume"
const val REMOVE_FROM_CONTINUE_WATCHING = "remove from continue watching"

/** Watched: a dark check on green once watched, else the outlined check -
 *  the check means watched, on buttons and cards alike. */
@Composable
fun WatchedButton(watched: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    TitleIconButton(
        icon = Lucide.Check,
        name = watchedName(watched),
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
