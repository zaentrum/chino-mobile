package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.HttpClientFactory
import cloud.nalet.chino.mobile.data.auth.AccountStore
import cloud.nalet.chino.mobile.data.auth.OidcDeviceClient
import cloud.nalet.chino.mobile.data.auth.OidcDiscovery
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import cloud.nalet.chino.mobile.data.auth.StreamTokenManager
import cloud.nalet.chino.mobile.data.auth.TokenManager
import cloud.nalet.chino.mobile.data.auth.TokenStore
import cloud.nalet.chino.mobile.data.telemetry.Telemetry
import cloud.nalet.chino.mobile.feedback.BugReporter
import cloud.nalet.chino.mobile.feedback.PendingReportStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled DI graph for the mobile client. Mirrors chino-androidtv's
 * AppContainer in shape so the two clients are easy to compare — the
 * difference is platform abstractions (expect/actual factories) and that
 * everything composed here is multiplatform-safe.
 */
class AppContainer(
    /** Build-flavor defaults supplied by the host (Android BuildConfig / iOS
     *  Info.plist). For the neutral self-host client these are ONLY the
     *  Add-Server prefilled default + a fallback; the live values come from
     *  the persisted [ServerConfig] via [config] below. */
    private val buildConfig: AppConfig,
    private val accountStoreFactory: PlatformAccountStoreFactory,
    private val tokenStoreFactory: PlatformTokenStoreFactory,
    private val settingsStoreFactory: PlatformSettingsStoreFactory,
    private val serverConfigStoreFactory: PlatformServerConfigStoreFactory,
    /** Static device/app fields the host platform supplies — model,
     *  manufacturer, OS version, app flavor, etc. Stamped on every
     *  telemetry event without each call site re-passing the same fields. */
    private val deviceStaticContext: Map<String, String>,
    /** Crash-report files the host's uncaught-exception handler wrote on a
     *  previous process's way down; BugReporter.flushPending drains them
     *  once signed in. Defaults null for hosts without a crash handler
     *  (iOS for now). */
    private val pendingReportStore: PendingReportStore? = null,
) {
    /** Application-lifetime scope for fire-and-forget POSTs (progress,
     *  watched, telemetry) that must outlive a screen-model's onCleared —
     *  terminal saves race against view-model cancellation otherwise. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Persisted connected-server config (URL + OIDC issuer/client +
     *  discovered endpoints). Plaintext-but-sandboxed — not secret; tokens
     *  stay in [accountStore]. */
    val serverConfigStore: ServerConfigStore by lazy { serverConfigStoreFactory.create() }

    /** The build-flavor server offered as the Add-Server prefill / one-tap
     *  suggestion: the origin from the build's SERVER_PRESET. BLANK for the
     *  neutral store build (no baked operator URL) — beta carries the beta
     *  origin. Sourced from [AppConfig.serverPreset], NOT apiBaseUrl, so the
     *  internal API fallback can stay set while the UI ships neutral. */
    val presetServerUrl: String get() = buildConfig.serverPreset
        .removeSuffix("/")
        .removeSuffix("/api")
        .trim()

    /** Build-flavor [ServerConfig] used to seed an existing install on the
     *  first launch after the Add-Server roll-out (so upgraders aren't bounced
     *  to Add-Server) and as the in-memory fallback. */
    fun buildDefaultServerConfig(): ServerConfig = ServerConfig(
        baseUrl = buildConfig.apiBaseUrl,
        issuer = buildConfig.oidcIssuer,
        clientId = buildConfig.oidcClientId,
    )

    /**
     * The effective app config: the build-flavor defaults overlaid with the
     * persisted (discovered) [ServerConfig] when present. apiBaseUrl + OIDC
     * issuer + OIDC client id come from the connected server; the AppAuth
     * redirectScheme + flavor + displayName stay the build's own. Resolved
     * once off the main thread (currentBlocking mirrors AccountStore's
     * snapshotBlocking); falls back to [buildConfig] when nothing is stored
     * yet (purely defensive — the boot gate routes fresh installs to
     * Add-Server before any client below is built).
     */
    val config: AppConfig by lazy {
        buildConfig.withServer(serverConfigStore.currentBlocking())
    }

    /** Discovered OIDC endpoints from the connected server (null fields when
     *  no server connected / discovery didn't advertise them). The AppAuth
     *  launcher prefers these over the Keycloak path-layout guess. */
    val serverConfig: ServerConfig? by lazy { serverConfigStore.currentBlocking() }

    // Every dependency below is `by lazy`. The platform factories that
    // produce `tokenStore` + `accountStore` round-trip through Android
    // Keystore / iOS Keychain for the encrypted-preferences master key,
    // which on AOSP x86 emulators (and any cold-start device whose keystore
    // daemon hasn't warmed) stalls multiple seconds. Touching them on the
    // main thread during Application.onCreate used to fire process-startup
    // ANRs reliably. Architecture rule: keep this constructor cheap, let
    // each consumer touch its dependencies from a Dispatchers.IO boundary
    // (see ChinoApp's produceState boot-snapshot wrapper).

    /** Legacy single-token store, kept only so [AccountStore] can migrate
     *  any in-place v0 tokens on first launch. After migration runs nothing
     *  reads from this any more. */
    val tokenStore: TokenStore by lazy { tokenStoreFactory.create() }

    val accountStore: AccountStore by lazy {
        accountStoreFactory.create().also { store ->
            // One-shot legacy migration. Best-effort: if it fails the user
            // re-runs the device flow as if first-launch.
            appScope.launch {
                runCatching { store.migrateFromTokenStoreIfPresent(tokenStore) }
            }
            // Keep Telemetry's account-id snapshot in sync with the active
            // account so cross-account switches inside the same process
            // show up on every subsequent event.
            appScope.launch {
                store.activeAccountId.collect { id -> telemetry.setActiveAccount(id) }
            }
            // Invalidate the stream token whenever the active user changes
            // so poster/player URLs the next screen renders are signed for
            // the new user — the old token is HMAC-bound to the previous
            // Keycloak sub and chino-stream would reject it.
            appScope.launch {
                var seen: String? = store.snapshotBlocking().activeAccount?.id
                store.activeAccountId.collect { id ->
                    if (id != seen) {
                        streamTokenManager.invalidate()
                        seen = id
                    }
                }
            }
        }
    }

    /** Unauthenticated client for Keycloak device-code / token / userinfo
     *  endpoints. Can't share the authenticated client because the Auth
     *  plugin would attach a Bearer header to the unauthenticated token
     *  exchange, which Keycloak rejects with `unauthorized_client`. */
    private val unauthHttp: HttpClient by lazy { HttpClientFactory.createUnauthenticated(config) }

    val oidcDeviceClient: OidcDeviceClient by lazy {
        val sc = serverConfig
        // Prefer the endpoints OIDC discovery found on the connected server
        // (token + userinfo back token-refresh + the post-AppAuth userinfo
        // lookup) so the client is neutral against any compliant OIDC provider.
        // Fall back to issuer-derived Keycloak paths when discovery didn't
        // populate them (e.g. a seeded build-default server).
        if (sc?.tokenEndpoint != null && sc.userinfoEndpoint != null) {
            OidcDeviceClient(
                http = unauthHttp,
                deviceAuthEndpoint = sc.deviceAuthEndpoint
                    ?: "${config.oidcIssuer}/protocol/openid-connect/auth/device",
                tokenEndpoint = sc.tokenEndpoint,
                userinfoEndpoint = sc.userinfoEndpoint,
                clientId = config.oidcClientId,
            )
        } else {
            OidcDeviceClient(
                http = unauthHttp,
                issuer = config.oidcIssuer,
                clientId = config.oidcClientId,
            )
        }
    }

    /** Bare unauthenticated client for the first-run server probe. Separate
     *  from [unauthHttp] because it is built BEFORE any server is configured
     *  (the user-entered URL is the only input). */
    private val probeHttp: HttpClient by lazy { HttpClientFactory.createProbe() }

    /** First-run / change-server probe: healthz -> /api/config -> OIDC
     *  discovery -> [ServerConfig]. Used by the Add-Server flow. */
    val serverBootstrap: ServerBootstrap by lazy {
        ServerBootstrap(http = probeHttp, discovery = OidcDiscovery(probeHttp))
    }

    val tokenManager: TokenManager by lazy { TokenManager(accountStore, oidcDeviceClient) }

    /** Authenticated client — every chino-api call routes through here. */
    val http: HttpClient by lazy { HttpClientFactory.create(config, tokenManager) }

    val chinoApi: ChinoApi by lazy { ChinoApi(http) }

    val streamTokenManager: StreamTokenManager by lazy { StreamTokenManager(chinoApi) }

    val settings: SettingsStore by lazy { settingsStoreFactory.create() }

    val userFlags: UserFlagsRepository by lazy { UserFlagsRepository(chinoApi, appScope) }

    /** Lists-aware companion to [userFlags] — backs the multiple-named-
     *  watchlists surface + the detail add-to-list picker. */
    val watchlists: WatchlistsRepository by lazy { WatchlistsRepository(chinoApi, appScope) }

    val telemetry: Telemetry by lazy {
        Telemetry(
            api = chinoApi,
            staticContext = deviceStaticContext,
            appScope = appScope,
        )
    }

    /** Bug-report funnel (POST /v1/feedback → server-side bug ticket). Same
     *  static-context injection as [telemetry]; auto reports are silent
     *  fire-and-forget, the Settings dialog goes through reportManual. */
    val bugReporter: BugReporter by lazy {
        BugReporter(
            api = chinoApi,
            staticContext = deviceStaticContext,
            pendingStore = pendingReportStore,
        )
    }
}
