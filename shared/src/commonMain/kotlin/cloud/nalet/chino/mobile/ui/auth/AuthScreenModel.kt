package cloud.nalet.chino.mobile.ui.auth

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.ServerRefresh
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.SignInLauncher
import cloud.nalet.chino.mobile.data.auth.SignInResult
import cloud.nalet.chino.mobile.data.refreshServerConfig
import cloud.nalet.chino.mobile.ui.downloads.leaveCastingOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface AuthUiState {
    /** The connected server is asked for its sign-in settings before a sign-in
     *  is offered (see [AuthScreenModel]). */
    data object Checking : AuthUiState
    data object Idle : AuthUiState
    data object Signing : AuthUiState
    data object Authenticated : AuthUiState
    data class Error(val message: String) : AuthUiState
}

/**
 * Drives the browser-redirect sign-in. The platform [SignInLauncher] runs
 * the Chrome Custom Tab / ASWebAuthenticationSession dance; once it returns
 * tokens we resolve the user's identity via [OidcDeviceClient.fetchUserInfo]
 * (so the Account id matches the Keycloak `sub` claim) and persist via
 * [AccountStore.addOrUpdate].
 */
class AuthScreenModel(private val container: AppContainer) : ScreenModel {
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Checking)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /** True once the server was found to name another sign-in than the app
     *  had stored: it is stored now, and the screen has the graph rebuilt
     *  (LocalAppRestart) before anyone signs in through the old one. */
    private val _serverChanged = MutableStateFlow(false)
    val serverChanged: StateFlow<Boolean> = _serverChanged.asStateFlow()

    init {
        // The sign-in is the server's to name (/api/config), and it may name
        // another than when the app connected. A server that does not answer
        // in time does not hold the sign-in up: the stored settings stand.
        screenModelScope.launch {
            val refresh = withTimeoutOrNull(SERVER_CHECK_MS) {
                refreshServerConfig(container.serverConfigStore, container.serverBootstrap::probe)
            }
            if (refresh == ServerRefresh.Changed) {
                _serverChanged.value = true
            } else if (_state.value == AuthUiState.Checking) {
                _state.value = AuthUiState.Idle
            }
        }
    }

    fun signIn(launcher: SignInLauncher) {
        if (_state.value == AuthUiState.Signing || _state.value == AuthUiState.Checking) return
        _state.value = AuthUiState.Signing
        container.telemetry.event("auth_signin_started")
        screenModelScope.launch {
            when (val result = runCatching { launcher.signIn() }.getOrElse {
                SignInResult.Error(it.message ?: it::class.simpleName.orEmpty())
            }) {
                is SignInResult.Success -> {
                    val tokens = result.tokens
                    val info = runCatching {
                        container.oidcDeviceClient.fetchUserInfo(tokens.accessToken)
                    }.getOrNull()
                    val account = Account(
                        id = info?.sub ?: "anon-${currentTimeMillis()}",
                        displayName = info?.bestDisplayName() ?: "Account",
                        email = info?.email ?: "",
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        expiresAtEpochMillis = tokens.expiresAtEpochMillis,
                        lastUsedAt = currentTimeMillis(),
                    )
                    // Another account signed in in its place - "Add account"
                    // in the picker is a switch: the one casting stops, its
                    // last position written first, and a toast says so.
                    accountLeavingFor(container.accountStore.snapshotBlocking().activeAccount?.id, account.id)
                        ?.let { leaveCastingOf(container, it) }
                    container.accountStore.addOrUpdate(account, setActive = true)
                    container.telemetry.event(
                        "auth_signin_completed",
                        extra = mapOf("account_sub" to account.id),
                    )
                    _state.value = AuthUiState.Authenticated
                }
                SignInResult.Cancelled -> {
                    container.telemetry.event("auth_signin_cancelled")
                    _state.value = AuthUiState.Idle
                }
                is SignInResult.Error -> {
                    container.telemetry.event(
                        "auth_signin_failed",
                        extra = mapOf("error" to result.message),
                    )
                    _state.value = AuthUiState.Error(result.message)
                }
            }
        }
    }
}

/** The account that leaves as [signedIn] is signed in and made the active
 *  one: the one active before, where it is another; null for none. */
internal fun accountLeavingFor(activeBefore: String?, signedIn: String): String? = activeBefore?.takeIf { it != signedIn }

/** How long the sign-in screen waits for the server's settings before it
 *  offers the sign-in with the stored ones. */
private const val SERVER_CHECK_MS = 4_000L
