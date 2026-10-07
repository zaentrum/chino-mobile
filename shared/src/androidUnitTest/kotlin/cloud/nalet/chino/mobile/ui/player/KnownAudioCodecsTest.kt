package cloud.nalet.chino.mobile.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import cloud.nalet.chino.mobile.data.api.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The master of a client served the 5.1 companions, read with /play/info's
 * codecs: each rendition one codec, each variant one audio codec — what
 * Media3 needs to prepare from the master alone (HlsMediaPeriod: one audio
 * codec in a rendition's and in the variant's CODECS).
 */
class KnownAudioCodecsTest {
    /** The demo's Sintel with `eac3` in the caps, as Media3's parser reads
     *  it: every rendition of the group with the variant's two audio codecs. */
    private val master = playlist(
        variant("hvc1.1.2.L120.90,ec-3,mp4a.40.2", "audio-surround"),
        rendition("audio-surround", "English 5.1", channels = 6, default = true),
        rendition("audio-surround", "English", channels = 2),
    )

    /** /play/info's audio_tracks for the same caps. */
    private val info = listOf(
        TrackInfo(index = 0, codec = "ec-3", language = "eng", name = "English 5.1", default = true, channels = 6, group = "audio-surround", rendition = "a1"),
        TrackInfo(index = 1, codec = "mp4a.40.2", language = "eng", name = "English", channels = 2, group = "audio-surround", rendition = "a0"),
    )

    @Test
    fun eachRenditionReadsAsItsOwnCodecAndTheVariantAsTheOneItStartsOn() {
        val read = master.withAudioCodecs(info)

        assertEquals(listOf("ec-3", "mp4a.40.2"), read.audios.map { it.format.codecs })
        assertEquals(listOf(MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AAC), read.audios.map { it.format.sampleMimeType })
        // What the rendition was otherwise stays: its name, channels, DEFAULT.
        assertEquals(listOf("English 5.1", "English"), read.audios.map { it.format.label })
        assertEquals(listOf(6, 2), read.audios.map { it.format.channelCount })
        assertEquals(C.SELECTION_FLAG_DEFAULT, read.audios[0].format.selectionFlags)
        // The video's codec and the DEFAULT rendition's.
        assertEquals("hvc1.1.2.L120.90,ec-3", read.variants.single().format.codecs)
        // Media3's conditions for preparing without loading media.
        assertEquals(listOf(1, 1), read.audios.map { Util.getCodecCountOfType(it.format.codecs, C.TRACK_TYPE_AUDIO) })
        assertEquals(1, Util.getCodecCountOfType(read.variants.single().format.codecs, C.TRACK_TYPE_AUDIO))
    }

    @Test
    fun theOnTheFlySpellingsAreTheCodecsMedia3Reads() {
        // chino-stream's on-the-fly union names them "eac3" and "aac".
        val read = master.withAudioCodecs(info.map { it.copy(codec = if (it.channels == 6) "eac3" else "aac") })

        assertEquals(listOf("ec-3", "mp4a.40.2"), read.audios.map { it.format.codecs })
        assertEquals("ac-3", mediaCodec("ac3"))
        assertEquals("mp4a.40.2", mediaCodec("mp4a"))
        assertNull(mediaCodec("dts"))
        assertNull(mediaCodec(null))
    }

    @Test
    fun aGroupPlayInfoDoesNotDescribeWhollyIsReadAsServed() {
        // A rendition of another name: Media3 tells them apart from the media.
        assertSame(master, master.withAudioCodecs(listOf(info[0], info[1].copy(name = "English (2)"))))
        // A codec this does not know.
        assertSame(master, master.withAudioCodecs(listOf(info[0], info[1].copy(codec = "dts"))))
        // Another group's tracks.
        assertSame(master, master.withAudioCodecs(info.map { it.copy(group = "aud") }))
        // No play info.
        assertSame(master, master.withAudioCodecs(emptyList()))
    }

    @Test
    fun theStereoGroupNeedsNothing() {
        val stereo = playlist(variant("hvc1.1.2.L120.90,mp4a.40.2", "audio"), rendition("audio", "English", channels = 2, codecs = "mp4a.40.2"))

        assertSame(stereo, stereo.withAudioCodecs(listOf(TrackInfo(index = 0, codec = "mp4a", language = "eng", name = "English"))))
    }

    private fun playlist(variant: HlsMultivariantPlaylist.Variant, vararg audios: HlsMultivariantPlaylist.Rendition) =
        HlsMultivariantPlaylist(
            "https://media.example.org/api/v1/items/m1/play/master.m3u8",
            emptyList(), listOf(variant), emptyList(), audios.toList(), emptyList(), emptyList(),
            null, null, true, emptyMap(), emptyList(),
        )

    private fun variant(codecs: String, audioGroup: String) = HlsMultivariantPlaylist.Variant(
        Uri.EMPTY,
        Format.Builder().setId("0").setCodecs(codecs).setWidth(1920).setHeight(818).build(),
        null, audioGroup, null, null,
    )

    /** A TYPE=AUDIO rendition as Media3's parser makes it: its codecs those
     *  of the variants naming its group. */
    private fun rendition(group: String, name: String, channels: Int, default: Boolean = false, codecs: String = "ec-3,mp4a.40.2") =
        HlsMultivariantPlaylist.Rendition(
            Uri.EMPTY,
            Format.Builder()
                .setId("$group:$name")
                .setLabel(name)
                .setLanguage("en")
                .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
                .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0)
                .setCodecs(codecs)
                .setSampleMimeType(MimeTypes.getMediaMimeType(codecs))
                .setChannelCount(channels)
                .build(),
            group,
            name,
        )
}
