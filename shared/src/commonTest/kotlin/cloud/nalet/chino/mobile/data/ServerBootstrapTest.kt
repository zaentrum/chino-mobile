package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.auth.OidcDiscovery
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Where the Add-Server probe looks, and that a build which is https only
 *  (iOS) tries plain http for localhost alone. */
class ServerBootstrapTest {
    @Test
    fun aBareHostIsTriedOverHttpsThenHttp() {
        assertEquals(
            listOf("https://media.example.org", "http://media.example.org"),
            ServerBootstrap.candidates("media.example.org/"),
        )
        assertEquals(
            listOf("http://10.0.0.5:8080", "https://10.0.0.5:8080"),
            ServerBootstrap.candidates(" http://10.0.0.5:8080 "),
        )
    }

    @Test
    fun anHttpsOnlyBuildDropsPlainHttpExceptForThisDevice() {
        val ios = ::isLocalDevelopmentHost
        assertEquals(listOf("https://media.example.org"), ServerBootstrap.candidates("media.example.org", ios))
        assertEquals(listOf("https://192.168.1.20:8099"), ServerBootstrap.candidates("http://192.168.1.20:8099", ios))
        assertEquals(
            listOf("https://127.0.0.1:8099", "http://127.0.0.1:8099"),
            ServerBootstrap.candidates("127.0.0.1:8099", ios),
        )
        assertEquals(
            listOf("http://localhost:8099", "https://localhost:8099"),
            ServerBootstrap.candidates("http://localhost:8099", ios),
        )
    }

    @Test
    fun theHostOfAnAddress() {
        assertEquals("127.0.0.1", ServerBootstrap.hostOf("http://127.0.0.1:8099/api"))
        assertEquals("media.example.org", ServerBootstrap.hostOf("media.example.org"))
        assertEquals("media.example.org", ServerBootstrap.hostOf("https://viewer@media.example.org:443/x?y"))
        assertEquals("::1", ServerBootstrap.hostOf("http://[::1]:8099"))
    }

    @Test
    fun onlyLocalhostAnd127001AreLocalDevelopmentHosts() {
        for (host in listOf("localhost", "LOCALHOST", "127.0.0.1")) assertEquals(true, isLocalDevelopmentHost(host), host)
        for (host in listOf("127.0.0.2", "::1", "192.168.1.20", "localhost.example.org", "media.local")) {
            assertEquals(false, isLocalDevelopmentHost(host), host)
        }
    }

    private fun bootstrap(seen: MutableList<String>, issuer: String): ServerBootstrap {
        val http = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                seen += url
                when {
                    url.startsWith("https://192.168.1.20") -> respondError(HttpStatusCode.ServiceUnavailable)
                    url.endsWith("/api/healthz") -> respondJson("""{"status":"ok","product":"chino"}""")
                    url.endsWith("/api/config") -> respondJson(
                        """{"product":"chino","oidcIssuer":"$issuer","oidcClientId":{"mobile":"chino-mobile"}}""",
                    )
                    url.contains("openid-configuration") -> respondJson(
                        """{"issuer":"$issuer","authorization_endpoint":"$issuer/auth","token_endpoint":"$issuer/token"}""",
                    )
                    else -> respondError(HttpStatusCode.NotFound)
                }
            },
        )
        return ServerBootstrap(http, OidcDiscovery(http), allowPlainHttp = ::isLocalDevelopmentHost)
    }

    @Test
    fun aPlainHttpLanServerIsRefusedAndSaysWhy() = runTest {
        val seen = mutableListOf<String>()
        val result = bootstrap(seen, "https://id.example.org/realms/media").probe("http://192.168.1.20:8099")

        assertIs<BootstrapResult.Fail>(result)
        assertEquals(BootstrapResult.Fail.Kind.HTTPS_ONLY, result.kind)
        assertEquals(listOf("https://192.168.1.20:8099/api/healthz"), seen)
    }

    @Test
    fun aLocalDevelopmentServerConnectsOverPlainHttp() = runTest {
        val seen = mutableListOf<String>()
        val result = bootstrap(seen, "http://127.0.0.1:8099/oidc").probe("http://127.0.0.1:8099")

        assertIs<BootstrapResult.Ok>(result)
        assertEquals("http://127.0.0.1:8099/api/", result.config.baseUrl)
        assertEquals("chino-mobile", result.config.clientId)
    }

    @Test
    fun aPlainHttpIssuerElsewhereIsRefusedToo() = runTest {
        val result = bootstrap(mutableListOf(), "http://id.example.org/realms/media").probe("http://localhost:8099")

        assertIs<BootstrapResult.Fail>(result)
        assertEquals(BootstrapResult.Fail.Kind.HTTPS_ONLY, result.kind)
    }
}
