package cloud.nalet.chino.mobile.ui.auth

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.SignInLauncher
import cloud.nalet.chino.mobile.data.auth.SignInResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthUiState {
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
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun signIn(launcher: SignInLauncher) {
        if (_state.value == AuthUiState.Signing) return
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
