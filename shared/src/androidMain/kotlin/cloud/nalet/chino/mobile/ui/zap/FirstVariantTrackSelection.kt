package cloud.nalet.chino.mobile.ui.zap

import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.ImmutableList

/**
 * Adaptive track selections that start on the variant the master lists
 * first, then adapt as Media3's own do.
 *
 * chino-stream lists first the variant a client is to start on and warms it
 * when the master is fetched; the Zap prefetch warms the same one
 * ([ZapPrefetcher], ZapStart.kt). Media3's adaptive selection starts on
 * the best variant under its bandwidth estimate instead — on a ladder, often
 * a rung nobody warmed, so the card started cold after its bytes had been
 * fetched. (chino-web starts hls.js on the first variant for the same reason,
 * lib/hlsConfig.ts.)
 *
 * The master's first variant is the first format of the HLS main track
 * group, which lists the variants in master order. Until a chunk of it is
 * buffered the selection is that variant; from then on Media3 adapts from
 * what is in the buffer, with its default parameters. Where the track
 * selector left the first variant out (a decoder that cannot take it), the
 * start is Media3's own.
 */
internal class FirstVariantTrackSelectionFactory : AdaptiveTrackSelection.Factory() {
    override fun createAdaptiveTrackSelection(
        group: TrackGroup,
        tracks: IntArray,
        type: Int,
        bandwidthMeter: BandwidthMeter,
        adaptationCheckpoints: ImmutableList<AdaptiveTrackSelection.AdaptationCheckpoint>,
    ): AdaptiveTrackSelection = StartOnFirstVariant(group, tracks, type, bandwidthMeter, adaptationCheckpoints)
}

internal class StartOnFirstVariant(
    group: TrackGroup,
    tracks: IntArray,
    type: Int,
    bandwidthMeter: BandwidthMeter,
    adaptationCheckpoints: List<AdaptiveTrackSelection.AdaptationCheckpoint>,
    clock: Clock = Clock.DEFAULT,
) : AdaptiveTrackSelection(
    group,
    tracks,
    type,
    bandwidthMeter,
    DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS.toLong(),
    DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS.toLong(),
    DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS.toLong(),
    DEFAULT_MAX_WIDTH_TO_DISCARD,
    DEFAULT_MAX_HEIGHT_TO_DISCARD,
    DEFAULT_BANDWIDTH_FRACTION,
    DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
    adaptationCheckpoints,
    clock,
) {
    /** The first variant's index in this selection, or unset when it is not in it. */
    private val first = indexOf(0)

    /** Nothing of this selection buffered yet: the first variant stands. */
    private var starting = first != C.INDEX_UNSET

    override fun updateSelectedTrack(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        availableDurationUs: Long,
        queue: List<MediaChunk>,
        mediaChunkIterators: Array<MediaChunkIterator>,
    ) {
        if (queue.isNotEmpty()) starting = false
        super.updateSelectedTrack(playbackPositionUs, bufferedDurationUs, availableDurationUs, queue, mediaChunkIterators)
    }

    override fun getSelectedIndex(): Int = if (starting) first else super.getSelectedIndex()

    override fun getSelectionReason(): Int = if (starting) C.SELECTION_REASON_INITIAL else super.getSelectionReason()
}
