package cloud.nalet.chino.mobile.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.api.PersonDetail
import cloud.nalet.chino.mobile.data.api.artworkUrl
import cloud.nalet.chino.mobile.data.api.catalogueMessage
import cloud.nalet.chino.mobile.data.model.PersonFact
import cloud.nalet.chino.mobile.data.model.acceptLanguage
import cloud.nalet.chino.mobile.data.model.formatRoles
import cloud.nalet.chino.mobile.data.model.personFacts
import cloud.nalet.chino.mobile.formatDeviceDate
import cloud.nalet.chino.mobile.preferredLanguageTags
import cloud.nalet.chino.mobile.todayCatalogDate
import cloud.nalet.chino.mobile.ui.components.MediaCard
import cloud.nalet.chino.mobile.ui.detail.DetailScreen
import cloud.nalet.chino.mobile.ui.detail.MetaBlock
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Content width from which the header stands beside the portrait. */
private val WIDE_HEADER = 600.dp

/** Biography lines shown before "Read more" (chino-web's line-clamp-6). */
private const val BIOGRAPHY_LINES = 6

/**
 * Person / Filmography surface, as chino-web's PersonPage. Reached from the
 * search "Cast & crew" section and from tappable cast/crew names on the
 * Detail page. Header: the portrait (initials without one), the name, the
 * number of titles, then what the catalog knows — known for, born (with the
 * age), died, the birthplace — and the biography, in the device's language
 * when the catalog has it. Body: the filmography as poster cards, each naming
 * the person's roles on the title, in the order the catalog sends it.
 *
 * Phone: the portrait sits beside the name, the facts and biography run full
 * width beneath. Tablet: the name, facts and biography stand in a column
 * beside a larger portrait.
 *
 * Data: GET /v1/people/{id} (chino-api), with the device's languages as
 * Accept-Language for the biography.
 */
class PersonScreen(private val personId: String, private val initialName: String? = null) : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val nav = LocalNavigator.currentOrThrow
        val container = LocalAppContainer.current
        var state by remember(personId) { mutableStateOf<PersonUiState>(PersonUiState.Loading) }
        var streamToken by remember { mutableStateOf(container.streamTokenManager.current.value.orEmpty()) }

        LaunchedEffect(personId) {
            state = PersonUiState.Loading
            coroutineScope {
                // The portrait and posters carry the stream token; reached from
                // a deep link, nothing may have minted one yet.
                val token = async { runCatching { container.streamTokenManager.valid() }.getOrNull() }
                val person = runCatching {
                    container.chinoApi.getPerson(
                        id = personId,
                        limit = 100,
                        acceptLanguage = acceptLanguage(preferredLanguageTags()),
                    )
                }
                token.await()?.let { streamToken = it }
                state = person.fold(
                    onSuccess = { p -> if (p == null) PersonUiState.NotFound else PersonUiState.Ready(p) },
                    onFailure = { PersonUiState.Error(it.catalogueMessage()) },
                )
            }
        }

        val baseUrl = container.config.apiBaseUrl.trimEnd('/')

        Scaffold(containerColor = MaterialTheme.colorScheme.background) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    PersonUiState.Loading -> Center { CircularProgressIndicator(color = ChinoCloudBlue) }
                    PersonUiState.NotFound -> Center {
                        Text("Person not found.", color = ChinoMuted, fontSize = 16.sp)
                    }
                    is PersonUiState.Error -> Center {
                        Text(
                            text = s.message,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                    is PersonUiState.Ready -> PersonContent(
                        person = s.person,
                        baseUrl = baseUrl,
                        streamToken = streamToken,
                        onItemClick = { id -> nav.push(DetailScreen(id)) },
                    )
                }
                // Back chip — same overlay treatment as DetailScreen.
                Box(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 16.dp, top = 16.dp)
                        .size(40.dp)
                        .clip(RectangleShape)
                        .background(Color(0x80000000))
                        .clickable(role = Role.Button) { nav.pop() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.ArrowLeft,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonContent(
    person: PersonDetail,
    baseUrl: String,
    streamToken: String,
    onItemClick: (String) -> Unit,
) {
    val facts = remember(person) { personFacts(person, todayCatalogDate(), ::formatDeviceDate) }
    val portraitUrl = remember(person, baseUrl, streamToken) {
        if (person.hasProfile) artworkUrl(baseUrl, person.profileUrl, streamToken) else null
    }
    // One card per title; an id the grid saw twice would crash it.
    val titles = remember(person) { person.items.distinctBy { it.id } }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_HEADER
        // The Browse grid's columns, so a filmography card is a Browse card's size.
        val columns = when {
            maxWidth < 600.dp -> 3
            maxWidth < 900.dp -> 4
            maxWidth < 1200.dp -> 5
            else -> 6
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PersonHeader(person = person, portraitUrl = portraitUrl, facts = facts, wide = wide)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Filmography",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (titles.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("No titles available for this person.", color = ChinoMuted, fontSize = 14.sp)
                }
            }
            items(titles, key = { it.id }) { item ->
                MediaCard(
                    item = item,
                    posterUrl = artworkUrl(baseUrl, item.posterUrl, streamToken)
                        ?: "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                    onClick = { onItemClick(item.id) },
                    cardWidth = Dp.Unspecified,
                    credit = formatRoles(item.roles),
                )
            }
        }
    }
}

/**
 * The header: portrait, name, title count, facts, biography. With nothing to
 * say beyond the name it is the avatar and the name side by side.
 */
@Composable
private fun PersonHeader(person: PersonDetail, portraitUrl: String?, facts: List<PersonFact>, wide: Boolean) {
    val biography = person.biography?.trim()?.takeIf { it.isNotEmpty() }
    val hasAbout = facts.isNotEmpty() || biography != null
    // Leave room for the overlaid back chip.
    val modifier = Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 8.dp)
    if (wide) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            PersonPicture(person.name, portraitUrl, portraitWidth = 176.dp, initialsSize = 112.dp)
            Column(
                modifier = Modifier.weight(1f).widthIn(max = 768.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                NameBlock(person, nameSize = 34.sp)
                if (hasAbout) About(facts, biography, person.biographyLang)
            }
        }
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PersonPicture(person.name, portraitUrl, portraitWidth = 112.dp, initialsSize = 72.dp)
                NameBlock(person, nameSize = 26.sp, modifier = Modifier.weight(1f))
            }
            if (hasAbout) About(facts, biography, person.biographyLang)
        }
    }
}

/** The portrait in a 2:3 frame like the posters; the initials, square, for
 *  someone the catalog has no portrait of. */
@Composable
private fun PersonPicture(name: String, portraitUrl: String?, portraitWidth: Dp, initialsSize: Dp) {
    if (portraitUrl != null) {
        PersonAvatar(name = name, portraitUrl = portraitUrl, width = portraitWidth, portrait = true)
    } else {
        PersonAvatar(name = name, portraitUrl = null, width = initialsSize)
    }
}

@Composable
private fun NameBlock(person: PersonDetail, nameSize: TextUnit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = person.name,
            color = Color.White,
            fontSize = nameSize,
            lineHeight = (nameSize.value * 1.15f).sp,
            fontWeight = FontWeight.Bold,
        )
        Text(text = titleCount(person.items.size), color = ChinoMuted, fontSize = 14.sp)
    }
}

/** The facts, two to a row (chino-web's grid-cols-2), then the biography. */
@Composable
private fun About(facts: List<PersonFact>, biography: String?, biographyLang: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (facts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (row in facts.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (fact in row) {
                            MetaBlock(label = fact.label, modifier = Modifier.weight(1f)) {
                                for (line in fact.lines) {
                                    Text(
                                        text = buildAnnotatedString {
                                            append(line.text)
                                            line.note?.let { note ->
                                                withStyle(SpanStyle(color = ChinoMuted)) { append(" $note") }
                                            }
                                        },
                                        color = ChinoFg2,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                    )
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (biography != null) Biography(biography, biographyLang)
    }
}

/**
 * The biography, in the language the catalog had it in (for line breaking
 * and hyphenation). A long one is cut to [BIOGRAPHY_LINES] lines with "Read
 * more", so the filmography stays within reach on a phone; the toggle shows
 * only when the text is in fact cut.
 */
@Composable
private fun Biography(text: String, lang: String?) {
    var expanded by remember(text) { mutableStateOf(false) }
    var clamped by remember(text) { mutableStateOf(false) }
    val style = remember(lang) {
        lang?.trim()?.takeIf { LANGUAGE_SUBTAG.matches(it) }?.let { TextStyle(localeList = LocaleList(it)) }
            ?: TextStyle.Default
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = text,
            color = ChinoFg2,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            style = style,
            maxLines = if (expanded) Int.MAX_VALUE else BIOGRAPHY_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> if (!expanded) clamped = layout.hasVisualOverflow },
        )
        if (clamped || expanded) {
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (expanded) "Show less" else "Read more",
                    color = ChinoCloudBlue,
                    fontSize = 14.sp,
                )
                Icon(
                    imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = null,
                    tint = ChinoCloudBlue,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** biography_lang is a primary language subtag ("de"). */
private val LANGUAGE_SUBTAG = Regex("^[A-Za-z]{2,8}$")

private sealed interface PersonUiState {
    data object Loading : PersonUiState
    data object NotFound : PersonUiState
    data class Ready(val person: PersonDetail) : PersonUiState
    data class Error(val message: String) : PersonUiState
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
