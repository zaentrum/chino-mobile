package cloud.nalet.chino.mobile.ui.home

import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.Episode
import cloud.nalet.chino.mobile.data.api.Season
import cloud.nalet.chino.mobile.data.model.Extra
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.Trailer
import cloud.nalet.chino.mobile.ui.trailer.TrailerChoice
import cloud.nalet.chino.mobile.ui.trailer.trailerChoice
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hero picks and plays as chino-web's does, so a server shows one hero:
 *  titles with a trailer — this server's first, then a YouTube link — whose
 *  Trailer does what the title's page's does. */
class HeroPoolTest {
    @Test
    fun aTrailerUrlNamesItsYouTubeVideo() {
        assertEquals("dQw4w9WgXcQ", youTubeKey("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youTubeKey("https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youTubeKey("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youTubeKey("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertNull(youTubeKey("https://video.example.com/trailer.mp4"))
        assertNull(youTubeKey("https://youtu.be/abc"))
    }

    @Test
    fun thePoolIsTheCandidatesWithATrailerWithTheirOverview() {
        val candidates = listOf(
            item("m1", "movie"), item("m2", "movie"), item("s1", "series"),
            item("m3", "movie"), item("m4", "movie"), item("m5", "movie"),
        )
        val details = mapOf(
            "m1" to item("m1", "movie", overview = "Long story.", trailer = "https://youtu.be/dQw4w9WgXcQ"),
            "m2" to item("m2", "movie", trailer = "https://video.example.com/trailer.mp4"),
            "s1" to item("s1", "series", trailer = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            // A trailer this server plays, and no link at all.
            "m3" to item("m3", "movie", extras = listOf(extra("x3", "trailer"))),
            // A teaser counts too; a featurette alone does not.
            "m4" to item("m4", "movie", extras = listOf(extra("x4", "teaser"))),
            "m5" to item("m5", "movie", extras = listOf(extra("x5", "featurette"))),
        )
        val pool = pickHeroPool(candidates, details, random = Random(1))
        assertEquals(setOf("m1", "s1", "m3", "m4"), pool.map { it.id }.toSet())
        assertEquals("Long story.", pool.single { it.id == "m1" }.overview)
        assertEquals("series", pool.single { it.id == "s1" }.kind)
    }

    @Test
    fun titlesWithATrailerOfTheirOwnComeFirstEachGroupShuffled() {
        val candidates = (1..6).map { item("m$it", "movie") }
        // m1-m3 have a YouTube link only, m4-m6 a trailer this server plays
        // (m6 a link as well): the server's ones lead, whatever the order.
        val details = candidates.associate { c ->
            val n = c.id.drop(1).toInt()
            c.id to c.copy(
                trailers = if (n <= 3 || n == 6) listOf(Trailer("https://youtu.be/dQw4w9WgXcQ")) else emptyList(),
                extras = if (n >= 4) listOf(extra("x$n", "trailer")) else emptyList(),
            )
        }
        val orders = (1..20).map { seed -> pickHeroPool(candidates, details, random = Random(seed)).map { it.id } }
        for (ids in orders) {
            assertEquals(setOf("m4", "m5", "m6"), ids.take(3).toSet())
            assertEquals(setOf("m1", "m2", "m3"), ids.drop(3).toSet())
        }
        // Shuffled within each group, not kept in the candidates' order.
        assertTrue(orders.map { it.take(3) }.toSet().size > 1)
        assertTrue(orders.map { it.drop(3) }.toSet().size > 1)
    }

    @Test
    fun thePoolHoldsAtMostEight() {
        val candidates = (1..12).map { item("m$it", "movie") }
        val details = candidates.associate { it.id to it.copy(trailers = listOf(Trailer("https://youtu.be/dQw4w9WgXcQ"))) }
        assertEquals(HERO_POOL_SIZE, pickHeroPool(candidates, details).size)

        // Ten with a trailer of their own and five with a link: the eight are theirs.
        val more = (1..15).map { item("m$it", "movie") }
        val mixed = more.associate { c ->
            val n = c.id.drop(1).toInt()
            c.id to if (n <= 10) c.copy(extras = listOf(extra("x$n", "trailer"))) else c.copy(trailers = listOf(Trailer("https://youtu.be/dQw4w9WgXcQ")))
        }
        val pool = pickHeroPool(more, mixed, random = Random(3))
        assertEquals(HERO_POOL_SIZE, pool.size)
        assertTrue(pool.all { it.id.drop(1).toInt() <= 10 })
    }

    @Test
    fun aHeroTitleCarriesItsDetailsExtrasAndTrailerLinks() {
        // The list leaves both out; the hero's Trailer chooses from them.
        val featurette = extra("f1", "featurette")
        val own = extra("x1", "trailer")
        val link = Trailer("https://www.youtube.com/watch?v=dQw4w9WgXcQ", site = "YouTube", title = "Official Trailer")
        val candidates = listOf(item("m1", "movie"), item("s1", "series"))
        val details = mapOf(
            "m1" to item("m1", "movie", extras = listOf(featurette, own)).copy(trailers = listOf(link)),
            "s1" to item("s1", "series").copy(trailers = listOf(link)),
        )
        val pool = pickHeroPool(candidates, details, random = Random(1)).associateBy { it.id }
        assertEquals(listOf(featurette, own), pool.getValue("m1").extras)
        assertEquals(listOf(link), pool.getValue("m1").trailers)
        assertEquals(listOf(link), pool.getValue("s1").trailers)
        assertTrue(pool.getValue("s1").extras.isEmpty())
    }

    @Test
    fun theHerosTrailerPlaysTheTitlesOwnElseOpensItsLink() {
        val own = extra("x1", "trailer")
        val teaser = extra("z1", "teaser")
        val link = Trailer("https://www.youtube.com/watch?v=dQw4w9WgXcQ", site = "YouTube", title = "Official Trailer")
        val candidates = listOf(item("m1", "movie"), item("m2", "movie"), item("s1", "series"))
        val details = mapOf(
            // Its own and a link: its own plays, as on the title's page.
            "m1" to item("m1", "movie", extras = listOf(own)).copy(trailers = listOf(link)),
            // A link only: the link opens.
            "m2" to item("m2", "movie").copy(trailers = listOf(link)),
            // A series' teaser of its own.
            "s1" to item("s1", "series", extras = listOf(teaser)),
        )
        val pool = pickHeroPool(candidates, details, random = Random(1)).associateBy { it.id }
        assertEquals(TrailerChoice.Local(own), trailerChoice(pool.getValue("m1")))
        assertEquals(TrailerChoice.Link(link), trailerChoice(pool.getValue("m2")))
        assertEquals(TrailerChoice.Local(teaser), trailerChoice(pool.getValue("s1")))
        // A title as the list gives it, without its details, has neither: no
        // button.
        assertNull(trailerChoice(item("m1", "movie")))
    }

    @Test
    fun aCandidateWithoutDetailsIsLeftOut() {
        assertTrue(pickHeroPool(listOf(item("m1", "movie")), emptyMap()).isEmpty())
    }

    @Test
    fun aSeriesPlaysTheEpisodeContinueWatchingNamesFirst() {
        val cw = listOf(
            row("e9", type = "episode", parent = "other"),
            row("m1", type = "movie"),
            row("e5", type = "episode", parent = "s1"),
            row("e2", type = "episode", parent = "s1"),
        )
        assertEquals("e5", episodeToPlay("s1", cw, seasons()))
    }

    @Test
    fun aSeriesNobodyStartedPlaysItsFirstEpisode() {
        assertEquals("s1e1", episodeToPlay("s1", emptyList(), seasons()))
    }

    @Test
    fun theFirstEpisodeIsTheLowestOfTheLowestRegularSeason() {
        assertEquals("s1e1", firstEpisode(seasons()))
        // Season 0 (specials) only when nothing else has episodes.
        assertEquals("sp1", firstEpisode(listOf(Season(0, listOf(episode("sp1", 1))), Season(1, emptyList()))))
        assertNull(firstEpisode(emptyList()))
    }

    private fun seasons() = listOf(
        Season(0, listOf(episode("sp1", 1))),
        Season(2, listOf(episode("s2e1", 1))),
        Season(1, listOf(episode("s1e2", 2), episode("s1e1", 1))),
    )

    private fun item(id: String, kind: String, overview: String? = null, trailer: String? = null, extras: List<Extra> = emptyList()) =
        Item(
            id = id,
            title = id.uppercase(),
            kind = kind,
            overview = overview,
            trailers = listOfNotNull(trailer?.let { Trailer(it) }),
            extras = extras,
        )

    private fun extra(id: String, kind: String) =
        Extra(id = id, kind = kind, title = kind, local = true, playPath = "/api/v1/items/m/extras/$id/play/master.m3u8")

    private fun episode(id: String, number: Int) = Episode(id = id, title = id, episodeNumber = number)

    private fun row(id: String, type: String, parent: String? = null) =
        ContinueWatchingItem(id = id, title = id, type = type, parentId = parent)
}
