package cloud.nalet.chino.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** One file, several episodes: one entry, the file's. */
class EpisodeFilesTest {
    private data class Ep(
        override val id: String,
        override val episodeNumber: Int? = null,
        override val coveredBy: String? = null,
        override val covers: List<String> = emptyList(),
        override val episodeEnd: Int? = null,
    ) : FileMember

    // A season whose episodes 18 to 21 are one file (a four-part finale) and
    // 14 and 15 another, as chino-api lists it.
    private val season = listOf(
        Ep("e13", 13),
        Ep("e14", 14, covers = listOf("e15"), episodeEnd = 15),
        Ep("e15", 15, coveredBy = "e14"),
        Ep("e16", 16),
        Ep("e17", 17),
        Ep("e18", 18, covers = listOf("e19", "e20", "e21"), episodeEnd = 21),
        Ep("e19", 19, coveredBy = "e18"),
        Ep("e20", 20, coveredBy = "e18"),
        Ep("e21", 21, coveredBy = "e18"),
    )

    @Test
    fun aFileThatHoldsSeveralEpisodesIsOneEntry() {
        val entries = fileEntries(season)
        assertEquals(listOf("e13", "e14", "e16", "e17", "e18"), entries.map { it.episode.id })
        assertEquals(listOf(13 to 13, 14 to 15, 16 to 16, 17 to 17, 18 to 21), entries.map { it.first to it.last })
        assertEquals(listOf("e18", "e19", "e20", "e21"), entries.last().ids)
        assertEquals(emptyList(), entries.first().covered)
    }

    @Test
    fun aListThatNamesNoCoversIsOneEntryPerEpisode() {
        val plain = listOf(Ep("a", 1), Ep("b", 2))
        assertEquals(listOf(Triple("a", 1, 1), Triple("b", 2, 2)), fileEntries(plain).map { Triple(it.episode.id, it.first, it.last) })
        assertEquals(emptyList(), fileEntries(emptyList<Ep>()))
    }

    @Test
    fun eitherSideAloneNamesTheFile() {
        val byCovers = listOf(Ep("a", 1, covers = listOf("b")), Ep("b", 2))
        assertEquals(listOf("a" to 2), fileEntries(byCovers).map { it.episode.id to it.last })
        val byCoveredBy = listOf(Ep("a", 1), Ep("b", 2, coveredBy = "a"))
        assertEquals(listOf("a" to 2), fileEntries(byCoveredBy).map { it.episode.id to it.last })
    }

    @Test
    fun anEpisodeWhoseHolderIsNotListedKeepsAnEntryOfItsOwn() {
        val orphan = listOf(Ep("b", 2, coveredBy = "gone"), Ep("c", 3))
        assertEquals(listOf("b", "c"), fileEntries(orphan).map { it.episode.id })
    }

    @Test
    fun aWatchedFileIsOneRowOfTheHistoryInThePlaceOfItsFirst() {
        val history = listOf(
            Ep("e21", coveredBy = "e18"),
            Ep("m1"),
            Ep("e19", coveredBy = "e18"),
            Ep("e18", covers = listOf("e19", "e20", "e21"), episodeEnd = 21),
            Ep("e20", coveredBy = "e18"),
            Ep("e17"),
        )
        assertEquals(listOf("e18", "m1", "e17"), oneRowPerFile(history).map { it.id })
        // A covered row whose holder the list does not have stays, once.
        val page = listOf(Ep("e20", coveredBy = "e18"), Ep("e19", coveredBy = "e18"), Ep("m1"))
        assertEquals(listOf("e20", "m1"), oneRowPerFile(page).map { it.id })
    }

    @Test
    fun theCodeOfAFileNamesTheEpisodesItHolds() {
        assertEquals("S03E18", episodeCode(3, 18))
        assertEquals("S03E18", episodeCode(3, 18, 18))
        assertEquals("S03E18–21", episodeCode(3, 18, 21))
        assertEquals("E05", episodeCode(null, 5))
        assertEquals("S02", episodeCode(2, null, 9))
        assertEquals("", episodeCode(null, null))
    }
}
