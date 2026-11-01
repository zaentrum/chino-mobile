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
        val bytes = Pgs().set(
            1_000_000,
            composition(0, STATE_EPOCH_START, Shown(0, 100, 60), Shown(1, 660, 950)),
            PALETTE to palette(0, 0, white(1)),
            OBJECT to brokenObject(0),
            *objectSegments(1, 600, 40, colour = 1).map { OBJECT to it }.toTypedArray(),
        ).clears(2_000_000, number = 1).sup()
        assertBox(parseAll(bytes).single().cues.single(), 600, 40, 660, 950)
        assertBox(SupParser().parseToLegacySubtitle(bytes, 0, bytes.size).getCues(1_500_000).single(), 600, 40, 660, 950)
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
}
