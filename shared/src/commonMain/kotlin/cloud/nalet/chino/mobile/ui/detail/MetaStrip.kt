package cloud.nalet.chino.mobile.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.data.model.CastMember
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.groupCredits
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide

/** How many actors "Starring" shows before "Full cast" (chino-web's CAST_PREVIEW). */
private const val CAST_PREVIEW = 6

/** Width of the content column from which the strip runs three columns. */
private val WIDE_STRIP = 600.dp

/** One labelled cell of the strip: a crew role, or a fact. */
private class MetaCell(val label: String, val content: @Composable () -> Unit)

/**
 * The detail page's meta strip, laid out as chino-web's DetailPage does: the
 * actors in billing order with the parts they play ("Starring", full width),
 * then one block per crew role in [groupCredits] order — "Created by",
 * "Director(s)", "Writer(s)", "Producer(s)", "Music", … — and the Subtitles
 * and Analyzed facts. Two columns on a phone, three where the content column
 * is tablet-wide. A name the catalog links to a person opens their page.
 */
@Composable
internal fun MetaStrip(item: Item, onPersonNavigate: ((String) -> Unit)?) {
    val credits = remember(item.cast) { groupCredits(item.cast) }
    val subtitles = remember(item.subtitles) { subtitleSummary(item) }
    val analyzed = remember(item.segments) { analyzedSummary(item) }
    val cells = buildList {
        for (group in credits.crew) add(MetaCell(group.label) { CastNames(group.people, onPersonNavigate) })
        subtitles?.let { add(MetaCell("Subtitles") { MetaText(it, maxLines = 2) }) }
        analyzed?.let { add(MetaCell("Analyzed") { MetaText(it) }) }
    }
    if (credits.actors.isEmpty() && cells.isEmpty()) return

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val columns = if (maxWidth >= WIDE_STRIP) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (credits.actors.isNotEmpty()) {
                Starring(actors = credits.actors, columns = columns, onPersonNavigate = onPersonNavigate)
            }
            for (row in cells.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (cell in row) {
                        MetaBlock(label = cell.label, modifier = Modifier.weight(1f), content = cell.content)
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * "Starring": the actors in the order the catalog bills them, each name with
 * the character beneath it, [columns] to a row. A long cast shows its first
 * [CAST_PREVIEW] and a "Full cast (N)" toggle; a cast only a name or two
 * longer is shown whole, since a button that hides two names saves nothing.
 */
@Composable
private fun Starring(actors: List<CastMember>, columns: Int, onPersonNavigate: ((String) -> Unit)?) {
    var expanded by remember(actors) { mutableStateOf(false) }
    val collapsible = actors.size > CAST_PREVIEW + 2
    val shown = if (collapsible && !expanded) actors.take(CAST_PREVIEW) else actors
    MetaBlock(label = "Starring") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in shown.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (actor in row) {
                        Column(modifier = Modifier.weight(1f)) {
                            PersonName(actor, onPersonNavigate)
                            actor.character?.trim()?.takeIf { it.isNotEmpty() }?.let { part ->
                                Text(text = part, color = ChinoMuted, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (collapsible) {
                Row(
                    modifier = Modifier
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (expanded) "Show less" else "Full cast (${actors.size})",
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
}

/** Comma-separated names, each a link when the catalog knows the person; the
 *  comma stays with its name when the row wraps, but outside the link. */
@Composable
private fun CastNames(people: List<CastMember>, onPersonNavigate: ((String) -> Unit)?) {
    FlowRow {
        people.forEachIndexed { index, person ->
            Row {
                PersonName(person, onPersonNavigate)
                if (index < people.lastIndex) {
                    Text(text = ", ", color = ChinoFg2, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

/** A credited name: accent-coloured and tappable to the person's page when
 *  the credit carries a person id, plain text otherwise. */
@Composable
private fun PersonName(person: CastMember, onPersonNavigate: ((String) -> Unit)?) {
    val id = person.personId?.takeIf { it.isNotBlank() }
    val navigate = onPersonNavigate
    val link = id != null && navigate != null
    Text(
        text = person.name,
        color = if (link) ChinoCloudBlue else ChinoFg2,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = if (id != null && navigate != null) {
            Modifier.clickable(role = Role.Button) { navigate(id) }
        } else {
            Modifier
        },
    )
}

/** A muted label over its value — the same block for credits and facts, so
 *  the detail and person pages read alike (chino-web's MetaItem). */
@Composable
internal fun MetaBlock(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, color = ChinoMuted, fontSize = 13.sp)
        content()
    }
}

@Composable
internal fun MetaText(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        color = ChinoFg2,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The subtitle languages, each once: a label, else the language code. */
private fun subtitleSummary(item: Item): String? =
    item.subtitles
        .mapNotNull { sub -> sub.label?.takeIf { it.isNotBlank() } ?: sub.lang.takeIf { it.isNotBlank() } }
        .distinct()
        .joinToString(", ")
        .takeIf { it.isNotBlank() }

/** Which segments the analyzer found ("Intro · Credits"). */
private fun analyzedSummary(item: Item): String? =
    item.segments?.takeIf { it.count > 0 }?.let { seg ->
        listOfNotNull(
            "Intro".takeIf { seg.hasIntro },
            "Credits".takeIf { seg.hasCredits },
            "Recap".takeIf { seg.hasRecap },
        ).joinToString(" · ").ifEmpty { "Segments available" }
    }
