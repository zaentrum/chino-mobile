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
 * `eac3` (and `ac3`) also gets a client a package's 5.1 companions: the
 * master's one audio group then holds the E-AC-3 5.1 tracks next to their
 * stereo twins ("English 5.1", "English"), which /play/info lists with their
 * group and name. So the tokens go only to a device that plays the codec.
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

    /** The Dolby tokens, which also get a client the 5.1 companions: a
     *  device plays them with a decoder, or by passing the bitstream on to
     *  the output the sound goes to (a receiver, a TV). */
    val SURROUND_TOKENS: Set<String> = setOf("ac3", "eac3")

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
     * audio decodes cheaply. AC-3 / E-AC-3 where the device ships a Dolby
     * decoder, and where the output passes the bitstream through as it is
     * ([passthrough], the [SURROUND_TOKENS] it takes); without either the
     * server's stereo AAC is the answer.
     */
    fun audioTokensFor(decoderMimes: Collection<String>, passthrough: Collection<String> = emptySet()): Set<String> {
        val mimes = decoderMimes.mapTo(HashSet()) { it.lowercase() }
        return AUDIO_DECODER_MIMES.filter { (token, mime) -> mime in mimes || (token in SURROUND_TOKENS && token in passthrough) }.keys
    }

    /**
     * The token of a client that plays the master with the platform's own
     * HLS player, which picks between audio groups itself (AVPlayer).
     * chino-stream then serves Apple's shape: a group per codec, each with
     * the same members under the same names, the 5.1 group's played by the
     * companions; /play/info lists the stereo tracks, each with `surround`
     * where a companion plays it. It says nothing of what the client decodes.
     */
    const val NATIVE_TOKEN: String = "native"

    /** Video tokens in the order given, then the known [audio] tokens in
     *  [AUDIO_TOKENS] order, then [NATIVE_TOKEN] for a [native] player;
     *  comma-separated, "" for nothing at all. */
    fun build(video: List<VideoDecoderCaps>, audio: Collection<String>, native: Boolean = false): String =
        buildList {
            for (v in video) {
                val ceiling = v.maxHardwareHeight?.takeIf { it > 0 }
                add(if (ceiling != null) "${v.token}:$ceiling" else v.token)
            }
            for (token in AUDIO_TOKENS) if (token in audio) add(token)
            if (native) add(NATIVE_TOKEN)
        }.joinToString(",")
}
