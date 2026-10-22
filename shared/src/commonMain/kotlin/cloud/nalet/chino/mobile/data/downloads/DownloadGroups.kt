package cloud.nalet.chino.mobile.data.downloads

/** One group of the downloads screen: the movies, or one series. */
data class DownloadGroup(
    /** The series' id; null for the movies. */
    val seriesId: String?,
    /** The series' title, as the download saved it; null for the movies,
     *  and for a series none of whose downloads saved it. */
    val seriesTitle: String?,
    val seasons: List<DownloadSeason>,
) {
    val entries: List<DownloadEntry> get() = seasons.flatMap { it.entries }
}

/** A series' season on the downloads screen ([season] null: the movies, or
 *  episodes without a season number). */
data class DownloadSeason(val season: Int?, val entries: List<DownloadEntry>)

/**
 * The downloads screen's groups, newest first: the movies in one group of
 * their own, each series in its own, ordered by the newest download in
 * them - the account's own, by when it asked. Movies newest first; a series
 * by its seasons, each season's episodes in their order.
 */
fun downloadGroups(entries: List<DownloadEntry>): List<DownloadGroup> {
    val (episodes, films) = entries.partition { it.title.isEpisode && it.title.item.parentId != null }
    val groups = ArrayList<Pair<Long, DownloadGroup>>()
    if (films.isNotEmpty()) {
        val sorted = films.sortedByDescending { it.addedAtMs }
        groups += sorted.first().addedAtMs to DownloadGroup(null, null, listOf(DownloadSeason(null, sorted)))
    }
    for ((seriesId, inSeries) in episodes.groupBy { it.title.item.parentId!! }) {
        val seasons = inSeries
            .groupBy { it.title.item.seasonNumber }
            .entries
            .sortedBy { it.key ?: Int.MAX_VALUE }
            .map { (season, eps) -> DownloadSeason(season, eps.sortedBy { it.title.item.episodeNumber ?: Int.MAX_VALUE }) }
        val title = inSeries.firstNotNullOfOrNull { it.title.seriesTitle }
        groups += inSeries.maxOf { it.addedAtMs } to DownloadGroup(seriesId, title, seasons)
    }
    return groups.sortedByDescending { it.first }.map { it.second }
}
