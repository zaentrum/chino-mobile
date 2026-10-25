package cloud.nalet.chino.mobile.ui.detail

import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.Episode
import cloud.nalet.chino.mobile.data.api.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A series' page plays an episode, its first button naming it by its code
 * (docs/chino title actions, "On a series"), as chino-web's
 * seriesPlayTarget and the TV's title page do.
 */
class SeriesPlayTest {
    private fun ep(id: String, season: Int, n: Int, minutes: Long = 45, end: Int? = null) =
        Episode(id = id, title = id, seasonNumber = season, episodeNumber = n, parentId = "harbour", durationMs = minutes * 60_000, episodeEnd = end)

    private val seasons = listOf(
        Season(season = 0, episodes = listOf(ep("special", 0, 1))),
        Season(season = 2, episodes = listOf(ep("s2e1", 2, 1))),
        Season(season = 1, episodes = listOf(ep("s1e3", 1, 3), ep("s1e1", 1, 1), ep("s1e2", 1, 2))),
    )

    private fun row(id: String, position: Int, upNext: Boolean = false, season: Int? = null, episode: Int? = null) =
        ContinueWatchingItem(
            id = id,
            title = id,
            positionSec = position,
            durationSec = 2700,
            upNext = upNext,
            seasonNumber = season,
            episodeNumber = episode,
            type = "episode",
            parentId = "harbour",
        )

    @Test
    fun nothingWatchedYetPlaysS01E01() {
        val t = seriesPlayTarget("harbour", emptyList(), seasons)!!
        assertEquals("s1e1", t.episodeId)
        assertEquals("play S01E01", t.label)
    }

    @Test
    fun anEpisodePartlyWatchedResumesByItsCodeAndStartsOver() {
        val t = seriesPlayTarget("harbour", listOf(row("s1e3", 754, season = 1, episode = 3)), seasons)!!
        assertEquals("resume S01E03", t.label)
        assertEquals(754, t.resumeSec)
        // Its position is in its row, never in the button.
        assertEquals(false, t.label.contains(":"))
    }

    @Test
    fun afterAFinishedOneTheNextPlaysFromItsStart() {
        val t = seriesPlayTarget("harbour", listOf(row("s1e2", 0, upNext = true, season = 1, episode = 2)), seasons)!!
        assertEquals("play S01E02", t.label)
        assertEquals(0, t.resumeSec)
    }

    @Test
    fun everyEpisodeFinishedPlaysS01E01Again() {
        // Continue watching lists nothing of a series watched to its end.
        val others = listOf(row("elsewhere", 600).copy(parentId = "another"))
        assertEquals("play S01E01", seriesPlayTarget("harbour", others, seasons)!!.label)
    }

    @Test
    fun aRowWithoutItsNumbersIsNamedFromItsSeasonAndOneBarelyStartedPlays() {
        assertEquals("resume S01E02", seriesPlayTarget("harbour", listOf(row("s1e2", 754)), seasons)!!.label)
        // Barely started, or in its last minute: it plays, as the player would.
        assertEquals("play S01E02", seriesPlayTarget("harbour", listOf(row("s1e2", 20)), seasons)!!.label)
        assertEquals("play S01E02", seriesPlayTarget("harbour", listOf(row("s1e2", 2680)), seasons)!!.label)
        // Not in the seasons either: the verb alone.
        assertEquals("resume", seriesPlayTarget("harbour", listOf(row("gone", 754)), seasons)!!.label)
    }

    @Test
    fun aFileOfTwoEpisodesIsNamedByBoth() {
        val holder = listOf(Season(season = 3, episodes = listOf(ep("s3e18", 3, 18, end = 19))))
        assertEquals("play S03E18–19", seriesPlayTarget("harbour", emptyList(), holder)!!.label)
    }

    @Test
    fun aSeriesWithoutEpisodesHasNoButtonAndOnlySpecialsPlayTheFirstSpecial() {
        assertNull(seriesPlayTarget("harbour", emptyList(), emptyList()))
        val specials = listOf(Season(season = 0, episodes = listOf(ep("special", 0, 1))))
        assertEquals("special", seriesPlayTarget("harbour", emptyList(), specials)!!.episodeId)
    }
}
