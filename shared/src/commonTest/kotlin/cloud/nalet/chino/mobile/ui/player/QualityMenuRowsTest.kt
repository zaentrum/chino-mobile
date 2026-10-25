package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.QualityRung
import kotlin.test.Test
import kotlin.test.assertEquals

/** The quality menu has a row per name, and a button only where there is
 *  more than one row to pick from. */
class QualityMenuRowsTest {
    @Test
    fun qsTheServerNamesAlikeAreOneRowAndNothingToSwitchTo() {
        // A 480p source on a server that lists every rung: one picture.
        val sameName = listOf(QualityRung("high", "Low (480p)"), QualityRung("medium", "Low (480p)"), QualityRung("low", "Low (480p)"))
        val rows = qualityMenuRows(sameName)
        assertEquals(1, rows.size)
        assertEquals("Low (480p)", rows.single().first)
        // The first of them is the one picked, the others play the same.
        assertEquals(listOf("high", "medium", "low"), rows.single().second.map { it.name })
    }

    @Test
    fun differentNamesAreRowsInTheServersOrder() {
        val listed = listOf(QualityRung("high", "High (1080p)"), QualityRung("medium", "Medium (720p)"), QualityRung("low", "medium (720P)"))
        assertEquals(listOf("High (1080p)", "Medium (720p)"), qualityMenuRows(listed).map { it.first })
        // An older server's bare words and heights by the table.
        assertEquals(
            listOf("Medium (720p)", "Low"),
            qualityMenuRows(listOf(QualityRung("medium", "720p"), QualityRung("low", ""))).map { it.first },
        )
        assertEquals(emptyList(), qualityMenuRows(emptyList()))
    }
}
