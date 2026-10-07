package cloud.nalet.chino.mobile.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import cloud.nalet.chino.mobile.data.api.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Android audio menu over Media3's tracks: each rendition described by
 * the /play/info track of its group and NAME — the 5.1 companion next to its
 * stereo twin — and picked as the Media3 track it is.
 */
class AudioMenuTracksTest {
    /** /play/info with `eac3` in the caps: the demo's Sintel. */
    private val info = listOf(
        TrackInfo(index = 0, codec = "ec-3", language = "eng", name = "English 5.1", default = true, channels = 6, group = "audio-surround", rendition = "a1"),
        TrackInfo(index = 1, codec = "mp4a.40.2", language = "eng", name = "English", channels = 2, group = "audio-surround", rendition = "a0"),
    )

    @Test
    fun theCompanionAndItsTwinAreToldByPlayInfoAndPickedAsTheirMedia3Tracks() {
        // Media3's ids where the sidecars are merged in: "0:group:name".
        val rows = collectAudioTracks(tracks(surround("0:"), stereo("0:", selected = true)), info)

        assertEquals(listOf("English", "English"), rows.map { it.label })
        assertEquals(listOf("E-AC-3 · 5.1", "AAC · Stereo"), rows.map { it.detail })
        assertEquals(listOf("English 5.1", "English"), rows.map { it.name })
        assertEquals(listOf(false, true), rows.map { it.selected })
        assertEquals(listOf(true, true), rows.map { it.playable })
        // The row is the Media3 track an override selects.
        assertEquals("0:audio-surround:English 5.1", rows[0].group.getTrackFormat(rows[0].trackIndex).id)
    }

    @Test
    fun aRenditionIsFoundByGroupAndNameWhereverPlayInfoListsIt() {
        // No sidecars, no prefix; /play/info in the other order.
        val rows = collectAudioTracks(tracks(surround(""), stereo("", selected = true)), info.reversed())

        assertEquals(listOf("E-AC-3 · 5.1", "AAC · Stereo"), rows.map { it.detail })
    }

    @Test
    fun aCompanionTheDeviceCannotPlayIsListedNotPlayable() {
        val rows = collectAudioTracks(tracks(surround("", support = C.FORMAT_UNSUPPORTED_TYPE), stereo("", selected = true)), info)

        assertEquals(listOf(false, true), rows.map { it.playable })
    }

    @Test
    fun withoutPlayInfoMedia3SaysWhatTheTrackIs() {
        val rows = collectAudioTracks(tracks(surround(""), stereo("", selected = true)), emptyList())

        assertEquals(listOf("English", "English"), rows.map { it.label })
        assertEquals(listOf("E-AC-3 · 5.1", "AAC · Stereo"), rows.map { it.detail })
    }

    private fun surround(prefix: String, support: Int = C.FORMAT_HANDLED) = group(
        Format.Builder().setId("${prefix}audio-surround:English 5.1").setLabel("English 5.1").setLanguage("en")
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).build(),
        support,
        selected = false,
    )

    private fun stereo(prefix: String, selected: Boolean) = group(
        Format.Builder().setId("${prefix}audio-surround:English").setLabel("English").setLanguage("en")
            .setSampleMimeType(MimeTypes.AUDIO_AAC).setCodecs("mp4a.40.2").setChannelCount(2).build(),
        C.FORMAT_HANDLED,
        selected,
    )

    private fun group(format: Format, support: Int, selected: Boolean) =
        Tracks.Group(TrackGroup(format), false, intArrayOf(support), booleanArrayOf(selected))

    private fun tracks(vararg groups: Tracks.Group) = Tracks(groups.toList())
}
