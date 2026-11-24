package cloud.nalet.chino.mobile.ui.search

/** The two groups a search's results stand in. */
internal enum class SearchGroup { Titles, CastAndCrew }

/**
 * The order a search's groups stand in (change 9.C16 of the specification,
 * its section 9.6.1): the titles first and cast & crew under them; where only
 * people match, they stand at the top.
 *
 * [titlesFound] is how many titles the search found, null while that is not
 * known - it still searches, or it could not be made - and [peopleFound] how
 * many people. The titles' place is always there: it holds the results'
 * heading and what stands in for the titles while there are none to show.
 * Cast & crew is left out while no person matches.
 */
internal fun searchGroupOrder(titlesFound: Int?, peopleFound: Int): List<SearchGroup> = when {
    peopleFound <= 0 -> listOf(SearchGroup.Titles)
    titlesFound == 0 -> listOf(SearchGroup.CastAndCrew, SearchGroup.Titles)
    else -> listOf(SearchGroup.Titles, SearchGroup.CastAndCrew)
}
