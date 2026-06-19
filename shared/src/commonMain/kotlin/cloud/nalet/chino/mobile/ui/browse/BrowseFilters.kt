package cloud.nalet.chino.mobile.ui.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mirrors chino-web's BrowseQuery shape (BrowseFilters.tsx L5-11). */
data class BrowseQuery(
    val genre: String? = null,
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val ratingMin: Double? = null,
    val sort: String? = null, // "rating" / "year" / "title" / "newest"
)

private data class Decade(val label: String, val min: Int, val max: Int)
private val DECADES = listOf(
    Decade("2020s", 2020, 2099),
    Decade("2010s", 2010, 2019),
    Decade("2000s", 2000, 2009),
    Decade("1990s", 1990, 1999),
    Decade("1980s", 1980, 1989),
    Decade("Older", 1900, 1979),
)

private data class RatingTier(val label: String, val min: Double)
private val RATINGS = listOf(
    RatingTier("8.0+", 8.0),
    RatingTier("7.0+", 7.0),
    RatingTier("6.0+", 6.0),
)

/** Sort options as (label, BrowseQuery.sort value) — same labels + values the
 *  old Sort dropdown offered, now rendered as a single-select chip row. "Title"
 *  ("title") is the default catalogue order (see [hasActiveFilter]). */
private val SORTS = listOf(
    "Title" to "title",
    "Newest added" to "newest",
    "Rating" to "rating",
    "Year (newest)" to "year",
)

/**
 * Filter strip rendered above the Movies / Shows grids. Every axis is a
 * horizontal chip row (NO dropdowns / popup menus — see the UI strategy:
 * single-select-from-a-set is always an all-options-visible chip row), the
 * same idiom chino-androidtv's BrowseScreen uses:
 *   Row 1: [Genre label] [All] [Action] [Comedy] …   (scrollable LazyRow)
 *   Row 2: [Decade label] [2020s] … [Rating label] [8.0+] …
 *   Row 3: [Sort label] [Title] [Newest added] [Rating] [Year (newest)]
 *          (+ trailing [Clear filters] while any filter is active)
 *
 * The active chip is highlighted in accent (#58A6FF). For genre + decade +
 * rating, tapping the active chip clears that filter ("All" also clears the
 * genre); sort is a single-select that always has one chip active.
 */
@Composable
fun BrowseFilters(
    value: BrowseQuery,
    genres: List<String>,
    onChange: (BrowseQuery) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Genre row — a horizontally scrollable chip row (all options visible,
        // one tap) mirroring chino-androidtv's BrowseScreen genre LazyRow. The
        // "All" chip clears the genre; tapping the active genre clears it too.
        LazyRow(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("genre-label") { FilterLabel("Genre") }
            item("genre-all") {
                Chip(
                    label = "All",
                    active = value.genre == null,
                    onClick = { onChange(value.copy(genre = null)) },
                )
            }
            items(genres, key = { "genre:$it" }) { g ->
                Chip(
                    label = g,
                    active = value.genre == g,
                    onClick = { onChange(value.copy(genre = if (value.genre == g) null else g)) },
                )
            }
        }
        // Decade + rating toggle chips. Horizontally scrollable so the group
        // never clips on a narrow phone. Tapping the active chip clears it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            FilterLabel("Decade")
            DECADES.forEach { d ->
                val active = value.yearMin == d.min && value.yearMax == d.max
                Chip(
                    label = d.label,
                    active = active,
                    onClick = {
                        onChange(
                            value.copy(
                                yearMin = if (active) null else d.min,
                                yearMax = if (active) null else d.max,
                            )
                        )
                    },
                )
            }
            Box(modifier = Modifier.width(16.dp))
            FilterLabel("Rating")
            RATINGS.forEach { r ->
                val active = value.ratingMin == r.min
                Chip(
                    label = r.label,
                    active = active,
                    onClick = { onChange(value.copy(ratingMin = if (active) null else r.min)) },
                )
            }
        }
        // Sort row — single-select chip row of the 4 sort options (no dropdown).
        // "Title"/null both render the default order, so the Title chip is the
        // active default. A "Clear filters" chip trails while any axis deviates
        // from defaults, resetting genre/decade/rating/sort together via the
        // same onChange path as any chip tap.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            FilterLabel("Sort")
            SORTS.forEach { (label, sortValue) ->
                val active = (value.sort ?: "title") == sortValue
                Chip(
                    label = label,
                    active = active,
                    onClick = { onChange(value.copy(sort = sortValue)) },
                )
            }
            if (hasActiveFilter(value)) {
                Box(modifier = Modifier.width(16.dp))
                ClearFiltersChip(onClick = { onChange(BrowseQuery()) })
            }
        }
    }
}

/** True when at least one filter deviates from the defaults. An explicit
 *  sort == "title" IS the default catalogue order (chino-api falls through to
 *  ORDER BY sorttitle ASC when sort is empty), so it doesn't count on its own. */
private fun hasActiveFilter(q: BrowseQuery): Boolean =
    q.genre != null || q.yearMin != null || q.yearMax != null ||
        q.ratingMin != null || (q.sort != null && q.sort != "title")

@Composable
private fun FilterLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFF8B949E),
        fontSize = 13.sp,
        modifier = Modifier.widthIn(min = 56.dp),
    )
}

/** Web's clear control is muted text rather than a highlighted chip — keep
 *  the inactive-chip pill shape for tap-target consistency in the strip, but
 *  with the muted label color (#8B949E, same as the filter labels). */
@Composable
private fun ClearFiltersChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF161B22))
            .border(BorderStroke(1.dp, Color(0xFF30363D)), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "Clear filters",
            color = Color(0xFF8B949E),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) Color(0xFF58A6FF) else Color(0xFF161B22)
    val border = if (active) Color(0xFF58A6FF) else Color(0xFF30363D)
    val fg = if (active) Color.White else Color(0xFFC9D1D9)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
