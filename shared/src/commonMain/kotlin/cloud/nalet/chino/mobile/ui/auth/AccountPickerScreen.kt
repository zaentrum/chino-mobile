package cloud.nalet.chino.mobile.ui.auth

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.LocalSignInLauncher
import cloud.nalet.chino.mobile.ui.components.Avatar
import cloud.nalet.chino.mobile.ui.shell.MainShellScreen
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import kotlinx.coroutines.launch

/**
 * "Who's watching?" account picker — phone/tablet touch adaptation of
 * chino-androidtv's AccountPickerScreen. Lists every saved account (avatar +
 * name, the active one highlighted), an "Add account" tile that re-runs the
 * AppAuth sign-in flow and persists a new account, and a per-account remove.
 *
 * Tapping an account makes it active and lands on the main shell. The
 * AppContainer's activeAccountId watcher invalidates the stream token on the
 * switch so the new user's poster/player URLs are signed for them
 * ([StreamTokenManager.invalidate]); the authenticated Ktor client also reads
 * the new account's bearer on its next call, so data refreshes for the new
 * user automatically.
 *
 * Accounts arrive pre-sorted lastUsedAt DESC from [AccountStore.accounts], so
 * the tile order matches the TV's last-used-first layout. We seed the cold-
 * emitting flow with [AccountStore.snapshotBlocking] so the grid renders its
 * real tiles on the first frame rather than flashing the Add-account tile
 * alone.
 *
 * Reached from: the boot gate / sign-out (≥1 account remaining), and the
 * Profile screen's Sign out (it routes here when other accounts remain).
 */
class AccountPickerScreen : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val nav = LocalNavigator.currentOrThrow
        val container = LocalAppContainer.current
        val launcher = LocalSignInLauncher.current
        val scope = rememberCoroutineScope()
        val store = container.accountStore

        val seed = remember { store.snapshotBlocking().accounts }
        val accounts by store.accounts.collectAsState(initial = seed)
        val activeId by store.activeAccountId.collectAsState(initial = store.snapshotBlocking().activeAccount?.id)

        // Drives the "Add account" sign-in. Reuses AuthScreenModel so the
        // userinfo lookup + Account persist is identical to first-run sign-in.
        val authModel = remember { AuthScreenModel(container) }
        val authState by authModel.state.collectAsState()
        var pendingRemoval by remember { mutableStateOf<Account?>(null) }

        // When the Add-account sign-in completes, the new account is already
        // persisted + set active by AuthScreenModel; jump to the shell.
        LaunchedEffect(authState) {
            if (authState is AuthUiState.Authenticated) {
                nav.replaceAll(MainShellScreen())
            }
        }

        val signing = authState is AuthUiState.Signing

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val isWide = maxWidth >= 600.dp
            // Tablet shows wider tiles in more columns; phone packs ~3-up.
            val minTile = if (isWide) 160.dp else 110.dp
            val outerPad = if (isWide) 48.dp else 24.dp

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = minTile),
                contentPadding = PaddingValues(outerPad),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(
                            text = "Who's watching?",
                            color = Color.White,
                            fontSize = if (isWide) 34.sp else 26.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Tap to switch. Tap the sign-out badge to remove an account.",
                            color = ChinoMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                        )
                    }
                }

                items(accounts, key = { it.id }) { account ->
                    AccountTile(
                        account = account,
                        isActive = account.id == activeId,
                        enabled = !signing,
                        onSelect = {
                            scope.launch {
                                store.setActive(account.id)
                                nav.replaceAll(MainShellScreen())
                            }
                        },
                        onRemove = { pendingRemoval = account },
                    )
                }

                item(key = "__add_account__") {
                    AddAccountTile(
                        signing = signing,
                        onClick = { if (!signing) scope.launch { authModel.signIn(launcher) } },
                    )
                }
            }
        }

        pendingRemoval?.let { acct ->
            ConfirmRemoveDialog(
                account = acct,
                onConfirm = {
                    scope.launch {
                        store.remove(acct.id)
                        val remaining = store.snapshotBlocking().accounts
                        pendingRemoval = null
                        // Removing the last account leaves nobody to pick —
                        // fall through to Auth, matching App.kt's boot gate.
                        if (remaining.isEmpty()) nav.replaceAll(AuthScreen())
                    }
                },
                onDismiss = { pendingRemoval = null },
            )
        }
    }
}

@Composable
private fun AccountTile(
    account: Account,
    isActive: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(8.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RectangleShape)
                    .then(
                        if (isActive) {
                            Modifier.border(width = 3.dp, color = ChinoCloudBlue, shape = RectangleShape)
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Avatar(displayName = account.displayName, email = account.email, size = 88.dp)
            }
            // Per-account remove / sign-out badge — a small touch target on the
            // tile corner (the TV uses long-press; phones get an explicit tap
            // target since there's no remote long-press affordance).
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RectangleShape)
                    .background(ChinoBg2.copy(alpha = 0.8f))
                    .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape)
                    .clickable(enabled = enabled, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.LogOut,
                    contentDescription = "Remove ${account.displayName}",
                    tint = ChinoFg2,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = account.displayName,
            color = if (isActive) Color.White else ChinoFg2,
            fontSize = 14.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddAccountTile(signing: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .clickable(enabled = !signing, onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RectangleShape)
                .background(Color(0x1AFFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            if (signing) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), color = Color.White)
            } else {
                Icon(
                    imageVector = Lucide.Plus,
                    contentDescription = "Add account",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            text = if (signing) "Signing in…" else "Add account",
            color = ChinoFg2,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConfirmRemoveDialog(account: Account, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // Full-screen scrim + centered card. Compose-MP has no Material AlertDialog
    // in commonMain without pulling the material3 Dialog (which works, but a
    // hand-rolled scrim keeps the dark-canvas styling consistent with the rest
    // of the app and matches the TV's pattern). Scrim click = dismiss; the card
    // swallows its own clicks.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .clip(RectangleShape)
                .background(ChinoSurface)
                .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape)
                .clickable(enabled = false) {}
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(displayName = account.displayName, email = account.email, size = 64.dp)
            Text(
                text = "Remove ${account.displayName}?",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "You'll need to sign in again to use this account on this device.",
                color = ChinoFg2,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogButton(label = "Cancel", containerColor = ChinoBorder2, onClick = onDismiss)
                DialogButton(label = "Remove", containerColor = ChinoRed, onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, containerColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RectangleShape)
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.White, fontSize = 14.sp)
    }
}
