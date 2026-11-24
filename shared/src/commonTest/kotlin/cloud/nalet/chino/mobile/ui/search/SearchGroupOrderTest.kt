package cloud.nalet.chino.mobile.ui.search

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The order of a search's two groups (change 9.C16 of the specification, its
 * section 9.6.1): the titles first and cast & crew under them; where only
 * people match, they stand at the top.
 */
class SearchGroupOrderTest {
    private val titles = SearchGroup.Titles
    private val castAndCrew = SearchGroup.CastAndCrew

    @Test
    fun change9_C16_theTitlesStandFirstAndCastAndCrewUnderThem() {
        assertEquals(listOf(titles, castAndCrew), searchGroupOrder(titlesFound = 4, peopleFound = 3))
        assertEquals(listOf(titles, castAndCrew), searchGroupOrder(titlesFound = 1, peopleFound = 12))
    }

    @Test
    fun change9_C16_whereOnlyPeopleMatchTheyStandAtTheTop() {
        assertEquals(listOf(castAndCrew, titles), searchGroupOrder(titlesFound = 0, peopleFound = 1))
    }

    @Test
    fun change9_C16_whereNoPersonMatchesCastAndCrewIsLeftOut() {
        assertEquals(listOf(titles), searchGroupOrder(titlesFound = 4, peopleFound = 0))
        assertEquals(listOf(titles), searchGroupOrder(titlesFound = 0, peopleFound = 0))
    }

    @Test
    fun change9_C16_whileTheTitlesAreNotKnownTheirPlaceStaysFirst() {
        // Searching still, or the search could not be made: the people of
        // the words before stay under the titles' place.
        assertEquals(listOf(titles, castAndCrew), searchGroupOrder(titlesFound = null, peopleFound = 2))
        assertEquals(listOf(titles), searchGroupOrder(titlesFound = null, peopleFound = 0))
    }
}
