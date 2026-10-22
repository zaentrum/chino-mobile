package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.model.Item
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The load request in the Cast SDK's own classes: the Default Media
 * Receiver's HLS in fMP4 at the TV's master, the side-loaded subtitles, the
 * metadata, the phone's marker. The SDK's MediaMetadata keeps its values in a
 * Bundle, which the unit tests' android.jar only stubs: the metadata is read
 * through [CastMetadataSink] instead.
 */
class CastMediaInfoTest {
    private val base = "https://media.example.com/api"
    private val token = "tok123"

    private val sintel = Item(id = "m1", title = "Sintel", kind = "movie", durationMs = 888_000)
    private val episode = Item(
        id = "e3",
        title = "The Well",
        kind = "episode",
        parentId = "s1",
        seasonNumber = 1,
        episodeNumber = 3,
        durationMs = 2_640_000,
    )

    private fun media(
        item: Item = sintel,
        seriesTitle: String? = null,
        extraId: String? = null,
        start: CastSubtitleStart = CastSubtitleStart.Sidecar("s2"),
    ) = castMediaFor(
        CastMediaInput(
            apiBase = base,
            streamToken = token,
            itemId = item.id,
            extraId = extraId,
            phoneMasterUrl = "$base/v1/items/${item.id}/play/master.m3u8?stream=$token&caps=hvc:2160,aac,eac3&q=high",
            item = item,
            seriesTitle = seriesTitle,
            extraHeading = extraId?.let { "${item.title} · Trailer" },
            durationMs = item.durationMs,
            startMs = 754_000,
            autoplay = true,
            sidecars = listOf(
                SidecarSubtitle(id = "s1", label = "English", lang = "eng", url = "$base/v1/play/subs/s1.vtt?stream=$token", format = "webvtt"),
                SidecarSubtitle(id = "s2", lang = "ger", url = "$base/v1/play/subs/s2.vtt?stream=$token", format = "srt", forced = true),
            ),
            subtitleStart = start,
            writesProgress = extraId == null,
        ),
    )

    private class Sink : CastMetadataSink {
        val strings = LinkedHashMap<String, String>()
        val ints = LinkedHashMap<String, Int>()
        val images = mutableListOf<Triple<String, Int, Int>>()
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun addImage(url: String, width: Int, height: Int) { images += Triple(url, width, height) }
    }

    @Test
    fun theTvPlaysItsOwnMasterAsHlsInFmp4BufferedOfTheTitlesLength() {
        val info = media().toMediaInfo(owner = "k", metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE))
        assertEquals("m1", info.contentId)
        assertEquals("$base/v1/items/m1/play/master.m3u8?stream=$token&caps=avc:1080,aac", info.contentUrl)
        assertEquals("application/x-mpegURL", info.contentType)
        assertEquals(MediaInfo.STREAM_TYPE_BUFFERED, info.streamType)
        assertEquals(HlsSegmentFormat.FMP4, info.hlsSegmentFormat)
        assertEquals(HlsVideoSegmentFormat.FMP4, info.hlsVideoSegmentFormat)
        assertEquals(888_000, info.streamDuration)
    }

    @Test
    fun theSidecarsAreSideLoadedWebVttTracks() {
        val tracks = media().toMediaInfo(owner = "k", metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)).mediaTracks.orEmpty()
        assertEquals(listOf(1000L, 1001L), tracks.map { it.id })
        tracks.forEach {
            assertEquals(MediaTrack.TYPE_TEXT, it.type)
            assertEquals(MediaTrack.SUBTYPE_SUBTITLES, it.subtype)
            assertEquals("text/vtt", it.contentType)
        }
        assertEquals(listOf("$base/v1/play/subs/s1.vtt?stream=$token", "$base/v1/play/subs/s2.vtt?stream=$token"), tracks.map { it.contentId })
        assertEquals(listOf("en", "de"), tracks.map { it.language })
        assertEquals(listOf("English", "German (forced)"), tracks.map { it.name })
        assertEquals(listOf(listOf(MediaTrack.ROLE_SUBTITLE), listOf(MediaTrack.ROLE_FORCED_SUBTITLE)), tracks.map { it.roles })
    }

    @Test
    fun theLoadStartsWhereThePlayerWasWithItsSubtitleOn() {
        val request = media().toLoadRequest(owner = "k", metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE))
        assertEquals(true, request.autoplay)
        assertEquals(754_000, request.currentTime)
        assertContentEquals(longArrayOf(1001L), request.activeTrackIds)
        assertNull(media(start = CastSubtitleStart.Off).toLoadRequest("k", MediaMetadata()).activeTrackIds)
    }

    @Test
    fun theMediaCarriesThePhonesMarker() {
        val info = media().toMediaInfo(owner = "k", metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE))
        assertEquals(CastTag(itemId = "m1", extraId = null, owner = "k", writesProgress = true), castTagOf(info.customData))
        val trailer = media(extraId = "x1").toMediaInfo(owner = "k", metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE))
        assertEquals("m1/x1", trailer.contentId)
        assertEquals(CastTag(itemId = "m1", extraId = "x1", owner = "k", writesProgress = false), castTagOf(trailer.customData))
    }

    @Test
    fun mediaWithoutTheMarkerIsNoOnesHere() {
        assertNull(castTagOf(null))
        assertNull(castTagOf(JSONObject().put("other", JSONObject().put("item", "m1"))))
        assertNull(castTagOf(JSONObject().put("chino", JSONObject().put("item", "m1"))))
    }

    @Test
    fun aFilmsMetadataIsItsTitleAndArtwork() {
        val sink = Sink()
        media().metadata.writeTo(sink)
        assertEquals(MediaMetadata.MEDIA_TYPE_MOVIE, media().metadata.mediaType)
        assertEquals(mapOf(MediaMetadata.KEY_TITLE to "Sintel"), sink.strings)
        assertEquals(
            listOf(
                Triple("https://media.example.com/api/v1/items/m1/backdrop?stream=tok123", 1280, 720),
                Triple("https://media.example.com/api/v1/items/m1/poster?stream=tok123", 500, 750),
            ),
            sink.images,
        )
    }

    @Test
    fun anEpisodesMetadataIsATvShowsWithItsSeriesAndNumbers() {
        val m = media(item = episode, seriesTitle = "The Quiet Orchard")
        val sink = Sink()
        m.metadata.writeTo(sink)
        assertEquals(MediaMetadata.MEDIA_TYPE_TV_SHOW, m.metadata.mediaType)
        assertEquals(
            mapOf(
                MediaMetadata.KEY_TITLE to "The Quiet Orchard · S01E03",
                MediaMetadata.KEY_SUBTITLE to "The Well",
                MediaMetadata.KEY_SERIES_TITLE to "The Quiet Orchard",
            ),
            sink.strings,
        )
        assertEquals(mapOf(MediaMetadata.KEY_SEASON_NUMBER to 1, MediaMetadata.KEY_EPISODE_NUMBER to 3), sink.ints)
        assertEquals("https://media.example.com/api/v1/items/s1/poster?stream=tok123", sink.images[1].first)
    }

    @Test
    fun aReceiversErrorDataIsReadOneLevelDeep() {
        val data = JSONObject().put("httpStatus", 503).put("detail", JSONObject().put("statusCode", "404"))
        assertEquals(mapOf("httpStatus" to "503", "statusCode" to "404"), flatCustomData(data))
        assertEquals(503, httpStatusOf(flatCustomData(data), null))
    }
}
