package cloud.nalet.chino.mobile.ui.player

import java.io.ByteArrayOutputStream

/*
 * PGS made in the tests: segments of display sets, as a .sup file has them
 * (each behind a "PG" header with its PTS) or bare, as a Matroska sample
 * has them. The plane is 1920x1080; an object is a box of one colour.
 */

internal const val PLANE_WIDTH = 1920
internal const val PLANE_HEIGHT = 1080

internal const val PALETTE = 0x14
internal const val OBJECT = 0x15
internal const val COMPOSITION = 0x16
internal const val WINDOW = 0x17
internal const val END = 0x80

internal const val STATE_NORMAL = 0x00
internal const val STATE_ACQUISITION_POINT = 0x40
internal const val STATE_EPOCH_START = 0x80

private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

private fun u24(v: Int) = byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

/** An object a composition shows, where, whether it is forced, and the part
 *  of it shown ([crop]: x, y, width, height of the object) where it is cropped. */
internal class Shown(val objectId: Int, val x: Int, val y: Int, val forced: Boolean = false, val crop: IntArray? = null)

/** A composition's payload: the plane, its number and state, the palette,
 *  the objects it shows. */
internal fun composition(number: Int, state: Int, vararg shown: Shown, paletteId: Int = 0, paletteUpdate: Boolean = false): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(u16(PLANE_WIDTH))
    out.write(u16(PLANE_HEIGHT))
    out.write(0x10) // frame rate
    out.write(u16(number))
    out.write(state)
    out.write(if (paletteUpdate) 0x80 else 0)
    out.write(paletteId)
    out.write(shown.size)
    for (s in shown) {
        out.write(u16(s.objectId))
        out.write(0) // window
        out.write((if (s.crop != null) 0x80 else 0) or (if (s.forced) 0x40 else 0))
        out.write(u16(s.x))
        out.write(u16(s.y))
        s.crop?.forEach { out.write(u16(it)) }
    }
    return out.toByteArray()
}

/** A window definition's payload: one window over the whole plane. */
internal fun window(): ByteArray = byteArrayOf(1, 0) + u16(0) + u16(0) + u16(PLANE_WIDTH) + u16(PLANE_HEIGHT)

/** A palette entry: its index, Y, Cr, Cb and alpha. */
internal class Entry(val index: Int, val y: Int, val cr: Int, val cb: Int, val alpha: Int)

/** White, opaque: Y 235, no colour. */
internal fun white(index: Int, alpha: Int = 0xFF) = Entry(index, 235, 128, 128, alpha)

/** Yellow, opaque (BT.601). */
internal fun yellow(index: Int) = Entry(index, 210, 146, 16, 0xFF)

/** A palette's payload. */
internal fun palette(id: Int, version: Int, vararg entries: Entry): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(id)
    out.write(version)
    for (e in entries) {
        out.write(e.index)
        out.write(e.y)
        out.write(e.cr)
        out.write(e.cb)
        out.write(e.alpha)
    }
    return out.toByteArray()
}

/** A box [width] x [height] of the palette's [colour], run-length coded as
 *  PGS codes a line: one run of the colour, then the end of the line. */
internal fun box(width: Int, height: Int, colour: Int): ByteArray {
    val out = ByteArrayOutputStream()
    repeat(height) {
        out.write(0)
        out.write(0xC0 or (width shr 8))
        out.write(width and 0xFF)
        out.write(colour)
        out.write(0)
        out.write(0)
    }
    return out.toByteArray()
}

/** A box [width] x [height], its upper [upperRows] rows of the palette's
 *  [upper] colour, the rest of [lower]. */
internal fun twoTone(width: Int, height: Int, upperRows: Int, upper: Int, lower: Int): ByteArray =
    box(width, upperRows, upper) + box(width, height - upperRows, lower)

/** An object's payloads: one segment, or [parts] of it, the first with the
 *  object's size and the length of its data ([data]: a box of [colour]). */
internal fun objectSegments(
    id: Int,
    width: Int,
    height: Int,
    colour: Int,
    parts: Int = 1,
    version: Int = 0,
    data: ByteArray = box(width, height, colour),
): List<ByteArray> {
    val cut = (0..parts).map { data.size * it / parts }
    return (0 until parts).map { i ->
        val out = ByteArrayOutputStream()
        out.write(u16(id))
        out.write(version)
        out.write((if (i == 0) 0x80 else 0) or (if (i == parts - 1) 0x40 else 0))
        if (i == 0) {
            out.write(u24(data.size + 4))
            out.write(u16(width))
            out.write(u16(height))
        }
        out.write(data, cut[i], cut[i + 1] - cut[i])
        out.toByteArray()
    }
}

/** An object's payload that claims a size no picture can have (65535 x
 *  65535) for one pixel of data. */
internal fun brokenObject(id: Int): ByteArray {
    val data = box(1, 1, colour = 1)
    return u16(id) + byteArrayOf(0, 0xC0.toByte()) + u24(data.size + 4) + u16(0xFFFF) + u16(0xFFFF) + data
}

/** An object's payload of a size a picture can have, [width] x [height],
 *  whose one run (16383 pixels of colour 1) goes past its last pixel:
 *  PgsParser throws on it. */
internal fun overrunObject(id: Int, width: Int, height: Int): ByteArray {
    val data = byteArrayOf(0, 0xFF.toByte(), 0xFF.toByte(), 1)
    return u16(id) + byteArrayOf(0, 0xC0.toByte()) + u24(data.size + 4) + u16(width) + u16(height) + data
}

/** A stream of segments, as a .sup file has them ([sup], each behind its
 *  header) or bare ([bare]). */
internal class Pgs {
    private class Segment(val ptsUs: Long, val type: Int, val payload: ByteArray)

    private val segments = ArrayList<Segment>()

    fun segment(atUs: Long, type: Int, payload: ByteArray = ByteArray(0)) = apply { segments += Segment(atUs, type, payload) }

    /** A display set at [atUs]: the composition, a window, [between] (the
     *  palettes and objects), an end. */
    fun set(atUs: Long, composition: ByteArray, vararg between: Pair<Int, ByteArray>) = apply {
        segment(atUs, COMPOSITION, composition)
        segment(atUs, WINDOW, window())
        for ((type, payload) in between) segment(atUs, type, payload)
        segment(atUs, END)
    }

    /** A set that shows [shown] with object [objectId] a box of [width] x
     *  [height] in white, starting an epoch. */
    fun shows(atUs: Long, number: Int, width: Int, height: Int, x: Int, y: Int, objectId: Int = 0) =
        set(
            atUs,
            composition(number, STATE_EPOCH_START, Shown(objectId, x, y)),
            PALETTE to palette(0, 0, white(1)),
            *objectSegments(objectId, width, height, colour = 1).map { OBJECT to it }.toTypedArray(),
        )

    /** A set that clears the screen. */
    fun clears(atUs: Long, number: Int) = set(atUs, composition(number, STATE_NORMAL))

    /** The .sup file: each segment behind "PG", its PTS (90 kHz) and a DTS of 0. */
    fun sup(): ByteArray {
        val out = ByteArrayOutputStream()
        for (s in segments) {
            val pts = s.ptsUs * 9 / 100
            out.write('P'.code)
            out.write('G'.code)
            out.write(u16((pts shr 16).toInt()))
            out.write(u16(pts.toInt() and 0xFFFF))
            out.write(ByteArray(4))
            out.write(s.type)
            out.write(u16(s.payload.size))
            out.write(s.payload)
        }
        return out.toByteArray()
    }

    /** The segments bare, as a Matroska sample carries a display set. */
    fun bare(): ByteArray {
        val out = ByteArrayOutputStream()
        for (s in segments) {
            out.write(s.type)
            out.write(u16(s.payload.size))
            out.write(s.payload)
        }
        return out.toByteArray()
    }
}

/** The track the tests play: a line at 1 s, cleared at 3 s; another at 4 s,
 *  replaced at 6.5 s by one at the top, which is cleared at 8 s. */
internal fun threeLines(): Pgs = Pgs()
    .shows(1_000_000, number = 0, width = 400, height = 40, x = 760, y = 900)
    .clears(3_000_000, number = 1)
    .shows(4_000_000, number = 2, width = 600, height = 40, x = 660, y = 950)
    .set(
        6_500_000,
        composition(3, STATE_ACQUISITION_POINT, Shown(1, 100, 60)),
        PALETTE to palette(0, 0, white(1), yellow(2)),
        *objectSegments(1, 200, 30, colour = 2).map { OBJECT to it }.toTypedArray(),
    )
    .clears(8_000_000, number = 4)
