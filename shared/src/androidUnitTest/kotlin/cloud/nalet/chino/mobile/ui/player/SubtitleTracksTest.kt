package cloud.nalet.chino.mobile.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The player's text tracks as the menu and the subtitle rule read them: a
 * forced one by its flag or by what its label says - "Forced", "foreign
 * parts only" - and a PGS one as a picture.
 */
class SubtitleTracksTest {
    private fun text(id: String, label: String?, mime: String = MimeTypes.TEXT_VTT, flags: Int = 0) =
        Format.Builder().setId(id).setSampleMimeType(mime).setLanguage("en").setLabel(label).setSelectionFlags(flags).build()

    private fun tracks(vararg formats: Format) = Tracks(
        formats.map { Tracks.Group(TrackGroup(it), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)) },
    )

    @Test
    fun aTrackIsForcedWhereItsFlagOrItsLabelSaysSo() {
        val rows = collectSubtitleTracks(
            tracks(
                // A side-loaded PGS sidecar from a server that sends no flag.
                text("1:sidecar:s1", "English (foreign parts only)", MimeTypes.APPLICATION_PGS),
                text("1:sidecar:s2", "German (Forced)"),
                // A rendition with FORCED=YES and a plain name.
                text("0:subs:English", "English", flags = C.SELECTION_FLAG_FORCED),
                text("1:sidecar:s3", "English"),
                text("1:sidecar:s4", null),
            ),
        )
        assertEquals(listOf(true, true, true, false, false), rows.map { it.forced })
        assertEquals(listOf(true, false, false, false, false), rows.map { it.image })
    }
}
