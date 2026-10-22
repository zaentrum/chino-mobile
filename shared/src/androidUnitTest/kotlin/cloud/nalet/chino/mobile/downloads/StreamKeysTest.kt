package cloud.nalet.chino.mobile.downloads

import android.net.Uri
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import cloud.nalet.chino.mobile.data.api.PlayInfo
import cloud.nalet.chino.mobile.data.downloads.DownloadPlan
import cloud.nalet.chino.mobile.data.downloads.DownloadedTitle
import cloud.nalet.chino.mobile.data.downloads.OwnerClaim
import cloud.nalet.chino.mobile.data.downloads.parseHlsMaster
import cloud.nalet.chino.mobile.data.model.Item
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun aDownloadLeftFailedInTheIndexHasItsMediaFreedAtTheStart() {
        fun snapshot(state: Int) = DownloadSnapshot(
            state = state,
            percent = 0f,
            bytes = 7_000_000,
            request = DownloadRequest.Builder("k", Uri.parse(masterUrl)).build(),
        )
        fun title(id: String, owned: Boolean = true) = DownloadedTitle(
            itemId = id,
            item = Item(id = id, title = id, kind = "movie"),
            quality = "Medium (720p)",
            server = "s",
            owners = if (owned) listOf(OwnerClaim("anna", addedAtMs = 1, checkedAtMs = 1)) else emptyList(),
        )
        val titles = listOf(title("failed"), title("fine"), title("nobodys", owned = false), title("odd")).associateBy { it.key }
        val downloads = mapOf(
            title("failed").key to snapshot(Download.STATE_FAILED),
            title("fine").key to snapshot(Download.STATE_COMPLETED),
            title("nobodys").key to snapshot(Download.STATE_STOPPED),
            "gone~0" to snapshot(Download.STATE_QUEUED),
            "new~0" to snapshot(Download.STATE_QUEUED),
        )
        val freed = mediaToFreeAtStart(titles, downloads, starting = setOf("new~0"), unreadable = listOf(title("odd").key))
        assertEquals(setOf("gone~0", title("nobodys").key, title("odd").key, title("failed").key), freed.toSet())
    }

    @Test
    fun aTitleCountsAsOnThePhoneByItsMediaNotItsArtwork() {
        fun snapshot(state: Int, bytes: Long) = DownloadSnapshot(
            state = state,
            percent = 0f,
            bytes = bytes,
            request = DownloadRequest.Builder("k", Uri.parse(masterUrl)).build(),
        )
        // Partly fetched, done, or waiting with what it fetched: on the phone.
        assertTrue(countsAsOnThePhone(snapshot(Download.STATE_DOWNLOADING, 69_000_000)))
        assertTrue(countsAsOnThePhone(snapshot(Download.STATE_COMPLETED, 450_000_000)))
        assertTrue(countsAsOnThePhone(snapshot(Download.STATE_QUEUED, 3_000_000)))
        // Queued with nothing fetched - its poster in its folder only - or
        // none at all, failed, going: not.
        assertFalse(countsAsOnThePhone(snapshot(Download.STATE_QUEUED, 0)))
        assertFalse(countsAsOnThePhone(snapshot(Download.STATE_STOPPED, 0)))
        assertFalse(countsAsOnThePhone(null))
        assertFalse(countsAsOnThePhone(snapshot(Download.STATE_FAILED, 7_000_000)))
        assertFalse(countsAsOnThePhone(snapshot(Download.STATE_REMOVING, 7_000_000)))
        assertEquals(0L, mediaBytesOf(snapshot(Download.STATE_FAILED, 7_000_000)))
        assertEquals(69_000_000L, mediaBytesOf(snapshot(Download.STATE_DOWNLOADING, 69_000_000)))
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
