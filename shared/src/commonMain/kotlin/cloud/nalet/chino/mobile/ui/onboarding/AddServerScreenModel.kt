package cloud.nalet.chino.mobile.ui.onboarding

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.BootstrapResult
import cloud.nalet.chino.mobile.data.ServerBootstrap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AddServerState {
    data object Idle : AddServerState
    data object Probing : AddServerState
    data class Error(val message: String) : AddServerState
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
                    if (clearAccountsOnConnect) {
                        runCatching {
                            container.accountStore.snapshotBlocking().accounts.forEach {
                                container.accountStore.remove(it.id)
                            }
                        }
                    }
                    container.serverConfigStore.save(r.config)
                    container.serverConfigStore.addRecent(ServerBootstrap.normalize(rawUrl))
                    _state.value = AddServerState.Done
                }
                is BootstrapResult.Fail -> _state.value = AddServerState.Error(messageFor(r))
            }
        }
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
    }
}
