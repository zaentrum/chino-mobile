package cloud.nalet.chino.mobile.ui.zap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Zap card's ambient light, calm: the clip's colours, followed slowly.
 *
 * The platform hands it a small copy of the frame on screen about once a
 * second ([offer]). Each is averaged into a coarse grid ([AMBIENT_COLS] x
 * [AMBIENT_ROWS]), so no detail of the picture is left in it, and averaged
 * again with the light before ([AMBIENT_FOLLOW]), so a cut moves the light
 * part of the way. The light then eases to it over [AMBIENT_FADE_MS],
 * linearly: [over] fades in over [under], which stays opaque, so the light
 * never dips on its way. A new grid mid-ease starts from the colours on
 * screen ([mixGrids] of the two, at the ease's progress), never from either
 * end. The card's first light fades in over its backdrop in
 * [AMBIENT_FIRST_FADE_MS], as a card change does.
 *
 * Per frame only [overAlpha] changes, read in the draw phase; the grid work
 * is once a sample, off the main thread but for the mix of two 16 x 9 grids.
 */
@Stable
internal class ZapAmbientLight {
    /** The light under the ease, opaque; null while the backdrop is. */
    var under: ImageBitmap? by mutableStateOf(null)
        private set

    /** The light the ease goes to, drawn over [under] at [overAlpha]. */
    var over: ImageBitmap? by mutableStateOf(null)
        private set

    /** How far [over] has faded in, 0..1. Changes every frame of an ease:
     *  read it in the draw phase (a graphicsLayer), not in composition. */
    var overAlpha: Float by mutableFloatStateOf(0f)
        private set

    private val samples = Channel<ImageBitmap>(Channel.CONFLATED)

    /** A copy of the frame on screen. Any rate: one not taken up yet is
     *  replaced by the next. */
    fun offer(frame: ImageBitmap) {
        samples.trySend(frame)
    }

    /** Turns the copies into light while the card is composed. */
    suspend fun run(): Unit = coroutineScope {
        var underGrid: IntArray? = null
        var overGrid: IntArray? = null
        var target: IntArray? = null
        var easeStart = 0L
        var easeLength = AMBIENT_FIRST_FADE_MS
        var ease: Job? = null
        for (frame in samples) {
            val grid = withContext(Dispatchers.Default) { averageGrid(frame) } ?: continue
            val next = target?.let { mixGrids(it, grid, AMBIENT_FOLLOW) } ?: grid
            target = next
            val nextImage = withContext(Dispatchers.Default) { gridImage(next) }
            // The first light fades in whole over the backdrop, then takes its
            // place under the next.
            if (underGrid == null) ease?.join()
            ease?.cancel()
            withFrameMillis { now ->
                val from = overGrid
                if (from == null) {
                    easeLength = AMBIENT_FIRST_FADE_MS
                } else {
                    val shown = underGrid?.let { mixGrids(it, from, ambientEaseProgress(now, easeStart, easeLength)) }
                    underGrid = shown ?: from
                    under = if (shown == null) over else gridImage(shown)
                    easeLength = AMBIENT_FADE_MS
                }
                overGrid = next
                over = nextImage
                overAlpha = 0f
                easeStart = now
            }
            ease = launch {
                while (true) {
                    val progress = withFrameMillis { now -> ambientEaseProgress(now, easeStart, easeLength) }
                    overAlpha = progress
                    if (progress >= 1f) break
                }
            }
        }
    }
}

/** A card's [ZapAmbientLight], kept for as long as [key] (its title) is. */
@Composable
internal fun rememberZapAmbientLight(key: Any): ZapAmbientLight {
    val light = remember(key) { ZapAmbientLight() }
    LaunchedEffect(light) { light.run() }
    return light
}

/** The grid the light is made of: a 16:9 frame in cells of its colour. */
internal const val AMBIENT_COLS = 16
internal const val AMBIENT_ROWS = 9

/** How far the light moves toward a new frame's grid: half of the way. */
internal const val AMBIENT_FOLLOW = 0.5f

/** How long the light eases toward a new grid. */
internal const val AMBIENT_FADE_MS = 1800L

/** How long a card's first light takes over from its backdrop. */
internal const val AMBIENT_FIRST_FADE_MS = 600L

/** How far an ease that started at [startMs] and lasts [lengthMs] is at
 *  [nowMs]: linear, 0..1. */
internal fun ambientEaseProgress(nowMs: Long, startMs: Long, lengthMs: Long): Float =
    if (lengthMs <= 0L) 1f else ((nowMs - startMs).toFloat() / lengthMs).coerceIn(0f, 1f)

/** [frame] averaged into the light's grid; null for an empty frame. */
internal fun averageGrid(frame: ImageBitmap): IntArray? {
    if (frame.width <= 0 || frame.height <= 0) return null
    val pixels = IntArray(frame.width * frame.height)
    frame.readPixels(pixels)
    return averageGrid(pixels, frame.width, frame.height)
}

/**
 * [pixels] (ARGB, [width] x [height], row by row) averaged into [cols] x
 * [rows] opaque cells, each the mean of the pixels it covers. A frame smaller
 * than the grid in a direction lends a cell its nearest pixel.
 */
internal fun averageGrid(
    pixels: IntArray,
    width: Int,
    height: Int,
    cols: Int = AMBIENT_COLS,
    rows: Int = AMBIENT_ROWS,
): IntArray {
    val grid = IntArray(cols * rows)
    for (row in 0 until rows) {
        val y0 = row * height / rows
        val y1 = maxOf(y0 + 1, (row + 1) * height / rows)
        for (col in 0 until cols) {
            val x0 = col * width / cols
            val x1 = maxOf(x0 + 1, (col + 1) * width / cols)
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0L
            for (y in y0 until minOf(y1, height)) {
                for (x in x0 until minOf(x1, width)) {
                    val p = pixels[y * width + x]
                    r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
            }
            grid[row * cols + col] = if (n == 0L) OPAQUE_BLACK else argb(r / n, g / n, b / n)
        }
    }
    return grid
}

/** [a] moved toward [b] by [t] (0 = [a], 1 = [b]), cell by cell, channel by
 *  channel; opaque. */
internal fun mixGrids(a: IntArray, b: IntArray, t: Float): IntArray {
    require(a.size == b.size) { "grids of ${a.size} and ${b.size} cells" }
    val f = t.coerceIn(0f, 1f)
    return IntArray(a.size) { i ->
        val p = a[i]
        val q = b[i]
        argb(
            mix((p ushr 16) and 0xFF, (q ushr 16) and 0xFF, f),
            mix((p ushr 8) and 0xFF, (q ushr 8) and 0xFF, f),
            mix(p and 0xFF, q and 0xFF, f),
        )
    }
}

/** [grid] as a [AMBIENT_COLS] x [AMBIENT_ROWS] bitmap, a pixel a cell: the card
 *  draws it blown up, smoothed and blurred. */
internal fun gridImage(grid: IntArray, cols: Int = AMBIENT_COLS, rows: Int = AMBIENT_ROWS): ImageBitmap {
    val image = ImageBitmap(cols, rows)
    val canvas = Canvas(image)
    val paint = Paint().apply {
        isAntiAlias = false
        blendMode = BlendMode.Src
    }
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            paint.color = Color(grid[row * cols + col])
            canvas.drawRect(Rect(col.toFloat(), row.toFloat(), col + 1f, row + 1f), paint)
        }
    }
    return image
}

private const val OPAQUE_BLACK = 0xFF000000.toInt()

private fun mix(a: Int, b: Int, t: Float): Long = (a + (b - a) * t).roundToInt().toLong()

private fun argb(r: Long, g: Long, b: Long): Int =
    OPAQUE_BLACK or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
