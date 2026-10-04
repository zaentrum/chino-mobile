package cloud.nalet.chino.mobile.ui.zap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The audio a Zap card starts on, as chino-stream warms it with the master. */
class ZapStartTest {
    private data class Rendition(val uri: String, val group: String, val default: Boolean = false)

    private fun start(firstVariantGroup: String?, renditions: List<Rendition>) =
        startAudioRendition(firstVariantGroup, renditions, { it.group }, { it.default })?.uri

    // A ladder master as chino-stream serves it to a device that decodes
    // E-AC-3: the stereo group first (its variants come first), then the 5.1
    // group, each with one DEFAULT.
    private val ladder = listOf(
        Rendition("a0/playlist.m3u8", "audio"),
        Rendition("a1/playlist.m3u8", "audio", default = true),
        Rendition("a2/playlist.m3u8", "audio-surround"),
        Rendition("a3/playlist.m3u8", "audio-surround", default = true),
    )

    @Test
    fun theDefaultOfTheFirstVariantsGroupNotTheSurroundOne() {
        assertEquals("a1/playlist.m3u8", start("audio", ladder))
        // A first variant in the 5.1 group would start there.
        assertEquals("a3/playlist.m3u8", start("audio-surround", ladder))
    }

    @Test
    fun aGroupWithoutADefaultStartsOnItsFirst() {
        // Packages from before renditions.json mark no audio DEFAULT.
        val old = listOf(Rendition("a0/playlist.m3u8", "audio"), Rendition("a1/playlist.m3u8", "audio"))
        assertEquals("a0/playlist.m3u8", start("audio", old))
    }

    @Test
    fun aVariantWithItsOwnAudioHasNoRenditionToWarm() {
        assertNull(start(null, ladder))
        assertNull(start("", ladder))
        assertNull(start("audio-ac3", ladder))
    }
}
