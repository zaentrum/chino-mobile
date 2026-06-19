package cloud.nalet.chino.mobile.data.auth

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Platform-agnostic handoff for the browser-based OAuth 2.0 Authorization
 * Code + PKCE flow. The host platform (Android: AppAuth + Chrome Custom Tab;
 * iOS: ASWebAuthenticationSession) implements [signIn] and wires it into the
 * Compose tree via [LocalSignInLauncher] when constructing the root App.
 *
 * AuthScreen calls signIn() on button press and hands the resulting [Tokens]
 * to AuthScreenModel, which then runs [OidcDeviceClient.fetchUserInfo] +
 * [AccountStore.addOrUpdate] just like the old device-flow path did.
 */
interface SignInLauncher {
    suspend fun signIn(): SignInResult
}

sealed interface SignInResult {
    data class Success(val tokens: Tokens) : SignInResult
    data object Cancelled : SignInResult
    data class Error(val message: String) : SignInResult
}

/** Provided by [App] when the host wires a per-platform launcher in. */
val LocalSignInLauncher = staticCompositionLocalOf<SignInLauncher> {
    error(
        "No SignInLauncher provided. Wire one via CompositionLocalProvider before " +
            "rendering AuthScreen — see MainActivity (Android) or MainViewController (iOS).",
    )
}
