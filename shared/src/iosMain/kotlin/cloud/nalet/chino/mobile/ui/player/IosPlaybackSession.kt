package cloud.nalet.chino.mobile.ui.player

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.create
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoMediaTypeVideo
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyMediaType
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage

/**
 * The audio session for playback: the Playback category (sound with the
 * silent switch on, and in the background — UIBackgroundModes audio), in the
 * movie mode, active while the player is on screen. An interruption (a call,
 * Siri) pauses; its end resumes when the system says it should.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAudioSession(
    private val onInterrupted: () -> Unit,
    private val onResumable: () -> Unit,
) {
    private var observer: Any? = null

    fun activate() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeMoviePlayback, options = 0u, error = null)
        session.setActive(true, error = null)
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = session,
            queue = NSOperationQueue.mainQueue,
        ) { note ->
            val info = note?.userInfo ?: return@addObserverForName
            val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedLongValue
            when (type) {
                AVAudioSessionInterruptionTypeBegan -> onInterrupted()
                AVAudioSessionInterruptionTypeEnded -> {
                    val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedLongValue ?: 0u
                    if (options and AVAudioSessionInterruptionOptionShouldResume != 0uL) onResumable()
                }
            }
        }
    }

    fun deactivate() {
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observer = null
        // Let music another app paused come back.
        AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
    }
}

/** What the lock screen, Control Center and a remote may do with the player. */
internal interface RemoteControls {
    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekBy(deltaMs: Long)
    fun seekTo(ms: Long)

    /** Null when there is no next episode. */
    val next: (() -> Unit)?
}

/**
 * Now Playing (title, artwork, position, rate) and the remote commands
 * (play / pause / ±10 s / scrub / next episode) while the player is on
 * screen. Elapsed time is published at each state change — the system runs
 * the clock from the rate in between.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosNowPlaying(private val controls: RemoteControls) {
    private val center = MPRemoteCommandCenter.sharedCommandCenter()
    private val targets = ArrayList<Pair<MPRemoteCommand, Any>>()
    private var title = ""
    private var artwork: MPMediaItemArtwork? = null

    fun start(title: String) {
        this.title = title
        add(center.playCommand) { controls.play() }
        add(center.pauseCommand) { controls.pause() }
        add(center.togglePlayPauseCommand) { controls.togglePlayPause() }
        center.skipForwardCommand.preferredIntervals = listOf(NSNumber(double = 10.0))
        center.skipBackwardCommand.preferredIntervals = listOf(NSNumber(double = 10.0))
        add(center.skipForwardCommand) { controls.seekBy(10_000) }
        add(center.skipBackwardCommand) { controls.seekBy(-10_000) }
        targets += center.changePlaybackPositionCommand to center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val e = event as? MPChangePlaybackPositionCommandEvent
            if (e == null) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                controls.seekTo((e.positionTime * 1000).toLong())
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        val next = controls.next
        center.nextTrackCommand.enabled = next != null
        if (next != null) add(center.nextTrackCommand) { next() }
        center.previousTrackCommand.enabled = false
    }

    private fun add(command: MPRemoteCommand, action: () -> Unit) {
        command.enabled = true
        targets += command to command.addTargetWithHandler { _ ->
            action()
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    /** The poster (or backdrop) bytes, once fetched. */
    fun setArtwork(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        val image = UIImage.imageWithData(bytes.toNSData()) ?: return
        artwork = MPMediaItemArtwork(boundsSize = image.size) { _ -> image }
    }

    fun update(positionMs: Long, durationMs: Long, rate: Float) {
        val info = HashMap<Any?, Any?>()
        info[MPMediaItemPropertyTitle] = title
        info[MPNowPlayingInfoPropertyMediaType] = NSNumber(unsignedLongLong = MPNowPlayingInfoMediaTypeVideo)
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = NSNumber(double = positionMs / 1000.0)
        info[MPNowPlayingInfoPropertyPlaybackRate] = NSNumber(double = rate.toDouble())
        if (durationMs > 0) info[MPMediaItemPropertyPlaybackDuration] = NSNumber(double = durationMs / 1000.0)
        artwork?.let { info[MPMediaItemPropertyArtwork] = it }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    fun stop() {
        for ((command, target) in targets) command.removeTarget(target)
        targets.clear()
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}
