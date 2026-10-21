package cloud.nalet.chino.mobile.ui.search

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.Person
import cloud.nalet.chino.mobile.data.model.Item
import io.ktor.http.HttpStatusCode
import kotlin.coroutines.cancellation.CancellationException

/*
 * What the search asks chino-api and what it makes of the answer, apart from
 * the screen ([SearchResultsSection]) so commonTest runs it against a fake
 * server.
 */

/**
 * The types of title the search asks for: films and series, which the server
 * ranks together. It ranks episodes too ("episode"), but no client shows
 * episode results yet.
 *
 * To show episodes, add "episode" here. Each episode hit then carries its
 * `series` ([Item.series]: id, title, poster_url, matched) and its season and
 * episode numbers. The agreed card (the later UI rework, not this one) shows
 * it as its series' poster card with one line read from the data, like
 * "S05E14 · Ozymandias", and opens the series on that episode; and there is
 * one card per series when the series also matched on its own title
 * (`series.matched`).
 */
internal val SEARCH_TYPES: List<String> = listOf("movie", "series")

/** How many titles a search asks for (the server takes up to 100); asked as
 *  before, as many films and as many series. */
internal const val SEARCH_TITLES = 40

/** How many people a search asks for (the server takes up to 50). */
internal const val SEARCH_PEOPLE = 12

/** What a search found: the titles in the order to show them, or why they
 *  could not be had; and the people, best effort — none when they could not
 *  be had. */
internal data class SearchFound(val titles: Result<List<Item>>, val people: List<Person>)

/**
 * One search of the catalogue for [query]: GET /v1/search, the titles of
 * [SEARCH_TYPES] ranked together, best first, to be shown as they come —
 * never ranked again, never grouped by type — and the people of the same
 * answer. A server older than the search answers 404 and is searched as
 * before ([searchAsBefore]). Any other failure is a failed search: no titles,
 * no people. A search the next query cuts short is cancelled, not failed.
 */
internal suspend fun searchCatalogue(api: ChinoApi, query: String): SearchFound {
    val found = try {
        api.search(q = query, types = SEARCH_TYPES, limit = SEARCH_TITLES, people = SEARCH_PEOPLE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e is ApiStatusException && e.status == HttpStatusCode.NotFound.value) return searchAsBefore(api, query)
        return SearchFound(titles = Result.failure(e), people = emptyList())
    }
    return SearchFound(titles = Result.success(found.items), people = found.people)
}

/**
 * The search as it was before GET /v1/search, for a server without it: the
 * people (GET /v1/people), best effort — a failure there never blanks the
 * titles — then the films and then the series of the query (GET
 * /v1/items?q=&type=), each list in the server's order.
 */
private suspend fun searchAsBefore(api: ChinoApi, query: String): SearchFound {
    val people = attempt { api.searchPeople(q = query, limit = SEARCH_PEOPLE).people }.getOrDefault(emptyList())
    val titles = attempt {
        val movies = api.listItems(q = query, type = "movie", limit = SEARCH_TITLES).items
        val series = api.listItems(q = query, type = "series", limit = SEARCH_TITLES).items
        movies + series
    }
    return SearchFound(titles, people)
}

/** runCatching that lets cancellation through: a search the next query cut
 *  short must end quietly, not land as a failed search over the next one. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
