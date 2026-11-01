package cloud.nalet.chino.mobile.ui.player

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
 * object: each picture is decoded with the palette and the object it names,
 * one composition object at a time. Input that is not a .sup file - a
 * Matroska sample's bare segments - goes to PgsParser as it comes.
 *
 * Media3 reads a subtitle one of two ways, and both use it: SubtitleParsers
 * where a media source parses the subtitle as it loads it, SubtitleDecoders
 * where the text renderer decodes it - the player's way with its side-loaded
 * subtitles (legacy decoding). There the pictures are made one display set
 * at a time, as each is shown: a whole film's at once would not fit in the
 * phone's memory.
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
 * PGS from a .sup file (see the top of this file), or from a sample of bare
 * segments, which [PgsParser] reads as it always has.
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
        if (!isSupFile(data, offset, length)) {
            bare.parse(data, offset, length, outputOptions, output)
            return
        }
        val pictures = SupPictures()
        emitDisplaySets(readDisplaySets(data, offset, length), outputOptions, output, pictures::cuesOf)
    }

    /** A .sup file's display sets, each drawn only as it is shown; bare
     *  segments as [PgsParser] makes them. */
    override fun parseToLegacySubtitle(data: ByteArray, offset: Int, length: Int): Subtitle =
        if (isSupFile(data, offset, length)) {
            SupSubtitle(readDisplaySets(data, offset, length))
        } else {
            bare.parseToLegacySubtitle(data, offset, length)
        }

    override fun reset() = bare.reset()

    companion object {
        /** Each display set takes the place of the one before, as in [PgsParser]. */
        const val CUE_REPLACEMENT_BEHAVIOR: Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
    }
}

/** One display set of a .sup file: when it shows, and its pictures. None:
 *  it clears the screen. */
internal class SupDisplaySet(val startUs: Long, val pictures: List<SupPicture>)

/**
 * One picture of a display set, as the bare segments [PgsParser] decodes it
 * from: a composition of that one object, the palette it names, the
 * object's segments, an end. Each segment is a type, a length and the
 * payload, as in a Matroska sample.
 */
internal class SupPicture(
    private val composition: ByteArray,
    private val palette: ByteArray,
    private val objectSegments: List<ByteArray>,
) {
    val size: Int = composition.size + palette.size + objectSegments.sumOf { it.size } + END_SEGMENT.size

    /** Writes the segments into [out], which holds [size] bytes or more. */
    fun writeTo(out: ByteArray) {
        composition.copyInto(out, 0)
        var at = composition.size
        palette.copyInto(out, at)
        at += palette.size
        for (segment in objectSegments) {
            segment.copyInto(out, at)
            at += segment.size
        }
        END_SEGMENT.copyInto(out, at)
    }
}

/** Makes a display set's cues with a [PgsParser] of its own: one cue per
 *  picture. A picture that can't be drawn (a broken object, one too large
 *  for the memory) is left out; it never stops the playback. Not for two
 *  threads at once. */
internal class SupPictures {
    private val pgs = PgsParser()
    private var scratch = ByteArray(0)

    fun cuesOf(set: SupDisplaySet): List<Cue> {
        if (set.pictures.isEmpty()) return emptyList()
        val cues = ArrayList<Cue>(set.pictures.size)
        for (picture in set.pictures) {
            try {
                if (scratch.size < picture.size) scratch = ByteArray(picture.size)
                picture.writeTo(scratch)
                pgs.parse(scratch, 0, picture.size, SubtitleParser.OutputOptions.allCues()) { cues.addAll(it.cues) }
            } catch (e: RuntimeException) {
                Log.w(TAG, "A picture of the PGS display set at ${set.startUs} us is not drawn", e)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "A picture of the PGS display set at ${set.startUs} us is not drawn: out of memory")
            }
        }
        return cues
    }

    private companion object {
        const val TAG = "SupPictures"
    }
}

/**
 * A .sup file's display sets as a subtitle the text renderer reads: each set
 * an event at its start, its pictures made when it is shown - one set's at a
 * time, the last one kept.
 */
private class SupSubtitle(read: List<SupDisplaySet>) : Subtitle {
    // A file without a display set: one event that shows nothing.
    private val sets = read.ifEmpty { listOf(SupDisplaySet(0L, emptyList())) }
    private val startsUs = LongArray(sets.size) { sets[it].startUs }
    private val pictures = SupPictures()
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

/** An end segment, bare. */
private val END_SEGMENT = byteArrayOf(SEGMENT_END.toByte(), 0, 0)

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

private fun ByteArray.u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

private fun ByteArray.u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

/** Whether a segment's header starts at [at]: "PG", and a type a .sup file has. */
private fun ByteArray.supHeaderAt(at: Int, end: Int): Boolean {
    if (at + SUP_HEADER > end || this[at] != 'P'.code.toByte() || this[at + 1] != 'G'.code.toByte()) return false
    return when (u8(at + 10)) {
        SEGMENT_PALETTE, SEGMENT_OBJECT, SEGMENT_COMPOSITION, SEGMENT_WINDOW, SEGMENT_END -> true
        else -> false
    }
}

/** Whether the bytes are a .sup file, segments behind "PG" headers, rather
 *  than bare segments (whose first byte is a segment type). */
internal fun isSupFile(data: ByteArray, offset: Int, length: Int): Boolean =
    length >= 2 && data[offset] == 'P'.code.toByte() && data[offset + 1] == 'G'.code.toByte()

/** A segment as [PgsParser] reads it: its type, its length and its payload. */
private fun bareSegment(type: Int, data: ByteArray, payload: Int, length: Int): ByteArray {
    val out = ByteArray(3 + length)
    out[0] = type.toByte()
    out[1] = (length shr 8).toByte()
    out[2] = length.toByte()
    data.copyInto(out, 3, payload, payload + length)
    return out
}

/** One entry of a composition: the object it shows, where (and its
 *  cropping, where it has one), as the composition's bytes have it. */
private class CompositionObject(val objectId: Int, val entry: ByteArray)

/** A presentation composition: the header and the objects it shows. */
private class Composition(val ptsTicks: Long, val header: ByteArray, val objects: List<CompositionObject>) {
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
        fun read(ptsTicks: Long, data: ByteArray, payload: Int, length: Int): Composition? {
            if (length < COMPOSITION_HEADER) return null
            val end = payload + length
            val objects = ArrayList<CompositionObject>()
            var at = payload + COMPOSITION_HEADER
            for (n in 0 until data.u8(payload + COMPOSITION_HEADER - 1)) {
                if (at + 8 > end) break
                // Cropped: four more values, the part of the object shown.
                val entryLength = if (data.u8(at + 3) and 0x80 != 0) 16 else 8
                if (at + entryLength > end) break
                objects += CompositionObject(data.u16(at), data.copyOfRange(at, at + entryLength))
                at += entryLength
            }
            return Composition(ptsTicks, data.copyOfRange(payload, payload + COMPOSITION_HEADER), objects)
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

    fun update(data: ByteArray, payload: Int, length: Int) {
        if (length < 2) return
        version = data.u8(payload + 1)
        var at = payload + 2
        while (at + 5 <= payload + length) {
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

/** The PTS of a .sup header (90 kHz) in microseconds. */
private fun ticksToUs(ticks: Long): Long = ticks * 100 / 9

/**
 * The display sets of a .sup file, in the order they show. A composition
 * starts a set, an end segment closes it (as does the next composition, or
 * the end of the file, where one is missing). The palettes and objects of
 * an epoch hold until the next epoch starts: a set names the ones it shows,
 * from itself or an earlier set. Bytes that are no header are skipped to the
 * next one; a segment cut short ends the file. Of two sets at one time the
 * later one counts.
 */
internal fun readDisplaySets(data: ByteArray, offset: Int, length: Int): List<SupDisplaySet> {
    val end = offset + length
    val sets = ArrayList<SupDisplaySet>()
    val palettes = HashMap<Int, Palette>()
    val objects = HashMap<Int, MutableList<ByteArray>>()
    var open: Composition? = null

    fun close(composition: Composition) {
        val palette = palettes[composition.paletteId]?.segment()
        val pictures = if (palette == null) {
            emptyList()
        } else {
            composition.objects.mapNotNull { shown ->
                val segments = objects[shown.objectId] ?: return@mapNotNull null
                SupPicture(composition.of(shown), palette, segments.toList())
            }
        }
        sets += SupDisplaySet(ticksToUs(composition.ptsTicks), pictures)
    }

    var at = offset
    while (at + SUP_HEADER <= end) {
        if (data[at] != 'P'.code.toByte() || data[at + 1] != 'G'.code.toByte()) {
            at++
            while (at + SUP_HEADER <= end && !data.supHeaderAt(at, end)) at++
            continue
        }
        val pts = data.u32(at + 2)
        val type = data.u8(at + 10)
        val segmentLength = data.u16(at + 11)
        val payload = at + SUP_HEADER
        if (payload + segmentLength > end) break
        when (type) {
            SEGMENT_COMPOSITION -> {
                open?.let(::close)
                open = Composition.read(pts, data, payload, segmentLength)
                if (open?.state == EPOCH_START) {
                    palettes.clear()
                    objects.clear()
                }
            }
            SEGMENT_PALETTE -> if (open != null && segmentLength >= 2) {
                val id = data.u8(payload)
                palettes.getOrPut(id) { Palette(id) }.update(data, payload, segmentLength)
            }
            SEGMENT_OBJECT -> if (open != null && segmentLength >= 4) {
                val id = data.u16(payload)
                val segment = bareSegment(type, data, payload, segmentLength)
                // The first of an object's segments starts it anew; the rest
                // follow it.
                if (data.u8(payload + 3) and 0x80 != 0) {
                    objects[id] = mutableListOf(segment)
                } else {
                    objects[id]?.add(segment)
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
