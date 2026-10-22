package cloud.nalet.chino.mobile.downloads

import android.net.Uri
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import cloud.nalet.chino.mobile.data.api.PlayInfo
import cloud.nalet.chino.mobile.data.downloads.DownloadPlan
import cloud.nalet.chino.mobile.data.downloads.parseHlsMaster
import cloud.nalet.chino.mobile.data.model.Item
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a plan picks, as Media3 numbers the master: each pick found by its
 *  URL in Media3's own parse, whatever token the master carries - and no
 *  other rendition in the place of one that is not there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamKeysTest {
    private val masterUrl = "https://media.example.com/api/v1/items/film/play/master.m3u8?caps=avc"
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,URI="a0/playlist.m3u8?stream=NOW&v=2",GROUP-ID="audio",LANGUAGE="de",NAME="Deutsch",DEFAULT=YES
        #EXT-X-MEDIA:TYPE=AUDIO,URI="a1/playlist.m3u8?stream=NOW&v=2",GROUP-ID="audio",LANGUAGE="en",NAME="English",DEFAULT=NO
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,AVERAGE-BANDWIDTH=4000000,RESOLUTION=1920x1080,AUDIO="audio"
        v0/playlist.m3u8?stream=NOW&v=2
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="audio"
        v1/playlist.m3u8?stream=NOW&v=2
    """.trimIndent()

    private fun plan(text: String, variantUri: String, audioUris: List<String>): DownloadPlan {
        val parsed = parseHlsMaster(text)
        val variant = parsed.variants.first().copy(uri = variantUri)
        val audios = audioUris.map { u -> parsed.audios.first().copy(uri = u) }
        return DownloadPlan(
            item = Item(id = "film", title = "Film", kind = "movie"),
            seriesTitle = null,
            info = PlayInfo(mode = "packaged"),
            segments = emptyList(),
            masterUrl = masterUrl,
            masterText = text,
            master = parsed,
            variant = variant,
            audios = audios,
            subtitles = emptyList(),
            quality = 720,
            estimatedBytes = null,
            server = "https://media.example.com/api",
        )
    }

    @Test
    fun eachPickIsFoundByItsUrlWhateverTokenSignsIt() {
        val keys = streamKeysFor(plan(master, "v1/playlist.m3u8?stream=EARLIER&v=2", listOf("a1/playlist.m3u8?stream=EARLIER&v=2", "a0/playlist.m3u8?v=2")))
        assertEquals(
            listOf(
                StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, 1),
                StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, 1),
                StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, 0),
            ),
            keys,
        )
    }

    @Test
    fun everySubtitleRenditionComesAlongTheTextTracksOfThePackage() {
        val withSubtitles = master.replace(
            "#EXT-X-STREAM-INF:BANDWIDTH=6000000",
            "#EXT-X-MEDIA:TYPE=SUBTITLES,URI=\"s0/playlist.m3u8?stream=NOW&v=2\",GROUP-ID=\"subs\",LANGUAGE=\"en\",NAME=\"English\"\n" +
                "#EXT-X-MEDIA:TYPE=SUBTITLES,URI=\"s1/playlist.m3u8?stream=NOW&v=2\",GROUP-ID=\"subs\",LANGUAGE=\"de\",NAME=\"Deutsch (forced)\",FORCED=YES\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=6000000",
        )
        val keys = streamKeysFor(plan(withSubtitles, "v1/playlist.m3u8?stream=EARLIER&v=2", listOf("a0/playlist.m3u8?v=2")))
        assertEquals(
            listOf(
                StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, 1),
                StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, 0),
                StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_SUBTITLE, 0),
                StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_SUBTITLE, 1),
            ),
            keys,
        )
    }

    @Test
    fun whatATitleTakesOnThePhoneIsItsMediaAndItsFolderOnce() {
        fun snapshot(state: Int, bytes: Long) = DownloadSnapshot(
            state = state,
            percent = 0f,
            bytes = bytes,
            request = DownloadRequest.Builder("k", Uri.parse(masterUrl)).build(),
        )
        val figures = onPhoneBytes(
            downloads = mapOf(
                "there" to snapshot(Download.STATE_COMPLETED, 48_880_000),
                "coming" to snapshot(Download.STATE_DOWNLOADING, 10_000_000),
                "waiting" to snapshot(Download.STATE_QUEUED, 3_000_000),
                "failed" to snapshot(Download.STATE_FAILED, 7_000_000),
                "going" to snapshot(Download.STATE_REMOVING, 7_000_000),
            ),
            folders = mapOf("there" to 400_000, "coming" to 250_000, "failed" to 300_000, "artwork-only" to 300_000),
        )
        // Media and folder: what the row says, what the line and the
        // questions sum - partly fetched ones too, while they wait.
        assertEquals(mapOf("there" to 49_280_000L, "coming" to 10_250_000L, "waiting" to 3_000_000L), figures)
    }

    @Test
    fun aPickNotInTheMasterFailsTheDownloadRatherThanFetchAnother() {
        assertNull(streamKeysFor(plan(master, "v9/playlist.m3u8?v=2", emptyList())))
        assertNull(streamKeysFor(plan(master, "v1/playlist.m3u8?v=2", listOf("a7/playlist.m3u8"))))
        // A rendition of another package version is another rendition.
        assertNull(streamKeysFor(plan(master, "v1/playlist.m3u8?v=1", emptyList())))
        assertNull(streamKeysFor(plan(master, "v1/playlist.m3u8?v=2", emptyList()).copy(masterText = "not a playlist")))
    }
}
