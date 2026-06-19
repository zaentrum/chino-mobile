package cloud.nalet.chino.mobile.data.api

import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.auth.TokenManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object HttpClientFactory {
    /** Authenticated client used for every chino-api call. Auth plugin reads
     *  tokens through [TokenManager] which is multi-account aware — switching
     *  the active account in AccountStore propagates here without rewiring. */
    fun create(config: AppConfig, tokenManager: TokenManager): HttpClient = HttpClient {
        install(ContentNegotiation) {
            // coerceInputValues: the server sends `"qualities": null` (and can
            // null other defaulted fields) for remux items; without coercion
            // kotlinx throws "Expected '[' but had 'n'" and the whole PlayInfo
            // (and similar) responses fail to deserialize. Coercion maps a null
            // onto the property's default (emptyList()) instead of crashing.
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true })
        }
        install(Logging) {
            level = if (config.isBeta) LogLevel.INFO else LogLevel.NONE
        }
        install(Auth) {
            bearer {
                loadTokens {
                    tokenManager.validAccessToken()?.let { token ->
                        // refresh_token is opaque to Ktor's Auth plugin — it
                        // never sends it itself; TokenManager owns refresh
                        // entirely via the Keycloak token endpoint. Pass an
                        // empty string so the BearerTokens signature is
                        // satisfied.
                        BearerTokens(accessToken = token, refreshToken = "")
                    }
                }
                refreshTokens {
                    tokenManager.forceRefresh()?.let { token ->
                        BearerTokens(accessToken = token, refreshToken = "")
                    }
                }
            }
        }
        defaultRequest {
            url.takeFrom(URLBuilder().takeFrom(config.apiBaseUrl))
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
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true })
        }
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
        install(ContentNegotiation) {
            // coerceInputValues: the server sends `"qualities": null` (and can
            // null other defaulted fields) for remux items; without coercion
            // kotlinx throws "Expected '[' but had 'n'" and the whole PlayInfo
            // (and similar) responses fail to deserialize. Coercion maps a null
            // onto the property's default (emptyList()) instead of crashing.
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true })
        }
        install(Logging) {
            level = if (config.isBeta) LogLevel.INFO else LogLevel.NONE
        }
    }
}
