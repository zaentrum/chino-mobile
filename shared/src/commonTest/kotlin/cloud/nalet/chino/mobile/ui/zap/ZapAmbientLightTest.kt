package cloud.nalet.chino.mobile.ui.zap

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZapAmbientLightTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test
    fun aFrameOfOneColourIsAGridOfIt() {
        val grey = 0xFF808080.toInt()
        val grid = averageGrid(IntArray(160 * 90) { grey }, 160, 90)
        assertEquals(AMBIENT_COLS * AMBIENT_ROWS, grid.size)
        assertTrue(grid.all { it == grey })
    }

    @Test
    fun eachCellIsTheMeanOfThePixelsItCovers() {
        // Left half red, right half blue: the left eight columns red, the right
        // eight blue, in every row.
        val pixels = IntArray(160 * 90) { i -> if (i % 160 < 80) red else blue }
        val grid = averageGrid(pixels, 160, 90)
        for (row in 0 until AMBIENT_ROWS) {
            for (col in 0 until AMBIENT_COLS) {
                assertEquals(if (col < 8) red else blue, grid[row * AMBIENT_COLS + col], "cell $col,$row")
            }
        }
        // One cell of a 2 x 1 grid over black and white pixels: their grey.
        val mean = averageGrid(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 2, 1, cols = 1, rows = 1)
        assertContentEquals(intArrayOf(0xFF7F7F7F.toInt()), mean)
    }

    @Test
    fun aFrameSmallerThanTheGridLendsEachCellItsNearestPixel() {
        val pixels = IntArray(4 * 3) { i -> 0xFF000000.toInt() or ((i + 1) * 20 shl 8) }
        val grid = averageGrid(pixels, 4, 3)
        assertEquals(pixels[0], grid[0])
        assertEquals(pixels[3], grid[AMBIENT_COLS - 1])
        assertEquals(pixels[11], grid[AMBIENT_COLS * AMBIENT_ROWS - 1])
        assertTrue(grid.all { it != 0xFF000000.toInt() }, "no cell left without a pixel")
    }

    @Test
    fun theLightIsOpaqueWhateverTheFrame() {
        val halfClear = 0x80FF0000.toInt()
        val grid = averageGrid(IntArray(32 * 18) { halfClear }, 32, 18)
        assertTrue(grid.all { it == red })
    }

    @Test
    fun mixingMovesFromOneGridToTheOtherCellByCell() {
        val a = IntArray(4) { red }
        val b = IntArray(4) { blue }
        assertContentEquals(a, mixGrids(a, b, 0f))
        assertContentEquals(b, mixGrids(a, b, 1f))
        assertContentEquals(IntArray(4) { 0xFF800080.toInt() }, mixGrids(a, b, 0.5f))
        // Out of range, the ends.
        assertContentEquals(a, mixGrids(a, b, -1f))
        assertContentEquals(b, mixGrids(a, b, 2f))
        // Two equal grids mix to the same at any point of an ease: no dip.
        assertContentEquals(a, mixGrids(a, a.copyOf(), 0.37f))
    }

    @Test
    fun theLightFollowsANewFrameHalfOfTheWayEachTime() {
        var light = IntArray(1) { 0xFF000000.toInt() }
        val white = IntArray(1) { 0xFFFFFFFF.toInt() }
        val greens = mutableListOf<Int>()
        repeat(4) {
            light = mixGrids(light, white, AMBIENT_FOLLOW)
            greens += (light[0] ushr 8) and 0xFF
        }
        assertEquals(listOf(128, 192, 224, 240), greens)
    }

    @Test
    fun anEaseIsLinearFromItsStartToItsEnd() {
        assertEquals(0f, ambientEaseProgress(nowMs = 900, startMs = 1_000, lengthMs = 1_800))
        assertEquals(0f, ambientEaseProgress(nowMs = 1_000, startMs = 1_000, lengthMs = 1_800))
        assertEquals(0.5f, ambientEaseProgress(nowMs = 1_900, startMs = 1_000, lengthMs = 1_800))
        assertEquals(1f, ambientEaseProgress(nowMs = 2_800, startMs = 1_000, lengthMs = 1_800))
        assertEquals(1f, ambientEaseProgress(nowMs = 9_000, startMs = 1_000, lengthMs = 1_800))
        assertEquals(1f, ambientEaseProgress(nowMs = 1_000, startMs = 1_000, lengthMs = 0))
    }
}
