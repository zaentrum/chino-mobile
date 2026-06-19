package cloud.nalet.chino.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The server this client is connected to. For the neutral self-host client
 * the user enters their own server URL on first run and the OIDC config is
 * discovered from it ([ServerBootstrap]); the build-flavor values become only
 * the prefilled Add-Server default + an in-memory fallback.
 *
 * baseUrl / issuer / clientId are NOT secrets (tokens live in [AccountStore]),
 * so the same plaintext-but-sandboxed storage the AccountStore uses (DataStore
 * Preferences on Android, NSUserDefaults on iOS) is fine here too. The
 * discovered endpoints stay null until OIDC discovery has run; when null,
 * [cloud.nalet.chino.mobile.data.auth.OidcDeviceClient] falls back to building
 * the Keycloak openid-connect endpoint paths from the issuer (the original
 * behaviour).
 *
 * Mirrors chino-androidtv's ServerConfig field-for-field so the two clients
 * are easy to reason about together.
 */
@Serializable
data class ServerConfig(
    @SerialName("base_url") val baseUrl: String,
    @SerialName("issuer") val issuer: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("auth_endpoint") val authEndpoint: String? = null,
    @SerialName("device_auth_endpoint") val deviceAuthEndpoint: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String? = null,
    @SerialName("userinfo_endpoint") val userinfoEndpoint: String? = null,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}

/** Persisted blob — accounts the stored [ServerConfig] (or null when none
 *  connected yet) plus the most-recently-connected server URLs (newest first,
 *  max 5) for the Add-Server quick-connect list. Public visibility because
 *  [BaseServerConfigStore]'s protected read/write helpers reference it and the
 *  per-platform actuals live in a different module dir. */
@Serializable
data class ServerConfigBlob(
    @SerialName("config") val config: ServerConfig? = null,
    @SerialName("recents") val recents: List<String> = emptyList(),
)

/**
 * Platform-agnostic interface for the connected-server config. Concrete
 * storage is per-platform (Android DataStore Preferences, iOS NSUserDefaults)
 * — the SAME split as [AccountStore]. Wired into [AppContainer] via
 * [PlatformServerConfigStoreFactory].
 */
interface ServerConfigStore {
    /** Hot flow of the stored config; emits null when no server connected. */
    val config: Flow<ServerConfig?>

    /** The stored config, or null when no server has been connected yet
     *  (a fresh neutral install before Add-Server). */
    suspend fun current(): ServerConfig?

    /** Blocking read of the stored config (null if no server connected yet).
     *  Runs once while the lazy AppContainer graph materialises off the main
     *  thread; mirrors [AccountStore.snapshotBlocking]. Does NOT persist a
     *  default — seeding existing installs is the boot gate's job. */
    fun currentBlocking(): ServerConfig?

    /** Most-recently-connected server URLs (newest first, max 5). */
    suspend fun recents(): List<String>

    suspend fun addRecent(url: String)

    suspend fun save(config: ServerConfig)

    /** Forgets the connected server — used by Settings "Change server". */
    suspend fun clear()
}

/** Per-platform factory — Android gives us a Context, iOS gives us a suite
 *  name (mirrors [PlatformAccountStoreFactory]'s split). The host app wires
 *  the right constructor at startup. */
expect class PlatformServerConfigStoreFactory {
    fun create(): ServerConfigStore
}
