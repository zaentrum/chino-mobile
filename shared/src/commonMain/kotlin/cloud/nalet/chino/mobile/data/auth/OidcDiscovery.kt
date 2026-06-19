package cloud.nalet.chino.mobile.data.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Concrete OIDC endpoint set resolved from a server's issuer. */
data class OidcEndpoints(
    val issuer: String,
    val authEndpoint: String,
    val deviceAuthEndpoint: String?,
    val tokenEndpoint: String,
    val userinfoEndpoint: String,
)

/**
 * OIDC Authorization-Server metadata discovery (RFC 8414 + OIDC Discovery
 * 1.0). Faithful port of chino-androidtv's OidcDiscovery (swapping OkHttp for
 * the project's Ktor client). Turns an issuer URL into its concrete endpoint
 * set so Chino works against any compliant OIDC provider, not just Keycloak's
 * fixed path layout.
 *
 * For an issuer WITH a path component (Keycloak issuers look like
 * https://host/realms/name), RFC 8414 inserts the well-known segment before
 * the path (https://host/.well-known/oauth-authorization-server/realms/name)
 * while OIDC Discovery 1.0 appends it (https://host/realms/name plus
 * /.well-known/openid-configuration). Keycloak answers the appended form, so
 * we try the RFC 8414 form first and fall back to the appended one.
 *
 * Unlike the TV (which needs device_authorization_endpoint for RFC 8628), the
 * mobile client uses Authorization Code + PKCE via AppAuth, so the
 * authorization_endpoint + token_endpoint are the required pair;
 * device_authorization_endpoint is captured opportunistically (nullable) for
 * the legacy device-flow fallback path.
 */
class OidcDiscovery(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(issuer: String): OidcEndpoints? {
        val trimmed = issuer.trimEnd('/')
        for (url in candidateUrls(trimmed)) {
            val doc = fetch(url) ?: continue
            val auth = doc.authorizationEndpoint ?: continue
            val token = doc.tokenEndpoint ?: continue
            return OidcEndpoints(
                issuer = doc.issuer ?: trimmed,
                authEndpoint = auth,
                deviceAuthEndpoint = doc.deviceAuthorizationEndpoint,
                tokenEndpoint = token,
                userinfoEndpoint = doc.userinfoEndpoint
                    ?: "$trimmed/protocol/openid-connect/userinfo",
            )
        }
        return null
    }

    /** Both well-known forms for an issuer; collapses to one when the issuer
     *  has no path component. */
    private fun candidateUrls(issuer: String): List<String> {
        val schemeEnd = issuer.indexOf("://")
        if (schemeEnd < 0) return listOf("$issuer/.well-known/openid-configuration")
        val afterScheme = schemeEnd + 3
        val slash = issuer.indexOf('/', afterScheme)
        return if (slash < 0) {
            listOf("$issuer/.well-known/openid-configuration")
        } else {
            val origin = issuer.substring(0, slash)
            val path = issuer.substring(slash)
            listOf(
                "$origin/.well-known/oauth-authorization-server$path",
                "$issuer/.well-known/openid-configuration",
            )
        }
    }

    private suspend fun fetch(url: String): ProviderMetadata? = runCatching {
        val resp = http.get(url)
        if (resp.status.value !in 200..299) return null
        json.decodeFromString(ProviderMetadata.serializer(), resp.bodyAsText())
    }.getOrNull()
}

@Serializable
private data class ProviderMetadata(
    val issuer: String? = null,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String? = null,
    @SerialName("userinfo_endpoint") val userinfoEndpoint: String? = null,
    @SerialName("device_authorization_endpoint") val deviceAuthorizationEndpoint: String? = null,
    @SerialName("jwks_uri") val jwksUri: String? = null,
)
