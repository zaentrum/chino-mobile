package cloud.nalet.chino.mobile.ui.home

import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.Episode
import cloud.nalet.chino.mobile.data.api.Season
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.Trailer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hero picks and plays as chino-web's does, so a server shows one hero. */
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
        val candidates = listOf(item("m1", "movie"), item("m2", "movie"), item("s1", "series"))
        val details = mapOf(
            "m1" to item("m1", "movie", overview = "Long story.", trailer = "https://youtu.be/dQw4w9WgXcQ"),
            "m2" to item("m2", "movie", trailer = "https://video.example.com/trailer.mp4"),
            "s1" to item("s1", "series", trailer = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
        val pool = pickHeroPool(candidates, details, random = Random(1))
        assertEquals(setOf("m1", "s1"), pool.map { it.id }.toSet())
        assertEquals("Long story.", pool.single { it.id == "m1" }.overview)
        assertEquals("series", pool.single { it.id == "s1" }.kind)
    }

    @Test
    fun thePoolHoldsAtMostEight() {
        val candidates = (1..12).map { item("m$it", "movie") }
        val details = candidates.associate { it.id to it.copy(trailers = listOf(Trailer("https://youtu.be/dQw4w9WgXcQ"))) }
        assertEquals(HERO_POOL_SIZE, pickHeroPool(candidates, details).size)
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

    private fun item(id: String, kind: String, overview: String? = null, trailer: String? = null) =
        Item(id = id, title = id.uppercase(), kind = kind, overview = overview, trailers = listOfNotNull(trailer?.let { Trailer(it) }))

    private fun episode(id: String, number: Int) = Episode(id = id, title = id, episodeNumber = number)

    private fun row(id: String, type: String, parent: String? = null) =
        ContinueWatchingItem(id = id, title = id, type = type, parentId = parent)
}
