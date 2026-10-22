package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.model.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the TV is told to play: its own master, what it shows, its subtitles,
 *  where it starts. */
class CastMediaTest {
    private val base = "https://media.example.com/api"
    private val token = "tok123"

    /** The phone's master as the Android player asks for it: its own caps,
     *  the rung it starts on. */
    private val phoneMaster =
        "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=tok123&caps=avc:1080,hvc:2160,av1:2160,aac,mp3,opus,eac3&q=high"

    private val sintel = Item(
        id = "m1",
        title = "Sintel",
        kind = "movie",
        posterUrl = "/api/v1/items/m1/poster",
        backdropUrl = "/api/v1/items/m1/backdrop",
        durationMs = 888_000,
    )

    private val episode = Item(
        id = "e3",
        title = "The Well",
        kind = "episode",
        parentId = "s1",
        seasonNumber = 1,
        episodeNumber = 3,
        posterUrl = "/api/v1/items/e3/poster",
        backdropUrl = "/api/v1/items/e3/backdrop",
        durationMs = 2_640_000,
    )

    private fun sub(id: String, lang: String, label: String? = null, format: String? = "webvtt", forced: Boolean = false) =
        SidecarSubtitle(id = id, label = label, lang = lang, url = "$base/v1/play/subs/$id.vtt?stream=$token", format = format, forced = forced)

    private val sidecars = listOf(
        sub("s1", "eng", label = "English"),
        sub("s2", "ger", format = "srt"),
        sub("s3", "fre", format = "pgs"),
        sub("s4", "eng", label = "English (forced)", forced = true),
    )

    private fun input(
        item: Item? = sintel,
        seriesTitle: String? = null,
        startMs: Long = 0L,
        autoplay: Boolean = true,
        subs: List<SidecarSubtitle> = emptyList(),
        start: CastSubtitleStart = CastSubtitleStart.Off,
        extraId: String? = null,
        extraHeading: String? = null,
        master: String = phoneMaster,
        writes: Boolean = true,
    ) = CastMediaInput(
        apiBase = base,
        streamToken = token,
        itemId = item?.id ?: "m1",
        extraId = extraId,
        phoneMasterUrl = master,
        item = item,
        seriesTitle = seriesTitle,
        extraHeading = extraHeading,
        durationMs = item?.durationMs,
        startMs = startMs,
        autoplay = autoplay,
        sidecars = subs,
        subtitleStart = start,
        writesProgress = writes,
    )

    @Test
    fun theReceiverIsServedH264At1080AndAac() {
        assertEquals("avc:1080,aac", RECEIVER_CAPS)
    }

    @Test
    fun theTvsMasterCarriesTheReceiversCapsTheTokenAndNoRung() {
        val media = castMediaFor(input())
        assertEquals(
            "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=tok123&caps=avc:1080,aac",
            media.contentUrl,
        )
    }

    @Test
    fun anExtrasMasterKeepsItsPathAndToken() {
        assertEquals(
            "https://media.example.com/api/v1/items/m1/extras/x1/play/master.m3u8?stream=tok123&caps=avc:1080,aac",
            receiverMasterUrl("https://media.example.com/api/v1/items/m1/extras/x1/play/master.m3u8?stream=tok123&caps=hvc,aac&q=x1-720", RECEIVER_CAPS),
        )
        // A master the phone asked for without caps gets the receiver's.
        assertEquals(
            "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=tok123&caps=avc:1080,aac",
            receiverMasterUrl("https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=tok123", RECEIVER_CAPS),
        )
    }

    @Test
    fun theTvStartsWhereThePlayerSaysPlayingOrPaused() {
        val media = castMediaFor(input(startMs = 754_000, autoplay = false))
        assertEquals(754_000, media.startMs)
        assertFalse(media.autoplay)
        assertEquals(888_000, media.durationMs)
        assertEquals(0, castMediaFor(input(startMs = -5)).startMs)
    }

    @Test
    fun aFilmIsItsTitleWithItsBackdropFirstThenItsPoster() {
        val meta = castMediaFor(input()).metadata
        assertEquals(CastMediaKind.Movie, meta.kind)
        assertEquals("Sintel", meta.title)
        assertNull(meta.subtitle)
        assertEquals(
            listOf(
                CastImage("https://media.example.com/api/v1/items/m1/backdrop?stream=tok123", 1280, 720, CastImageShape.Landscape),
                CastImage("https://media.example.com/api/v1/items/m1/poster?stream=tok123", 500, 750, CastImageShape.Portrait),
            ),
            meta.images,
        )
    }

    @Test
    fun anEpisodeReadsItsSeriesAndCodeWithItsOwnTitleUnderIt() {
        val meta = castMediaFor(input(item = episode, seriesTitle = "The Quiet Orchard")).metadata
        assertEquals(CastMediaKind.Episode, meta.kind)
        assertEquals("The Quiet Orchard · S01E03", meta.title)
        assertEquals("The Well", meta.subtitle)
        assertEquals("The Quiet Orchard", meta.seriesTitle)
        assertEquals(1, meta.seasonNumber)
        assertEquals(3, meta.episodeNumber)
        // Its own still, and its series' poster.
        assertEquals(
            listOf(
                "https://media.example.com/api/v1/items/e3/backdrop?stream=tok123",
                "https://media.example.com/api/v1/items/s1/poster?stream=tok123",
            ),
            meta.images.map { it.url },
        )
    }

    @Test
    fun anEpisodeWithoutItsSeriesTitleLeadsWithItsCode() {
        val meta = castMediaFor(input(item = episode, seriesTitle = null)).metadata
        assertEquals("S01E03 · The Well", meta.title)
        assertNull(meta.subtitle)
        // A file of several episodes is named for them, as it plays.
        val box = episode.copy(episodeEnd = 4)
        assertEquals("The Quiet Orchard · S01E03–04", castMediaFor(input(item = box, seriesTitle = "The Quiet Orchard")).metadata.title)
    }

    @Test
    fun anExtraPlaysUnderItsHeadingAndWritesNoPosition() {
        val media = castMediaFor(
            input(
                extraId = "x1",
                extraHeading = "Sintel · Trailer",
                master = "https://media.example.com/api/v1/items/m1/extras/x1/play/master.m3u8?stream=tok123&caps=hvc,aac",
                writes = false,
            ),
        )
        assertEquals(CastMediaKind.Movie, media.metadata.kind)
        assertEquals("Sintel · Trailer", media.metadata.title)
        assertEquals("x1", media.extraId)
        assertFalse(media.writesProgress)
        assertEquals(CastTag("m1", "x1", "k", false), media.tag("k"))
    }

    @Test
    fun textSidecarsAreSideLoadedAndPicturesLeftOut() {
        val tracks = castMediaFor(input(subs = sidecars)).textTracks
        assertEquals(listOf("s1", "s2", "s4"), tracks.map { it.sidecarId })
        assertEquals(listOf(1000L, 1001L, 1002L), tracks.map { it.id })
        assertEquals(listOf("en", "de", "en"), tracks.map { it.language })
        assertEquals(listOf("English", "German", "English (forced)"), tracks.map { it.name })
        assertEquals(listOf(false, false, true), tracks.map { it.forced })
        assertEquals("https://media.example.com/api/v1/play/subs/s2.vtt?stream=tok123", tracks[1].url)
    }

    @Test
    fun aTrackInNoLanguageIsUnd() {
        assertEquals("und", castTextTracks(listOf(sub("s9", "", label = "Commentary"))).single().language)
    }

    @Test
    fun theTvStartsWithThePhonesSidecarOffOrTheDefaultRule() {
        fun active(start: CastSubtitleStart) = castMediaFor(input(subs = sidecars, start = start)).activeTextTrackId
        assertEquals(1001L, active(CastSubtitleStart.Sidecar("s2")))
        assertNull(active(CastSubtitleStart.Off))
        // A picture sidecar has no track on the TV: none comes on.
        assertNull(active(CastSubtitleStart.Sidecar("s3")))
        // Japanese audio, English subtitles in Settings: the full English track.
        assertEquals(1000L, active(CastSubtitleStart.Auto(audioLang = "jpn", subtitlePref = "en", audioPref = "orig")))
        // English audio: the forced English track - the lines in another language.
        assertEquals(1002L, active(CastSubtitleStart.Auto(audioLang = "eng", subtitlePref = "en", audioPref = "orig")))
        // The audio's language unknown: none.
        assertNull(active(CastSubtitleStart.Auto(audioLang = null, subtitlePref = "en", audioPref = "orig")))
    }

    @Test
    fun theOwnerKeyNamesNoAccountAndDiffersPerServer() {
        val key = castOwnerKey(base, "3f2a-sub")
        assertEquals(16, key.length)
        assertFalse(key.contains("3f2a"))
        assertEquals(key, castOwnerKey("$base/", "3f2a-sub"))
        assertNotEquals(key, castOwnerKey("https://other.example.org/api", "3f2a-sub"))
        assertNotEquals(key, castOwnerKey(base, "another-sub"))
    }

    @Test
    fun theBarShowsThePosterTheFullControlsTheBackdrop() {
        val images = castMediaFor(input()).metadata.images
        assertEquals(1, pickCastImage(images, CastArtworkUse.Thumbnail))
        assertEquals(0, pickCastImage(images, CastArtworkUse.Background))
        // Only one shape: that one, for both.
        val posterOnly = images.drop(1)
        assertEquals(0, pickCastImage(posterOnly, CastArtworkUse.Background))
        assertNull(pickCastImage(emptyList(), CastArtworkUse.Thumbnail))
        assertTrue(castImageShape(500, 750) == CastImageShape.Portrait && castImageShape(1280, 720) == CastImageShape.Landscape)
    }
}
