package cloud.nalet.chino.mobile.ui.player

/** One video codec family as the device decodes it: its `?caps=` token and
 *  the tallest frame its HARDWARE decoder takes — null when only a software
 *  decoder handles it (no known ceiling to report). */
data class VideoDecoderCaps(val token: String, val maxHardwareHeight: Int? = null)

/**
 * The `?caps=` value chino-stream's ParseCaps reads (internal/play/ffprobe.go):
 * the video tokens (`avc`, `hvc`, `av1`), each with an optional
 * `:<maxHeight>` hardware ceiling, then the audio tokens the device decodes.
 *
 * Audio matters as much as video. Once `?caps=` is non-empty chino-stream
 * starts from an EMPTY audio set, so the old video-only value told it the
 * device decodes no audio at all: every non-packaged title was remuxed with
 * its audio re-encoded to stereo AAC — an AAC film the phone plays as it is
 * included — and an AC-3 / E-AC-3 track never reached a device with a Dolby
 * decoder.
 *
 * Shared so the iOS player can build the same value from its own decoders.
 */
object CodecCapsQuery {
    /**
     * The audio tokens the client may advertise, in the order sent. All are
     * in ParseCaps' vocabulary and play from chino-stream's fMP4 segments.
     * Not here: `vorbis` (no Vorbis-in-MP4 path in the player) and `aacmc`
     * (multichannel AAC is left to the server's stereo downmix, as chino-web
     * and the TV client do).
     */
    val AUDIO_TOKENS: List<String> = listOf("aac", "mp3", "opus", "ac3", "eac3")

    /** Video tokens in the order given, then the known [audio] tokens in
     *  [AUDIO_TOKENS] order; comma-separated, "" for nothing at all. */
    fun build(video: List<VideoDecoderCaps>, audio: Collection<String>): String =
        buildList {
            for (v in video) {
                val ceiling = v.maxHardwareHeight?.takeIf { it > 0 }
                add(if (ceiling != null) "${v.token}:$ceiling" else v.token)
            }
            for (token in AUDIO_TOKENS) if (token in audio) add(token)
        }.joinToString(",")
}
