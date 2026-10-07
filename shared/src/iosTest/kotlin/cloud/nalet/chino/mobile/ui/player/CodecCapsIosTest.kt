package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The iOS caps on the simulator: H.264 first, AAC and MP3 always, only
 *  tokens chino-stream's ParseCaps knows, never vorbis / aacmc / opus, and
 *  `native` last: AVPlayer picks between audio groups itself. */
class CodecCapsIosTest {
    private val tokens = CodecCaps.queryParam.split(',')

    @Test
    fun videoAndTheNativeAudioAreAdvertised() {
        assertEquals("avc", tokens.first(), CodecCaps.queryParam)
        assertTrue("aac" in tokens && "mp3" in tokens, CodecCaps.queryParam)
    }

    @Test
    fun onlyTokensTheServerKnowsAndNoneLeftOut() {
        val known = setOf("avc", "hvc", "av1") + CodecCapsQuery.AUDIO_TOKENS + CodecCapsQuery.NATIVE_TOKEN
        assertTrue(tokens.all { it.substringBefore(':') in known }, CodecCaps.queryParam)
        assertTrue(tokens.none { it in setOf("vorbis", "aacmc", "opus") }, CodecCaps.queryParam)
    }

    @Test
    fun theAppSaysItPlaysTheMasterWithAVPlayer() {
        assertEquals(CodecCapsQuery.NATIVE_TOKEN, tokens.last(), CodecCaps.queryParam)
    }
}
