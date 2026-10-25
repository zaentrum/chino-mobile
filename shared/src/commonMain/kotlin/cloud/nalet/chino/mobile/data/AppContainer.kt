package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.account.AccountDeletion
import cloud.nalet.chino.mobile.data.account.deleteAccountThenSignOut
import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.HttpClientFactory
import cloud.nalet.chino.mobile.data.api.forgetBearer
import cloud.nalet.chino.mobile.data.auth.AccountStore
import cloud.nalet.chino.mobile.data.auth.OidcDeviceClient
import cloud.nalet.chino.mobile.data.auth.OidcDiscovery
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import cloud.nalet.chino.mobile.data.auth.StreamTokenManager
import cloud.nalet.chino.mobile.data.auth.TokenManager
import cloud.nalet.chino.mobile.data.auth.TokenStore
import cloud.nalet.chino.mobile.data.downloads.DownloadPlanner
import cloud.nalet.chino.mobile.data.downloads.DownloadStarts
import cloud.nalet.chino.mobile.data.downloads.Downloads
import cloud.nalet.chino.mobile.data.downloads.DownloadsPlatform
import cloud.nalet.chino.mobile.data.downloads.MemoryProgressBookStore
import cloud.nalet.chino.mobile.data.downloads.Owner
import cloud.nalet.chino.mobile.data.downloads.PlanResult
import cloud.nalet.chino.mobile.data.downloads.ProgressQueue
import cloud.nalet.chino.mobile.data.downloads.ProgressSync
import cloud.nalet.chino.mobile.data.downloads.ServerReach
import cloud.nalet.chino.mobile.data.notices.NoticesRepository
import cloud.nalet.chino.mobile.data.telemetry.Telemetry
import cloud.nalet.chino.mobile.feedback.BugReporter
import cloud.nalet.chino.mobile.feedback.PendingReportStore
import cloud.nalet.chino.mobile.ui.components.Toast
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    /** Hosts the Add-Server probe may reach over plain http (iOS: localhost
     *  only — see [ServerBootstrap]); every other host is https only. */
    private val allowPlainHttp: (host: String) -> Boolean = { true },
    /** Downloads and playing without the server, where the platform has
     *  them (Android); null: none ([DownloadsPlatform]). */
    private val downloadsPlatform: DownloadsPlatform? = null,
) {
    /** Application-lifetime scope for fire-and-forget POSTs (progress,
     *  watched, telemetry) that must outlive a screen-model's onCleared —
     *  terminal saves race against view-model cancellation otherwise. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The graph's end: the app built another one for another server (Change
     * server). Everything it still runs in [appScope] stops - the downloads'
     * watch of the manager and of the server, its checks, its sends - so that
     * only the new graph acts: two of them would each set the manager's stop
     * reasons for their own server and account, each change setting off the
     * other's.
     */
    fun close() {
        appScope.cancel()
    }

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
     * issuer + OIDC client id come from the connected server; flavor +
     * displayName stay the build's own (and the redirect URI is the app's
     * fixed OAuthRedirect.URI). Resolved
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
            // signs in again as on first launch.
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
            // Keycloak sub and chino-stream would reject it. The bearer the
            // HTTP client holds goes too: the Auth plugin keeps the first
            // one it loaded (or none, loaded while nobody was signed in) and
            // would send it on the new user's requests.
            appScope.launch {
                var seen: String? = store.snapshotBlocking().activeAccount?.id
                store.activeAccountId.collect { id ->
                    if (id != seen) {
                        streamTokenManager.invalidate()
                        http.forgetBearer()
                        seen = id
                    }
                }
            }
        }
    }

    /** Unauthenticated client for the OIDC token / userinfo endpoints. Can't share the authenticated client because the Auth
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
        ServerBootstrap(http = probeHttp, discovery = OidcDiscovery(probeHttp), allowPlainHttp = allowPlainHttp)
    }

    val tokenManager: TokenManager by lazy { TokenManager(accountStore, oidcDeviceClient) }

    /** Authenticated client — every chino-api call routes through here. */
    val http: HttpClient by lazy { HttpClientFactory.create(config, tokenManager, onAnswered = ::serverAnswered) }

    /**
     * A call to the server had its answer: the server is reached, at once
     * ([ServerReach.requestAnswered]). Where it was out of reach until now,
     * what it offers is asked for where it hasn't said yet, and what was
     * played here meanwhile goes out, as when its next look finds it.
     */
    private fun serverAnswered() {
        if (!serverReach.requestAnswered()) return
        appScope.launch {
            if (serverReach.features.value == null) runCatching { serverReach.check() }
            runCatching { progressSync.flush() }
        }
    }

    val chinoApi: ChinoApi by lazy { ChinoApi(http) }

    val streamTokenManager: StreamTokenManager by lazy { StreamTokenManager(chinoApi) }

    val settings: SettingsStore by lazy { settingsStoreFactory.create() }

    val userFlags: UserFlagsRepository by lazy { UserFlagsRepository(chinoApi, appScope) }

    /** Lists-aware companion to [userFlags] — backs the multiple-named-
     *  watchlists surface + the detail add-to-list picker. */
    val watchlists: WatchlistsRepository by lazy { WatchlistsRepository(chinoApi, appScope) }

    /** What addons told the signed-in person — the bell's count and the list
     *  it opens. Asked for by the poll beside the navigator
     *  (ui/notices/NoticesPolling), which forgets them whenever the
     *  signed-in account changes. */
    val notices: NoticesRepository by lazy { NoticesRepository(chinoApi, appScope) }

    /** The subtitle languages the server is set up for, asked once for the
     *  graph's life - a server, a session - and kept: the subtitles menu's
     *  top level after the viewer's own language. The last answer is kept
     *  on the phone with the server, for a start without it. */
    val subtitleLanguages: ServerSubtitleLanguages by lazy {
        val server = config.apiBaseUrl
        ServerSubtitleLanguages(
            fetch = { chinoApi.subtitleLanguages().languages },
            kept = serverConfigStore.subtitleLanguagesBlocking(server),
            keep = { serverConfigStore.saveSubtitleLanguages(server, it) },
        )
    }

    val telemetry: Telemetry by lazy {
        Telemetry(
            api = chinoApi,
            staticContext = deviceStaticContext,
            appScope = appScope,
        )
    }

    /**
     * Deletes [accountId] - the account signed in - on the server (DELETE
     * /v1/me) and, once the server says it is gone, signs it out here
     * ([forgetDeletedAccount]). While the request is out, nothing the TV
     * plays is written for it: a periodic position sent then would arrive
     * as the server deletes the account's rows, and come back as a row of
     * an account that is gone. The writes are held - one already out waited
     * for first, so it lands before the deletion - and go on as before
     * where the account was not deleted.
     */
    suspend fun deleteAccount(accountId: String): AccountDeletion {
        holdCastWrites(accountId, true)
        try {
            return chinoApi.deleteAccountThenSignOut { forgetDeletedAccount(accountId) }
        } finally {
            holdCastWrites(accountId, false)
        }
    }

    /**
     * Signs [accountId] out of this device once the server has deleted it:
     * its tokens leave [accountStore], and what the process still holds for
     * it goes as well — the bearer the HTTP client keeps (it would ride on
     * the next requests, whoever signs in next), the stream token, and the
     * watchlist, likes and lists cached for it.
     */
    suspend fun forgetDeletedAccount(accountId: String) {
        // Casting stops - the TV plays nothing on for whoever comes next -
        // and a toast says so: the question did not. Its last position is
        // not written: the server has just deleted what it kept of the
        // account, and nothing of it comes back.
        runCatching { leaveCasting(accountId, false) }.getOrNull()?.let { device ->
            toasts.tryEmit(Toast(cloud.nalet.chino.mobile.data.downloads.stoppedCastingToast(device)))
        }
        // Its downloads on this phone go too, as Delete Account said.
        runCatching { downloads?.dropOwner(Owner(serverBase, accountId)) }
        accountStore.remove(accountId)
        http.forgetBearer()
        streamTokenManager.invalidate()
        userFlags.clear()
        watchlists.clear()
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

    // ---- Downloads and playing without the server ------------------------

    /** The connected server's API base, without the trailing slash: what
     *  downloads and queued positions belong to. */
    val serverBase: String get() = config.apiBaseUrl.trimEnd('/')

    /** Whether this platform keeps downloads - without building them. */
    val downloadsSupported: Boolean get() = downloadsPlatform != null

    /** Titles kept on the phone, where the platform keeps them; null where
     *  it does not (no download control then). */
    val downloads: Downloads? by lazy { downloadsPlatform?.downloads(this) }

    /** Works a download out before it starts: whether the title can come
     *  along, what it keeps and what that comes to. */
    val downloadPlanner: DownloadPlanner by lazy {
        DownloadPlanner(
            api = chinoApi,
            streamToken = { streamTokenManager.valid() },
            apiBase = { serverBase },
            caps = { downloadsPlatform?.downloadCaps().orEmpty() },
        )
    }

    /** Downloads asked for on a page: worked out and started in the app's
     *  scope, so leaving the page stops none of them. */
    val downloadStarts: DownloadStarts by lazy {
        DownloadStarts(appScope) { itemId ->
            val d = downloads ?: return@DownloadStarts null
            val prefs = settings.flow.first()
            when (
                val r = downloadPlanner.plan(
                    itemId = itemId,
                    quality = d.preferences.value.quality,
                    audioPref = prefs.preferredAudioLang,
                )
            ) {
                is PlanResult.Ready -> {
                    d.start(r.plan)
                    null
                }
                is PlanResult.NotPossible -> r.reason
            }
        }
    }

    /** Whether the server answers, and what its /api/config says it
     *  offers (features.downloads). */
    val serverReach: ServerReach by lazy {
        ServerReach(
            fetch = {
                val resp = probeHttp.get("$serverBase/config") {
                    timeout {
                        connectTimeoutMillis = REACH_CONNECT_TIMEOUT_MS
                        requestTimeoutMillis = REACH_TIMEOUT_MS
                    }
                }
                if (resp.status.isSuccess()) resp.bodyAsText() else null
            },
            networkUp = { downloadsPlatform?.networkUp() },
        )
    }

    /** Who the downloads shown now are for: the account signed in now, by
     *  its subject, on the connected server; null for nobody. */
    val activeOwner: StateFlow<Owner?> by lazy {
        accountStore.activeAccountId
            .map { id -> id?.let { Owner(serverBase, it) } }
            .stateIn(appScope, SharingStarted.Eagerly, accountStore.snapshotBlocking().activeAccount?.let { Owner(serverBase, it.id) })
    }

    /** Item ids the server's word took off the phone just now (POST
     *  /me/available left them out): a player playing one of them from the
     *  phone stops, and says why. */
    val downloadsTaken = MutableSharedFlow<List<String>>(extraBufferCapacity = 8)

    /**
     * Whether playback goes to another screen now - a Cast session: Play on
     * a downloaded title then plays from the server there, and nothing on
     * the phone goes to it. Set by the platform that casts; false without.
     */
    var playsOnAnotherScreen: () -> Boolean = { false }

    /** The device [accountId] casts to now - a session connected while it is
     *  the account signed in - by its name; null where it casts nothing, or
     *  the platform casts nothing. Set by the platform that casts. */
    var castingDeviceOf: (accountId: String) -> String? = { null }

    /** [accountId] leaves the phone while it casts - signed out, removed,
     *  another account in its place: what the TV plays of it is written as
     *  it ([writeLast]; never for an account the server deleted), then
     *  casting stops. The device it stopped casting on; null where it cast
     *  nothing. Set by the platform that casts. */
    var leaveCasting: suspend (accountId: String, writeLast: Boolean) -> String? = { _, _ -> null }

    /** Holds [accountId]'s writes of what the TV plays ([hold]), or lets
     *  them go on (not [hold]): held, none starts for it - a write already
     *  out is waited for, a few seconds at most - while the server deletes
     *  the account. Set by the platform that casts. */
    var holdCastWrites: suspend (accountId: String, hold: Boolean) -> Unit = { _, _ -> }

    /** Short confirmations shown over any screen, the design system's
     *  toasts (ui/components/Toast.kt). */
    val toasts = MutableSharedFlow<Toast>(extraBufferCapacity = 4)

    /** The player's positions: sent at once, or kept and sent once the
     *  server can be reached again - with their age, and with the bearer of
     *  the account that played them. */
    val progressSync: ProgressSync by lazy {
        ProgressSync(
            queue = ProgressQueue(downloadsPlatform?.progressBookStore ?: MemoryProgressBookStore()),
            post = { id, body, bearer -> chinoApi.postProgress(id, body, bearer) },
            account = { accountStore.snapshotBlocking().activeAccount?.id },
            server = { serverBase },
            scope = appScope,
            postWatched = { id, bearer, playedMsAgo -> chinoApi.postWatched(id, bearer, playedMsAgo) },
            bearerOf = { accountId ->
                // The token of the account signed in now, renewed if it must
                // be - and only while it is still the one asked about.
                val token = tokenManager.validAccessToken()
                token.takeIf { accountStore.snapshotBlocking().activeAccount?.id == accountId }
            },
            onPhone = { accountId, itemId ->
                downloads?.isOnPhone(Owner(serverBase, accountId), itemId) ?: false
            },
            // A title's progress refused as not there (404, 410) for the
            // account, of a title on the phone: the server is asked whether
            // the account may still watch it - and the download goes if not.
            onRefused = { accountId, itemId ->
                val owner = Owner(serverBase, accountId)
                if (downloads?.isOnPhone(owner, itemId) == true && activeOwner.value == owner) {
                    appScope.launch { runCatching { downloads?.checkAvailability(listOf(itemId)) } }
                }
            },
            onQueued = { downloadsPlatform?.scheduleProgressFlush() },
            // A position the server answered is the server reached. One it
            // did not answer is a reason to look, not a verdict: a single
            // failed POST does not make the downloads ignore the server.
            onReach = { answered ->
                if (answered) serverReach.report(true) else appScope.launch { runCatching { serverReach.check() } }
            },
        )
    }

    private companion object {
        /** How long a look at the server waits: the app's start waits for
         *  it before it opens on the downloads. */
        const val REACH_CONNECT_TIMEOUT_MS = 3_000L
        const val REACH_TIMEOUT_MS = 4_000L
    }
}
