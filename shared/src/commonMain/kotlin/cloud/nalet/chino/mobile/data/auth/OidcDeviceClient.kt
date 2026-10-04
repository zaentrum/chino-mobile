package cloud.nalet.chino.mobile.data.auth

import cloud.nalet.chino.mobile.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The OIDC token and userinfo calls the app makes itself: the Authorization
 * Code + PKCE exchange (iOS; Android's AppAuth does its own), the silent
 * refresh, and the userinfo lookup after a sign-in.
 *
 * The name is historical: this began as an RFC 8628 device-flow client.
 * Sign-in has since moved to the platform browser (SignInLauncher), and the
 * device flow went with it.
 */
class OidcDeviceClient(
    private val http: HttpClient,
    private val tokenEndpoint: String,
    private val userinfoEndpoint: String,
    private val clientId: String,
) {
    /**
     * Builds the standard Keycloak openid-connect endpoint paths from a realm
     * issuer. Used as the fallback when OIDC discovery hasn't populated the
     * endpoint set (e.g. a seeded build-default server) — preserves the
     * original baked-in behaviour. The discovered-endpoint primary ctor keeps
     * the client neutral against any compliant OIDC provider, not just Keycloak.
     */
    constructor(http: HttpClient, issuer: String, clientId: String) : this(
        http = http,
        tokenEndpoint = "$issuer/protocol/openid-connect/token",
        userinfoEndpoint = "$issuer/protocol/openid-connect/userinfo",
        clientId = clientId,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * OAuth 2.0 Authorization Code + PKCE token exchange. Trades the `code`
     * returned on the redirect (captured by the platform in-app browser —
     * iOS ASWebAuthenticationSession) for tokens, proving possession of the
     * PKCE `code_verifier`. Public client, so no client secret: Keycloak's
     * `chino` client is configured as public + PKCE-required.
     *
     * The Android path uses AppAuth's own performTokenRequest instead; this
     * is the iOS counterpart, sharing the same discovered [tokenEndpoint] +
     * [clientId] so both platforms hit an identical exchange. [redirectUri]
     * must byte-match the one sent on the authorize request (and registered
     * on the OIDC client) or Keycloak rejects with invalid_grant.
     */
    suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): Tokens {
        val response = http.submitForm(
            url = tokenEndpoint,
            formParameters = parameters {
                append("client_id", clientId)
                append("grant_type", "authorization_code")
                append("code", code)
                append("code_verifier", codeVerifier)
                append("redirect_uri", redirectUri)
            },
        )
        if (response.status.value !in 200..299) {
            error("Code exchange failed: HTTP ${response.status.value} — ${response.bodyAsText()}")
        }
        val tok = json.decodeFromString(TokenResponse.serializer(), response.bodyAsText())
        return Tokens(
            accessToken = tok.accessToken,
            refreshToken = tok.refreshToken,
            expiresAtEpochMillis = currentTimeMillis() + tok.expiresIn * 1000L,
        )
    }

    /** Silent renew via refresh_token. Called from [TokenManager] when the
     *  cached access token is within REFRESH_SLACK_MS of expiry OR after a
     *  401. Returns null on any non-2xx so the caller can decide whether to
     *  drop to the AUTH screen or retry later. */
    suspend fun refresh(refreshToken: String): Tokens? {
        val response = http.submitForm(
            url = tokenEndpoint,
            formParameters = parameters {
                append("client_id", clientId)
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            },
        )
        if (response.status.value !in 200..299) return null
        val tok = runCatching {
            json.decodeFromString(TokenResponse.serializer(), response.bodyAsText())
        }.getOrNull() ?: return null
        return Tokens(
            accessToken = tok.accessToken,
            // Keycloak omits refresh_token on renewal when refresh-rotation
            // is off — keep the existing one in that case so the next call
            // can still renew.
            refreshToken = tok.refreshToken ?: refreshToken,
            expiresAtEpochMillis = currentTimeMillis() + tok.expiresIn * 1000L,
        )
    }

    /** Userinfo lookup — used to populate the Account row after a fresh
     *  sign-in (sub → account id, name → displayName, email → gravatar
     *  fallback). */
    suspend fun fetchUserInfo(accessToken: String): UserInfo? {
        val response = http.get(userinfoEndpoint) {
            header("Authorization", "Bearer $accessToken")
        }
        if (response.status.value !in 200..299) return null
        return runCatching {
            json.decodeFromString(UserInfo.serializer(), response.bodyAsText())
        }.getOrNull()
    }
}

@Serializable
data class UserInfo(
    val sub: String,
    val name: String? = null,
    @SerialName("preferred_username") val preferredUsername: String? = null,
    val email: String? = null,
    @SerialName("given_name") val givenName: String? = null,
    @SerialName("family_name") val familyName: String? = null,
) {
    /** Best-effort human label: name → preferred_username → email local-part → sub. */
    fun bestDisplayName(): String =
        name?.takeIf { it.isNotBlank() }
            ?: preferredUsername?.takeIf { it.isNotBlank() }
            ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: sub
}

// Wall-clock epoch ms. Defined as expect/actual in Platform.kt because Kotlin 2.1's
// kotlin.time.Clock isn't stable across all KMP targets yet.

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0L,
    @SerialName("token_type") val tokenType: String = "Bearer",
)
