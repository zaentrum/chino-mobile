package cloud.nalet.chino.mobile.ui.browse

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.paging.FirstPages
import cloud.nalet.chino.mobile.data.paging.Paged
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Movies and Shows with the server gone mid-session: the list the phone
 * already has stays, under the banner, its genre chips with it - never
 * "couldn't load the movies" over a list loaded minutes before.
 */
class BrowseOfflineTest {
    private val owner = "https://media.example.com/api/|anna"
    private val films = Paged(
        items = listOf(Item(id = "m1", title = "Harbour Lights", kind = "movie"), Item(id = "m2", title = "Northern Coast", kind = "movie")),
        nextOffset = 2,
        endReached = true,
    )
    private val noAnswer = IOException("refused")

    /** The server, asked: gone. */
    private val gone: suspend () -> Boolean = { false }

    /** Where the list had its answer, or a status, the server is not asked. */
    private val notAsked: suspend () -> Boolean = { error("the server asked") }

    @BeforeTest
    @AfterTest
    fun forget() = KnownBrowse.clear()

    @Test
    fun theServerAnswersTheListAsItGaveItNoBanner() = runTest {
        val load = browseFirstLoad(Result.success(FirstPages(films, laterPageFailed = false)), known = null, reachable = notAsked)
        assertEquals(films, load.paged)
        assertNull(load.error)
        assertFalse(load.offline)
    }

    @Test
    fun theServerGoneMidSessionTheListThePhoneHasStaysUnderTheBanner() = runTest {
        val load = browseFirstLoad(Result.failure(noAnswer), known = films, reachable = gone)
        assertEquals(films, load.paged)
        // Not "couldn't load the movies": the list, and the banner over it.
        assertNull(load.error)
        assertTrue(load.offline)
    }

    @Test
    fun nothingKnownForTheFilterItCouldntLoadUnderTheBannerWhichSaysWhy() = runTest {
        val load = browseFirstLoad(Result.failure(noAnswer), known = null, reachable = gone)
        assertTrue(load.paged.items.isEmpty())
        assertEquals(LoadFailure.NoAnswer, load.error)
        assertEquals("no answer", load.errorDetails)
        assertTrue(load.offline)
        // "Can't reach the server." once: in the banner, not again under it.
        assertFalse(browseSaysWhy(LoadFailure.NoAnswer, offline = true))
    }

    @Test
    fun aSlowListFromAServerThatAnswersIsTheListsOwnFailureNoBanner() = runTest {
        var asked = 0
        val load = browseFirstLoad(Result.failure(noAnswer), known = films, reachable = { asked++; true })
        assertEquals(1, asked)
        assertEquals(LoadFailure.NoAnswer, load.error)
        assertFalse(load.offline)
        assertTrue(browseSaysWhy(LoadFailure.NoAnswer, offline = false))
    }

    @Test
    fun aServerThatAnswersWithAStatusIsNotGoneAndNotAsked() = runTest {
        val load = browseFirstLoad(Result.failure(ApiStatusException(500, "/api/v1/items")), known = films, reachable = notAsked)
        assertEquals(LoadFailure.ServerProblem, load.error)
        assertEquals("HTTP 500", load.errorDetails)
        assertFalse(load.offline)
        assertTrue(browseSaysWhy(LoadFailure.ServerProblem, offline = true))
    }

    @Test
    fun theListsAreKeptPerTypeAndFilterAndTheGenresWithThem() {
        val action = BrowseQuery(genre = "Action")
        KnownBrowse.putList(owner, "movie", BrowseQuery(), films)
        KnownBrowse.putGenres(owner, listOf("Action", "Comedy"))
        assertEquals(films, KnownBrowse.list(owner, "movie", BrowseQuery()))
        // Another filter, another type: lists of their own.
        assertNull(KnownBrowse.list(owner, "movie", action))
        assertNull(KnownBrowse.list(owner, "series", BrowseQuery()))
        assertEquals(listOf("Action", "Comedy"), KnownBrowse.genres(owner))
    }

    @Test
    fun anotherAccountOrServerNeverSeesThem() {
        KnownBrowse.putList(owner, "movie", BrowseQuery(), films)
        KnownBrowse.putGenres(owner, listOf("Action"))
        val other = "https://media.example.com/api/|ben"
        assertNull(KnownBrowse.list(other, "movie", BrowseQuery()))
        assertNull(KnownBrowse.genres(other))
        // Once the other one is kept, the first account's are gone.
        KnownBrowse.putList(other, "movie", BrowseQuery(), Paged())
        assertNull(KnownBrowse.list(owner, "movie", BrowseQuery()))
        assertNull(KnownBrowse.genres(owner))
    }

    @Test
    fun aWatchedMarkTheServerTookIsInTheListsKeptSoTheGridWithoutTheServerShowsIt() = runTest {
        val animation = BrowseQuery(genre = "Animation")
        val other = "https://media.example.com/api/|ben"
        KnownBrowse.putList(owner, "movie", BrowseQuery(), films)
        KnownBrowse.putList(owner, "movie", animation, films.copy(items = films.items.take(1)))
        // Marked watched from its card; the server took it (toggleWatched).
        KnownBrowse.markWatched(owner, "m1", "optimistic")
        // The server gone, the same filter again: the list with the mark.
        val all = browseFirstLoad(Result.failure(noAnswer), known = KnownBrowse.list(owner, "movie", BrowseQuery()), reachable = gone)
        assertTrue(all.offline)
        assertEquals("optimistic", all.paged.items.single { it.id == "m1" }.watchedAt)
        assertNull(all.paged.items.single { it.id == "m2" }.watchedAt)
        // Every list kept with the title, the other filter's too.
        assertEquals("optimistic", KnownBrowse.list(owner, "movie", animation)?.items?.single()?.watchedAt)
        // Unmarked again: none.
        KnownBrowse.markWatched(owner, "m1", null)
        assertNull(KnownBrowse.list(owner, "movie", BrowseQuery())?.items?.first()?.watchedAt)
        assertNull(KnownBrowse.list(owner, "movie", animation)?.items?.single()?.watchedAt)
        // Another account's mark never reaches these lists.
        KnownBrowse.markWatched(other, "m1", "optimistic")
        assertNull(KnownBrowse.list(owner, "movie", BrowseQuery())?.items?.first()?.watchedAt)
    }

    @Test
    fun onlyTheLastListsAskedForAreKept() {
        val filters = (0..KnownBrowse.MAX_LISTS).map { BrowseQuery(yearMin = 1950 + it) }
        filters.forEach { KnownBrowse.putList(owner, "movie", it, films) }
        // The oldest went; the rest stay.
        assertNull(KnownBrowse.list(owner, "movie", filters.first()))
        assertEquals(films, KnownBrowse.list(owner, "movie", filters.last()))
        assertEquals(films, KnownBrowse.list(owner, "movie", filters[1]))
    }
}
