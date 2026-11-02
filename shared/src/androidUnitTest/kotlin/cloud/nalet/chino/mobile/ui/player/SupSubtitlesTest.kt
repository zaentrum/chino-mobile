package cloud.nalet.chino.mobile.ui.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.pgs.PgsParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PGS from a .sup file, as the server keeps a Blu-ray's subtitle (SupSubtitles.kt):
 * its display sets timed from their PTS to the next set's, a set without a
 * picture clearing the screen, the pictures Media3's own - and a Matroska
 * sample's bare segments read as before. The .sup files are made in the
 * test (SupFile.kt). Under Robolectric's native graphics: the pictures are
 * real bitmaps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupSubtitlesTest {
    private fun parseAll(bytes: ByteArray, options: SubtitleParser.OutputOptions = SubtitleParser.OutputOptions.allCues()): List<CuesWithTiming> {
        val out = ArrayList<CuesWithTiming>()
        SupParser().parse(bytes, 0, bytes.size, options) { out += it }
        return out
    }

    /** White as Media3 makes it of Y 235, no colour. */
    private val WHITE = 0xFFEBEBEB.toInt()

    private fun assertBox(cue: Cue, width: Int, height: Int, x: Int, y: Int, colour: Int = WHITE) {
        val bitmap = cue.bitmap!!
        assertEquals(width to height, bitmap.width to bitmap.height)
        assertEquals(x.toFloat() / PLANE_WIDTH, cue.position, 1e-6f)
        assertEquals(y.toFloat() / PLANE_HEIGHT, cue.line, 1e-6f)
        assertEquals(Cue.LINE_TYPE_FRACTION, cue.lineType)
        assertEquals(width.toFloat() / PLANE_WIDTH, cue.size, 1e-6f)
        assertEquals(height.toFloat() / PLANE_HEIGHT, cue.bitmapHeight, 1e-6f)
        assertEquals(colour, bitmap.getPixel(width / 2, height / 2))
    }

    @Test
    fun eachDisplaySetShowsFromItsTimeToTheNextSetsAndASetWithoutAPictureClears() {
        val cues = parseAll(threeLines().sup())
        // Five sets, three with a picture: the line at 1 s until the clear
        // at 3 s, the one at 4 s until the set at 6.5 s takes its place, that
        // one until the clear at 8 s.
        assertEquals(listOf(1_000_000L to 2_000_000L, 4_000_000L to 2_500_000L, 6_500_000L to 1_500_000L), cues.map { it.startTimeUs to it.durationUs })
        assertEquals(listOf(1, 1, 1), cues.map { it.cues.size })
        assertBox(cues[0].cues.single(), 400, 40, 760, 900)
        assertBox(cues[1].cues.single(), 600, 40, 660, 950)
        // Its own palette: yellow, as Media3 makes it of Y 210, Cr 146, Cb 16.
        assertBox(cues[2].cues.single(), 200, 30, 100, 60, colour = 0xFFEBEB0B.toInt())
    }

    @Test
    fun theTextRendererGetsTheSameSetsEachDrawnWhenItShows() {
        val bytes = threeLines().sup()
        val subtitle = SupParser().parseToLegacySubtitle(bytes, 0, bytes.size)
        assertEquals(listOf(1_000_000L, 3_000_000L, 4_000_000L, 6_500_000L, 8_000_000L), (0 until subtitle.eventTimeCount).map(subtitle::getEventTime))
        assertEquals(0, subtitle.getNextEventTimeIndex(0))
        assertEquals(1, subtitle.getNextEventTimeIndex(1_000_000))
        assertEquals(4, subtitle.getNextEventTimeIndex(7_999_999))
        assertEquals(C.INDEX_UNSET, subtitle.getNextEventTimeIndex(8_000_000))
        assertEquals(emptyList(), subtitle.getCues(999_999))
        assertBox(subtitle.getCues(1_000_000).single(), 400, 40, 760, 900)
        assertBox(subtitle.getCues(2_999_999).single(), 400, 40, 760, 900)
        assertEquals(emptyList(), subtitle.getCues(3_000_000))
        assertBox(subtitle.getCues(5_000_000).single(), 600, 40, 660, 950)
        assertBox(subtitle.getCues(7_000_000).single(), 200, 30, 100, 60, colour = 0xFFEBEB0B.toInt())
        assertEquals(emptyList(), subtitle.getCues(9_000_000))
        // The set on screen is drawn once, however often it is asked for.
        assertTrue(subtitle.getCues(7_100_000).single() === subtitle.getCues(7_900_000).single())
    }

    @Test
    fun aMatroskaSamplesBareSegmentsAreReadAsMedia3ReadsThem() {
        val sample = Pgs().shows(0, number = 0, width = 300, height = 50, x = 810, y = 980).bare()
        val ours = parseAll(sample)
        val media3 = ArrayList<CuesWithTiming>().also { out -> PgsParser().parse(sample, 0, sample.size, SubtitleParser.OutputOptions.allCues()) { out += it } }
        assertEquals(1, ours.size)
        assertEquals(C.TIME_UNSET, ours.single().startTimeUs)
        assertEquals(C.TIME_UNSET, ours.single().durationUs)
        assertEquals(media3.single().cues.size, ours.single().cues.size)
        assertBox(ours.single().cues.single(), 300, 50, 810, 980)
        // The text renderer's way too: one event, at the sample's own time.
        val legacy = SupParser().parseToLegacySubtitle(sample, 0, sample.size)
        assertEquals(1, legacy.eventTimeCount)
        assertEquals(0L, legacy.getEventTime(0))
        assertBox(legacy.getCues(0).single(), 300, 50, 810, 980)
    }

    @Test
    fun aSetShowsWhatEarlierSetsOfItsEpochBrought() {
        val pgs = Pgs()
            // Faint, then the palette alone turns it opaque (a fade).
            .set(
                1_000_000,
                composition(0, STATE_EPOCH_START, Shown(0, 760, 900)),
                PALETTE to palette(0, 0, white(1, alpha = 0x40)),
                *objectSegments(0, 400, 40, colour = 1).map { OBJECT to it }.toTypedArray(),
            )
            .set(1_200_000, composition(1, STATE_NORMAL, Shown(0, 760, 900), paletteUpdate = true), PALETTE to palette(0, 1, white(1)))
            // The same object again, moved, without its segments.
            .set(2_000_000, composition(2, STATE_NORMAL, Shown(0, 760, 600)))
            // A new epoch forgets it.
            .set(3_000_000, composition(3, STATE_EPOCH_START, Shown(0, 760, 900)), PALETTE to palette(0, 0, white(1)))
        val cues = parseAll(pgs.sup())
        assertEquals(listOf(1_000_000L, 1_200_000L, 2_000_000L), cues.map { it.startTimeUs })
        assertEquals(0x40, android.graphics.Color.alpha(cues[0].cues.single().bitmap!!.getPixel(200, 20)))
        assertBox(cues[1].cues.single(), 400, 40, 760, 900)
        assertBox(cues[2].cues.single(), 400, 40, 760, 600)
        // Shown until the set at 3 s, which has nothing to show.
        assertEquals(1_000_000L, cues[2].durationUs)
    }

    @Test
    fun twoObjectsOfASetAreTwoPicturesEachInItsPlace() {
        val pgs = Pgs().set(
            1_000_000,
            composition(0, STATE_EPOCH_START, Shown(0, 100, 60, forced = true), Shown(1, 660, 950)),
            PALETTE to palette(0, 0, white(1), yellow(2)),
            *objectSegments(0, 200, 30, colour = 2).map { OBJECT to it }.toTypedArray(),
            // The second object in three segments.
            *objectSegments(1, 600, 40, colour = 1, parts = 3).map { OBJECT to it }.toTypedArray(),
        ).clears(2_000_000, number = 1)
        val cues = parseAll(pgs.sup()).single().cues
        assertEquals(2, cues.size)
        assertBox(cues[0], 200, 30, 100, 60, colour = 0xFFEBEB0B.toInt())
        assertBox(cues[1], 600, 40, 660, 950)
    }

    @Test
    fun aPictureThatCannotBeDrawnIsLeftOutAndTheRestShows() {
        // One larger than any plane; one of a plane's size whose data PgsParser can't draw.
        for (broken in listOf(brokenObject(0), overrunObject(0, 400, 40))) {
            val bytes = Pgs().set(
                1_000_000,
                composition(0, STATE_EPOCH_START, Shown(0, 100, 60), Shown(1, 660, 950)),
                PALETTE to palette(0, 0, white(1)),
                OBJECT to broken,
                *objectSegments(1, 600, 40, colour = 1).map { OBJECT to it }.toTypedArray(),
            ).clears(2_000_000, number = 1).sup()
            assertBox(parseAll(bytes).single().cues.single(), 600, 40, 660, 950)
            assertBox(SupParser().parseToLegacySubtitle(bytes, 0, bytes.size).getCues(1_500_000).single(), 600, 40, 660, 950)
        }
    }

    @Test
    fun fromAStartTimeTheSetOnScreenIsCutToStartThereAndTheRestFollowsWhenAsked() {
        val bytes = threeLines().sup()
        // From 5 s: the line on screen since 4 s from 5 s, then the one at 6.5 s.
        assertEquals(
            listOf(5_000_000L to 1_500_000L, 6_500_000L to 1_500_000L),
            parseAll(bytes, SubtitleParser.OutputOptions.onlyCuesAfter(5_000_000)).map { it.startTimeUs to it.durationUs },
        )
        // And then the ones before it, the line on screen from 4 s to 5 s last.
        assertEquals(
            listOf(5_000_000L to 1_500_000L, 6_500_000L to 1_500_000L, 1_000_000L to 2_000_000L, 4_000_000L to 1_000_000L),
            parseAll(bytes, SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(5_000_000)).map { it.startTimeUs to it.durationUs },
        )
        // From a clear: nothing is cut.
        assertEquals(
            listOf(4_000_000L, 6_500_000L),
            parseAll(bytes, SubtitleParser.OutputOptions.onlyCuesAfter(3_500_000)).map { it.startTimeUs },
        )
    }

    @Test
    fun aFileCutShortOrWithBytesBetweenItsSegmentsKeepsWhatItHas() {
        val whole = threeLines().sup()
        // Cut in the middle of the object of the set at 6.5 s: the sets
        // before it stay; it shows nothing - its object is gone.
        val cut = whole.copyOf(whole.size - 200)
        assertEquals(listOf(1_000_000L to 2_000_000L, 4_000_000L to 2_500_000L), parseAll(cut).map { it.startTimeUs to it.durationUs })
        // Cut after a line's set: it stays on screen to the end.
        val lastShown = Pgs()
            .shows(1_000_000, number = 0, width = 400, height = 40, x = 760, y = 900)
            .shows(4_000_000, number = 1, width = 600, height = 40, x = 660, y = 950)
            .sup()
        assertEquals(listOf(1_000_000L to 3_000_000L, 4_000_000L to C.TIME_UNSET), parseAll(lastShown).map { it.startTimeUs to it.durationUs })
        // Junk between two sets is skipped to the next header.
        val first = Pgs().shows(1_000_000, number = 0, width = 400, height = 40, x = 760, y = 900).sup()
        val rest = Pgs().clears(3_000_000, number = 1).sup()
        val junk = first + byteArrayOf(1, 2, 3, 'P'.code.toByte(), 0x47, 9) + rest
        assertEquals(listOf(1_000_000L to 2_000_000L), parseAll(junk).map { it.startTimeUs to it.durationUs })
        // A .sup file without a display set shows nothing, ever.
        val none = SupParser().parseToLegacySubtitle("PG".toByteArray(), 0, 2)
        assertEquals(emptyList(), none.getCues(1_000_000))
        assertEquals(1, none.eventTimeCount)
    }

    @Test
    fun pgsGoesToSupParserEveryOtherFormatToMedia3sOwn() {
        val pgs = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build()
        val vtt = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()
        val vobsub = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_VOBSUB).build()
        val cea608 = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build()
        val parsers = SubtitleParsers()
        assertIs<SupParser>(parsers.create(pgs))
        assertIs<WebvttParser>(parsers.create(vtt))
        assertEquals(SupParser.CUE_REPLACEMENT_BEHAVIOR, parsers.getCueReplacementBehavior(pgs))
        // Media3 1.5.1 has no parser for VobSub.
        assertEquals(false, parsers.supportsFormat(vobsub))
        val decoders = SubtitleDecoders()
        for ((format, name) in listOf(pgs to "SupParserDecoder", vtt to "WebvttParserDecoder")) {
            val decoder = decoders.createDecoder(format)
            try {
                assertEquals(name, decoder.name)
            } finally {
                decoder.release()
            }
        }
        assertTrue(decoders.supportsFormat(cea608))
        assertEquals(false, decoders.supportsFormat(vobsub))
    }

    /** One sample, the whole file at 0 - as Media3 hands on a side-loaded
     *  subtitle (SingleSampleMediaSource). */
    private class WholeFile(private val format: Format, private val data: ByteArray) : SampleStream {
        private var sent = 0

        /** After a seek the file comes again, its format already known. */
        fun seek() {
            if (sent == 2) sent = 1
        }

        override fun isReady() = true

        override fun maybeThrowError() = Unit

        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            if (sent == 2) {
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }
            if (sent == 0 || readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0) {
                formatHolder.format = format
                sent = 1
                return C.RESULT_FORMAT_READ
            }
            buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)
            buffer.timeUs = 0
            buffer.ensureSpaceForWrite(data.size)
            buffer.data!!.put(data)
            sent = 2
            return C.RESULT_BUFFER_READ
        }

        override fun skipData(positionUs: Long) = 0
    }

    @Test
    fun thePlayersTextRendererShowsASideLoadedSupFile() {
        val shown = ArrayList<CueGroup>()
        val renderers = PlayerRenderersFactory(ApplicationProvider.getApplicationContext()).createRenderers(
            Handler(Looper.getMainLooper()),
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            { group -> shown += group },
            { },
        )
        val text = renderers.filterIsInstance<TextRenderer>().single()
        val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).setLanguage("en").build()
        text.init(0, PlayerId.UNSET, Clock.DEFAULT)
        text.enable(RendererConfiguration.DEFAULT, arrayOf(format), WholeFile(format, threeLines().sup()), 0, false, true, 0, 0, MediaSource.MediaPeriodId(Any()))
        text.setCurrentStreamFinal()
        text.start()
        fun renderAt(us: Long): CueGroup? {
            text.render(us, SystemClock.elapsedRealtime() * 1000)
            shadowOf(Looper.getMainLooper()).idle()
            return shown.lastOrNull()
        }
        try {
            // The file is decoded on the decoder's own thread: until then
            // nothing shows.
            val deadline = System.nanoTime() + 10_000_000_000L
            while (renderAt(1_500_000)?.cues.isNullOrEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertBox(shown.last().cues.single(), 400, 40, 760, 900)
            assertEquals(1_000_000L, shown.last().presentationTimeUs)
            assertEquals(emptyList(), renderAt(3_000_000)!!.cues)
            assertBox(renderAt(4_500_000)!!.cues.single(), 600, 40, 660, 950)
            assertBox(renderAt(7_000_000)!!.cues.single(), 200, 30, 100, 60, colour = 0xFFEBEB0B.toInt())
            assertEquals(emptyList(), renderAt(8_000_000)!!.cues)
        } finally {
            text.stop()
            text.disable()
            text.release()
        }
    }

    private val YELLOW = 0xFFEBEB0B.toInt()

    @Test
    fun ofTwoSetsAtOneTimeTheLaterOneCounts() {
        val pgs = Pgs()
            .shows(1_000_000, number = 0, width = 400, height = 40, x = 760, y = 900)
            // A clear, then a line at the same time: the line shows.
            .clears(3_000_000, number = 1)
            .shows(3_000_000, number = 2, width = 600, height = 40, x = 660, y = 950)
            // A line, then a clear at the same time: nothing shows.
            .shows(5_000_000, number = 3, width = 200, height = 30, x = 100, y = 60)
            .clears(5_000_000, number = 4)
        val bytes = pgs.sup()
        assertEquals(listOf(1_000_000L to 2_000_000L, 3_000_000L to 2_000_000L), parseAll(bytes).map { it.startTimeUs to it.durationUs })
        val subtitle = SupParser().parseToLegacySubtitle(bytes, 0, bytes.size)
        assertEquals(listOf(1_000_000L, 3_000_000L, 5_000_000L), (0 until subtitle.eventTimeCount).map(subtitle::getEventTime))
        assertBox(subtitle.getCues(4_000_000).single(), 600, 40, 660, 950)
        assertEquals(emptyList(), subtitle.getCues(5_500_000))
    }

    @Test
    fun aPaletteUpdateGivesSomeOfItsEntriesTheOthersStayAsTheyWere() {
        val pgs = Pgs()
            .set(
                1_000_000,
                composition(0, STATE_EPOCH_START, Shown(0, 100, 60), Shown(1, 660, 950)),
                PALETTE to palette(0, 0, white(1), yellow(2)),
                *objectSegments(0, 200, 30, colour = 1).map { OBJECT to it }.toTypedArray(),
                *objectSegments(1, 600, 40, colour = 2).map { OBJECT to it }.toTypedArray(),
            )
            // Only the yellow fades; the white is not given again.
            .set(
                1_500_000,
                composition(1, STATE_NORMAL, Shown(0, 100, 60), Shown(1, 660, 950), paletteUpdate = true),
                PALETTE to palette(0, 1, Entry(2, 210, 146, 16, 0x40)),
            )
            .clears(2_000_000, number = 2)
        val faded = parseAll(pgs.sup())[1].cues
        assertBox(faded[0], 200, 30, 100, 60)
        assertEquals(0x40, android.graphics.Color.alpha(faded[1].bitmap!!.getPixel(300, 20)))
    }

    @Test
    fun aCroppedObjectShowsThePartItsCompositionCutsTheObjectsAfterItInTheirPlaces() {
        // One object of two lines, white over yellow; the composition shows
        // its lower line at (760, 900), then a second object at (100, 60).
        val pgs = Pgs().set(
            1_000_000,
            composition(0, STATE_EPOCH_START, Shown(0, 760, 900, crop = intArrayOf(0, 40, 400, 40)), Shown(1, 100, 60)),
            PALETTE to palette(0, 0, white(1), yellow(2)),
            *objectSegments(0, 400, 80, colour = 1, data = twoTone(400, 80, 40, upper = 1, lower = 2)).map { OBJECT to it }.toTypedArray(),
            *objectSegments(1, 200, 30, colour = 1).map { OBJECT to it }.toTypedArray(),
        ).clears(2_000_000, number = 1)
        val cues = parseAll(pgs.sup()).single().cues
        assertEquals(2, cues.size)
        assertBox(cues[0], 200, 30, 100, 60)
        assertBox(cues[1], 400, 40, 760, 900, colour = YELLOW)
    }

    @Test
    fun aCompositionTooShortToReadClearsTheScreenAtItsTime() {
        for (length in listOf(0, 5, 10)) {
            val bytes = Pgs()
                .shows(1_000_000, number = 0, width = 400, height = 40, x = 760, y = 900)
                .segment(3_000_000, COMPOSITION, composition(1, STATE_NORMAL).copyOf(length))
                .segment(3_000_000, WINDOW, window())
                .segment(3_000_000, END)
                .shows(4_000_000, number = 2, width = 600, height = 40, x = 660, y = 950)
                .sup()
            assertEquals(listOf(1_000_000L to 2_000_000L, 4_000_000L to C.TIME_UNSET), parseAll(bytes).map { it.startTimeUs to it.durationUs }, "a composition of $length bytes")
            assertEquals(emptyList(), SupParser().parseToLegacySubtitle(bytes, 0, bytes.size).getCues(3_500_000))
        }
    }

    @Test
    fun aStrayHeaderOrOneWhoseLengthRunsPastTheEndIsSkippedToTheSegmentsThatFollow() {
        val first = Pgs().shows(1_000_000, number = 0, width = 400, height = 40, x = 760, y = 900).sup()
        val rest = Pgs().clears(3_000_000, number = 1).shows(4_000_000, number = 2, width = 600, height = 40, x = 660, y = 950).clears(6_000_000, number = 3).sup()
        val header = byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, COMPOSITION.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val expected = listOf(1_000_000L to 2_000_000L, 4_000_000L to 2_000_000L)
        // Between other bytes, its length past the end of the file.
        val overrunning = first + byteArrayOf(1, 2, 3) + header + byteArrayOf(9, 9) + rest
        assertEquals(expected, parseAll(overrunning).map { it.startTimeUs to it.durationUs })
        // Right after a segment, its length into the next one.
        val stray = first + header.copyOf().also { it[11] = 0; it[12] = 40 } + rest
        assertEquals(expected, parseAll(stray).map { it.startTimeUs to it.durationUs })
        // A segment cut short at the end still ends the file. (The last
        // set's composition cut after its first byte: its clear is lost.)
        val cut = (first + rest).let { it.copyOf(it.size - 46) }
        assertEquals(listOf(1_000_000L to 2_000_000L, 4_000_000L to C.TIME_UNSET), parseAll(cut).map { it.startTimeUs to it.durationUs })
    }

    @Test
    fun aHeaderOfATypeNoSupFileHasIsNoHeaderEvenWhereItsLengthEndsOnTheNextOne() {
        val first = Pgs().shows(1_000_000, number = 0, width = 400, height = 40, x = 760, y = 900).sup()
        val clear = Pgs().clears(3_000_000, number = 1).sup()
        val next = Pgs().shows(4_000_000, number = 2, width = 600, height = 40, x = 660, y = 950).clears(6_000_000, number = 3).sup()
        // "PG", a type 0x01 and a length that ends where the set at 4 s starts: round the clear at 3 s.
        val fake = byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0x01, (clear.size shr 8).toByte(), clear.size.toByte())
        assertEquals(listOf(1_000_000L to 2_000_000L, 4_000_000L to 2_000_000L), parseAll(first + fake + clear + next).map { it.startTimeUs to it.durationUs })
    }

    @Test
    fun anObjectsSegmentsPastItsDeclaredSizeAreLeftOutItsPictureDrawn() {
        val segments = objectSegments(0, 400, 40, colour = 1)
        // A damaged stream: hundreds of continuation segments after the object's last.
        val junk = byteArrayOf(0, 0, 0, 0) + ByteArray(200) { 7 }
        val bytes = Pgs().set(
            1_000_000,
            composition(0, STATE_EPOCH_START, Shown(0, 760, 900)),
            PALETTE to palette(0, 0, white(1)),
            *segments.map { OBJECT to it }.toTypedArray(),
            *Array(400) { OBJECT to junk },
        ).clears(2_000_000, number = 1).sup()
        assertBox(parseAll(bytes).single().cues.single(), 400, 40, 760, 900)
    }

    @Test
    fun theNextSetsPicturesAreMadeBeforeItShowsReadFromTheFileThen() {
        val file = java.io.File.createTempFile("track", ".sup").apply {
            deleteOnExit()
            writeBytes(threeLines().sup())
        }
        val ref = SupFileRef.of(file)
        val subtitle = SupParser().parseToLegacySubtitle(ref, 0, ref.size)
        // The clear at 3 s shows: the line at 4 s is made ahead.
        assertEquals(emptyList(), subtitle.getCues(3_500_000))
        Thread.sleep(2_000)
        // Gone from disk now: the line at 4 s was read before.
        assertTrue(file.delete())
        assertBox(subtitle.getCues(4_500_000).single(), 600, 40, 660, 950)
        // The set after it, made ahead as 4 s showed, from a file already gone: nothing.
        assertEquals(emptyList(), subtitle.getCues(7_000_000))
    }

    @Test
    fun thePicturesOfOneHalfOfThePlaneAreOnePictureEachInItsPlaceInIt() {
        // Two lines as two objects, 5 pixels apart, in the lower half; a
        // sign in the upper half.
        val pgs = Pgs().set(
            1_000_000,
            composition(0, STATE_EPOCH_START, Shown(0, 760, 900), Shown(1, 660, 945), Shown(2, 100, 60)),
            PALETTE to palette(0, 0, white(1), yellow(2)),
            *objectSegments(0, 400, 40, colour = 1).map { OBJECT to it }.toTypedArray(),
            *objectSegments(1, 600, 40, colour = 2).map { OBJECT to it }.toTypedArray(),
            *objectSegments(2, 200, 30, colour = 2).map { OBJECT to it }.toTypedArray(),
        ).clears(2_000_000, number = 1)
        val cues = parseAll(pgs.sup()).single().cues
        assertEquals(2, cues.size)
        assertBox(cues[0], 200, 30, 100, 60, colour = YELLOW)
        // The lower half's two: one picture from (660, 900) to (1260, 985).
        val lower = cues[1]
        val bitmap = lower.bitmap!!
        assertEquals(600 to 85, bitmap.width to bitmap.height)
        assertEquals(660f / PLANE_WIDTH, lower.position, 1e-6f)
        assertEquals(900f / PLANE_HEIGHT, lower.line, 1e-6f)
        assertEquals(85f / PLANE_HEIGHT, lower.bitmapHeight, 1e-6f)
        assertEquals(WHITE, bitmap.getPixel(300, 20))
        assertEquals(YELLOW, bitmap.getPixel(300, 65))
        // Between the lines and beside the upper one: nothing.
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(300, 42)))
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(50, 20)))
    }

    @Test
    fun noPictureIsLargerThanItsPlaneNorAPlaneLargerThanAnUhdFrame() {
        fun cuesOf(composition: ByteArray, objectPayload: ByteArray): List<Cue> {
            val bytes = Pgs().set(1_000_000, composition, PALETTE to palette(0, 0, white(1)), OBJECT to objectPayload).clears(2_000_000, number = 1).sup()
            return SupParser().parseToLegacySubtitle(bytes, 0, bytes.size).getCues(1_500_000)
        }
        // An object taller and wider than its 1920x1080 plane, one row of
        // a single run each: 31 KB that would be a 108 MB picture.
        assertEquals(emptyList(), cuesOf(composition(0, STATE_EPOCH_START, Shown(0, 0, 0)), runsObject(0, 5200, 5200)))
        assertEquals(emptyList(), cuesOf(composition(0, STATE_EPOCH_START, Shown(0, 0, 0)), runsObject(0, 3000, 1000)))
        // A plane larger than an UHD frame: nothing of it is drawn.
        val huge = composition(0, STATE_EPOCH_START, Shown(0, 0, 0)).also { it[0] = 0x3F; it[1] = 0xFF.toByte(); it[2] = 0x3F; it[3] = 0xFF.toByte() }
        assertEquals(emptyList(), cuesOf(huge, runsObject(0, 100, 10)))
        // A picture past the plane's edge: the part inside it.
        val edge = cuesOf(composition(0, STATE_EPOCH_START, Shown(0, 1820, 1050)), runsObject(0, 200, 50)).single()
        assertEquals(100 to 30, edge.bitmap!!.width to edge.bitmap!!.height)
    }

    /** An object [width] x [height] of colour 1, one run a row. */
    private fun runsObject(id: Int, width: Int, height: Int): ByteArray {
        val data = java.io.ByteArrayOutputStream()
        repeat(height) {
            data.write(0)
            data.write(0xC0 or (width shr 8))
            data.write(width and 0xFF)
            data.write(1)
            data.write(0)
            data.write(0)
        }
        return objectSegments(id, width, height, colour = 1, data = data.toByteArray()).single()
    }

    @Test
    fun aFileCutAnywhereOrDamagedNeverThrows() {
        val whole = threeLines().sup()
        for (cut in 0..whole.size) assertNull(everything(whole.copyOf(cut)), "cut at $cut")
        val random = java.util.Random(42)
        repeat(500) { run ->
            val damaged = whole.copyOf()
            repeat(1 + random.nextInt(8)) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
            assertNull(everything(damaged), "damaged, run $run")
        }
    }

    /** Everything the player does with [bytes]; what it throws, or null. */
    private fun everything(bytes: ByteArray): Throwable? = try {
        SupParser().parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { }
        SupParser().parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(2_000_000)) { }
        val subtitle = SupParser().parseToLegacySubtitle(bytes, 0, bytes.size)
        for (i in 0 until subtitle.eventTimeCount) {
            val time = subtitle.getEventTime(i)
            subtitle.getCues(time)
            subtitle.getCues(time - 1)
            subtitle.getNextEventTimeIndex(time)
        }
        subtitle.getEventTime(subtitle.eventTimeCount - 1)
        null
    } catch (e: Throwable) {
        e
    }

    @Test
    fun textSidecarsAreDecodedAsBefore() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nOne\n\n00:00:03.000 --> 00:00:04.000\nTwo\n"
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nOne\n\n2\n00:00:03,000 --> 00:00:04,000\nTwo\n"
        for ((mime, text) in listOf(MimeTypes.TEXT_VTT to vtt, MimeTypes.APPLICATION_SUBRIP to srt)) {
            val decoder = SubtitleDecoders().createDecoder(Format.Builder().setSampleMimeType(mime).build())
            try {
                val bytes = text.toByteArray()
                val input = decoder.dequeueInputBuffer()!!
                input.ensureSpaceForWrite(bytes.size)
                input.data!!.put(bytes)
                input.timeUs = 0
                input.flip()
                decoder.queueInputBuffer(input)
                val deadline = System.nanoTime() + 10_000_000_000L
                var output = decoder.dequeueOutputBuffer()
                while (output == null && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                    output = decoder.dequeueOutputBuffer()
                }
                val subtitle = output!!
                assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L), (0 until subtitle.eventTimeCount).map(subtitle::getEventTime), mime)
                assertEquals("One", subtitle.getCues(1_500_000).single().text.toString(), mime)
                assertEquals("Two", subtitle.getCues(3_500_000).single().text.toString(), mime)
                subtitle.release()
            } finally {
                decoder.release()
            }
        }
    }

    /** The player's text renderer, its cue groups into [shown]. */
    private fun textRenderer(shown: MutableList<CueGroup>): TextRenderer =
        PlayerRenderersFactory(ApplicationProvider.getApplicationContext()).createRenderers(
            Handler(Looper.getMainLooper()),
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            { group -> shown += group },
            { },
        ).filterIsInstance<TextRenderer>().single()

    @Test
    fun thePlayersTextRendererShowsASupFileFromItsReferenceOnDisk() {
        val file = java.io.File.createTempFile("track", ".sup").apply {
            deleteOnExit()
            writeBytes(threeLines().sup())
        }
        val shown = ArrayList<CueGroup>()
        val text = textRenderer(shown)
        val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).setLanguage("en").build()
        // What Media3 is handed for the file (SupSidecarDataSource): a few bytes.
        text.init(0, PlayerId.UNSET, Clock.DEFAULT)
        text.enable(RendererConfiguration.DEFAULT, arrayOf(format), WholeFile(format, SupFileRef.of(file)), 0, false, true, 0, 0, MediaSource.MediaPeriodId(Any()))
        text.setCurrentStreamFinal()
        text.start()
        fun renderAt(us: Long): CueGroup? {
            text.render(us, SystemClock.elapsedRealtime() * 1000)
            shadowOf(Looper.getMainLooper()).idle()
            return shown.lastOrNull()
        }
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (renderAt(1_500_000)?.cues.isNullOrEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertBox(shown.last().cues.single(), 400, 40, 760, 900)
            assertEquals(emptyList(), renderAt(3_000_000)!!.cues)
            assertBox(renderAt(4_500_000)!!.cues.single(), 600, 40, 660, 950)
            assertBox(renderAt(7_000_000)!!.cues.single(), 200, 30, 100, 60, colour = YELLOW)
        } finally {
            text.stop()
            text.disable()
            text.release()
        }
    }

    @Test
    fun afterASeekTheTextRendererShowsTheSetAtTheNewTimeNeverOneFromBefore() {
        val shown = ArrayList<CueGroup>()
        val text = textRenderer(shown)
        val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).setLanguage("en").build()
        val stream = WholeFile(format, threeLines().sup())
        text.init(0, PlayerId.UNSET, Clock.DEFAULT)
        text.enable(RendererConfiguration.DEFAULT, arrayOf(format), stream, 0, false, true, 0, 0, MediaSource.MediaPeriodId(Any()))
        text.start()
        fun renderAt(us: Long): CueGroup? {
            text.render(us, SystemClock.elapsedRealtime() * 1000)
            shadowOf(Looper.getMainLooper()).idle()
            return shown.lastOrNull()
        }
        /** What shows at [us] once the file is decoded: the pictures' widths. */
        fun settleAt(us: Long, widths: List<Int>): List<List<Int>> {
            val from = shown.size
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                val group = renderAt(us)
                if (shown.size > from && group != null && group.cues.map { it.bitmap!!.width } == widths) break
                Thread.sleep(5)
            }
            return shown.drop(from).map { group -> group.cues.map { it.bitmap!!.width } }
        }
        try {
            assertEquals(listOf(400), settleAt(1_500_000, listOf(400)).last())
            // Forward, back, into a clear, past the last set: the screen
            // clears at once, then shows what is on at the new time.
            for ((at, widths) in listOf(7_000_000L to listOf(200), 1_500_000L to listOf(400), 3_500_000L to emptyList(), 9_000_000L to emptyList())) {
                text.resetPosition(at)
                stream.seek()
                val groups = settleAt(at, widths)
                assertEquals(emptyList(), groups.first(), "after a seek to $at")
                assertEquals(widths, groups.last(), "after a seek to $at")
                assertTrue(groups.all { it.isEmpty() || it == widths }, "after a seek to $at: $groups")
            }
        } finally {
            text.stop()
            text.disable()
            text.release()
        }
    }
}
