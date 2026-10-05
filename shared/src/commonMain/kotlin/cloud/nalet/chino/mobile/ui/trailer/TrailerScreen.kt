package cloud.nalet.chino.mobile.ui.trailer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.artworkUrl
import cloud.nalet.chino.mobile.data.api.catalogueMessage
import cloud.nalet.chino.mobile.data.model.Trailer
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoHeading
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Youtube
import kotlinx.coroutines.CancellationException

/** What the trailer screen shows. */
sealed interface TrailerUiState {
    data object Loading : TrailerUiState

    /** Plays [url]: the trailer [label] ("Trailer") of the title [title].
     *  [link] is the title's trailer link, offered should the master answer
     *  404 after all. */
    data class Ready(val url: String, val title: String, val label: String, val link: Trailer?) : TrailerUiState

    /** There is no such trailer to play: the title or the extra is gone, the
     *  viewer's rating cap hides the title, or the master answered 404.
     *  [link] is the title's trailer link, when it has one. */
    data class NotAvailable(val title: String?, val link: Trailer?) : TrailerUiState

    /** It did not load or play for another reason; Try again loads again. */
    data class Failed(val title: String?, val message: String) : TrailerUiState
}

/**
 * An extra's master URL: its [playPath], from the server root, against the
 * API base [apiBase] with the stream token ([artworkUrl], the asset helper),
 * and the device's [caps] — as a title's master is asked for. No `q`:
 * chino-stream serves the ladder, the variant to start on first.
 */
fun trailerMasterUrl(apiBase: String, playPath: String, streamToken: String, caps: String): String? {
    val url = artworkUrl(apiBase, playPath, streamToken) ?: return null
    if (caps.isEmpty()) return url
    return url + (if ('?' in url) '&' else '?') + "caps=" + caps
}

/**
 * What the trailer screen plays: the extra [extraId] of the title [itemId],
 * as the title's detail lists it. The detail is the one request: no
 * progress, watched, segments, trickplay, subtitles, next episode or
 * prewarm, so Continue Watching never hears of a trailer. A 404 for the
 * title — gone, or above the viewer's rating cap — or an extra the detail no
 * longer lists is [TrailerUiState.NotAvailable]; any other failure throws.
 */
internal suspend fun loadTrailer(
    api: ChinoApi,
    apiBase: String,
    streamToken: suspend () -> String,
    caps: String,
    itemId: String,
    extraId: String,
): TrailerUiState {
    val item = try {
        api.getItem(itemId)
    } catch (e: ApiStatusException) {
        if (e.status == 404) return TrailerUiState.NotAvailable(title = null, link = null)
        throw e
    }
    val link = pickTrailer(item.trailers)
    val extra = item.extras.firstOrNull { it.id == extraId && it.playable }
        ?: return TrailerUiState.NotAvailable(item.title, link)
    val url = trailerMasterUrl(apiBase, extra.playPath, streamToken(), caps)
        ?: return TrailerUiState.NotAvailable(item.title, link)
    return TrailerUiState.Ready(url = url, title = item.title, label = extra.title.ifBlank { "Trailer" }, link = link)
}

/**
 * A title's trailer from this server: [extraId], one of the extras of
 * [itemId] (the series' for an episode). It plays from the start with sound
 * on the platform's own player and controls ([TrailerVideo]), and the screen
 * closes at its end, on Back, or on the system Back. Nothing here writes
 * progress or watched — Continue Watching is left alone. A trailer that is
 * not there (404) says so and offers the title's trailer link when it has
 * one. The first playback sends one trailer_play.
 */
class TrailerScreen(private val itemId: String, private val extraId: String) : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val container = LocalAppContainer.current
        val nav = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current
        var attempt by remember { mutableStateOf(0) }
        var state by remember { mutableStateOf<TrailerUiState>(TrailerUiState.Loading) }
        var played by remember { mutableStateOf(false) }
        // The end and a Back can come together: pop once, and only while
        // this is still the screen on top.
        val close: () -> Unit = { if (nav.lastItem === this@TrailerScreen) nav.pop() }
        TrailerBackHandler(onBack = close)

        LaunchedEffect(attempt) {
            state = TrailerUiState.Loading
            state = try {
                loadTrailer(
                    api = container.chinoApi,
                    apiBase = container.config.apiBaseUrl,
                    streamToken = { container.streamTokenManager.valid() },
                    caps = trailerCodecCaps(),
                    itemId = itemId,
                    extraId = extraId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrailerUiState.Failed(title = null, message = e.catalogueMessage())
            }
        }

        val s = state
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            TrailerTopBar(
                title = when (s) {
                    is TrailerUiState.Ready -> s.title
                    is TrailerUiState.NotAvailable -> s.title
                    is TrailerUiState.Failed -> s.title
                    TrailerUiState.Loading -> null
                },
                label = (s as? TrailerUiState.Ready)?.label,
                onBack = close,
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when (s) {
                    TrailerUiState.Loading -> CircularProgressIndicator(color = ChinoCloudBlue)
                    is TrailerUiState.Ready -> TrailerVideo(
                        url = s.url,
                        modifier = Modifier.fillMaxSize(),
                        onStarted = {
                            if (!played) {
                                played = true
                                container.telemetry.event(
                                    "trailer_play",
                                    itemId = itemId,
                                    extra = mapOf("extra_id" to extraId, "local" to "true"),
                                )
                            }
                        },
                        onEnded = close,
                        onNotFound = { state = TrailerUiState.NotAvailable(s.title, s.link) },
                        onFailed = { message -> state = TrailerUiState.Failed(s.title, message) },
                    )
                    is TrailerUiState.NotAvailable -> TrailerMessage(
                        heading = "Trailer not available",
                        detail = null,
                        action = s.link?.let { link ->
                            TrailerAction(trailerLinkLabel(link), Lucide.Youtube) { uriHandler.openUri(link.url) }
                        },
                        onBack = close,
                    )
                    is TrailerUiState.Failed -> TrailerMessage(
                        heading = "The trailer didn't play",
                        detail = s.message,
                        action = TrailerAction("Try again", icon = null) { attempt += 1 },
                        onBack = close,
                    )
                }
            }
        }
    }
}

/** Back, then the title and the trailer's label, over the video. */
@Composable
private fun TrailerTopBar(title: String?, label: String?, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RectangleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.ArrowLeft, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = ChinoHeading,
                )
            }
            if (label != null) {
                Text(text = label, color = ChinoMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A button of [TrailerMessage]: what it says, its icon, what it does. */
private class TrailerAction(val label: String, val icon: ImageVector?, val onClick: () -> Unit)

/** Why there is no trailer to watch, its [action] (the link, Try again) and Back. */
@Composable
private fun TrailerMessage(heading: String, detail: String?, action: TrailerAction?, onBack: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = heading,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            style = ChinoHeading,
        )
        if (detail != null) {
            Text(text = detail, color = ChinoMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        }
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (action != null) MessageButton(action.label, action.icon, primary = true, onClick = action.onClick)
            MessageButton("Back", icon = null, primary = false, onClick = onBack)
        }
    }
}

@Composable
private fun MessageButton(label: String, icon: ImageVector?, primary: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RectangleShape)
            .background(if (primary) ChinoCloudBlue else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(text = label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
