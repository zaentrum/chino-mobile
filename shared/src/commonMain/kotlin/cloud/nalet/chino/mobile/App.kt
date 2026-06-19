package cloud.nalet.chino.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cafe.adriel.voyager.navigator.Navigator
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.auth.AccountStore
import cloud.nalet.chino.mobile.data.auth.LocalSignInLauncher
import cloud.nalet.chino.mobile.data.auth.SignInLauncher
import cloud.nalet.chino.mobile.ui.auth.AccountPickerScreen
import cloud.nalet.chino.mobile.ui.auth.AuthScreen
import cloud.nalet.chino.mobile.ui.onboarding.AddServerScreen
import cloud.nalet.chino.mobile.ui.shell.LogoMark
import cloud.nalet.chino.mobile.ui.shell.MainShellScreen
import cloud.nalet.chino.mobile.ui.theme.ChinoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App-scoped DI handle. Screens access this via [current] rather than
 *  receiving AppContainer in their constructors, because Voyager preserves
 *  screens across configuration change / process death by writing them into
 *  a Bundle — AppContainer isn't Serializable (it owns HTTP clients,
 *  coroutine scopes, native handles) and putting it into a Bundle would
 *  throw NotSerializableException, exactly the crash observed on the first
 *  auth-redirect handoff. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer not provided — wire it in App() before the Navigator.")
}

/**
 * Host-supplied "rebuild the app graph" hook. After the user connects to (or
 * changes) a server, the AppContainer's lazy clients (OIDC / Ktor / config)
 * may already have been materialised from the previous (build-default) server
 * config; `save()` doesn't rebuild them. Mirroring chino-androidtv's
 * process-restart-on-connect, the host recreates the AppContainer + remounts
 * the App so the graph re-reads the just-saved server. Android backs this with
 * Activity.recreate() (+ a fresh AppContainer); iOS is a no-op for now (its
 * sign-in is still the stub).
 */
val LocalAppRestart = staticCompositionLocalOf<() -> Unit> {
    {} // default no-op; Android MainActivity provides the real recreate.
}

/**
 * Pick the initial screen from an AccountStore snapshot resolved off the
 * main thread — AppContainer's accountStore is `by lazy` so its first
 * access materialises the EncryptedSharedPreferences master key, which
 * round-trips through AndroidKeyStore / iOS Keychain and can stall multiple
 * seconds. Doing that work on the main thread used to fire
 * "failed to complete startup" ANRs on emulators reliably. While the
 * snapshot resolves the [BootSplash] (`>c` logo on the dark canvas) is
 * shown, then Navigator composes with the correct initial screen — Voyager
 * doesn't re-pick the start destination on recomposition, so we have to
 * wait for the real value before mounting it.
 *
 * Host platforms (Android MainActivity, iOS MainViewController) pass their
 * platform-specific [SignInLauncher] via [signInLauncher]; AuthScreen pulls
 * it from [LocalSignInLauncher] when the user taps Sign in.
 */
@Composable
fun App(
    container: AppContainer,
    signInLauncher: SignInLauncher,
    /** Host hook to rebuild the app graph after a server connect/change.
     *  Android passes Activity.recreate-with-fresh-container; iOS defaults to
     *  a no-op (its sign-in is still the stub). */
    restart: () -> Unit = {},
) {
    // Boot snapshot: account state PLUS whether a server is configured,
    // resolved together off the main thread (accountStore + serverConfigStore
    // are `by lazy`; their first access round-trips through platform storage).
    // Mirrors chino-androidtv's ChinoTvNavHost BootState, including the
    // upgrade-migration seed: an existing user (has accounts) with no stored
    // server is seeded from the build-flavor default so they land in the shell,
    // not the Add-Server screen. Fresh installs (no accounts, no server) fall
    // through to AddServerScreen.
    val boot by produceState<BootState?>(initialValue = null) {
        value = withContext(Dispatchers.Default) {
            val accts = container.accountStore.snapshotBlocking()
            var configured = container.serverConfigStore.current()?.isConfigured == true
            if (!configured && accts.accounts.isNotEmpty()) {
                container.serverConfigStore.save(container.buildDefaultServerConfig())
                configured = true
            }
            BootState(accounts = accts, serverConfigured = configured)
        }
    }
    CompositionLocalProvider(
        LocalAppContainer provides container,
        LocalSignInLauncher provides signInLauncher,
        LocalAppRestart provides restart,
    ) {
        ChinoTheme {
            val snapshot = boot
            Box(modifier = Modifier.fillMaxSize()) {
                if (snapshot == null) {
                    BootSplash()
                } else {
                    // Start-destination gate (mirrors the TV NavHost):
                    //  - no server configured              -> Add-Server (neutral first run)
                    //  - server, no active account          -> Auth (sign in)
                    //  - server + >=2 accounts              -> "Who's watching?" picker
                    //  - server + exactly one active account -> MainShell
                    val initialScreen = when {
                        !snapshot.serverConfigured -> AddServerScreen()
                        snapshot.accounts.activeAccount == null -> AuthScreen()
                        snapshot.accounts.accounts.size >= 2 -> AccountPickerScreen()
                        else -> MainShellScreen()
                    }
                    Navigator(initialScreen)
                }
                // M3 status-bar accent strip — scrims the system-status-
                // bar inset zone with #161B22 (one step lighter than the
                // canvas). Visually separates the system clock/icons from
                // the app's canvas without adding a hard divider line on
                // the chrome below. Renders on top of every screen because
                // it's the outermost Box's last child.
                StatusBarAccent()
            }
        }
    }
}

/** Boot-time snapshot: account state plus whether a server is configured
 *  (post-migration), resolved together off the main thread so the Navigator's
 *  initial screen is locked on the first real frame. Mirrors the TV NavHost's
 *  BootState. */
private data class BootState(
    val accounts: AccountStore.Snapshot,
    val serverConfigured: Boolean,
)

@Composable
private fun StatusBarAccent() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(Color(0xFF161B22)),
    )
}

/** Brand-coloured cold-launch splash. Shows for the duration of the
 *  AccountStore first-access (EncryptedSharedPreferences open + decrypt) —
 *  typically <300 ms on real hardware, 1-3 s on AOSP x86 emulators where
 *  AndroidKeyStore is software-emulated. Same `>c` logo the LibraryScreen
 *  top bar renders, so the transition into the shell feels like the logo
 *  "finds its place" rather than the screen flashing through a blank frame. */
@Composable
private fun BootSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117)),
        contentAlignment = Alignment.Center,
    ) {
        LogoMark(sizeDp = 64)
    }
}
