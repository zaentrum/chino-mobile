package cloud.nalet.chino.mobile.data.paging

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagedTest {
    private fun ids(range: IntRange) = range.map { "t$it" }

    @Test
    fun aFullPageKeepsGoingFromTheRowsReceived() {
        val paged = Paged<String>().append(ids(1..48), pageSize = 48) { it }

        assertEquals(48, paged.items.size)
        assertEquals(48, paged.nextOffset)
        assertFalse(paged.endReached)
    }

    @Test
    fun aShortPageIsTheLast() {
        val paged = Paged<String>()
            .append(ids(1..48), pageSize = 48) { it }
            .append(ids(49..60), pageSize = 48) { it }

        assertEquals(ids(1..60), paged.items)
        assertEquals(60, paged.nextOffset)
        assertTrue(paged.endReached)
    }

    @Test
    fun anEmptyPageAfterAnExactMultipleIsTheLast() {
        // 96 titles in pages of 48: the third request answers [] — the end.
        val paged = Paged<String>()
            .append(ids(1..48), pageSize = 48) { it }
            .append(ids(49..96), pageSize = 48) { it }
            .append(emptyList(), pageSize = 48) { it }

        assertEquals(96, paged.items.size)
        assertEquals(96, paged.nextOffset)
        assertTrue(paged.endReached)
    }

    @Test
    fun aTitleOnBothSidesOfAPageBoundaryIsListedOnce() {
        // A title added between the requests shifts t3 onto the second page
        // as well. The grid keys by id, so it must appear once; the offset
        // still counts every row the server sent.
        val paged = Paged<String>()
            .append(listOf("t1", "t2", "t3"), pageSize = 3) { it }
            .append(listOf("t3", "t4", "t5"), pageSize = 3) { it }

        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), paged.items)
        assertEquals(6, paged.nextOffset)
        assertFalse(paged.endReached)
    }

    @Test
    fun mapChangesItemsButNotWhereTheNextPageStarts() {
        val paged = Paged<String>().append(ids(1..2), pageSize = 2) { it }
        val mapped = paged.map { it.uppercase() }

        assertEquals(listOf("T1", "T2"), mapped.items)
        assertEquals(paged.nextOffset, mapped.nextOffset)
        assertEquals(paged.endReached, mapped.endReached)
    }

    /** A catalogue of [size] titles, served [pageSize] at a time; the offsets
     *  asked for go to [asked]. */
    private fun catalogue(size: Int, asked: MutableList<Int>, pageSize: Int = 48): suspend (Int) -> List<String> = { offset ->
        asked += offset
        ids(offset + 1..minOf(offset + pageSize, size))
    }

    @Test
    fun theRowsAGridHadAreAskedForAgainPageByPage() = runTest {
        // 100 rows loaded before the process was killed: three pages of 48.
        val asked = mutableListOf<Int>()
        val first = loadFirstPages(rows = 100, pageSize = 48, key = { it }, fetch = catalogue(300, asked))

        assertEquals(listOf(0, 48, 96), asked)
        assertEquals(ids(1..144), first.paged.items)
        assertEquals(144, first.paged.nextOffset)
        assertFalse(first.paged.endReached)
        assertFalse(first.laterPageFailed)
    }

    @Test
    fun withNoRowsToGetBackTheFirstPageIsAll() = runTest {
        val asked = mutableListOf<Int>()
        val first = loadFirstPages(rows = 0, pageSize = 48, key = { it }, fetch = catalogue(300, asked))

        assertEquals(listOf(0), asked)
        assertEquals(48, first.paged.items.size)
    }

    @Test
    fun aCatalogueThatEndsSoonerEndsTheRun() = runTest {
        // Titles were removed while the app was away: 60 left of the 100.
        val asked = mutableListOf<Int>()
        val first = loadFirstPages(rows = 100, pageSize = 48, key = { it }, fetch = catalogue(60, asked))

        assertEquals(listOf(0, 48), asked)
        assertEquals(ids(1..60), first.paged.items)
        assertTrue(first.paged.endReached)
        assertFalse(first.laterPageFailed)
    }

    @Test
    fun aLaterPageThatFailsEndsTheRunWithThePagesBeforeIt() = runTest {
        val first = loadFirstPages(rows = 100, pageSize = 48, key = { it }) { offset ->
            if (offset == 0) ids(1..48) else error("chino-api is down")
        }

        assertEquals(ids(1..48), first.paged.items)
        assertEquals(48, first.paged.nextOffset)
        assertTrue(first.laterPageFailed)
    }

    @Test
    fun theFirstPageFailingIsThrownAsASinglePageIs() = runTest {
        assertFailsWith<IllegalStateException> {
            loadFirstPages<String>(rows = 100, pageSize = 48, key = { it }) { error("chino-api is down") }
        }
    }
}
