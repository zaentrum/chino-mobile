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

    /** The TV's own token. */
    private val tv = CastToken("tv456", expiresAtMs = 1_800_000_000_000)

    private fun media(
        item: Item = sintel,
        seriesTitle: String? = null,
        extraId: String? = null,
        start: CastSubtitleStart = CastSubtitleStart.Sidecar("s2", "ger", forced = true),
    ) = castMediaFor(
        CastMediaInput(
            apiBase = base,
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
        tv,
        null,
        "L1",
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
        assertEquals("$base/v1/items/m1/play/master.m3u8?stream=tv456&caps=avc:1080,aac", info.contentUrl)
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
        assertEquals(listOf("$base/v1/play/subs/s1.vtt?stream=tv456", "$base/v1/play/subs/s2.vtt?stream=tv456"), tracks.map { it.contentId })
        assertEquals(listOf("en", "de"), tracks.map { it.language })
        assertEquals(listOf("English", "German · foreign-language parts only"), tracks.map { it.name })
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
        assertEquals(CastTag("m1", null, "k", writesProgress = true, load = "L1", linksExpireAtMs = 1_800_000_000_000), castTagOf(info.customData))
        val trailer = media(extraId = "x1").toMediaInfo(owner = "k", metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE))
        assertEquals("m1/x1", trailer.contentId)
        assertEquals(CastTag("m1", "x1", "k", writesProgress = false, load = "L1", linksExpireAtMs = 1_800_000_000_000), castTagOf(trailer.customData))
    }

    @Test
    fun mediaWithoutTheMarkerIsNoOnesHere() {
        assertNull(castTagOf(null))
        assertNull(castTagOf(JSONObject().put("other", JSONObject().put("item", "m1"))))
        assertNull(castTagOf(JSONObject().put("chino", JSONObject().put("item", "m1"))))
        // An older marker, without a load or the links' life: read as such.
        val older = castTagOf(JSONObject().put("chino", JSONObject().put("item", "m1").put("owner", "k").put("progress", true)))
        assertEquals(CastTag("m1", null, "k", writesProgress = true, load = "", linksExpireAtMs = 0L), older)
    }

    @Test
    fun theMediaTheTvPlaysIsLoadedAgainSignedAnewWhereItWas() {
        val playing = media().toMediaInfo(owner = "k", metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE))
        val again = CastReload(CastToken("tv789", 1_900_000_000_000), load = "L2", atMs = 2_345_000, autoplay = false, activeTrackIds = listOf(2, 1001))
        val request = reloadRequest(playing, again)!!
        val info = request.mediaInfo!!
        assertEquals("$base/v1/items/m1/play/master.m3u8?stream=tv789&caps=avc:1080,aac", info.contentUrl)
        assertEquals(listOf("$base/v1/play/subs/s1.vtt?stream=tv789", "$base/v1/play/subs/s2.vtt?stream=tv789"), info.mediaTracks.orEmpty().map { it.contentId })
        assertEquals(listOf(listOf(MediaTrack.ROLE_SUBTITLE), listOf(MediaTrack.ROLE_FORCED_SUBTITLE)), info.mediaTracks.orEmpty().map { it.roles })
        assertEquals(CastTag("m1", null, "k", writesProgress = true, load = "L2", linksExpireAtMs = 1_900_000_000_000), castTagOf(info.customData))
        assertEquals(false, request.autoplay)
        assertEquals(2_345_000, request.currentTime)
        assertContentEquals(longArrayOf(2L, 1001L), request.activeTrackIds)
        assertEquals(HlsSegmentFormat.FMP4, info.hlsSegmentFormat)
        assertEquals(888_000, info.streamDuration)
        // Not this app's media: nothing to load again.
        assertNull(reloadRequest(MediaInfo.Builder("other").setContentUrl("https://example.org/x.m3u8").build(), again))
    }

    @Test
    fun theViewersSdhStaysOnWhenTheTvListsItsSideLoadedTracksWithoutTheirSidecars() {
        // The TV after the load, as RemoteMediaClient lists it: the master's
        // audio, the two side-loaded English sidecars. The load turned the
        // SDH one (1001) on.
        val reported = listOf(
            MediaTrack.Builder(2, MediaTrack.TYPE_AUDIO).setLanguage("en").setName("English").build(),
            MediaTrack.Builder(1000, MediaTrack.TYPE_TEXT).setLanguage("en").setName("English").setRoles(listOf(MediaTrack.ROLE_SUBTITLE)).build(),
            MediaTrack.Builder(1001, MediaTrack.TYPE_TEXT).setLanguage("en").setName("English · SDH").setRoles(listOf(MediaTrack.ROLE_SUBTITLE)).build(),
        ).map { it.toTvTrack() }
        val sdh = CastSubtitleStart.Sidecar("s3", "eng", forced = false, sdh = true)
        val media = castMediaFor(
            CastMediaInput(
                apiBase = base, itemId = "m1", phoneMasterUrl = "$base/v1/items/m1/play/master.m3u8?stream=$token",
                item = sintel, seriesTitle = null, durationMs = 888_000, startMs = 0, autoplay = true,
                sidecars = listOf(
                    SidecarSubtitle(id = "s1", label = "English", lang = "eng", url = "$base/v1/play/subs/s1.vtt?stream=$token"),
                    SidecarSubtitle(id = "s3", label = "English SDH", lang = "eng", url = "$base/v1/play/subs/s3.vtt?stream=$token"),
                ),
                subtitleStart = sdh,
                writesProgress = true,
            ),
            tv,
            null,
            "L1",
        )
        val known = withWhatThePhoneKnows(reported, media, master = null)
        assertEquals(listOf(null, "s1", "s3"), known.map { it.sidecarId })
        // Plain English does not replace the SDH the viewer had: nothing to send.
        assertNull(castActiveTracks(known, active = listOf(2, 1001), wish = CastTrackWish("eng", "orig", sdh)))
    }

    @Test
    fun theTvsTracksInCastingsTerms() {
        val forced = MediaTrack.Builder(6, MediaTrack.TYPE_TEXT).setLanguage("en").setName("English").setRoles(listOf(MediaTrack.ROLE_FORCED_SUBTITLE)).build()
        assertEquals(TvTrack(6, TvTrackType.Text, "en", "English", forced = true), forced.toTvTrack())
        val named = MediaTrack.Builder(7, MediaTrack.TYPE_TEXT).setLanguage("en").setName("English (Forced)").build()
        assertEquals(true, named.toTvTrack().forced)
        val sdh = MediaTrack.Builder(8, MediaTrack.TYPE_TEXT).setLanguage("en").setName("English SDH").build()
        assertEquals(true, sdh.toTvTrack().sdh)
        val audio = MediaTrack.Builder(2, MediaTrack.TYPE_AUDIO).setLanguage("ja").setName("Japanese").build()
        assertEquals(TvTrack(2, TvTrackType.Audio, "ja", "Japanese"), audio.toTvTrack())
        assertEquals(TvTrackType.Video, MediaTrack.Builder(1, MediaTrack.TYPE_VIDEO).build().toTvTrack().type)
    }

    @Test
    fun theChooserOfTheTvsTracksListsItsSubtitlesNamedAsThePhoneNamesThem() {
        val tracks = listOf(
            MediaTrack.Builder(2, MediaTrack.TYPE_AUDIO).setLanguage("en").setName("English 5.1").build(),
            MediaTrack.Builder(5, MediaTrack.TYPE_TEXT).setLanguage("en").setName("English SDH").build(),
            MediaTrack.Builder(6, MediaTrack.TYPE_TEXT).setLanguage("de").setName("German").setRoles(listOf(MediaTrack.ROLE_FORCED_SUBTITLE)).build(),
            MediaTrack.Builder(7, MediaTrack.TYPE_TEXT).setLanguage("en").setName("English").build(),
        )
        val info = MediaInfo.Builder("m1").setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMediaTracks(tracks).build()
        val named = info.withSubtitleNames().mediaTracks.orEmpty()
        assertEquals(listOf(2L, 5L, 6L, 7L), named.map { it.id })
        assertEquals(
            listOf("English 5.1", "English · for deaf and hard of hearing", "German · foreign-language parts only", "English"),
            named.map { it.name },
        )
        assertEquals(listOf("en", "en", "de", "en"), named.map { it.language })
        assertEquals(listOf(null, null, listOf(MediaTrack.ROLE_FORCED_SUBTITLE), null), named.map { it.roles })
        // The TV's own media stays as the TV reported it.
        assertEquals("English SDH", info.mediaTracks.orEmpty()[1].name)
    }

    @Test
    fun aFilmsMetadataIsItsTitleAndArtwork() {
        val sink = Sink()
        media().metadata.writeTo(sink)
        assertEquals(MediaMetadata.MEDIA_TYPE_MOVIE, media().metadata.mediaType)
        assertEquals(mapOf(MediaMetadata.KEY_TITLE to "Sintel"), sink.strings)
        assertEquals(
            listOf(
                Triple("https://media.example.com/api/v1/items/m1/backdrop?stream=tv456", 1280, 720),
                Triple("https://media.example.com/api/v1/items/m1/poster?stream=tv456", 500, 750),
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
        assertEquals("https://media.example.com/api/v1/items/s1/poster?stream=tv456", sink.images[1].first)
    }

}
