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

    /** The decoder MIME type (MediaCodec's) behind each [AUDIO_TOKENS] entry. */
    val AUDIO_DECODER_MIMES: Map<String, String> = mapOf(
        "aac" to "audio/mp4a-latm",
        "mp3" to "audio/mpeg",
        "opus" to "audio/opus",
        "ac3" to "audio/ac3",
        "eac3" to "audio/eac3",
    )

    /**
     * The audio tokens for a device whose decoders take [decoderMimes]: a
     * token when some decoder takes its MIME type, hardware or software —
     * audio decodes cheaply. So AC-3 / E-AC-3 only where the device ships a
     * Dolby decoder; passing the bitstream through to a receiver is not
     * counted, and without a decoder the server's stereo AAC is the answer.
     */
    fun audioTokensFor(decoderMimes: Collection<String>): Set<String> {
        val mimes = decoderMimes.mapTo(HashSet()) { it.lowercase() }
        return AUDIO_DECODER_MIMES.filterValues { it in mimes }.keys
    }

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
