package cloud.nalet.chino.mobile.ui.zap

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Zap card starts on the master's first variant — the one chino-stream and
 * the prefetcher warm — whatever Media3's bandwidth estimate would pick.
 */
class FirstVariantTrackSelectionTest {
    /** A ladder as chino-stream lists it: the HEVC top rung first, then the
     *  ones under it — the HLS main track group in master order. */
    private val ladder = TrackGroup(
        video("v0", bitrate = 6_000_000, height = 1080),
        video("v1", bitrate = 3_000_000, height = 720),
        video("v2", bitrate = 1_200_000, height = 480),
    )

    @Test
    fun aCardStartsOnTheFirstVariantNotTheEstimatesPick() {
        // 2 Mbit/s: Media3's own start is the 480p rung, which nobody warmed.
        assertEquals(2, startOf(media3s(intArrayOf(0, 1, 2), FixedMeter(2_000_000))))
        assertEquals(0, startOf(firstVariant(intArrayOf(0, 1, 2), FixedMeter(2_000_000))))
        // Above the top rung's bitrate both start there.
        assertEquals(0, startOf(firstVariant(intArrayOf(0, 1, 2), FixedMeter(50_000_000))))
    }

    @Test
    fun aFirstVariantTheDeviceCannotTakeLeavesTheStartToMedia3() {
        // The track selector left v0 out (beyond the decoder): Media3's pick.
        assertEquals(2, startOf(firstVariant(intArrayOf(1, 2), FixedMeter(2_000_000))))
        assertEquals(1, startOf(firstVariant(intArrayOf(1, 2), FixedMeter(10_000_000))))
    }

    private fun firstVariant(tracks: IntArray, meter: BandwidthMeter) =
        StartOnFirstVariant(ladder, tracks, C.TRACK_TYPE_VIDEO, meter, emptyList(), FixedClock)

    /** Media3's own adaptive selection, with its default parameters. */
    private fun media3s(tracks: IntArray, meter: BandwidthMeter): AdaptiveTrackSelection =
        object : AdaptiveTrackSelection(
            ladder, tracks, C.TRACK_TYPE_VIDEO, meter,
            DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS.toLong(),
            DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS.toLong(),
            DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS.toLong(),
            DEFAULT_MAX_WIDTH_TO_DISCARD, DEFAULT_MAX_HEIGHT_TO_DISCARD,
            DEFAULT_BANDWIDTH_FRACTION, DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
            emptyList(), FixedClock,
        ) {}

    /** The variant (index in the track group) a selection starts on: its
     *  first update, with nothing buffered. */
    private fun startOf(selection: AdaptiveTrackSelection): Int {
        selection.enable()
        selection.updateSelectedTrack(0, 0, C.TIME_UNSET, emptyList(), arrayOf<MediaChunkIterator>())
        return selection.selectedIndexInTrackGroup
    }

    private fun video(id: String, bitrate: Int, height: Int): Format = Format.Builder()
        .setId(id)
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setAverageBitrate(bitrate)
        .setPeakBitrate(bitrate)
        .setWidth(height * 16 / 9)
        .setHeight(height)
        .build()

    private class FixedMeter(private val bitrate: Long) : BandwidthMeter {
        override fun getBitrateEstimate(): Long = bitrate
        override fun getTransferListener(): TransferListener? = null
        override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) = Unit
        override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = Unit
    }

    /** A clock that stands still (the JVM has no android.os.SystemClock). */
    private object FixedClock : Clock {
        override fun currentTimeMillis(): Long = 1_000L
        override fun elapsedRealtime(): Long = 1_000L
        override fun uptimeMillis(): Long = 1_000L
        override fun nanoTime(): Long = 1_000_000_000L
        override fun createHandler(looper: Looper, callback: Handler.Callback?): HandlerWrapper =
            throw UnsupportedOperationException()
        override fun onThreadBlocked() = Unit
    }
}
