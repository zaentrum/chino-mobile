package cloud.nalet.chino.mobile.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.auth.LocalSignInLauncher
import cloud.nalet.chino.mobile.ui.shell.MainShellScreen
import kotlinx.coroutines.launch

/**
 * Single "Sign in" landing screen. Tap → host platform's [SignInLauncher]
 * fires (Chrome Custom Tab → Keycloak code+PKCE → redirect) → tokens land
 * → AuthScreenModel runs userinfo + persists the Account → we navigate to
 * the main shell.
 *
 * Mirrors the web's react-oidc-context auto-redirect behaviour, except the
 * button press is explicit so the user can see what they're signing into
 * before the browser tab opens. Future polish: auto-trigger on first
 * composition if we want one-tap-fewer.
 */
class AuthScreen : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val nav = LocalNavigator.currentOrThrow
        val container = LocalAppContainer.current
        val launcher = LocalSignInLauncher.current
        val model = remember { AuthScreenModel(container) }
        val state by model.state.collectAsState()
        val scope = rememberCoroutineScope()

        LaunchedEffect(state) {
            if (state is AuthUiState.Authenticated) {
                nav.replaceAll(MainShellScreen())
            }
        }

        Scaffold { padding ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Text(
                            text = "Chino",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            // Neutral / server-aware: a self-host client signs
                            // in against the user's OWN server's identity
                            // provider, not a fixed central account. Derive
                            // the host from the connected server for a small
                            // "you're signing into <your server>" cue.
                            text = container.serverConfig?.baseUrl
                                ?.substringAfter("://")
                                ?.substringBefore("/")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { "Films and series — sign in to $it." }
                                ?: "Films and series — sign in to your account.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        when (val s = state) {
                            AuthUiState.Idle -> {
                                Button(onClick = {
                                    scope.launch { model.signIn(launcher) }
                                }) { Text("Sign in") }
                            }
                            AuthUiState.Signing -> CircularProgressIndicator()
                            AuthUiState.Authenticated -> Unit
                            is AuthUiState.Error -> {
                                Text(
                                    text = s.message,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                )
                                Button(onClick = {
                                    scope.launch { model.signIn(launcher) }
                                }) { Text("Try again") }
                            }
                        }
                    }
                }
            }
        }
    }
}
