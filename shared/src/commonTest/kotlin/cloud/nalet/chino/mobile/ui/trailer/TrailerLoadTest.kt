package cloud.nalet.chino.mobile.ui.trailer

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.FAKE_API_BASE
import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import cloud.nalet.chino.mobile.data.model.Trailer
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What the trailer screen plays, against chino-api's answers (FakeServer):
 * the extra's play_path with the stream token and the caps, from the title's
 * detail — and that detail is the one request a trailer makes. No progress,
 * watched, segments, trickplay, subtitles, next episode or prewarm.
 */
class TrailerLoadTest {
    private fun server(status: HttpStatusCode = HttpStatusCode.OK, body: String = DETAIL) = FakeServer { request ->
        if (request.url.encodedPath == "/api/v1/items/m1") {
            if (status == HttpStatusCode.OK) respondJson(body) else respondText("not here", status)
        } else {
            respondText("not found", HttpStatusCode.NotFound)
        }
    }

    private suspend fun FakeServer.load(extraId: String = "x1") = loadTrailer(
        api = api,
        apiBase = FAKE_API_BASE,
        streamToken = { "tok" },
        caps = "avc:1080,aac,mp3",
        itemId = "m1",
        extraId = extraId,
    )

    @Test
    fun aListedTrailerPlaysItsPlayPathWithTheTokenAndTheCapsAndTheDetailIsTheOneRequest() = runTest {
        val server = server()

        val state = server.load()

        assertEquals(
            TrailerUiState.Ready(
                url = "https://media.example.com/api/v1/items/m1/extras/x1/play/master.m3u8?stream=tok&caps=avc:1080,aac,mp3",
                title = "A Film",
                label = "Trailer",
                link = LINK,
            ),
            state,
        )
        assertEquals(listOf("GET /api/v1/items/m1"), server.requests.map { "${it.method.value} ${it.url.encodedPath}" })
    }

    @Test
    fun anExtraTheDetailNoLongerListsIsNotAvailableAndTheLinkIsOffered() = runTest {
        assertEquals(TrailerUiState.NotAvailable(title = "A Film", link = LINK), server().load(extraId = "gone"))
    }

    @Test
    fun aTitleThatAnswers404IsNotAvailable() = runTest {
        // Gone, or above the viewer's rating cap: chino-api's title gate says 404.
        assertEquals(TrailerUiState.NotAvailable(title = null, link = null), server(HttpStatusCode.NotFound).load())
    }

    @Test
    fun anyOtherFailureIsNotTakenForAMissingTrailer() = runTest {
        val failure = assertFailsWith<ApiStatusException> { server(HttpStatusCode.BadGateway).load() }
        assertEquals(502, failure.status)
    }

    @Test
    fun theMasterUrlCarriesTheTokenThenTheCaps() {
        assertEquals(
            "https://media.example.com/api/v1/items/s/extras/e/play/master.m3u8?stream=tok",
            trailerMasterUrl(FAKE_API_BASE, "/api/v1/items/s/extras/e/play/master.m3u8", "tok", ""),
        )
        assertEquals(
            "https://media.example.com/api/v1/items/s/extras/e/play/master.m3u8?caps=avc",
            trailerMasterUrl("https://media.example.com/api", "/api/v1/items/s/extras/e/play/master.m3u8", "", "avc"),
        )
        assertNull(trailerMasterUrl(FAKE_API_BASE, "", "tok", "avc"))
    }

    private companion object {
        val LINK = Trailer(url = "https://www.youtube.com/watch?v=x1", site = "YouTube", externalId = "x1", title = "Official Trailer")

        const val DETAIL = """{"id":"m1","type":"movie","title":"A Film",
            "trailers":[{"site":"YouTube","external_id":"x1","url":"https://www.youtube.com/watch?v=x1","title":"Official Trailer"}],
            "extras":[{"id":"x1","kind":"trailer","title":"Trailer","language":"en","duration_ms":33000,"local":true,
              "play_path":"/api/v1/items/m1/extras/x1/play/master.m3u8"}]}"""
    }
}
