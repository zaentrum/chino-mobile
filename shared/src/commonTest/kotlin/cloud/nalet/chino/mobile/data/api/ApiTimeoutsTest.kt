package cloud.nalet.chino.mobile.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The player's start waits for /play/info 8 s at most, on the app's client
 *  ([chinoApiClient]) as the app has it - on the test's clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class ApiTimeoutsTest {
    /** The app's client over a server that answers after [answerAfterMs]. */
    private fun TestScope.api(answerAfterMs: Long): ChinoApi {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    delay(answerAfterMs)
                    respondJson("""{"mode":"packaged","default_quality":"auto"}""")
                }
            },
        )
        return ChinoApi(HttpClient(engine) { chinoApiClient(FAKE_API_BASE, { null }, { null }) })
    }

    @Test
    fun aPlayInfoTheServerHoldsUpIsGivenUpAtEightSeconds() = runTest {
        // chino-stream held Agent 327's for 21 s on the demo.
        val api = api(answerAfterMs = 21_000)
        val start = currentTime
        assertFailsWith<HttpRequestTimeoutException> { api.playInfo("t1", caps = "avc:4096") }
        assertEquals(PLAY_INFO_TIMEOUT_MS, currentTime - start)
    }

    @Test
    fun aPlayInfoWithinTheBudgetIsRead() = runTest {
        val api = api(answerAfterMs = PLAY_INFO_TIMEOUT_MS - 100)
        assertEquals("packaged", api.playInfo("t1").mode)
    }
}
