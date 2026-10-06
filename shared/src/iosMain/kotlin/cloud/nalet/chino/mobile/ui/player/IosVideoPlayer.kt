package cloud.nalet.chino.mobile.ui.player

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicContainsOnlyForcedSubtitles
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionGroup
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerAudiovisualBackgroundPlaybackPolicyContinuesIfPossible
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.CMTimeRangeValue
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.appliesMediaSelectionCriteriaAutomatically
import platform.AVFoundation.asset
import platform.AVFoundation.audiovisualBackgroundPlaybackPolicy
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.errorLog
import platform.AVFoundation.loadMediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.loadedTimeRanges
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.selectMediaOption
import platform.AVFoundation.selectedMediaOptionInMediaSelectionGroup
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.CoreGraphics.CGRectZero
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSUnderlyingErrorKey
import platform.Foundation.NSValue
import platform.Foundation.languageCode
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** What the screen reads off the player on every tick. */
internal data class PlayerSnapshot(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    /** AVPlayer is playing (not paused, not waiting for data). */
    val playing: Boolean = false,
    /** AVPlayer wants to play and waits for data (start, stall, seek). */
    val waiting: Boolean = false,
    /** The resume seek has landed (or there was none): playback may start. */
    val startSeekDone: Boolean = false,
    /** The layer has a frame to show. */
    val firstFrame: Boolean = false,
    val ended: Boolean = false,
)

/** Why playback cannot go on. */
internal data class PlayerFailure(
    /** The master playlist answered 404: there is nothing to stream. */
    val notFound: Boolean,
    /** The panel's heading. */
    val title: String,
    /** For the viewer, under the heading. */
    val message: String,
    /** The technical signature, for the report. */
    val tech: String,
    /** Error domain + code: the report's fingerprint. */
    val signature: String,
)

/** An audio rendition of the master playlist (AVFoundation's audible group). */
internal data class AudioRendition(
    val index: Int,
    /** The rendition's NAME. */
    val name: String,
    /** Its LANGUAGE, as tagged ("eng", "ger"). */
    val language: String?,
    val selected: Boolean,
)

/** A subtitle rendition of the master playlist (AVFoundation's legible
 *  group): what an extra's captions menu lists, AVPlayer drawing the one
 *  picked. A title's subtitles are its sidecars, on the overlay. */
internal data class SubtitleRendition(
    val index: Int,
    /** The rendition's NAME. */
    val name: String,
    /** Its LANGUAGE, as tagged ("en"). */
    val language: String?,
    /** FORCED=YES: it carries only the forced subtitles. */
    val forced: Boolean,
)

/**
 * AVPlayer for chino-stream's HLS master, behind the player screen.
 *
 * Playback holds still until the resume seek has landed: the seek goes out as
 * soon as the item can take it, and only then does the rate go up — so the
 * first frame shown, and the first position reported as played, is the resume
 * point, never 0:00 ([ProgressGuard] backs that up). The screen polls [tick]
 * on the main thread; nothing here is observed by KVO.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosVideoPlayer {
    val player = AVPlayer()
    val playerLayer: AVPlayerLayer = AVPlayerLayer.playerLayerWithPlayer(player)
    val view = PlayerLayerView(playerLayer)

    private var item: AVPlayerItem? = null
    private var generation = 0
    private var startSec = 0.0
    private var startSeek = StartSeek.Done
    private var ended = false
    private var endObserver: Any? = null

    /** The viewer wants it playing: play / pause, and the start of a load. */
    var wantsPlay = true
        private set

    /** Playback speed; applied whenever playback runs. */
    var speed = 1f
        set(value) {
            field = value
            if (wantsPlay && startSeek == StartSeek.Done && player.rate > 0f) player.rate = value
        }

    var audioGroup: AVMediaSelectionGroup? = null
        private set

    /** Called on the main thread once the audio renditions are known. */
    var onAudioGroupLoaded: (() -> Unit)? = null

    /** The master's subtitle renditions, once known; none is shown until
     *  [selectSubtitleRendition] picks one. */
    private var legibleGroup: AVMediaSelectionGroup? = null

    /** Called on the main thread once the subtitle renditions are known. */
    var onLegibleGroupLoaded: (() -> Unit)? = null

    private val pipDelegate = PipDelegate()
    val pip: AVPictureInPictureController? =
        if (AVPictureInPictureController.isPictureInPictureSupported()) {
            AVPictureInPictureController(playerLayer = playerLayer).also {
                it.delegate = pipDelegate
                // Home or a swipe away while it plays: carry on in a window.
                it.canStartPictureInPictureAutomaticallyFromInline = true
            }
        } else {
            null
        }

    /** PiP is up (the delegate says so). */
    val pipActive: Boolean get() = pipDelegate.active

    init {
        playerLayer.videoGravity = AVLayerVideoGravityResizeAspect
        player.automaticallyWaitsToMinimizeStalling = true
        // Audio is picked by the screen (Settings, then the viewer); text is
        // drawn by the screen's overlay, so AVPlayer shows none of its own.
        player.appliesMediaSelectionCriteriaAutomatically = false
        player.allowsExternalPlayback = true
        // Background (UIBackgroundModes audio): the sound goes on without the
        // picture instead of pausing.
        player.audiovisualBackgroundPlaybackPolicy = AVPlayerAudiovisualBackgroundPlaybackPolicyContinuesIfPossible
    }

    /** Plays [url] from [startAtSec]; [play] = start once the seek landed. */
    fun load(url: String, startAtSec: Double, play: Boolean) {
        generation += 1
        removeEndObserver()
        val nsUrl = NSURL.URLWithString(url) ?: return
        val newItem = AVPlayerItem(asset = AVURLAsset(uRL = nsUrl, options = null))
        item = newItem
        ended = false
        audioGroup = null
        legibleGroup = null
        wantsPlay = play
        startSec = startAtSec
        player.pause()
        player.replaceCurrentItemWithPlayerItem(newItem)
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = newItem,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            ended = true
            wantsPlay = false
        }
        startSeek = if (startAtSec > 0.5) StartSeek.Needed else StartSeek.Done
        // Seek the new item before it is ready: AVPlayer then starts loading
        // at the resume point rather than at 0:00. Should it refuse (not
        // seekable yet), the tick seeks again once the item is ready.
        if (startSeek == StartSeek.Needed) issueStartSeek(newItem)
        loadSelectionGroups(newItem, generation)
    }

    /** One look at the player; runs the start seek and the start itself. */
    fun tick(): PlayerSnapshot {
        val it = item ?: return PlayerSnapshot()
        if (it.status == AVPlayerItemStatusReadyToPlay) {
            if (startSeek == StartSeek.Needed) issueStartSeek(it)
            if (startSeek == StartSeek.Done && wantsPlay && player.rate == 0f && !ended) player.rate = speed
        }
        val control = player.timeControlStatus
        return PlayerSnapshot(
            positionMs = seconds(player.currentTime()).toMs(),
            durationMs = seconds(it.duration).toMs(),
            bufferedMs = bufferedEndSec(it).toMs(),
            playing = control == AVPlayerTimeControlStatusPlaying,
            waiting = control == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate ||
                (wantsPlay && startSeek != StartSeek.Done),
            startSeekDone = startSeek == StartSeek.Done,
            firstFrame = playerLayer.readyForDisplay,
            ended = ended,
        )
    }

    private fun issueStartSeek(it: AVPlayerItem) {
        startSeek = StartSeek.Pending
        val gen = generation
        it.seekToTime(
            time = CMTimeMakeWithSeconds(startSec, 600),
            toleranceBefore = CMTimeMake(0, 1),
            toleranceAfter = CMTimeMake(0, 1),
        ) { finished ->
            dispatch_async(dispatch_get_main_queue()) {
                if (gen != generation) return@dispatch_async
                // Interrupted (another seek, not seekable yet): try again on
                // the next tick; otherwise playback may start.
                startSeek = if (finished) StartSeek.Done else StartSeek.Needed
            }
        }
    }

    /** Why the item stopped, or null while it plays on. */
    fun failure(): PlayerFailure? {
        val it = item ?: return null
        if (it.status != AVPlayerItemStatusFailed) return null
        val error = it.error
        val events = it.errorLog()?.events.orEmpty().filterIsInstance<AVPlayerItemErrorLogEvent>()
        val masterMissing = it.masterPlaylistMissing()
        val tech = buildString {
            append("AVPlayerItem failed")
            for (e in errorChain(error)) append("\n").append(e.domain).append(" ").append(e.code).append(": ").append(e.localizedDescription)
            events.takeLast(3).forEach { e ->
                append("\nerror log: ").append(e.errorDomain).append(" ").append(e.errorStatusCode)
                e.errorComment?.let { c -> append(" — ").append(c) }
                e.URI?.let { u -> append(" @ ").append(u.substringBefore('?')) }
            }
        }
        val reason = error?.localizedDescription?.trim()?.trimEnd('.')?.replaceFirstChar { it.uppercase() }
            ?: "The stream could not be played"
        return PlayerFailure(
            notFound = masterMissing,
            title = if (masterMissing) "Not available yet" else "Playback failed",
            message = if (masterMissing) "This title isn't available to stream yet." else "$reason.",
            tech = tech,
            signature = error?.let { "${it.domain} ${it.code}" } ?: "AVPlayerItemStatusFailed",
        )
    }

    fun play() {
        ended = false
        wantsPlay = true
        if (startSeek == StartSeek.Done && item?.status == AVPlayerItemStatusReadyToPlay) player.rate = speed
    }

    fun pause() {
        wantsPlay = false
        player.pause()
    }

    /** A seek the viewer made (scrub, ±10 s, skip, remote). */
    fun seekTo(ms: Long) {
        val it = item ?: return
        ended = false
        if (startSeek != StartSeek.Done) {
            // Still before the start: the seek replaces the resume point.
            startSec = ms / 1000.0
            startSeek = StartSeek.Needed
            return
        }
        it.seekToTime(
            time = CMTimeMakeWithSeconds(ms / 1000.0, 600),
            toleranceBefore = CMTimeMake(0, 1),
            toleranceAfter = CMTimeMake(0, 1),
            completionHandler = null,
        )
    }

    var volume: Float
        get() = player.volume
        set(value) { player.volume = value }

    var muted: Boolean
        get() = player.muted
        set(value) { player.muted = value }

    fun audioRenditions(): List<AudioRendition> {
        val group = audioGroup ?: return emptyList()
        val it = item ?: return emptyList()
        val selected = it.selectedMediaOptionInMediaSelectionGroup(group)
        return group.options.filterIsInstance<AVMediaSelectionOption>().mapIndexed { i, o ->
            AudioRendition(index = i, name = o.displayName, language = o.extendedLanguageTag ?: o.locale?.languageCode, selected = o == selected)
        }
    }

    fun selectAudio(index: Int) {
        val group = audioGroup ?: return
        val option = group.options.getOrNull(index) as? AVMediaSelectionOption ?: return
        item?.selectMediaOption(option, inMediaSelectionGroup = group)
    }

    fun subtitleRenditions(): List<SubtitleRendition> {
        val group = legibleGroup ?: return emptyList()
        return group.options.filterIsInstance<AVMediaSelectionOption>().mapIndexed { i, o ->
            SubtitleRendition(
                index = i,
                name = o.displayName,
                language = o.extendedLanguageTag ?: o.locale?.languageCode,
                forced = o.hasMediaCharacteristic(AVMediaCharacteristicContainsOnlyForcedSubtitles),
            )
        }
    }

    /** Shows the master's subtitle rendition [index], AVPlayer drawing it, or
     *  none (null). */
    fun selectSubtitleRendition(index: Int?) {
        val group = legibleGroup ?: return
        val option = index?.let { group.options.getOrNull(it) as? AVMediaSelectionOption }
        if (option == null && (index != null || !group.allowsEmptySelection)) return
        item?.selectMediaOption(option, inMediaSelectionGroup = group)
    }

    private fun loadSelectionGroups(forItem: AVPlayerItem, gen: Int) {
        forItem.asset.loadMediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible) { group, _ ->
            dispatch_async(dispatch_get_main_queue()) {
                if (gen != generation) return@dispatch_async
                audioGroup = group
                onAudioGroupLoaded?.invoke()
            }
        }
        forItem.asset.loadMediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible) { group, _ ->
            dispatch_async(dispatch_get_main_queue()) {
                if (gen != generation || group == null) return@dispatch_async
                // In-playlist subtitles would draw under the overlay's: off,
                // until one is picked (an extra's, which has no overlay).
                if (group.allowsEmptySelection) forItem.selectMediaOption(null, inMediaSelectionGroup = group)
                legibleGroup = group
                onLegibleGroupLoaded?.invoke()
            }
        }
    }

    fun togglePip() {
        val p = pip ?: return
        if (p.pictureInPictureActive) p.stopPictureInPicture() else if (p.pictureInPicturePossible) p.startPictureInPicture()
    }

    /** Tears the item down (a give-up): no more loading, no more sound. */
    fun stop() {
        generation += 1
        removeEndObserver()
        wantsPlay = false
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        item = null
    }

    fun release() {
        if (pip?.pictureInPictureActive == true) pip.stopPictureInPicture()
        pip?.delegate = null
        stop()
    }

    private fun removeEndObserver() {
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
    }

    private fun bufferedEndSec(it: AVPlayerItem): Double {
        val now = seconds(player.currentTime())
        var end = 0.0
        for (v in it.loadedTimeRanges) {
            val range = (v as? NSValue)?.CMTimeRangeValue ?: continue
            val (s, e) = range.useContents { seconds(start.readValue()) to seconds(CMTimeRangeGetEnd(this.readValue())) }
            if (now >= s - 0.5 && now <= e && e > end) end = e
        }
        return end
    }

    private enum class StartSeek { Needed, Pending, Done }
}

/** The AVPlayerLayer's host view: the layer follows the view's bounds. */
@OptIn(ExperimentalForeignApi::class)
internal class PlayerLayerView(private val playerLayer: AVPlayerLayer) : UIView(frame = CGRectZero.readValue()) {
    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}

private class PipDelegate : NSObject(), AVPictureInPictureControllerDelegateProtocol {
    var active = false

    override fun pictureInPictureControllerDidStartPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
        active = true
    }

    override fun pictureInPictureControllerDidStopPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
        active = false
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler: (Boolean) -> Unit,
    ) {
        // The player screen is still there behind the window.
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler(true)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun seconds(t: kotlinx.cinterop.CValue<platform.CoreMedia.CMTime>): Double =
    CMTimeGetSeconds(t).takeIf { it.isFinite() && it >= 0 } ?: 0.0

private fun Double.toMs(): Long = (this * 1000).toLong()

/** The failed item's master playlist answered 404, so there is nothing to
 *  stream: its error log says so, or CoreMedia's HTTP 404 (-12938) is in its
 *  error. The player's rule, a title's and an extra's. */
@OptIn(ExperimentalForeignApi::class)
internal fun AVPlayerItem.masterPlaylistMissing(): Boolean {
    val events = errorLog()?.events.orEmpty().filterIsInstance<AVPlayerItemErrorLogEvent>()
    return events.any { e -> e.errorStatusCode == 404L && e.URI?.contains(".m3u8") == true } ||
        errorChain(error).any { e -> e.domain == "CoreMediaErrorDomain" && e.code == -12938L }
}

/** The error and its underlying errors, outermost first. */
private fun errorChain(error: NSError?): List<NSError> {
    val out = ArrayList<NSError>()
    var e = error
    while (e != null && out.size < 5) {
        out += e
        e = e.userInfo[NSUnderlyingErrorKey] as? NSError
    }
    return out
}
