package cloud.nalet.chino.mobile.ui.onboarding

import androidx.compose.ui.graphics.RectangleShape

import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoBg2
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder2
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoDim
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.LocalAppRestart
import cloud.nalet.chino.mobile.ui.auth.AuthScreen
import cloud.nalet.chino.mobile.ui.shell.LogoMark

/**
 * First-run "connect to your server" screen for the neutral self-host client
 * — Voyager-flavoured port of chino-androidtv's ServerSetupScreen. The user
 * types their server address, or one-taps the build-flavor preset / a recent.
 * On submit the model probes the server and, on success, persists the config
 * and asks the host to rebuild the app graph so the just-saved server takes
 * effect (mirrors the TV's process-restart-on-connect). When the restart hook
 * is a no-op (e.g. iOS) we fall back to navigating to AuthScreen directly.
 *
 * [changeServer] = true is the Settings "Change server" entry — it prefills the
 * current server, clears existing accounts on connect, and lets the user back
 * out.
 */
class AddServerScreen(private val changeServer: Boolean = false) : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val container = LocalAppContainer.current
        val nav = LocalNavigator.currentOrThrow
        val restart = LocalAppRestart.current
        val model = remember { AddServerScreenModel(container, clearAccountsOnConnect = changeServer) }
        val state by model.state.collectAsState()
        val recents by model.recents.collectAsState()

        // Prefill: when changing servers, prefer the currently-connected
        // origin; otherwise the build-flavor preset (BLANK on the neutral store
        // build, so the field starts empty with a generic placeholder).
        val prefill = remember {
            if (changeServer) {
                container.serverConfig?.baseUrl
                    ?.removeSuffix("/")
                    ?.removeSuffix("/api")
                    ?.takeIf { it.isNotBlank() }
                    ?: model.presetUrl
            } else {
                model.presetUrl
            }
        }
        var url by remember { mutableStateOf(prefill) }

        LaunchedEffect(state) {
            if (state is AddServerState.Done) {
                // Rebuild the graph so the lazy OIDC/Ktor clients re-read the
                // saved server. If the host wired a real restart it remounts
                // App() at the boot gate (which now routes to Auth); if it's a
                // no-op, navigate to Auth ourselves so the flow still proceeds.
                restart()
                nav.replaceAll(AuthScreen())
            }
        }

        val probing = state is AddServerState.Probing

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                ) {
                    LogoMark(sizeDp = 56)
                    Text(
                        text = if (changeServer) "Change server" else "Connect to your server",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Enter the address of your Chino server.",
                        color = ChinoMuted,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )

                    UrlField(
                        value = url,
                        onChange = { url = it },
                        onSubmit = { model.connect(url) },
                        enabled = !probing,
                    )

                    PrimaryButton(
                        label = if (probing) "Connecting…" else "Connect",
                        enabled = !probing && url.isNotBlank(),
                        onClick = { model.connect(url) },
                    )

                    if (model.presetUrl.isNotBlank() && url.trim() != model.presetUrl) {
                        SecondaryButton(
                            label = "Use ${model.presetUrl.substringAfter("://")}",
                            enabled = !probing,
                            onClick = {
                                url = model.presetUrl
                                model.connect(model.presetUrl)
                            },
                        )
                    }

                    if (probing) {
                        CircularProgressIndicator()
                    }

                    (state as? AddServerState.Error)?.let {
                        Text(
                            text = it.message,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (recents.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(text = "Recent", color = ChinoMuted, fontSize = 13.sp)
                            recents.forEach { r ->
                                SecondaryButton(
                                    label = r.substringAfter("://"),
                                    enabled = !probing,
                                    onClick = { url = r; model.connect(r) },
                                )
                            }
                        }
                    }

                    if (changeServer) {
                        Text(
                            text = "Cancel",
                            color = ChinoMuted,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RectangleShape)
                                .clickable(enabled = !probing) { nav.pop() }
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlField(
    value: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RectangleShape)
            .background(ChinoSurface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) ChinoCloudBlue else ChinoBorder,
                shape = RectangleShape,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                enabled = enabled,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(ChinoCloudBlue),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        // Neutral placeholder — must NOT be an operator (nalet)
                        // URL on the store build, which starts with an empty field.
                        Text(
                            text = "https://media.example.com",
                            color = ChinoMuted,
                            fontSize = 16.sp,
                        )
                    }
                    inner()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RectangleShape)
            .background(if (enabled) ChinoCloudBlue else ChinoBorder)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) ChinoBg2 else ChinoMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SecondaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RectangleShape)
            .background(ChinoSurface)
            .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.White, fontSize = 14.sp)
    }
}
