package cloud.nalet.chino.mobile.ui.detail

import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.Episode
import cloud.nalet.chino.mobile.data.api.Season
import cloud.nalet.chino.mobile.data.model.episodeCode

/*
 * A series' page plays an episode, and its first button names it by its
 * code (docs/chino title actions, "On a series"): "play S01E01" when nothing
 * is watched yet, "resume S01E03" and "start over" for an episode partly
 * watched, "play S01E02" - the next one - after a finished one, and "play
 * S01E01" once every episode is finished. The position shows in the
 * episode's row, never in the button. chino-web's seriesPlayTarget, as the
 * TV's title page does it.
 */

/** What the first button on a series' page plays. */
data class SeriesPlayTarget(
    val episodeId: String,
    /** Its season and episode, where known: what the button names. */
    val season: Int?,
    val episode: Int?,
    /** The last episode of a file that holds several ("S03E18–19"). */
    val episodeEnd: Int?,
    /** Where it resumes; 0 plays it from its start. */
    val resumeSec: Int,
) {
    /** "resume" for one partly watched, else "play". */
    val resumes: Boolean get() = resumeSec > 0

    /** The episode's code, capitals kept; null where its numbers are not
     *  known. */
    val code: String? get() = if (season != null && episode != null) episodeCode(season, episode, episodeEnd) else null

    /** The button's label: "play S01E01", "resume S01E03"; the verb alone
     *  without a code. */
    val label: String get() = seriesPlayLabel(resumes, code)
}

/** A series' first button: the verb and the episode's code ("resume
 *  S01E03"), its capitals kept - the rest of the row is lowercase. */
fun seriesPlayLabel(resumes: Boolean, code: String?): String {
    val verb = if (resumes) "resume" else "play"
    return if (code.isNullOrBlank()) verb else "$verb $code"
}

/**
 * What the first button on [seriesId]'s page plays: the series' first row
 * in Continue watching ([continueWatching], the most recent first) - the
 * episode the viewer is in, resumed where they are (where the player resumes
 * it, [resumesAt]), or the next one after the last they finished (an up-next
 * row), from its start - else S01E01. A row without its numbers takes them
 * from the season it is in. Null when the series has no episodes.
 */
fun seriesPlayTarget(seriesId: String, continueWatching: List<ContinueWatchingItem>, seasons: List<Season>): SeriesPlayTarget? {
    val row = continueWatching.firstOrNull { it.type == "episode" && it.parentId == seriesId }
    if (row != null) {
        val listed = episodeIn(seasons, row.id)
        // The feed's length, else the episode's catalogue runtime, as its row
        // draws its bar against.
        val durationSec = if (row.durationSec > 0) row.durationSec else ((listed?.second?.durationMs ?: 0L) / 1000L).toInt()
        val resumes = !row.upNext && resumesAt(row.positionSec, durationSec.toDouble())
        return SeriesPlayTarget(
            episodeId = row.id,
            season = row.seasonNumber ?: listed?.first,
            episode = row.episodeNumber ?: listed?.second?.episodeNumber,
            episodeEnd = row.episodeEnd ?: listed?.second?.episodeEnd,
            resumeSec = if (resumes) row.positionSec else 0,
        )
    }
    val (season, first) = firstEpisodeOf(seasons) ?: return null
    return SeriesPlayTarget(first.id, season, first.episodeNumber, first.episodeEnd, resumeSec = 0)
}

/** S01E01 and its season: the lowest episode of the lowest season from 1
 *  on; the specials of season 0 only when there is nothing else. */
private fun firstEpisodeOf(seasons: List<Season>): Pair<Int, Episode>? {
    val withEpisodes = seasons.filter { it.episodes.isNotEmpty() }
    val regular = withEpisodes.filter { it.season >= 1 }
    val season = regular.ifEmpty { withEpisodes }.minByOrNull { it.season } ?: return null
    val first = season.episodes.minByOrNull { it.episodeNumber ?: Int.MAX_VALUE } ?: return null
    return season.season to first
}

/** The season and the episode with that id, where the seasons list it. */
private fun episodeIn(seasons: List<Season>, id: String): Pair<Int, Episode>? {
    for (s in seasons) s.episodes.firstOrNull { it.id == id }?.let { return s.season to it }
    return null
}
