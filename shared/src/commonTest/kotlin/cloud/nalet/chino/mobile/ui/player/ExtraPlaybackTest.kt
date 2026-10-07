package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.FAKE_API_BASE
import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.QualityRung
import cloud.nalet.chino.mobile.data.api.TrackInfo
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import cloud.nalet.chino.mobile.data.model.Extra
import cloud.nalet.chino.mobile.data.model.Trailer
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The player's extra mode against chino-api's answers (FakeServer): the
 * extra's master with the stream token and the caps, from the title's detail
 * — the detail and the master are the two requests an extra makes. No
 * progress, watched, play info, segments, trickplay, subtitles, episodes or
 * prewarm. And what the player reads off the master in place of /play/info.
 */
class ExtraPlaybackTest {
    private fun server(
        detail: HttpStatusCode = HttpStatusCode.OK,
        master: HttpStatusCode = HttpStatusCode.OK,
    ) = FakeServer { request ->
        when (request.url.encodedPath) {
            "/api/v1/items/m1" ->
                if (detail == HttpStatusCode.OK) respondJson(DETAIL) else respondText("not here", detail)
            "/api/v1/items/m1/extras/x1/play/master.m3u8" ->
                if (master == HttpStatusCode.OK) respond(MASTER) else respondText("extra not found", master)
            else -> respondText("not found", HttpStatusCode.NotFound)
        }
    }

    /** The session lengths the load asked a token for. */
    private val tokenLives = mutableListOf<Long>()

    private suspend fun FakeServer.load(extraId: String = "x1") = loadExtraPlayback(
        api = api,
        apiBase = FAKE_API_BASE,
        streamToken = { lifeMs ->
            tokenLives += lifeMs
            "tok"
        },
        caps = "avc:1080,aac,mp3",
        itemId = "m1",
        extraId = extraId,
    )

    private fun FakeServer.asked() = requests.map { "${it.method.value} ${it.url.encodedPath}" }

    @Test
    fun aListedExtraPlaysItsMasterAndTheDetailAndTheMasterAreTheOnlyRequests() = runTest {
        val server = server()

        val ready = assertIs<ExtraPlayback.Ready>(server.load())

        val master = "https://media.example.com/api/v1/items/m1/extras/x1/play/master.m3u8?stream=tok&caps=avc:1080,aac,mp3"
        assertEquals(master, ready.masterUrl)
        // Signed with a token for the extra's session: its 33 s from the
        // head and the half hour more, asked for once its length was known.
        assertEquals(listOf(33_000L + SESSION_MARGIN_MS), tokenLives)
        assertEquals("tok", ready.streamToken)
        assertEquals("A Film · Trailer", ready.heading)
        assertEquals(LINK, ready.link)
        assertEquals("A Film", ready.item.title)
        assertEquals(
            listOf("GET /api/v1/items/m1", "GET /api/v1/items/m1/extras/x1/play/master.m3u8"),
            server.asked(),
        )
        // The master is asked for as the player asks for it.
        assertEquals(master, server.requests.last().url.toString())
        // What /play/info would have said: packaged, Auto and the two rungs.
        assertEquals("packaged", ready.info.mode)
        assertEquals(AUTO_QUALITY, ready.info.defaultQuality)
        assertEquals(listOf("auto", "v0", "v1"), ready.info.qualities.map { it.name })
        assertEquals(33_000L, ready.info.durationMs)
    }

    @Test
    fun anExtraTheDetailNoLongerListsIsNotAvailableAndTheLinkIsOffered() = runTest {
        val server = server()
        assertEquals(ExtraPlayback.NotAvailable(title = "A Film", link = LINK), server.load(extraId = "gone"))
        // No master is asked for.
        assertEquals(listOf("GET /api/v1/items/m1"), server.asked())
    }

    @Test
    fun aTitleThatAnswers404IsNotAvailable() = runTest {
        // Gone, or above the viewer's rating cap: chino-api's title gate says 404.
        assertEquals(ExtraPlayback.NotAvailable(title = null, link = null), server(detail = HttpStatusCode.NotFound).load())
    }

    @Test
    fun aMasterThatAnswers404IsNotAvailable() = runTest {
        // The package is gone, or chino-stream has none under this title.
        assertEquals(ExtraPlayback.NotAvailable(title = "A Film", link = LINK), server(master = HttpStatusCode.NotFound).load())
    }

    @Test
    fun aMasterThatCannotBeReadOtherwisePlaysWithoutAQualityMenu() = runTest {
        val ready = assertIs<ExtraPlayback.Ready>(server(master = HttpStatusCode.BadGateway).load())
        assertEquals("packaged", ready.info.mode)
        assertEquals(emptyList(), ready.info.qualities)
        assertEquals(33_000L, ready.info.durationMs)
    }

    @Test
    fun anyOtherFailureOfTheDetailIsNotTakenForAMissingExtra() = runTest {
        val failure = assertFailsWith<ApiStatusException> { server(detail = HttpStatusCode.BadGateway).load() }
        assertEquals(502, failure.status)
    }

    @Test
    fun theMasterUrlCarriesTheTokenThenTheCapsThenAPickedRung() {
        val path = "/api/v1/items/s/extras/e/play/master.m3u8"
        assertEquals(
            "https://media.example.com/api/v1/items/s/extras/e/play/master.m3u8?stream=tok",
            extraMasterUrl(FAKE_API_BASE, path, "tok", ""),
        )
        assertEquals(
            "https://media.example.com/api/v1/items/s/extras/e/play/master.m3u8?caps=avc",
            extraMasterUrl("https://media.example.com/api", path, "", "avc"),
        )
        assertEquals(
            "https://media.example.com/api/v1/items/s/extras/e/play/master.m3u8?stream=tok&caps=avc&q=v1",
            extraMasterUrl(FAKE_API_BASE, path, "tok", "avc", quality = "v1"),
        )
        assertNull(extraMasterUrl(FAKE_API_BASE, "", "tok", "avc"))
        // Auto asks for the ladder as the first load did: no q.
        assertEquals("https://h/m.m3u8?stream=t", withQuality("https://h/m.m3u8?stream=t", AUTO_QUALITY))
        assertEquals("https://h/m.m3u8?stream=t&q=v0", withQuality("https://h/m.m3u8?stream=t", "v0"))
        assertEquals("https://h/m.m3u8?q=v0", withQuality("https://h/m.m3u8", "v0"))
    }

    @Test
    fun theHeadingNamesTheTitleThenTheExtra() {
        assertEquals("Trailer", extraLabel(Extra(id = "x", kind = "trailer", title = "Trailer")))
        assertEquals("Final Trailer", extraLabel(Extra(id = "x", kind = "trailer", title = " Final Trailer ")))
        assertEquals("Teaser", extraLabel(Extra(id = "x", kind = "teaser", title = "")))
        assertEquals("Trailer", extraLabel(Extra(id = "x")))
    }

    @Test
    fun theMasterSaysWhatPlayInfoWould() {
        val info = extraPlayInfo(MASTER, durationMs = 52_000)

        assertEquals("packaged", info.mode)
        assertEquals("cmaf", info.container)
        // The variant playback starts on: the first.
        assertEquals("avc1.64001f", info.videoCodec)
        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        assertEquals("aac", info.audioCodec)
        assertEquals(52_000L, info.durationMs)
        // Auto, then the rungs, the tallest first, as chino-stream lists a
        // packaged title's; the name is the q that asks for the rung.
        assertEquals(
            listOf(QualityRung("auto", "Auto"), QualityRung("v0", "720p"), QualityRung("v1", "480p")),
            info.qualities,
        )
        assertEquals(AUTO_QUALITY, info.defaultQuality)
        assertEquals(
            listOf(
                TrackInfo(
                    index = 0, codec = "mp4a", language = "en", title = "English", name = "English",
                    default = true, channels = 2, group = "audio",
                ),
            ),
            info.audioTracks,
        )
        assertEquals(emptyList(), info.subtitleTracks)
    }

    @Test
    fun withTheFiveOneCompanionsInTheGroupNoRenditionGetsACodecTheMasterDoesNotSay() {
        // `eac3` in the caps: one group, each variant naming both codecs.
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,URI="a1/playlist.m3u8?stream=t",GROUP-ID="audio-surround",LANGUAGE="en",NAME="English 5.1",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="6"
            #EXT-X-MEDIA:TYPE=AUDIO,URI="a0/playlist.m3u8?stream=t",GROUP-ID="audio-surround",LANGUAGE="en",NAME="English",DEFAULT=NO,AUTOSELECT=YES,CHANNELS="2"
            #EXT-X-STREAM-INF:BANDWIDTH=1700000,CODECS="avc1.64001f,ec-3,mp4a.40.2",RESOLUTION=1280x720,AUDIO="audio-surround"
            v0/playlist.m3u8?stream=t
        """.trimIndent()

        val tracks = extraPlayInfo(master, durationMs = 33_000).audioTracks

        assertEquals(listOf("English 5.1", "English"), tracks.map { it.name })
        assertEquals(listOf(6, 2), tracks.map { it.channels })
        assertEquals(listOf(null, null), tracks.map { it.codec })
        assertEquals(listOf("audio-surround", "audio-surround"), tracks.map { it.group })
    }

    @Test
    fun aRungOnceThoughEveryAudioGroupListsItAndEachPictureSizeOnce() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,URI="a0/playlist.m3u8?stream=t",GROUP-ID="audio",LANGUAGE="de",NAME="German",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2"
            #EXT-X-MEDIA:TYPE=AUDIO,URI="a1/playlist.m3u8?stream=t",GROUP-ID="audio-surround",LANGUAGE="de",NAME="German 5.1",DEFAULT=YES,CHANNELS="6"
            #EXT-X-MEDIA:TYPE=SUBTITLES,URI="s0/playlist.m3u8?stream=t",GROUP-ID="subs",LANGUAGE="en",NAME="English"
            #EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x804,AUDIO="audio",SUBTITLES="subs"
            v0/playlist.m3u8?stream=t
            #EXT-X-STREAM-INF:BANDWIDTH=500000,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1280x536,AUDIO="audio",SUBTITLES="subs"
            v1/playlist.m3u8?stream=t
            #EXT-X-STREAM-INF:BANDWIDTH=480000,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1280x534,AUDIO="audio",SUBTITLES="subs"
            v2/playlist.m3u8?stream=t
            #EXT-X-STREAM-INF:BANDWIDTH=1300000,CODECS="avc1.640028,ec-3",RESOLUTION=1920x804,AUDIO="audio-surround",SUBTITLES="subs"
            v0/playlist.m3u8?stream=t
        """.trimIndent()

        val info = extraPlayInfo(master, durationMs = null)

        // A 2.39:1 film's 1920x804 is 1080p, 1280x536 720p; v2 is 720p again.
        assertEquals(listOf("auto", "v0", "v1"), info.qualities.map { it.name })
        assertEquals(listOf("Auto", "1080p", "720p"), info.qualities.map { it.label })
        // The audio of the first variant's group alone: the stereo one.
        assertEquals(listOf("German"), info.audioTracks.map { it.title })
        assertNull(info.durationMs)
    }

    @Test
    fun oneRungIsNothingToPick() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080
            v0/playlist.m3u8?stream=t
        """.trimIndent()
        assertEquals(emptyList(), extraPlayInfo(master, durationMs = 1_000).qualities)
        assertEquals(1080, extraPlayInfo(master, durationMs = 1_000).height)
        // No master at all: the mode alone, and nothing to pick.
        val bare = extraPlayInfo(null, durationMs = 2_000)
        assertEquals("packaged", bare.mode)
        assertNull(bare.videoCodec)
        assertEquals(emptyList(), bare.qualities)
    }

    @Test
    fun aRungIsNamedByTheBoxItsFrameFits() {
        assertEquals("720p", rungLabel("v0", 1280, 720))
        assertEquals("480p", rungLabel("v1", 854, 480))
        assertEquals("720p", rungLabel("v1", 1280, 536))
        assertEquals("2160p", rungLabel("v0", 3840, 1606))
        // DCI: 10 % of width to spare.
        assertEquals("2160p", rungLabel("v0", 4096, 2160))
        assertEquals("v3", rungLabel("v3", 0, 0))
    }

    private companion object {
        val LINK = Trailer(url = "https://www.youtube.com/watch?v=x1", site = "YouTube", externalId = "x1", title = "Official Trailer")

        const val DETAIL = """{"id":"m1","type":"movie","title":"A Film","duration_ms":7200000,
            "trailers":[{"site":"YouTube","external_id":"x1","url":"https://www.youtube.com/watch?v=x1","title":"Official Trailer"}],
            "extras":[{"id":"x1","kind":"trailer","title":"Trailer","language":"en","duration_ms":33000,"local":true,
              "play_path":"/api/v1/items/m1/extras/x1/play/master.m3u8"}]}"""

        /** An extra's master as chino-stream serves it (EXTRA_LADDER
         *  720p:h264,480p:h264, its English stereo track), every URI carrying
         *  the request's query. */
        val MASTER = """
            #EXTM3U
            ## Media playlists by packager version v3.4.2-c819dea-release; master assembled by the zaentrum packager

            #EXT-X-INDEPENDENT-SEGMENTS

            #EXT-X-MEDIA:TYPE=AUDIO,URI="a0/playlist.m3u8?stream=tok&caps=avc:1080,aac,mp3",GROUP-ID="audio",LANGUAGE="en",NAME="English",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2"

            #EXT-X-STREAM-INF:BANDWIDTH=1505031,AVERAGE-BANDWIDTH=1489008,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=23.976,VIDEO-RANGE=SDR,AUDIO="audio",CLOSED-CAPTIONS=NONE
            v0/playlist.m3u8?stream=tok&caps=avc:1080,aac,mp3
            #EXT-X-STREAM-INF:BANDWIDTH=706413,AVERAGE-BANDWIDTH=702883,CODECS="avc1.64001e,mp4a.40.2",RESOLUTION=854x480,FRAME-RATE=23.976,VIDEO-RANGE=SDR,AUDIO="audio",CLOSED-CAPTIONS=NONE
            v1/playlist.m3u8?stream=tok&caps=avc:1080,aac,mp3

            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=41087,AVERAGE-BANDWIDTH=44915,CODECS="avc1.64001f",RESOLUTION=1280x720,CLOSED-CAPTIONS=NONE,URI="v0/iframes.m3u8?stream=tok&caps=avc:1080,aac,mp3"
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=24200,AVERAGE-BANDWIDTH=27023,CODECS="avc1.64001e",RESOLUTION=854x480,CLOSED-CAPTIONS=NONE,URI="v1/iframes.m3u8?stream=tok&caps=avc:1080,aac,mp3"
        """.trimIndent()
    }
}
