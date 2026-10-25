package cloud.nalet.chino.mobile.ui.onboarding

import cloud.nalet.chino.mobile.ui.downloads.leaveCastingOf
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.BootstrapResult
import cloud.nalet.chino.mobile.data.ServerBootstrap
import cloud.nalet.chino.mobile.data.ServerConfig
import cloud.nalet.chino.mobile.data.downloads.DownloadsSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AddServerState {
    data object Idle : AddServerState
    data object Probing : AddServerState
    data class Error(val message: String) : AddServerState

    /** The new server answered, and changing to it deletes the downloads of
     *  the one connected now ([dropsDownloads]: another server), or stops
     *  casting: asked first ([AddServerScreenModel.confirm]). */
    data class AskDropDownloads(
        val summary: DownloadsSummary,
        val config: ServerConfig,
        val url: String,
        val dropsDownloads: Boolean = true,
    ) : AddServerState
    data object Done : AddServerState
}

/**
 * Drives the Add-Server screen — Voyager-flavoured port of chino-androidtv's
 * ServerSetupViewModel. Probes a user-entered URL via [ServerBootstrap]
 * (healthz -> /api/config -> OIDC discovery) and, on success, persists the
 * resolved [cloud.nalet.chino.mobile.data.ServerConfig] + records the URL in
 * recents, then signals Done so the host advances to sign-in.
 *
 * [clearAccountsOnConnect] = true is set by the "Change server" entry from
 * Settings: switching servers means the existing accounts are tokens for a
 * DIFFERENT OIDC issuer, so they're wiped before we point at the new server.
 */
class AddServerScreenModel(
    private val container: AppContainer,
    private val clearAccountsOnConnect: Boolean = false,
) : ScreenModel {
    /** Build-flavor server origin offered as a one-tap preset / prefill
     *  (blank for a neutral build with no baked default). */
    val presetUrl: String get() = container.presetServerUrl

    private val _state = MutableStateFlow<AddServerState>(AddServerState.Idle)
    val state: StateFlow<AddServerState> = _state.asStateFlow()

    private val _recents = MutableStateFlow<List<String>>(emptyList())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    init {
        screenModelScope.launch {
            _recents.value = runCatching { container.serverConfigStore.recents() }.getOrDefault(emptyList())
        }
    }

    fun connect(rawUrl: String) {
        if (_state.value is AddServerState.Probing || rawUrl.isBlank()) return
        _state.value = AddServerState.Probing
        // No telemetry on this path: the telemetry funnel hangs off the
        // authenticated chinoApi/http client, which is built from the
        // (build-default) config. Touching it before the user has connected to
        // their real server would materialise that client against the wrong
        // base URL. The probe uses its own bare client (container.serverBootstrap).
        screenModelScope.launch {
            when (val r = runCatching { container.serverBootstrap.probe(rawUrl) }.getOrElse {
                BootstrapResult.Fail(BootstrapResult.Fail.Kind.UNREACHABLE, it.message)
            }) {
                is BootstrapResult.Ok -> {
                    // Another server than the one connected now: its downloads
                    // go - asked first where there are any.
                    val oldServer = container.serverBase
                    val another = clearAccountsOnConnect && r.config.baseUrl.trimEnd('/') != oldServer
                    val summary = if (another) container.downloads?.summaryOfServer(oldServer) else null
                    // Asked, too, while the account casts: that stops - also
                    // connecting to the same server again, which removes
                    // every account all the same.
                    val casting = activeAccountId()?.let { container.castingDeviceOf(it) } != null
                    if (asksBeforeChangingServer(clearAccountsOnConnect, summary, casting)) {
                        _state.value = AddServerState.AskDropDownloads(summary ?: DownloadsSummary(0, 0L), r.config, rawUrl, dropsDownloads = another)
                    } else {
                        finish(r.config, rawUrl, dropDownloadsOf = oldServer.takeIf { another }, castingSaid = false)
                    }
                }
                is BootstrapResult.Fail -> _state.value = AddServerState.Error(messageFor(r))
            }
        }
    }

    /** "change server", confirmed: the old server's downloads go, then as
     *  before. */
    fun confirm() {
        val ask = _state.value as? AddServerState.AskDropDownloads ?: return
        _state.value = AddServerState.Probing
        screenModelScope.launch {
            finish(ask.config, ask.url, dropDownloadsOf = container.serverBase.takeIf { ask.dropsDownloads }, castingSaid = true)
        }
    }

    /** "cancel": nothing changes, the old server stays. */
    fun cancel() {
        if (_state.value is AddServerState.AskDropDownloads) _state.value = AddServerState.Idle
    }

    private fun activeAccountId(): String? = runCatching { container.accountStore.snapshotBlocking().activeAccount?.id }.getOrNull()

    private suspend fun finish(config: ServerConfig, rawUrl: String, dropDownloadsOf: String?, castingSaid: Boolean) {
        // Another server, or every account removed for the same one again:
        // whoever casts now stops - its last position written first - as
        // the question said (else a toast does), and the TV plays nothing on
        // for whoever signs in next.
        if (changeServerLeavesCast(clearAccountsOnConnect, dropDownloadsOf != null)) {
            activeAccountId()?.let { leaveCastingOf(container, it, castingSaid = castingSaid) }
        }
        if (dropDownloadsOf != null) runCatching { container.downloads?.dropServer(dropDownloadsOf) }
        if (clearAccountsOnConnect) {
            runCatching {
                container.accountStore.snapshotBlocking().accounts.forEach {
                    container.accountStore.remove(it.id)
                }
            }
        }
        container.serverConfigStore.save(config)
        container.serverConfigStore.addRecent(ServerBootstrap.normalize(rawUrl))
        _state.value = AddServerState.Done
    }

    private fun messageFor(f: BootstrapResult.Fail): String = when (f.kind) {
        BootstrapResult.Fail.Kind.UNREACHABLE ->
            "Couldn't reach that server. Check the address and that it's online."
        BootstrapResult.Fail.Kind.NOT_CHINO ->
            "That address responded, but it doesn't look like a Chino server."
        BootstrapResult.Fail.Kind.TLS ->
            "Secure connection failed — the server's certificate isn't trusted."
        BootstrapResult.Fail.Kind.NO_CONFIG ->
            "Server reachable, but it didn't return its configuration (/api/config)."
        BootstrapResult.Fail.Kind.NO_DISCOVERY ->
            "Couldn't read the login provider's configuration (OIDC discovery failed)."
        BootstrapResult.Fail.Kind.HTTPS_ONLY ->
            "This app connects over HTTPS only; plain http works just for localhost. Use the server's https:// address."
    }
}

/** Whether "change server" asks first: the downloads of the server
 *  connected now go ([downloads], another server's change), or - where the
 *  accounts are removed ([clearsAccounts], Change server, even to the same
 *  server) - the account casting now stops ([casting]). */
internal fun asksBeforeChangingServer(clearsAccounts: Boolean, downloads: DownloadsSummary?, casting: Boolean): Boolean =
    (downloads != null && !downloads.isEmpty) || (clearsAccounts && casting)

/** Whether connecting stops casting: wherever it removes the accounts
 *  ([clearsAccounts]) or the old server's downloads ([dropsDownloads]) - a
 *  title never plays on under the next person. */
internal fun changeServerLeavesCast(clearsAccounts: Boolean, dropsDownloads: Boolean): Boolean = clearsAccounts || dropsDownloads
