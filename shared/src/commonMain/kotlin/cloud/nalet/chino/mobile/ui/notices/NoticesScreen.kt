package cloud.nalet.chino.mobile.ui.notices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.notices.Notice
import cloud.nalet.chino.mobile.data.notices.ageText
import cloud.nalet.chino.mobile.data.notices.fromText
import cloud.nalet.chino.mobile.data.notices.noticeHref
import cloud.nalet.chino.mobile.data.notices.noticeItemId
import cloud.nalet.chino.mobile.data.notices.noticeText
import cloud.nalet.chino.mobile.ui.detail.DetailScreen
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder2
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoDim
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.utcOffsetMillis
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay

/**
 * The person's notices, newest first — the list the top bar's bell opens.
 * Each says whom it is from (its addon's title) and when it came, then the
 * addon's title and text, as plain text with the text's line breaks kept.
 *
 * Opening a notice reads it and takes the person where it leads: the title
 * it is about to its detail page, else its link — a page of this server,
 * nothing else — to the system browser. A notice with both offers the link
 * as well. Mark All Read reads every one; X deletes one. Each change shows at
 * once and is sent ([cloud.nalet.chino.mobile.data.notices.NoticesRepository]).
 *
 * Asked for again as it opens. While notices are not available it shows
 * nothing, not an error.
 */
class NoticesScreen : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val nav = LocalNavigator.currentOrThrow
        val container = LocalAppContainer.current
        val uriHandler = LocalUriHandler.current
        val notices = container.notices
        val state by notices.state.collectAsState()
        val apiBaseUrl = container.config.apiBaseUrl
        LaunchedEffect(Unit) { notices.refresh() }
        // Ages move on while the list is open.
        val now by produceState(currentTimeMillis()) {
            while (true) {
                delay(30_000)
                value = currentTimeMillis()
            }
        }
        // No browser to open it in is no reason to fail.
        val openLink: (String) -> Unit = { href -> runCatching { uriHandler.openUri(href) } }
        val list = state.list

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val outerPad = if (maxWidth >= 600.dp) 24.dp else 16.dp
            LazyColumn(
                contentPadding = PaddingValues(start = outerPad, end = outerPad, bottom = outerPad),
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
            ) {
                item {
                    NoticesHeader(
                        unread = list?.unread ?: 0,
                        onBack = { nav.pop() },
                        onReadAll = { notices.readAll() },
                    )
                }
                when {
                    list == null -> Unit
                    list.notices.isEmpty() -> item {
                        Text(
                            text = "No notices. An addon you use can tell you something here.",
                            color = ChinoMuted,
                            fontSize = 14.sp,
                        )
                    }
                    else -> itemsIndexed(list.notices, key = { _, it -> it.id }) { index, notice ->
                        val itemId = noticeItemId(notice)
                        val href = noticeHref(notice.link, apiBaseUrl)
                        Column(modifier = Modifier.fillMaxWidth().background(ChinoSurface)) {
                            if (index > 0) {
                                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ChinoBorder2))
                            }
                            NoticeRow(
                                notice = notice,
                                now = now,
                                opensTitle = itemId != null,
                                opensLink = href != null,
                                onOpen = {
                                    notices.read(notice)
                                    when {
                                        itemId != null -> nav.push(DetailScreen(itemId))
                                        href != null -> openLink(href)
                                    }
                                },
                                onOpenLink = {
                                    notices.read(notice)
                                    if (href != null) openLink(href)
                                },
                                onDelete = { notices.delete(notice) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticesHeader(unread: Int, onBack: () -> Unit, onReadAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RectangleShape)
                .background(Color(0x80000000))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.ArrowLeft,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = "Notices",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (unread > 0) {
            Row(
                modifier = Modifier
                    .clip(RectangleShape)
                    .clickable(onClick = onReadAll)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Lucide.CheckCheck,
                    contentDescription = null,
                    tint = ChinoCloudBlue,
                    modifier = Modifier.size(16.dp),
                )
                Text(text = "Mark All Read", color = ChinoCloudBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * One notice. An unread one is marked on its leading edge in the accent, its
 * title brighter, as in the portal's panel. The text is the addon's, as text:
 * never markup, its line breaks kept ([noticeText]). The whole row opens it;
 * X deletes it without opening it.
 */
@Composable
private fun NoticeRow(
    notice: Notice,
    now: Long,
    opensTitle: Boolean,
    opensLink: Boolean,
    onOpen: () -> Unit,
    onOpenLink: () -> Unit,
    onDelete: () -> Unit,
) {
    val unread = notice.readAt == null
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(if (unread) ChinoCloudBlue else Color.Transparent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = noticeText(fromText(notice)),
                    color = ChinoDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = ageText(notice.createdAt, now, ::utcOffsetMillis),
                    color = ChinoDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            Text(
                text = noticeText(notice.title),
                color = if (unread) ChinoFg else ChinoFg2,
                fontSize = 15.sp,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
            )
            val body = noticeText(notice.body)
            if (body.isNotBlank()) {
                Text(text = body, color = ChinoMuted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (opensTitle || opensLink) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (opensTitle) NoticeAction(label = "Open Title", onClick = onOpen)
                    if (opensLink) NoticeAction(label = "Open Link", onClick = onOpenLink)
                }
            }
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RectangleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.X,
                contentDescription = "Delete Notice",
                tint = ChinoMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun NoticeAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = ChinoCloudBlue,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RectangleShape).clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}
