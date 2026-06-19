package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.data.auth.OidcDiscovery
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Outcome of probing a user-entered server address. Mirrors chino-androidtv's
 *  BootstrapResult so the two clients show the same actionable errors. */
sealed interface BootstrapResult {
    data class Ok(val config: ServerConfig) : BootstrapResult
    data class Fail(val kind: Kind, val detail: String? = null) : BootstrapResult {
        enum class Kind { UNREACHABLE, NOT_CHINO, TLS, NO_CONFIG, NO_DISCOVERY }
    }
}

/**
 * Turns a typed server address into a ready [ServerConfig] by probing the
 * server: confirm it is reachable and is a Chino server (/api/healthz), read
 * its self-describing config (/api/config) for the OIDC issuer + client id,
 * then run OIDC discovery against that issuer. Faithful port of chino-
 * androidtv's ServerBootstrap, swapping OkHttp for the project's Ktor client
 * and picking the `mobile` client id from /api/config (the TV picks `tv`).
 *
 * Distinct failure kinds let the Add-Server UI show actionable errors instead
 * of a generic stack trace.
 *
 * Note: Ktor Multiplatform surfaces a TLS-trust failure as a generic
 * transport exception (no portable SSLException type across Darwin/OkHttp
 * engines), so unlike the TV we don't tease TLS apart from UNREACHABLE here;
 * both map to UNREACHABLE with the message carrying the detail. The TLS kind
 * is kept in the enum for parity / a future engine-specific refinement.
 */
class ServerBootstrap(
    private val http: HttpClient,
    private val discovery: OidcDiscovery,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Probe a user-entered address, inferring the scheme when the user didn't
     * type one: a bare host (or IP, or host:port) is tried as https FIRST then
     * http, so a server on 443 or on 80 both connect from "chino.example.com".
     * When the user typed an explicit scheme we honour it but still fall back
     * to the other scheme. The first candidate that reaches a real Chino server
     * wins; otherwise we return the most informative failure across candidates
     * (a NOT_CHINO/NO_CONFIG/NO_DISCOVERY connected-but-wrong beats a bare
     * UNREACHABLE). Each candidate runs under the probe client's bounded
     * 10s/15s timeout, so trying two stays fast.
     */
    suspend fun probe(rawUrl: String): BootstrapResult {
        val candidates = candidates(rawUrl)
        if (candidates.isEmpty()) {
            return BootstrapResult.Fail(BootstrapResult.Fail.Kind.UNREACHABLE, "empty URL")
        }
        // If the user typed an explicit scheme, honor it: only fall back to the
        // other scheme when the typed one was UNREACHABLE. A scheme that
        // CONNECTED but was wrong/unconfigured (NOT_CHINO/NO_CONFIG/…) is the
        // intended endpoint — surface that failure immediately instead of
        // wasting a round-trip on the other scheme (which could hit an
        // unrelated service on :80).
        val explicitScheme = rawUrl.trim().let { it.startsWith("http://") || it.startsWith("https://") }
        var best: BootstrapResult.Fail? = null
        for ((i, base) in candidates.withIndex()) {
            when (val r = probeOne(base)) {
                is BootstrapResult.Ok -> return r
                is BootstrapResult.Fail -> {
                    best = preferred(best, r)
                    if (explicitScheme && i == 0 && r.kind != BootstrapResult.Fail.Kind.UNREACHABLE) {
                        return r
                    }
                }
            }
        }
        return best ?: BootstrapResult.Fail(BootstrapResult.Fail.Kind.UNREACHABLE, candidates.first())
    }

    /** Runs the healthz -> /api/config -> OIDC-discovery sequence against one
     *  normalized base (scheme + host, no trailing slash). */
    private suspend fun probeOne(base: String): BootstrapResult {
        val apiBase = "$base/api"

        // 1) reachability + "is this a Chino server?"
        val health = when (val r = getJson("$apiBase/healthz", Health.serializer())) {
            is Fetch.Ok -> r.value
            is Fetch.Err -> return BootstrapResult.Fail(BootstrapResult.Fail.Kind.UNREACHABLE, r.detail ?: base)
        }
        if (health?.product != "chino") {
            return BootstrapResult.Fail(BootstrapResult.Fail.Kind.NOT_CHINO, health?.product)
        }

        // 2) self-describing bootstrap config
        val cfg = when (val r = getJson("$apiBase/config", AppConfigDoc.serializer())) {
            is Fetch.Ok -> r.value
            else -> null
        }
        val issuer = cfg?.oidcIssuer
            ?: return BootstrapResult.Fail(BootstrapResult.Fail.Kind.NO_CONFIG, "$apiBase/config")
        val clientId = cfg.oidcClientId?.mobile ?: "chino"

        // 3) OIDC discovery against the advertised issuer
        val ep = discovery.discover(issuer)
            ?: return BootstrapResult.Fail(BootstrapResult.Fail.Kind.NO_DISCOVERY, issuer)

        return BootstrapResult.Ok(
            ServerConfig(
                // Trailing slash matches the apiBaseUrl convention Ktor's
                // defaultRequest expects (HttpClientFactory.create).
                baseUrl = "$apiBase/",
                issuer = ep.issuer,
                clientId = clientId,
                authEndpoint = ep.authEndpoint,
                deviceAuthEndpoint = ep.deviceAuthEndpoint,
                tokenEndpoint = ep.tokenEndpoint,
                userinfoEndpoint = ep.userinfoEndpoint,
            ),
        )
    }

    /** Keep the more informative of two failures: a candidate that CONNECTED
     *  but turned out wrong (NOT_CHINO / NO_CONFIG / NO_DISCOVERY / TLS) tells
     *  the user more than a bare UNREACHABLE, so it wins. */
    private fun preferred(a: BootstrapResult.Fail?, b: BootstrapResult.Fail): BootstrapResult.Fail {
        if (a == null) return b
        val aReached = a.kind != BootstrapResult.Fail.Kind.UNREACHABLE
        val bReached = b.kind != BootstrapResult.Fail.Kind.UNREACHABLE
        return if (bReached && !aReached) b else a
    }

    private sealed interface Fetch<out T> {
        data class Ok<T>(val value: T?) : Fetch<T>
        data class Err(val detail: String?) : Fetch<Nothing>
    }

    private suspend fun <T> getJson(url: String, serializer: KSerializer<T>): Fetch<T> = try {
        val resp = http.get(url)
        if (resp.status.value !in 200..299) {
            Fetch.Err("HTTP ${resp.status.value}")
        } else {
            Fetch.Ok(json.decodeFromString(serializer, resp.bodyAsText()))
        }
    } catch (e: Exception) {
        // Includes TLS-trust failures, DNS, connection-refused, timeouts.
        Fetch.Err(e.message)
    }

    companion object {
        /** Trims, defaults the scheme to https, strips a trailing slash. The
         *  canonical single form used for display + recents storage (the probe
         *  itself tries both schemes via [candidates]). */
        fun normalize(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return s
            if (!hasScheme(s)) s = "https://$s"
            return s.trimEnd('/')
        }

        /**
         * Bases to try, in order, each trailing-slash-trimmed:
         *  - no scheme typed (bare host / IP / host:port) -> [https, http],
         *    https first so a TLS server is preferred but a plain-http server
         *    (e.g. a LAN box on :80) still connects.
         *  - scheme typed -> that scheme first, then the other as a fallback,
         *    so an explicit `http://` LAN host upgrades to https if available
         *    and a `https://` typo still reaches a http-only box.
         *  Empty input yields no candidates.
         */
        fun candidates(raw: String): List<String> {
            val s = raw.trim()
            if (s.isEmpty()) return emptyList()
            return when {
                s.startsWith("https://") -> listOf(s, "http://" + s.removePrefix("https://"))
                s.startsWith("http://") -> listOf(s, "https://" + s.removePrefix("http://"))
                else -> listOf("https://$s", "http://$s")
            }.map { it.trimEnd('/') }.distinct()
        }

        private fun hasScheme(s: String): Boolean =
            s.startsWith("http://") || s.startsWith("https://")
    }
}

@Serializable
private data class Health(val status: String? = null, val product: String? = null)

@Serializable
private data class AppConfigDoc(
    val oidcIssuer: String? = null,
    val oidcAudience: String? = null,
    val oidcClientId: ClientIds? = null,
)

@Serializable
private data class ClientIds(
    val tv: String? = null,
    val mobile: String? = null,
    val web: String? = null,
)
