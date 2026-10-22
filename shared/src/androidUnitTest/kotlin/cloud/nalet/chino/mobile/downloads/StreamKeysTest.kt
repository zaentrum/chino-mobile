package cloud.nalet.chino.mobile.downloads

import androidx.media3.common.StreamKey
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
    fun aPickNotInTheMasterFailsTheDownloadRatherThanFetchAnother() {
        assertNull(streamKeysFor(plan(master, "v9/playlist.m3u8?v=2", emptyList())))
        assertNull(streamKeysFor(plan(master, "v1/playlist.m3u8?v=2", listOf("a7/playlist.m3u8"))))
        // A rendition of another package version is another rendition.
        assertNull(streamKeysFor(plan(master, "v1/playlist.m3u8?v=1", emptyList())))
        assertNull(streamKeysFor(plan(master, "v1/playlist.m3u8?v=2", emptyList()).copy(masterText = "not a playlist")))
    }
}
