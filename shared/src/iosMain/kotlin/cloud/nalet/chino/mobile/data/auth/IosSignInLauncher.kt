package cloud.nalet.chino.mobile.data.auth

/**
 * iOS sign-in stub. Real implementation needs ASWebAuthenticationSession
 * via cinterop — not yet built. The Compose entry point on iOS
 * (MainViewController) wires this into App() so commonMain still compiles
 * for the Darwin target; trying to actually sign in returns Error so we
 * notice if someone wires the iOS host before the real impl lands.
 */
class IosSignInLauncher : SignInLauncher {
    override suspend fun signIn(): SignInResult =
        SignInResult.Error("iOS sign-in not implemented yet — ASWebAuthenticationSession TODO")
}
