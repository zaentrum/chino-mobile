package cloud.nalet.chino.mobile.data.downloads

/** One group of the downloads screen: the films, or one series. */
data class DownloadGroup(
    /** The series' id; null for the films. */
    val seriesId: String?,
    /** The series' title; null for the films. */
    val seriesTitle: String?,
    val seasons: List<DownloadSeason>,
) {
    val entries: List<DownloadEntry> get() = seasons.flatMap { it.entries }
}

/** A series' season on the downloads screen ([season] null: the films, or
 *  episodes without a season number). */
data class DownloadSeason(val season: Int?, val entries: List<DownloadEntry>)

/**
 * The downloads screen's groups, newest first: the films in one group of
 * their own, each series in its own, ordered by the newest download in
 * them. Films newest first; a series by its seasons, each season's episodes
 * in their order.
 */
fun downloadGroups(entries: List<DownloadEntry>): List<DownloadGroup> {
    val (episodes, films) = entries.partition { it.title.isEpisode && it.title.item.parentId != null }
    val groups = ArrayList<Pair<Long, DownloadGroup>>()
    if (films.isNotEmpty()) {
        val sorted = films.sortedByDescending { it.title.addedAtMs }
        groups += sorted.first().title.addedAtMs to DownloadGroup(null, null, listOf(DownloadSeason(null, sorted)))
    }
    for ((seriesId, inSeries) in episodes.groupBy { it.title.item.parentId!! }) {
        val seasons = inSeries
            .groupBy { it.title.item.seasonNumber }
            .entries
            .sortedBy { it.key ?: Int.MAX_VALUE }
            .map { (season, eps) -> DownloadSeason(season, eps.sortedBy { it.title.item.episodeNumber ?: Int.MAX_VALUE }) }
        val title = inSeries.firstNotNullOfOrNull { it.title.seriesTitle }
        groups += inSeries.maxOf { it.title.addedAtMs } to DownloadGroup(seriesId, title, seasons)
    }
    return groups.sortedByDescending { it.first }.map { it.second }
}
