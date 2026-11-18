package cloud.nalet.chino.mobile.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher

/** The API base the app's client is configured with (ServerBootstrap stores
 *  `<server>/api/`; Ktor resolves "v1/..." against it). */
const val FAKE_API_BASE = "https://media.example.com/api/"

/**
 * A [ChinoApi] over a MockEngine: [handler] answers each request in place of
 * chino-api, and every request is recorded in [requests]. The client is set
 * up as the app's is ([chinoApiClient]): its JSON, its base URL, its bearer
 * (from [accessToken], [renewedToken] after a 401) and its check of every
 * answer — so a test sees what the app would see. [dispatcher]: where the
 * fake answers, the test's own where a test waits for requests the app sends
 * in the background (its IO threads else). [onAccountDeleted]: the bearer of
 * each request answered 401 `account_deleted`.
 */
class FakeServer(
    private val accessToken: suspend () -> String? = { null },
    private val renewedToken: suspend () -> String? = { null },
    dispatcher: CoroutineDispatcher? = null,
    private val onAccountDeleted: (bearer: String) -> Unit = {},
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()

    val http: HttpClient = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher?.let { this.dispatcher = it }
                addHandler { request ->
                    requests += request
                    handler(request)
                }
            },
        ),
    ) {
        chinoApiClient(FAKE_API_BASE, accessToken, renewedToken, onAccountDeleted = onAccountDeleted)
    }

    val api: ChinoApi = ChinoApi(http)
}

/** A JSON response, as chino-api's writeJSON sends it. */
fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

/** A plain-text error, as chino-api's http.Error sends it. */
fun MockRequestHandleScope.respondText(body: String, status: HttpStatusCode): HttpResponseData =
    respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
    )
