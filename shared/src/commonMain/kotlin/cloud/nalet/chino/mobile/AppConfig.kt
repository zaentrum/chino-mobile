package cloud.nalet.chino.mobile

/**
 * Per-flavor runtime config supplied by the host app at startup.
 *
 * Android: built from BuildConfig (set by Gradle product flavors beta/prod).
 * iOS: built from Bundle.main.infoDictionary keys (set by Configuration/Beta.xcconfig
 * or Configuration/Prod.xcconfig).
 *
 * Keeping this in commonMain lets us write a single Compose/Voyager tree that
 * doesn't know whether it's running against beta or prod.
 */
data class AppConfig(
    val flavor: Flavor,
    val apiBaseUrl: String,
    val oidcIssuer: String,
    val oidcClientId: String,
    val displayName: String,
    // Server origin offered in the Add-Server UI as a prefill + one-tap
    // suggestion. Beta ships the beta origin for convenience; the neutral
    // store/prod build ships this BLANK so the field starts empty with a
    // generic placeholder and no operator URL is suggested. Distinct from
    // [apiBaseUrl] (the internal non-UI fallback). Defaults empty so a host
    // platform with no value (or older callers) is honestly neutral.
    val serverPreset: String = "",
    // OIDC redirect scheme (e.g. "cloud.nalet.chino.mobile.beta.debug"). Must be
    // registered on the OIDC client; the host builds it per flavor/build-type.
    // Defaults empty so the launcher falls back to the package name.
    val redirectScheme: String = "",
) {
    enum class Flavor { BETA, PROD }

    val isBeta: Boolean get() = flavor == Flavor.BETA

    /**
     * Overlay the persisted, discovered [ServerConfig] on top of the build-
     * flavor defaults. The server the user connected to wins for everything
     * the neutral client must NOT hardcode — apiBaseUrl, OIDC issuer, OIDC
     * client id. [redirectScheme], [flavor] and [displayName] stay the
     * build's own (the redirect scheme is the app's OWN scheme the operator
     * registers on their OIDC client; we never take a redirect from the
     * server).
     *
     * Returns the unchanged build config when [server] is null (no server
     * connected yet — purely defensive; the boot gate routes a fresh install
     * to Add-Server before any API client is built).
     */
    fun withServer(server: cloud.nalet.chino.mobile.data.ServerConfig?): AppConfig {
        if (server == null || !server.isConfigured) return this
        return copy(
            apiBaseUrl = server.baseUrl,
            oidcIssuer = server.issuer,
            oidcClientId = server.clientId,
        )
    }
}
