package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecCapsQueryTest {
    @Test
    fun videoCeilingsThenTheAudioTheDeviceDecodes() {
        val caps = CodecCapsQuery.build(
            video = listOf(
                VideoDecoderCaps("avc", 1080),
                VideoDecoderCaps("hvc", 1080),
                VideoDecoderCaps("av1", 2160),
            ),
            audio = setOf("aac", "mp3", "opus", "eac3", "ac3"),
        )

        assertEquals("avc:1080,hvc:1080,av1:2160,aac,mp3,opus,ac3,eac3", caps)
    }

    @Test
    fun aSoftwareOnlyCodecGoesBare() {
        val caps = CodecCapsQuery.build(
            video = listOf(VideoDecoderCaps("avc", 2160), VideoDecoderCaps("av1", null), VideoDecoderCaps("hvc", 0)),
            audio = listOf("aac"),
        )

        assertEquals("avc:2160,av1,hvc,aac", caps)
    }

    @Test
    fun audioIsSentEvenWithoutADolbyDecoder() {
        // The phone without AC-3 still says it plays AAC: with video tokens
        // alone chino-stream would re-encode every audio track.
        val caps = CodecCapsQuery.build(listOf(VideoDecoderCaps("avc", 1080)), setOf("mp3", "aac", "opus"))

        assertEquals("avc:1080,aac,mp3,opus", caps)
    }

    @Test
    fun tokensTheServerDoesNotKnowAreNotSent() {
        assertEquals("avc,aac", CodecCapsQuery.build(listOf(VideoDecoderCaps("avc")), setOf("aac", "flac", "dts", "vorbis")))
        assertEquals("", CodecCapsQuery.build(emptyList(), emptySet()))
    }

    /** A phone's decoder MIME types (MediaCodecList) without a Dolby decoder. */
    private val phoneDecoders = listOf(
        "video/avc", "video/hevc", "audio/mp4a-latm", "audio/mpeg", "audio/opus",
        "audio/vorbis", "audio/flac", "audio/raw", "audio/3gpp",
    )

    @Test
    fun noDolbyDecoderNoAc3OrEac3() {
        assertEquals(setOf("aac", "mp3", "opus"), CodecCapsQuery.audioTokensFor(phoneDecoders))
    }

    @Test
    fun aDolbyDecoderAddsAc3AndEac3() {
        assertEquals(
            setOf("aac", "mp3", "opus", "ac3", "eac3"),
            CodecCapsQuery.audioTokensFor(phoneDecoders + "audio/ac3" + "audio/eac3"),
        )
        // E-AC-3 alone (a decoder for one Dolby format) claims only that one.
        assertEquals(setOf("eac3"), CodecCapsQuery.audioTokensFor(listOf("audio/eac3")))
    }

    @Test
    fun vorbisAndMultichannelAacAreNeverSent() {
        val tokens = CodecCapsQuery.audioTokensFor(phoneDecoders)

        assertTrue("vorbis" !in tokens)
        assertTrue("aacmc" !in CodecCapsQuery.AUDIO_TOKENS)
    }

    @Test
    fun mimeTypesMatchInAnyCase() {
        assertEquals(setOf("aac", "ac3"), CodecCapsQuery.audioTokensFor(listOf("AUDIO/MP4A-LATM", "audio/AC3")))
    }

    @Test
    fun aPhoneEndToEnd() {
        val caps = CodecCapsQuery.build(
            video = listOf(VideoDecoderCaps("avc", 1080), VideoDecoderCaps("hvc", 2160)),
            audio = CodecCapsQuery.audioTokensFor(phoneDecoders),
        )

        assertEquals("avc:1080,hvc:2160,aac,mp3,opus", caps)
    }

    @Test
    fun everyAudioTokenIsOneParseCapsKnows() {
        // chino-stream internal/play/ffprobe.go ParseCaps' audio cases.
        val parseCaps = setOf("aac", "mp3", "opus", "vorbis", "ac3", "eac3", "ec3", "aacmc")

        assertTrue(CodecCapsQuery.AUDIO_TOKENS.all { it in parseCaps }, CodecCapsQuery.AUDIO_TOKENS.toString())
    }
}
