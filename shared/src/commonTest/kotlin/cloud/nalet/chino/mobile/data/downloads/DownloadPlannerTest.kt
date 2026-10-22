package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A download worked out from the server: whether the title can come along,
 *  what it keeps, what it comes to - before it starts. */
class DownloadPlannerTest {
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,URI="a0/playlist.m3u8?stream=TOKEN&v=1",GROUP-ID="audio",LANGUAGE="de",NAME="Deutsch",DEFAULT=YES,CHANNELS="2"
        #EXT-X-MEDIA:TYPE=AUDIO,URI="a1/playlist.m3u8?stream=TOKEN&v=1",GROUP-ID="audio",LANGUAGE="en",NAME="English",DEFAULT=NO,CHANNELS="2"
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,AVERAGE-BANDWIDTH=4000000,CODECS="hvc1.1.2.L120.90,mp4a.40.2",RESOLUTION=1920x1080,AUDIO="audio"
        v0/playlist.m3u8?stream=TOKEN&v=1
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=2000000,CODECS="hvc1.1.2.L93.90,mp4a.40.2",RESOLUTION=1280x720,AUDIO="audio"
        v1/playlist.m3u8?stream=TOKEN&v=1
    """.trimIndent()

    private val requests = mutableListOf<String>()
    private val server = FakeServer { request ->
        val path = request.url.encodedPath
        requests += path
        when {
            path.endsWith("/v1/items/film/play/info") ->
                respondJson("""{"mode":"packaged","duration_ms":7200000,"audio_tracks":[{"language":"deu","name":"Deutsch","default":true}]}""")
            path.endsWith("/v1/items/stream/play/info") -> respondJson("""{"mode":"transcode","duration_ms":7200000}""")
            path.endsWith("/v1/items/broken/play/info") -> respondText("bad gateway", HttpStatusCode.BadGateway)
            path.endsWith("/v1/items/film/play/master.m3u8") -> respondText(master, HttpStatusCode.OK)
            path.endsWith("/v1/items/film/subtitles") ->
                respondJson("""{"subtitles":[{"id":"s-en","lang":"eng","url":"/api/v1/play/subs/s-en.vtt","format":"webvtt"},{"id":"s-fr","lang":"fre","url":"/api/v1/play/subs/s-fr.vtt"}]}""")
            path.endsWith("/v1/items/film/segments") -> respondJson("""{"segments":[{"kind":"credits","start_ms":7000000,"end_ms":7200000}]}""")
            path.endsWith("/v1/items/film/progress") -> respondJson("""{"position_sec":1200}""")
            path.endsWith("/v1/items/film") -> respondJson("""{"id":"film","title":"A Film","type":"movie","duration_ms":7200000}""")
            path.endsWith("/v1/me/stream-token") -> respondJson("""{"stream_token":"TOKEN"}""")
            path.endsWith("/v1/play/packaged-ids") -> respondJson("""{"ids":["film"]}""")
            else -> respondText("not found", HttpStatusCode.NotFound)
        }
    }
    private val planner = DownloadPlanner(
        api = server.api,
        streamToken = { "TOKEN" },
        apiBase = { "https://media.example.com/api" },
        caps = { "avc:1080,hvc:2160,aac" },
    )

    @Test
    fun aPackagedTitleOffersItsSizeAtTheChosenQuality() = runTest {
        // Two hours at the 720p rung's AVERAGE-BANDWIDTH.
        assertEquals(DownloadOffer.Available(2_000_000L * 7_200 / 8), planner.offer("film", 720, durationMs = null))
        assertEquals(DownloadOffer.Available(4_000_000L * 7_200 / 8), planner.offer("film", 1080, durationMs = null))
        // What was read is kept: one play info and one master for both.
        assertEquals(1, requests.count { it.endsWith("/play/info") })
        assertEquals(1, requests.count { it.endsWith("/master.m3u8") })
    }

    @Test
    fun aTitleThatOnlyStreamsCannotComeAlongAndItsMasterIsNotAskedFor() = runTest {
        assertEquals(DownloadOffer.StreamOnly, planner.offer("stream", 720, null))
        assertTrue(requests.none { it.contains("stream/play/master") })
        assertEquals(PlanResult.NotPossible(DownloadFailure.StreamOnly), planner.plan("stream", 720, "eng", "off"))
    }

    @Test
    fun aServerThatFailsSaysSo() = runTest {
        assertEquals(DownloadOffer.Unknown(DownloadFailure.ServerProblem), planner.offer("broken", 720, null))
        assertEquals(DownloadOffer.Unknown(DownloadFailure.Gone), planner.offer("missing", 720, null))
    }

    @Test
    fun aPlanKeepsOneRenditionTheViewersAudioAndTheOriginalAndTheirSubtitles() = runTest {
        val plan = assertIs<PlanResult.Ready>(planner.plan("film", 720, audioPref = "eng", subtitlePref = "off")).plan
        assertEquals("v1/playlist.m3u8?stream=TOKEN&v=1", plan.variant.uri)
        assertEquals(listOf("en", "de"), plan.audios.map { it.language })
        assertEquals(listOf("s-en"), plan.subtitles.map { it.id })
        // The master without the token it was read with, at Auto: the whole
        // ladder this device is served.
        assertEquals("https://media.example.com/api/v1/items/film/play/master.m3u8?caps=avc:1080,hvc:2160,aac", plan.masterUrl)
        assertEquals((2_000_000L + EXTRA_AUDIO_BPS) * 7_200 / 8, plan.estimatedBytes)
        assertEquals("A Film", plan.item.title)
        assertEquals(1, plan.segments.size)
        assertEquals("https://media.example.com/api", plan.server)
        // Where the viewer was, kept for a resume without the server.
        assertEquals(1200, plan.savedPositionSec)
    }

    @Test
    fun settingsReadTheSizeOfAnHourFromThePackagedTitlesMasters() = runTest {
        assertEquals(
            listOf(DownloadQuality(1080, bytesPerHour(4_000_000)), DownloadQuality(720, bytesPerHour(2_000_000))),
            planner.qualities(),
        )
    }
}
