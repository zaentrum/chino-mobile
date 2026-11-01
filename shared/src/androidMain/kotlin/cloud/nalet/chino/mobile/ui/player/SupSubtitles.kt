package cloud.nalet.chino.mobile.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.Log
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SimpleSubtitleDecoder
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.pgs.PgsParser
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/*
 * PGS subtitles (a Blu-ray's pictures) as the server keeps them: a .sup file,
 * a whole track in one stream of segments, each behind a 13-byte header -
 * "PG", the presentation time (PTS, 90 kHz), the decoding time, then the
 * segment's type and length. Media3's PgsParser reads one display set's bare
 * segments, as a Matroska file carries them, a set to a sample. Side-loaded,
 * a subtitle reaches it as one sample, the whole file: it reads the "P" of
 * the first header as a segment's type, and nothing ever shows.
 *
 * SupParser reads the headers. A presentation composition starts a display
 * set, an end segment closes it; each set's segments go, headers stripped, to
 * Media3's own PgsParser, so the pictures are Media3's as before, and each set
 * shows from its PTS to the next set's - a set without a picture, the one that
 * ends a line, clears the screen. A set can show an object an earlier set of
 * its epoch brought (a fade changes only the palette) and more than one
 * object: each is decoded with the palette and the object it names, cut to
 * the part its composition shows (cropping), and the pictures of one half of
 * the plane are drawn as one, so that they keep their places to each other
 * whatever the shape of the picture played (inItsShape). A picture is drawn
 * only inside its plane, and a plane no larger than an UHD frame: no picture
 * is ever too large to draw. Input that is not a .sup file - a Matroska
 * sample's bare segments - goes to PgsParser as it comes.
 *
 * A whole track is tens of megabytes. The player hands Media3 the file's
 * place on disk rather than its bytes (SupFiles.kt): SupParser reads the
 * file's segment headers into an index - each set's time, its compositions
 * and palettes, where its objects' bytes are - and reads an object's bytes
 * from the file only as its set is drawn. Bytes handed in place of a file
 * are read the same way, from a copy.
 *
 * Media3 reads a subtitle one of two ways, and both use it: SubtitleParsers
 * where a media source parses the subtitle as it loads it, SubtitleDecoders
 * where the text renderer decodes it - the player's way with its side-loaded
 * subtitles (legacy decoding). There the pictures are made one display set
 * at a time, as each is shown: a whole film's at once would not fit in
 * memory.
 */

/** Whether [format] is PGS. */
private fun isPgs(format: Format): Boolean = format.sampleMimeType == MimeTypes.APPLICATION_PGS

/**
 * Media3's subtitle parsers ([DefaultSubtitleParserFactory]), with PGS read by
 * [SupParser]: a .sup file, or the bare display set of a Matroska sample.
 * What a media source that parses its subtitles as it loads them is given.
 */
internal class SubtitleParsers(
    private val defaults: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean = isPgs(format) || defaults.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        if (isPgs(format)) SupParser.CUE_REPLACEMENT_BEHAVIOR else defaults.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser = if (isPgs(format)) SupParser() else defaults.create(format)
}

/**
 * The text renderer's decoders, for the subtitles it decodes itself (legacy
 * decoding): Media3's own ([SubtitleDecoderFactory.DEFAULT]), but PGS, which
 * a parser of [parsers] reads - [SupParser], a .sup file's display sets drawn
 * as each is shown.
 */
internal class SubtitleDecoders(
    private val parsers: SubtitleParser.Factory = SubtitleParsers(),
) : SubtitleDecoderFactory {
    override fun supportsFormat(format: Format): Boolean =
        (isPgs(format) && parsers.supportsFormat(format)) || SubtitleDecoderFactory.DEFAULT.supportsFormat(format)

    override fun createDecoder(format: Format): SubtitleDecoder =
        if (isPgs(format) && parsers.supportsFormat(format)) {
            ParsingSubtitleDecoder(parsers.create(format))
        } else {
            SubtitleDecoderFactory.DEFAULT.createDecoder(format)
        }
}

/** A decoder that hands a sample to [parser], as Media3's own does for the
 *  formats its parsers read. */
private class ParsingSubtitleDecoder(private val parser: SubtitleParser) : SimpleSubtitleDecoder("SupParserDecoder") {
    override fun decode(data: ByteArray, length: Int, reset: Boolean): Subtitle {
        if (reset) parser.reset()
        return parser.parseToLegacySubtitle(data, 0, length)
    }
}

/**
 * PGS from a .sup file (see the top of this file) - on disk ([SupFileRef])
 * or in the bytes handed in - or from a sample of bare segments, which
 * [PgsParser] reads as it always has.
 */
internal class SupParser : SubtitleParser {
    private val bare = PgsParser()

    override fun getCueReplacementBehavior(): Int = CUE_REPLACEMENT_BEHAVIOR

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        // The bytes are read before this returns: no copy of them.
        val source = SupSource.of(data, offset, length, keep = false)
        if (source == null) {
            bare.parse(data, offset, length, outputOptions, output)
            return
        }
        emitDisplaySets(setsOf(source), outputOptions, output, SupPictures(source)::cuesOf)
    }

    /** A .sup file's display sets, each drawn only as it is shown; bare
     *  segments as [PgsParser] makes them. */
    override fun parseToLegacySubtitle(data: ByteArray, offset: Int, length: Int): Subtitle {
        // The subtitle outlives the decoder's buffer: bytes handed in are copied.
        val source = SupSource.of(data, offset, length, keep = true) ?: return bare.parseToLegacySubtitle(data, offset, length)
        return SupSubtitle(source, setsOf(source))
    }

    override fun reset() = bare.reset()

    /** [source]'s display sets; none where its file can't be read - the
     *  decoder's thread never sees the error. */
    private fun setsOf(source: SupSource): List<SupDisplaySet> = try {
        source.displaySets()
    } catch (e: IOException) {
        Log.w(TAG, "A PGS file can't be read: it shows nothing", e)
        emptyList()
    }

    companion object {
        /** Each display set takes the place of the one before, as in [PgsParser]. */
        const val CUE_REPLACEMENT_BEHAVIOR: Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

        private const val TAG = "SupParser"
    }
}

/** Where a .sup file's bytes are: in a file on disk, read as they are
 *  needed, or in memory. */
internal abstract class SupSource {
    /** A reader of the bytes, for one pass or one set's pictures. */
    abstract fun open(): SupReader

    /** The file's display sets, in the order they show ([readDisplaySets]). */
    abstract fun displaySets(): List<SupDisplaySet>

    /** A .sup file on disk ([SupFileRef]): its index read once and kept
     *  for the last files read ([SupIndexCache]). */
    class OnDisk(val file: File) : SupSource() {
        override fun open(): SupReader = FileReader(file)

        override fun displaySets(): List<SupDisplaySet> = SupIndexCache.setsOf(file) { open().use(::readDisplaySets) }
    }

    /** A .sup file's bytes. */
    class InMemory(private val data: ByteArray, private val offset: Int, private val length: Int) : SupSource() {
        override fun open(): SupReader = ArrayReader(data, offset, length)

        override fun displaySets(): List<SupDisplaySet> = open().use(::readDisplaySets)
    }

    companion object {
        /** What [data] holds: a reference to a .sup file on disk, a .sup
         *  file's bytes ([keep]: copied, to be read after this returns), or
         *  bare segments (null). */
        fun of(data: ByteArray, offset: Int, length: Int, keep: Boolean): SupSource? {
            SupFileRef.fileOf(data, offset, length)?.let { return OnDisk(it) }
            if (!isSupFile(data, offset, length)) return null
            return if (keep) InMemory(data.copyOfRange(offset, offset + length), 0, length) else InMemory(data, offset, length)
        }
    }
}

/** Random access to a .sup file's bytes. Every read is inside the file:
 *  the reader of the index checks each position against [length] first. */
internal abstract class SupReader : Closeable {
    abstract val length: Long

    /** The byte at [position]. */
    abstract fun u8(position: Long): Int

    /** Copies [count] bytes from [position] into [into] at [at]. */
    abstract fun read(position: Long, into: ByteArray, at: Int, count: Int)

    fun u16(position: Long): Int = (u8(position) shl 8) or u8(position + 1)

    fun u32(position: Long): Long = (u16(position).toLong() shl 16) or u16(position + 2).toLong()

    fun copy(position: Long, count: Int): ByteArray = ByteArray(count).also { read(position, it, 0, count) }

    override fun close() = Unit
}

private class ArrayReader(private val data: ByteArray, private val offset: Int, size: Int) : SupReader() {
    override val length: Long = size.toLong()

    override fun u8(position: Long): Int = data[offset + position.toInt()].toInt() and 0xFF

    override fun read(position: Long, into: ByteArray, at: Int, count: Int) {
        data.copyInto(into, at, offset + position.toInt(), offset + position.toInt() + count)
    }
}

/** A file read through a window of [WINDOW] bytes: the segment headers of a
 *  pass over the file come from a few reads, an object's bytes from one. */
private class FileReader(file: File) : SupReader() {
    private val file = RandomAccessFile(file, "r")
    override val length: Long = this.file.length()
    private val window = ByteArray(WINDOW)
    private var windowAt = 0L
    private var windowLength = 0

    private fun load(position: Long) {
        file.seek(position)
        var n = 0
        while (n < WINDOW) {
            val read = file.read(window, n, WINDOW - n)
            if (read < 0) break
            n += read
        }
        windowAt = position
        windowLength = n
    }

    override fun u8(position: Long): Int {
        if (position < windowAt || position >= windowAt + windowLength) load(position)
        val i = (position - windowAt).toInt()
        if (i >= windowLength) throw IOException("A .sup file ends before byte $position")
        return window[i].toInt() and 0xFF
    }

    override fun read(position: Long, into: ByteArray, at: Int, count: Int) {
        if (position >= windowAt && position + count <= windowAt + windowLength) {
            window.copyInto(into, at, (position - windowAt).toInt(), (position - windowAt).toInt() + count)
        } else {
            file.seek(position)
            file.readFully(into, at, count)
        }
    }

    override fun close() = file.close()

    companion object {
        const val WINDOW = 64 * 1024
    }
}

/** The index of the last .sup files read from disk: a seek has Media3 hand
 *  the text renderer the file again, and its index need not be read again. */
internal object SupIndexCache {
    private const val KEEP = 2

    private data class Key(val path: String, val length: Long, val modified: Long)

    private val entries = LinkedHashMap<Key, List<SupDisplaySet>>(4, 0.75f, true)

    @Synchronized
    fun setsOf(file: File, read: () -> List<SupDisplaySet>): List<SupDisplaySet> {
        val key = Key(file.absolutePath, file.length(), file.lastModified())
        entries[key]?.let { return it }
        return read().also { sets ->
            entries[key] = sets
            while (entries.size > KEEP) entries.remove(entries.keys.first())
        }
    }
}

/** One display set of a .sup file: when it shows, and its pictures. None:
 *  it clears the screen. */
internal class SupDisplaySet(val startUs: Long, val pictures: List<SupPicture>)

/** The bytes a set's pictures keep in memory (its compositions and
 *  palettes); their objects' bytes stay in the file. */
internal fun heldBytes(sets: List<SupDisplaySet>): Long {
    val palettes = HashSet<Any>()
    var bytes = 0L
    for (set in sets) {
        for (picture in set.pictures) {
            bytes += picture.composition.size + 12L * picture.fragments
            if (palettes.add(picture.palette)) bytes += picture.palette.size
        }
    }
    return bytes
}

/**
 * One picture of a display set: an object [objectWidth] x [objectHeight], the
 * part of it its composition shows ([cutX], [cutY], [width] x [height]) at
 * ([x], [y]) of a [planeWidth] x [planeHeight] plane, and what PgsParser
 * decodes it from - a composition of that one object, the palette it names,
 * the object's segments (where they are in the file), an end.
 */
internal class SupPicture(
    val planeWidth: Int,
    val planeHeight: Int,
    val x: Int,
    val y: Int,
    val cutX: Int,
    val cutY: Int,
    val width: Int,
    val height: Int,
    val objectWidth: Int,
    val objectHeight: Int,
    val composition: ByteArray,
    val palette: ByteArray,
    private val fragmentAt: LongArray,
    private val fragmentLength: IntArray,
) {
    val fragments: Int get() = fragmentAt.size

    /** Whether the composition shows only part of the object. */
    val cut: Boolean get() = cutX != 0 || cutY != 0 || width != objectWidth || height != objectHeight

    /** The segments' bytes, as PgsParser reads them. */
    val size: Int = composition.size + palette.size + fragmentLength.sumOf { 3 + it } + END_SEGMENT.size

    /** Writes the segments into [out], which holds [size] bytes or more,
     *  the object's bytes read by [reader]. */
    fun writeTo(reader: SupReader, out: ByteArray) {
        composition.copyInto(out, 0)
        var at = composition.size
        palette.copyInto(out, at)
        at += palette.size
        for (i in fragmentAt.indices) {
            val length = fragmentLength[i]
            out[at] = SEGMENT_OBJECT.toByte()
            out[at + 1] = (length shr 8).toByte()
            out[at + 2] = length.toByte()
            reader.read(fragmentAt[i], out, at + 3, length)
            at += 3 + length
        }
        END_SEGMENT.copyInto(out, at)
    }
}

/** Makes a display set's cues with a [PgsParser] of its own, the objects'
 *  bytes read from [source]: each picture cut to the part its composition
 *  shows, the pictures of one half of the plane one cue. A picture that can't
 *  be drawn (a broken object, one too large for the memory, a file gone) is
 *  left out; it never stops the playback. Not for two threads at once. */
internal class SupPictures(private val source: SupSource) {
    private val pgs = PgsParser()
    private var scratch = ByteArray(0)

    /** A picture drawn: the bitmap of the part shown, where on the plane. */
    private class Drawn(val picture: SupPicture, val bitmap: Bitmap)

    fun cuesOf(set: SupDisplaySet): List<Cue> {
        if (set.pictures.isEmpty()) return emptyList()
        val drawn = ArrayList<Drawn>(set.pictures.size)
        try {
            source.open().use { reader ->
                for (picture in set.pictures) {
                    try {
                        draw(reader, picture)?.let { drawn += Drawn(picture, it) }
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "A picture of the PGS display set at ${set.startUs} us is not drawn", e)
                    } catch (e: OutOfMemoryError) {
                        Log.w(TAG, "A picture of the PGS display set at ${set.startUs} us is not drawn: out of memory")
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "The PGS display set at ${set.startUs} us is not drawn: its file can't be read", e)
        }
        return try {
            inHalves(drawn)
        } catch (e: RuntimeException) {
            Log.w(TAG, "The PGS display set at ${set.startUs} us is not drawn", e)
            emptyList()
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "The PGS display set at ${set.startUs} us is not drawn: out of memory")
            emptyList()
        }
    }

    /** [picture]'s bitmap, the part its composition shows; null where the
     *  object makes none. */
    private fun draw(reader: SupReader, picture: SupPicture): Bitmap? {
        if (scratch.size < picture.size) scratch = ByteArray(picture.size)
        picture.writeTo(reader, scratch)
        var bitmap: Bitmap? = null
        pgs.parse(scratch, 0, picture.size, SubtitleParser.OutputOptions.allCues()) { cues ->
            cues.cues.firstOrNull()?.bitmap?.let { bitmap = it }
        }
        val whole = bitmap ?: return null
        if (whole.width != picture.objectWidth || whole.height != picture.objectHeight) return null
        return if (picture.cut) Bitmap.createBitmap(whole, picture.cutX, picture.cutY, picture.width, picture.height) else whole
    }

    /** The pictures drawn, those in the plane's upper half one cue, those in
     *  its lower half another: each keeps its place to the others of its half
     *  however the look shapes the cue (inItsShape). */
    private fun inHalves(drawn: List<Drawn>): List<Cue> {
        if (drawn.isEmpty()) return emptyList()
        val (lower, upper) = drawn.partition { it.picture.y + it.picture.height / 2f > it.picture.planeHeight / 2f }
        return listOfNotNull(oneCue(upper), oneCue(lower))
    }

    private fun oneCue(parts: List<Drawn>): Cue? {
        if (parts.isEmpty()) return null
        val first = parts[0].picture
        if (parts.size == 1) return cueOf(parts[0].bitmap, first.x, first.y, first.width, first.height, first.planeWidth, first.planeHeight)
        val left = parts.minOf { it.picture.x }
        val top = parts.minOf { it.picture.y }
        val right = parts.maxOf { it.picture.x + it.picture.width }
        val bottom = parts.maxOf { it.picture.y + it.picture.height }
        // Inside the plane, as every picture is (readDisplaySets): never larger than it.
        val merged = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        for (part in parts) canvas.drawBitmap(part.bitmap, (part.picture.x - left).toFloat(), (part.picture.y - top).toFloat(), null)
        return cueOf(merged, left, top, right - left, bottom - top, first.planeWidth, first.planeHeight)
    }

    /** A picture [width] x [height] at ([x], [y]) of its plane, as PgsParser
     *  places one. */
    private fun cueOf(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int, planeWidth: Int, planeHeight: Int): Cue =
        Cue.Builder()
            .setBitmap(bitmap)
            .setPosition(x.toFloat() / planeWidth)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine(y.toFloat() / planeHeight, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setSize(width.toFloat() / planeWidth)
            .setBitmapHeight(height.toFloat() / planeHeight)
            .build()

    private companion object {
        const val TAG = "SupPictures"
    }
}

/**
 * A .sup file's display sets as a subtitle the text renderer reads: each set
 * an event at its start, its pictures made when it is shown - one set's at a
 * time, the last one kept, the objects' bytes read from [source] then.
 */
private class SupSubtitle(source: SupSource, read: List<SupDisplaySet>) : Subtitle {
    // A file without a display set: one event that shows nothing.
    private val sets = read.ifEmpty { listOf(SupDisplaySet(0L, emptyList())) }
    private val startsUs = LongArray(sets.size) { sets[it].startUs }
    private val pictures = SupPictures(source)
    private var shownIndex = -1
    private var shown: List<Cue> = emptyList()

    override fun getNextEventTimeIndex(timeUs: Long): Int {
        val next = firstStartAfter(timeUs)
        return if (next < startsUs.size) next else C.INDEX_UNSET
    }

    override fun getEventTimeCount(): Int = startsUs.size

    override fun getEventTime(index: Int): Long = startsUs[index]

    @Synchronized
    override fun getCues(timeUs: Long): List<Cue> {
        val index = firstStartAfter(timeUs) - 1
        if (index < 0) return emptyList()
        if (index != shownIndex) {
            shown = pictures.cuesOf(sets[index])
            shownIndex = index
        }
        return shown
    }

    /** The index of the first set that starts after [timeUs]; the count when none does. */
    private fun firstStartAfter(timeUs: Long): Int {
        var low = 0
        var high = startsUs.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (startsUs[mid] <= timeUs) low = mid + 1 else high = mid
        }
        return low
    }
}

/**
 * Hands on [sets] as [SubtitleParser.parse] does: each set with pictures
 * from its start to the next set's (to the end of the track for the last),
 * a set without one not at all - the set before ends where it starts. From
 * [SubtitleParser.OutputOptions.startTimeUs] on where the options give one,
 * the set on screen then cut to start there; with
 * [SubtitleParser.OutputOptions.outputAllCues] the ones before it after
 * them.
 */
internal fun emitDisplaySets(
    sets: List<SupDisplaySet>,
    options: SubtitleParser.OutputOptions,
    output: Consumer<CuesWithTiming>,
    cuesOf: (SupDisplaySet) -> List<Cue>,
) {
    val count = sets.size
    fun endOf(i: Int): Long = if (i + 1 < count) sets[i + 1].startUs else C.TIME_UNSET
    fun emit(i: Int, fromUs: Long, toUs: Long) {
        if (toUs != C.TIME_UNSET && toUs <= fromUs) return
        val cues = cuesOf(sets[i])
        if (cues.isEmpty()) return
        output.accept(CuesWithTiming(cues, fromUs, if (toUs == C.TIME_UNSET) C.TIME_UNSET else toUs - fromUs))
    }
    val from = options.startTimeUs
    if (from == C.TIME_UNSET) {
        for (i in 0 until count) emit(i, sets[i].startUs, endOf(i))
        return
    }
    val first = sets.indexOfFirst { it.startUs >= from }.let { if (it < 0) count else it }
    // The set on screen at `from`, begun before it.
    val showing = (first - 1).takeIf { it >= 0 && (endOf(it) == C.TIME_UNSET || endOf(it) > from) }
    if (showing != null) emit(showing, from, endOf(showing))
    for (i in first until count) emit(i, sets[i].startUs, endOf(i))
    if (options.outputAllCues) {
        for (i in 0 until (showing ?: first)) emit(i, sets[i].startUs, endOf(i))
        if (showing != null) emit(showing, sets[showing].startUs, from)
    }
}

// The .sup file: its header, its segment types, the composition states.
private const val SUP_HEADER = 13
private const val SEGMENT_PALETTE = 0x14
private const val SEGMENT_OBJECT = 0x15
private const val SEGMENT_COMPOSITION = 0x16
private const val SEGMENT_WINDOW = 0x17
private const val SEGMENT_END = 0x80
private const val EPOCH_START = 0x80

/** A composition's fixed part: the plane's size, frame rate, number, state,
 *  palette update flag, palette and the count of objects. */
private const val COMPOSITION_HEADER = 11

/** The largest plane a picture is drawn on, in pixels: an UHD frame's. A
 *  Blu-ray's is 1920 x 1080; a picture, never larger than its plane, stays
 *  far below what a hardware canvas draws (100 MiB, 26 million pixels). */
internal const val MAX_PLANE_PIXELS = 3840 * 2160

/** An end segment, bare. */
private val END_SEGMENT = byteArrayOf(SEGMENT_END.toByte(), 0, 0)

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

private fun ByteArray.u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

/** Whether the bytes are a .sup file, segments behind "PG" headers, rather
 *  than bare segments (whose first byte is a segment type). */
internal fun isSupFile(data: ByteArray, offset: Int, length: Int): Boolean =
    length >= 2 && data[offset] == 'P'.code.toByte() && data[offset + 1] == 'G'.code.toByte()

/** One entry of a composition: the object it shows, where, the part of the
 *  object shown where it is cropped, as the composition's bytes have it. */
private class CompositionObject(val objectId: Int, val x: Int, val y: Int, val crop: IntArray?, val entry: ByteArray)

/** A presentation composition: the header and the objects it shows. One too
 *  short to read shows nothing: it clears the screen at its time. */
private class Composition(val ptsTicks: Long, val header: ByteArray, val objects: List<CompositionObject>) {
    val planeWidth: Int get() = header.u16(0)
    val planeHeight: Int get() = header.u16(2)
    val state: Int get() = header.u8(7)
    val paletteId: Int get() = header.u8(9)

    /** A composition of [shown] alone, bare. */
    fun of(shown: CompositionObject): ByteArray {
        val length = COMPOSITION_HEADER + shown.entry.size
        val out = ByteArray(3 + length)
        out[0] = SEGMENT_COMPOSITION.toByte()
        out[1] = (length shr 8).toByte()
        out[2] = length.toByte()
        header.copyInto(out, 3)
        out[3 + COMPOSITION_HEADER - 1] = 1
        shown.entry.copyInto(out, 3 + COMPOSITION_HEADER)
        return out
    }

    companion object {
        fun read(ptsTicks: Long, reader: SupReader, payload: Long, length: Int): Composition {
            if (length < COMPOSITION_HEADER) return Composition(ptsTicks, ByteArray(COMPOSITION_HEADER), emptyList())
            val data = reader.copy(payload, length)
            val objects = ArrayList<CompositionObject>()
            var at = COMPOSITION_HEADER
            for (n in 0 until data.u8(COMPOSITION_HEADER - 1)) {
                if (at + 8 > length) break
                // Cropped: four more values, the part of the object shown.
                val cropped = data.u8(at + 3) and 0x80 != 0
                val entryLength = if (cropped) 16 else 8
                if (at + entryLength > length) break
                val crop = if (cropped) intArrayOf(data.u16(at + 8), data.u16(at + 10), data.u16(at + 12), data.u16(at + 14)) else null
                objects += CompositionObject(data.u16(at), data.u16(at + 4), data.u16(at + 6), crop, data.copyOfRange(at, at + entryLength))
                at += entryLength
            }
            return Composition(ptsTicks, data.copyOfRange(0, COMPOSITION_HEADER), objects)
        }
    }
}

/** A palette of an epoch: its entries as the palette segments so far gave
 *  them (an update may give some of them only), bare as one segment. */
private class Palette(private val id: Int) {
    private val entries = ByteArray(256 * 4)
    private val given = BooleanArray(256)
    private var version = 0
    private var segment: ByteArray? = null

    fun update(data: ByteArray) {
        version = data.u8(1)
        var at = 2
        while (at + 5 <= data.size) {
            val index = data.u8(at)
            data.copyInto(entries, index * 4, at + 1, at + 5)
            given[index] = true
            at += 5
        }
        segment = null
    }

    fun segment(): ByteArray = segment ?: run {
        val count = given.count { it }
        val length = 2 + 5 * count
        val out = ByteArray(3 + length)
        out[0] = SEGMENT_PALETTE.toByte()
        out[1] = (length shr 8).toByte()
        out[2] = length.toByte()
        out[3] = id.toByte()
        out[4] = version.toByte()
        var at = 5
        for (index in 0 until 256) {
            if (!given[index]) continue
            out[at] = index.toByte()
            entries.copyInto(out, at + 1, index * 4, index * 4 + 4)
            at += 5
        }
        out.also { segment = it }
    }
}

/** An object of an epoch: its size, and where its segments' payloads are. */
private class ObjectBytes(val width: Int, val height: Int) {
    private var at = LongArray(1)
    private var length = IntArray(1)
    private var count = 0

    fun add(payload: Long, size: Int) {
        if (count == at.size) {
            at = at.copyOf(count * 2)
            length = length.copyOf(count * 2)
        }
        at[count] = payload
        length[count] = size
        count++
    }

    fun positions(): LongArray = at.copyOf(count)

    fun lengths(): IntArray = length.copyOf(count)
}

/** The PTS of a .sup header (90 kHz) in microseconds. */
private fun ticksToUs(ticks: Long): Long = ticks * 100 / 9

/**
 * The display sets of a .sup file, in the order they show. A composition
 * starts a set, an end segment closes it (as does the next composition, or
 * the end of the file, where one is missing). The palettes and objects of
 * an epoch hold until the next epoch starts: a set names the ones it shows,
 * from itself or an earlier set. A picture is kept only inside its plane, the
 * plane no larger than [MAX_PLANE_PIXELS] and the object no larger than the
 * plane. Bytes that are no header are skipped to the next one; so is a header
 * whose segment runs past the end of the file or into bytes that are no
 * header, where a header inside it starts segments that follow each other.
 * Of two sets at one time the later one counts.
 */
internal fun readDisplaySets(reader: SupReader): List<SupDisplaySet> {
    val end = reader.length
    val sets = ArrayList<SupDisplaySet>()
    val palettes = HashMap<Int, Palette>()
    val objects = HashMap<Int, ObjectBytes>()
    var open: Composition? = null

    fun close(composition: Composition) {
        val planeWidth = composition.planeWidth
        val planeHeight = composition.planeHeight
        val palette = palettes[composition.paletteId]?.segment()
        val drawable = planeWidth > 0 && planeHeight > 0 && planeWidth.toLong() * planeHeight <= MAX_PLANE_PIXELS
        val pictures = if (palette == null || !drawable) {
            emptyList()
        } else {
            composition.objects.mapNotNull { shown ->
                val bytes = objects[shown.objectId] ?: return@mapNotNull null
                if (bytes.width <= 0 || bytes.height <= 0 || bytes.width > planeWidth || bytes.height > planeHeight) return@mapNotNull null
                // The part shown: the cropping, inside the object, then inside the plane.
                var cutX = 0
                var cutY = 0
                var width = bytes.width
                var height = bytes.height
                shown.crop?.let { (x, y, w, h) ->
                    cutX = x.coerceAtMost(bytes.width)
                    cutY = y.coerceAtMost(bytes.height)
                    width = w.coerceAtMost(bytes.width - cutX)
                    height = h.coerceAtMost(bytes.height - cutY)
                }
                width = width.coerceAtMost(planeWidth - shown.x)
                height = height.coerceAtMost(planeHeight - shown.y)
                if (width <= 0 || height <= 0) return@mapNotNull null
                SupPicture(
                    planeWidth = planeWidth,
                    planeHeight = planeHeight,
                    x = shown.x,
                    y = shown.y,
                    cutX = cutX,
                    cutY = cutY,
                    width = width,
                    height = height,
                    objectWidth = bytes.width,
                    objectHeight = bytes.height,
                    composition = composition.of(shown),
                    palette = palette,
                    fragmentAt = bytes.positions(),
                    fragmentLength = bytes.lengths(),
                )
            }
        }
        sets += SupDisplaySet(ticksToUs(composition.ptsTicks), pictures)
    }

    /** Whether a segment's header starts at [at]: "PG", and a type a .sup file has. */
    fun headerAt(at: Long): Boolean {
        if (at < 0 || at + SUP_HEADER > end || reader.u8(at) != 'P'.code || reader.u8(at + 1) != 'G'.code) return false
        return when (reader.u8(at + 10)) {
            SEGMENT_PALETTE, SEGMENT_OBJECT, SEGMENT_COMPOSITION, SEGMENT_WINDOW, SEGMENT_END -> true
            else -> false
        }
    }

    /** Where the segment whose header is at [at] ends. */
    fun endOf(at: Long): Long = at + SUP_HEADER + reader.u16(at + 11)

    /** Whether what follows the segment at [at] is the next one: a header,
     *  or the end of the file (less than a header's bytes before it). */
    fun followed(at: Long): Boolean {
        val next = endOf(at)
        return next <= end && (end - next < SUP_HEADER || headerAt(next))
    }

    /** The first header from [from] on; [end] where none is. */
    fun nextHeader(from: Long): Long {
        var at = from
        while (at + SUP_HEADER <= end) {
            if (headerAt(at)) return at
            at++
        }
        return end
    }

    var at = 0L
    while (at + SUP_HEADER <= end) {
        if (!headerAt(at)) {
            at = nextHeader(at + 1)
            continue
        }
        if (!followed(at)) {
            // A header of bytes between segments, or the last segment, cut
            // short: a header inside it that is followed by the next is the
            // stream's own.
            val stop = minOf(endOf(at), end)
            var inside = at + 1
            while (inside < stop && !(headerAt(inside) && followed(inside))) inside++
            if (inside < stop) {
                at = inside
                continue
            }
            // A segment cut short ends the file, whatever headers its bytes hold.
            if (endOf(at) > end) break
        }
        val pts = reader.u32(at + 2)
        val type = reader.u8(at + 10)
        val segmentLength = reader.u16(at + 11)
        val payload = at + SUP_HEADER
        when (type) {
            SEGMENT_COMPOSITION -> {
                open?.let(::close)
                val composition = Composition.read(pts, reader, payload, segmentLength)
                if (segmentLength >= COMPOSITION_HEADER && composition.state == EPOCH_START) {
                    palettes.clear()
                    objects.clear()
                }
                open = composition
            }
            SEGMENT_PALETTE -> if (open != null && segmentLength >= 2) {
                val id = reader.u8(payload)
                palettes.getOrPut(id) { Palette(id) }.update(reader.copy(payload, segmentLength))
            }
            SEGMENT_OBJECT -> if (open != null && segmentLength >= 4) {
                val id = reader.u16(payload)
                // The first of an object's segments starts it anew, with its
                // size; the rest follow it.
                if (reader.u8(payload + 3) and 0x80 != 0) {
                    val sized = segmentLength >= 11
                    objects[id] = ObjectBytes(
                        width = if (sized) reader.u16(payload + 7) else 0,
                        height = if (sized) reader.u16(payload + 9) else 0,
                    ).also { it.add(payload, segmentLength) }
                } else {
                    objects[id]?.add(payload, segmentLength)
                }
            }
            SEGMENT_END -> {
                open?.let(::close)
                open = null
            }
        }
        at = payload + segmentLength
    }
    open?.let(::close)
    // In the order they show; of two at one time the later one.
    val ordered = ArrayList<SupDisplaySet>(sets.size)
    for (set in sets.sortedBy { it.startUs }) {
        if (ordered.isNotEmpty() && ordered.last().startUs == set.startUs) ordered[ordered.size - 1] = set else ordered += set
    }
    return ordered
}
