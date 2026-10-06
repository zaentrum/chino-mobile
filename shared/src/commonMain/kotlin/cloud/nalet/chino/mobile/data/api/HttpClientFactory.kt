package cloud.nalet.chino.mobile.data.api

import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.auth.TokenManager
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * How every client decodes chino-api's JSON. ignoreUnknownKeys: a field the
 * server adds never breaks an older app. coerceInputValues: the server sends
 * `"qualities": null` (and can null other defaulted fields) for remux items;
 * without coercion kotlinx throws "Expected '[' but had 'n'" and the whole
 * PlayInfo (and similar) responses fail to deserialize — coercion maps a null
 * onto the property's default (emptyList()) instead.
 */
internal val ChinoJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

/**
 * How the app talks to chino-api — the app's client and the tests' FakeServer
 * alike: JSON as chino-api writes it ([ChinoJson]), the bearer from
 * [accessToken] (and [renewedToken] after a 401), requests relative to
 * [apiBaseUrl], and every non-2xx answer an [ApiStatusException] with the
 * server's message. Ktor installs the response check before the Auth plugin
 * sends, so it sees the answer after a renewed token's retry: a 401 the
 * renewal fixes is no error.
 */
internal fun HttpClientConfig<*>.chinoApiClient(
    apiBaseUrl: String,
    accessToken: suspend () -> String?,
    renewedToken: suspend () -> String?,
) {
    install(ContentNegotiation) { json(ChinoJson) }
    apiTimeouts()
    install(Auth) {
        bearer {
            // refresh_token is opaque to Ktor's Auth plugin — it never sends
            // it itself; TokenManager owns refresh entirely via the issuer's
            // token endpoint. An empty string satisfies BearerTokens.
            loadTokens { accessToken()?.let { BearerTokens(accessToken = it, refreshToken = "") } }
            refreshTokens { renewedToken()?.let { BearerTokens(accessToken = it, refreshToken = "") } }
        }
    }
    HttpResponseValidator {
        validateResponse { response ->
            if (!response.status.isSuccess()) throw response.apiStatusException()
        }
    }
    defaultRequest {
        url.takeFrom(URLBuilder().takeFrom(apiBaseUrl))
    }
}

/** An API call's time to reach the server, and the longest it may wait for
 *  the next byte of the answer ([apiTimeouts]). */
internal const val API_CONNECT_TIMEOUT_MS = 5_000L
internal const val API_SOCKET_TIMEOUT_MS = 8_000L

/**
 * Explicit timeouts for an API client, the same on Android and iOS: 5 s to
 * connect, 8 s without a byte of the answer. Without them a server that holds
 * a call up held the app for the platform's default - OkHttp's 10 s read
 * timeout, NSURLSession's 60 s. Streams and segments keep the players' own
 * policy: Media3's client and AVPlayer fetch them, not this one.
 */
internal fun HttpClientConfig<*>.apiTimeouts() {
    install(HttpTimeout) {
        connectTimeoutMillis = API_CONNECT_TIMEOUT_MS
        socketTimeoutMillis = API_SOCKET_TIMEOUT_MS
    }
}

/**
 * Puts the client on the connections the app streams over, where the
 * platform's player shares its client's: Android's one OkHttp connection
 * pool, the API's, the player's and Zap's alike, so the player's first
 * request goes over a connection the API calls just used - a fresh one to
 * the same server cost the player about a second each time it opened. iOS
 * leaves the engine as it is: AVPlayer fetches on its own stack.
 */
internal expect fun HttpClientEngineConfig.shareConnections()

/**
 * Drops the bearer the Auth plugin holds. The plugin keeps what loadTokens
 * returned first (until a 401 renews it), so after the active account
 * changes it would go on sending the previous one's token; the next request
 * after this loads the token of whoever is active then.
 */
internal fun HttpClient.forgetBearer() {
    authProvider<BearerAuthProvider>()?.clearToken()
}

object HttpClientFactory {
    /** Authenticated client used for every chino-api call. Auth plugin reads
     *  tokens through [TokenManager] which is multi-account aware — switching
     *  the active account in AccountStore propagates here without rewiring. */
    fun create(config: AppConfig, tokenManager: TokenManager): HttpClient = HttpClient {
        // On the connections the player streams over, so its first request
        // to the server goes over one these calls just used (shareConnections).
        engine { shareConnections() }
        chinoApiClient(
            apiBaseUrl = config.apiBaseUrl,
            accessToken = { tokenManager.validAccessToken() },
            renewedToken = { tokenManager.forceRefresh() },
        )
        install(Logging) {
            level = if (config.isBeta) LogLevel.INFO else LogLevel.NONE
        }
    }

    /** Bare unauthenticated client for the first-run server probe
     *  ([cloud.nalet.chino.mobile.data.ServerBootstrap] + [cloud.nalet.chino.mobile.data.auth.OidcDiscovery]).
     *  Built WITHOUT an [AppConfig] because at probe time no server is
     *  configured yet — the user-entered URL is the only input. Logging is on
     *  so probe failures are visible in beta logcat. JSON content negotiation
     *  is installed so callers can decode /api/healthz + /api/config + the
     *  .well-known docs; non-2xx responses are surfaced to the caller (the
     *  probe maps them onto its sealed failure kinds). */
    fun createProbe(): HttpClient = HttpClient {
        install(ContentNegotiation) { json(ChinoJson) }
        install(Logging) { level = LogLevel.INFO }
        // Bound the first-run probe so a wrong host/port (firewalled IP, typo)
        // fails the Add-Server attempt in seconds instead of hanging on the
        // platform default socket timeout. Connect timeout is short (6s) because
        // probe() now tries up to TWO candidates (https then http) for a bare
        // host — two 6s connects keep an unreachable address under ~12s total
        // rather than ~20s.
        install(HttpTimeout) {
            connectTimeoutMillis = 6_000
            requestTimeoutMillis = 15_000
        }
    }

    /** Unauthenticated client used by [cloud.nalet.chino.mobile.data.auth.OidcDeviceClient]
     *  for the Keycloak device/token/userinfo endpoints. We can't share the
     *  authenticated client there because the Auth plugin would attach a
     *  Bearer header to the unauthenticated token-exchange call, which
     *  Keycloak rejects with `unauthorized_client`. */
    fun createUnauthenticated(config: AppConfig): HttpClient = HttpClient {
        install(ContentNegotiation) { json(ChinoJson) }
        // Every API call waits on it when the bearer is renewed: the API's
        // budget.
        apiTimeouts()
        install(Logging) {
            level = if (config.isBeta) LogLevel.INFO else LogLevel.NONE
        }
    }
}
